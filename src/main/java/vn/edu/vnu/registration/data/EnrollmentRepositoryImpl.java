package vn.edu.vnu.registration.data;

import org.springframework.jdbc.core.JdbcTemplate;
import vn.edu.vnu.registration.data.metrics.QueryTimer;
import vn.edu.vnu.registration.domain.enrollment.model.Cancellation;
import vn.edu.vnu.registration.domain.enrollment.model.Enrollment;
import vn.edu.vnu.registration.domain.enrollment.model.Registered;
import vn.edu.vnu.registration.domain.enrollment.model.Section;
import vn.edu.vnu.registration.domain.port.EnrollmentRepository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import static vn.edu.vnu.registration.domain.exception.BusinessException.error;
import static vn.edu.vnu.registration.domain.exception.BusinessException.sectionError;

public class EnrollmentRepositoryImpl extends GenericRepositoryImpl implements EnrollmentRepository {
    private static final String SECTION = "s.id, s.course_code, s.term, s.lecturer, s.capacity, s.enrolled_count, s.session, c.name AS course_name, c.credits, c.prerequisite";

    public EnrollmentRepositoryImpl(JdbcTemplate jdbc, QueryTimer timer) {
        super(jdbc, timer);
    }

    @Override
    public List<Registered> registered(long studentId, String term) {
        String sql = "SELECT " + SECTION + ", e.id AS enrollment_id, e.created_at FROM enrollment e JOIN section s ON s.id = e.section_id " +
                "JOIN course c ON c.code = s.course_code WHERE e.student_id = ? AND e.status = 'ENROLLED'" + (term == null ? "" : " AND s.term = ?") + " ORDER BY s.id";
        Object[] args = term == null ? new Object[]{studentId} : new Object[]{studentId, term};
        return query(sql, (rs, row) -> new Registered(section(rs, row), rs.getLong("enrollment_id"), rs.getTimestamp("created_at").toInstant()), args);
    }

    @Override
    public Enrollment enroll(long studentId, long sectionId) {
        if (update("UPDATE section SET enrolled_count = enrolled_count + 1 WHERE id = ? AND enrolled_count < capacity", sectionId) == 0) {
            throw sectionError(409, "SECTION_FULL", "Lớp học phần đã đầy.", sectionId);
        }

        return query("INSERT INTO enrollment (student_id, section_id, status) VALUES (?, ?, 'ENROLLED') RETURNING id, section_id, created_at",
                (rs, row) -> new Enrollment(rs.getLong("id"), rs.getLong("section_id"), rs.getTimestamp("created_at").toInstant()), studentId, sectionId).get(0);
    }

    @Override
    public Optional<Cancellation> enrollment(long studentId, long enrollmentId) {
        return first(query("""
                SELECT s.id, s.term FROM enrollment e JOIN section s ON s.id = e.section_id
                WHERE e.id = ? AND e.student_id = ? AND e.status = 'ENROLLED'
                """, (rs, row) -> new Cancellation(rs.getLong("id"), rs.getString("term")), enrollmentId, studentId));
    }

    @Override
    public void cancel(long studentId, long enrollmentId, long sectionId) {
        if (update("DELETE FROM enrollment WHERE id = ? AND student_id = ? AND status = 'ENROLLED'", enrollmentId, studentId) == 0) {
            throw error(404, "ENROLLMENT_NOT_FOUND", "Không tìm thấy lượt đăng ký.");
        }
        if (update("UPDATE section SET enrolled_count = enrolled_count - 1 WHERE id = ? AND enrolled_count > 0", sectionId) != 1) {
            throw new IllegalStateException("Enrollment counter invariant violated");
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
