package vn.edu.vnu.registration.data;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.edu.vnu.registration.data.metrics.QueryTimer;
import vn.edu.vnu.registration.domain.enrollment.model.Section;
import vn.edu.vnu.registration.domain.enrollment.model.SectionInput;
import vn.edu.vnu.registration.domain.enrollment.model.SectionQuery;
import vn.edu.vnu.registration.domain.port.SectionRepository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SectionRepositoryImpl extends GenericRepositoryImpl implements SectionRepository {
    private static final String SECTION = "s.id, s.course_code, s.term, s.lecturer, s.capacity, s.enrolled_count, s.session, c.name AS course_name, c.credits, c.prerequisite";

    public SectionRepositoryImpl(JdbcTemplate jdbc, QueryTimer timer) {
        super(jdbc, timer);
    }

    @Override
    public List<Section> sections(SectionQuery q) {
        List<String> conditions = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        if (q.term() != null) { conditions.add("s.term = ?"); args.add(q.term()); }
        if (q.courseCode() != null) { conditions.add("s.course_code = ?"); args.add(q.courseCode()); }
        if (q.q() != null) {
            conditions.add("(c.name ILIKE ? OR s.course_code ILIKE ? OR s.lecturer ILIKE ?)");
            String term = "%" + q.q().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            args.addAll(List.of(term, term, term));
        }
        if (q.hasSeats() != null) conditions.add(q.hasSeats() ? "s.enrolled_count < s.capacity" : "s.enrolled_count >= s.capacity");
        if (q.cursor() != null) { conditions.add("s.id > ?"); args.add(q.cursor()); }
        args.add(q.limit());
        return query("SELECT " + SECTION + " FROM section s JOIN course c ON c.code = s.course_code " +
                (conditions.isEmpty() ? "" : "WHERE " + String.join(" AND ", conditions)) + " ORDER BY s.id LIMIT ?", this::section, args.toArray());
    }

    @Override
    public List<Section> lockSections(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        return query("SELECT " + SECTION + " FROM section s JOIN course c ON c.code = s.course_code WHERE s.id IN (" +
                placeholders + ") ORDER BY s.id FOR UPDATE OF s", this::section, ids.toArray());
    }

    @Override
    public void lockSectionAdmin(long id) {
        query("SELECT pg_advisory_xact_lock(hashtextextended('admin-section:' || ?::text, 0))", (rs, row) -> 1, id);
    }

    @Override
    public void upsertSection(long id, SectionInput s) {
        try {
            update("""
                    INSERT INTO section (id, course_code, term, lecturer, capacity, session) VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT (id) DO UPDATE SET course_code = EXCLUDED.course_code, term = EXCLUDED.term,
                      lecturer = EXCLUDED.lecturer, capacity = EXCLUDED.capacity, session = EXCLUDED.session
                    """, id, s.courseCode(), s.term(), s.lecturer(), s.capacity(), s.session());
        } catch (DataAccessException e) {
            throw translate(e);
        }
    }

    private Section section(ResultSet rs, int row) throws SQLException {
        return new Section(
                rs.getLong("id"),
                rs.getString("course_code"),
                rs.getString("term"),
                rs.getString("lecturer"),
                rs.getInt("capacity"),
                rs.getInt("enrolled_count"),
                rs.getInt("session"),
                rs.getString("course_name"),
                rs.getInt("credits"),
                rs.getString("prerequisite")
        );
    }
}
