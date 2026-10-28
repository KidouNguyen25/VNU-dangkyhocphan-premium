package vn.edu.vnu.registration.data;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.*;
import java.util.function.Function;
import static vn.edu.vnu.registration.domain.Models.*;
import static vn.edu.vnu.registration.domain.Ports.*;

/** Bộ ánh xạ dữ liệu PostgreSQL; mọi lệnh trong giao dịch dùng chung kết nối do Spring JDBC quản lý.
 * Trong Phase 1, toàn bộ các query cần thiết sẽ được xử lý trong lớp sau.
 *
 */
public class PostgresRepository implements Repository {
    private static final String PERIOD = "term, login_opens_at, opens_at, closes_at, status, credit_limit";
    private static final String SECTION = "s.id, s.course_code, s.term, s.lecturer, s.capacity, s.enrolled_count, s.session, c.name AS course_name, c.credits, c.prerequisite";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    private final ThreadLocal<Long> queryNanos = ThreadLocal.withInitial(() -> 0L);

    public PostgresRepository(JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper json) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.json = json;
    }

    private <T> T measured(java.util.function.Supplier<T> action) {
        long start = System.nanoTime();
        try { return action.get(); }
        finally { queryNanos.set(queryNanos.get() + System.nanoTime() - start); }
    }

    public void resetTiming() { queryNanos.remove(); }

    public long queryMillis() { return queryNanos.get() / 1_000_000; }

    private <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
        return measured(() -> jdbc.query(sql, mapper, args));
    }

    private int update(String sql, Object... args) { return measured(() -> jdbc.update(sql, args)); }

    private <T> Optional<T> first(List<T> rows) { return rows.stream().findFirst(); }

    // ============== ACCOUNT REPO ====================
    @Override
    public Optional<Account> accountByCode(String code) {
        return first(query("SELECT * FROM account WHERE code = ?", this::account, code));
    }

    @Override
    public Optional<Account> accountById(long id) {
        return first(query("SELECT * FROM account WHERE id = ?", this::account, id));
    }

    private Account account(ResultSet rs, int row) throws SQLException {
        return new Account(
                rs.getLong("id"),
                rs.getString("code"),
                rs.getString("full_name"),
                rs.getString("password_hash"),
                Role.valueOf(rs.getString("role"))
        );
    }

    //Registration Period
    @Override
    public Optional<Period> currentPeriod() { return readPeriod(false); }

    private Optional<Period> readPeriod(boolean lock) {
        return first(query("SELECT " + PERIOD + " FROM registration_period WHERE status = 'OPEN'" + (lock ? " FOR SHARE" : ""), (rs, row) ->
            new Period(rs.getString("term"), rs.getTimestamp("login_opens_at").toInstant(), rs.getTimestamp("opens_at").toInstant(),
                rs.getTimestamp("closes_at").toInstant(), PeriodStatus.valueOf(rs.getString("status")), rs.getInt("credit_limit"))));
    }

    //Section
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
    public List<Registered> registered(long studentId, String term) {
        String sql = "SELECT " + SECTION + ", e.id AS enrollment_id, e.created_at FROM enrollment e JOIN section s ON s.id = e.section_id " +
            "JOIN course c ON c.code = s.course_code WHERE e.student_id = ? AND e.status = 'ENROLLED'" + (term == null ? "" : " AND s.term = ?") + " ORDER BY s.id";
        Object[] args = term == null ? new Object[]{studentId} : new Object[]{studentId, term};
        return query(sql, (rs, row) -> new Registered(section(rs, row), rs.getLong("enrollment_id"), rs.getTimestamp("created_at").toInstant()), args);
    }

    @Override
    public void ping() { query("SELECT 1", (rs, row) -> rs.getInt(1)); }

    @Override
    public <T> T transaction(Function<Transaction, T> work) {
        try {
            return transactions.execute(status -> {
                jdbc.execute("SET LOCAL lock_timeout = '10s'");
                return work.apply(new PgTransaction(status));
            });
        } catch (DataAccessException e) { throw translate(e); }
    }

    private class PgTransaction implements Transaction {
        private final TransactionStatus status;

        PgTransaction(TransactionStatus status) { this.status = status; }

        @Override
        public Optional<Account> accountByCode(String code) { return PostgresRepository.this.accountByCode(code); }

        @Override
        public Optional<Account> accountById(long id) { return PostgresRepository.this.accountById(id); }

        @Override
        public Optional<Period> currentPeriod() { return readPeriod(true); }

        @Override
        public List<Section> sections(SectionQuery q) { return PostgresRepository.this.sections(q); }

        @Override
        public List<Registered> registered(long id, String term) { return PostgresRepository.this.registered(id, term); }

        @Override
        public void ping() { PostgresRepository.this.ping(); }

        @Override
        public boolean claimKey(long studentId, String key, String hash) {
            // API không cần DELETE: tái dùng dòng hết TTL bằng UPDATE, job owner dọn các dòng còn lại.
            return !query("""
                INSERT INTO idempotency_record (student_id, idempotency_key, request_hash, state, created_at, expires_at)
                VALUES (?, ?, ?, 'PROCESSING', clock_timestamp(), clock_timestamp() + interval '24 hours')
                ON CONFLICT (student_id, idempotency_key) DO UPDATE SET request_hash = EXCLUDED.request_hash,
                  state = 'PROCESSING', response_status = NULL, response_body = NULL,
                  created_at = clock_timestamp(), expires_at = clock_timestamp() + interval '24 hours'
                WHERE idempotency_record.expires_at <= clock_timestamp() RETURNING id
                """, (rs, row) -> rs.getLong(1), studentId, key, hash).isEmpty();
        }

        @Override
        public SavedResponse lockKey(long studentId, String key) {
            return first(query("""
                SELECT request_hash, state, response_status, response_body FROM idempotency_record
                WHERE student_id = ? AND idempotency_key = ? FOR UPDATE
                """, (rs, row) -> new SavedResponse(rs.getString("request_hash"), rs.getString("state"),
                    (Integer) rs.getObject("response_status", Integer.class), decode(rs.getString("response_body"))), studentId, key))
                .orElseThrow(() -> new IllegalStateException("Missing idempotency record"));
        }

        @Override
        public void completeKey(long studentId, String key, Outcome result) {
            update("UPDATE idempotency_record SET state = 'COMPLETED', response_status = ?, response_body = ?::jsonb WHERE student_id = ? AND idempotency_key = ?",
                result.status(), encode(result.body()), studentId, key);
        }
        @Override
        public void lockAccount(long id) {
            if (query("SELECT id FROM account WHERE id = ? FOR UPDATE", (rs, row) -> rs.getLong(1), id).isEmpty()) {
                throw error(401, "UNAUTHORIZED", "Tài khoản không tồn tại.");
            }
        }

        @Override
        public void lockSectionAdmin(long id) {
            // Khóa cả id chưa tồn tại, tránh hai PUT cùng tạo rồi ghi đè sau khi sinh viên đăng ký.
            query("SELECT pg_advisory_xact_lock(hashtextextended('admin-section:' || ?::text, 0))", (rs, row) -> 1, id);
        }

        @Override
        public List<Section> lockSections(List<Long> ids) {
            String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
            return query("SELECT " + SECTION + " FROM section s JOIN course c ON c.code = s.course_code WHERE s.id IN (" +
                placeholders + ") ORDER BY s.id FOR UPDATE OF s", PostgresRepository.this::section, ids.toArray());
        }

        @Override
        public Object savepoint() { return status.createSavepoint(); }

        @Override
        public void rollbackTo(Object savepoint) { status.rollbackToSavepoint(savepoint); }

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

        @Override
        public void upsertSection(long id, SectionInput s) {
            try {
                update("""
                    INSERT INTO section (id, course_code, term, lecturer, capacity, session) VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT (id) DO UPDATE SET course_code = EXCLUDED.course_code, term = EXCLUDED.term,
                      lecturer = EXCLUDED.lecturer, capacity = EXCLUDED.capacity, session = EXCLUDED.session
                    """, id, s.courseCode(), s.term(), s.lecturer(), s.capacity(), s.session());
            } catch (DataAccessException e) { throw translate(e); }
        }

        @Override
        public void upsertPeriod(Period p) {
            try {
                update("""
                    INSERT INTO registration_period (term, login_opens_at, opens_at, closes_at, status, credit_limit)
                    VALUES (?, ?, ?, ?, ?::registration_status, ?) ON CONFLICT (term) DO UPDATE SET
                      login_opens_at = EXCLUDED.login_opens_at, opens_at = EXCLUDED.opens_at,
                      closes_at = EXCLUDED.closes_at, status = EXCLUDED.status, credit_limit = EXCLUDED.credit_limit
                    """, p.term(), Timestamp.from(p.loginOpensAt()), Timestamp.from(p.opensAt()), Timestamp.from(p.closesAt()), p.status().name(), p.creditLimit());
            } catch (DataAccessException e) { throw translate(e); }
        }
    }

    private Object decode(String data) {
        if (data == null) return null;
        try { return json.readValue(data, Object.class); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Invalid stored response", e); }
    }

    private String encode(Object data) {
        try { return json.writeValueAsString(data); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Cannot encode response", e); }
    }

    private RuntimeException translate(DataAccessException e) {
        Throwable cause = e;
        while (cause.getCause() != null) cause = cause.getCause();
        if (cause instanceof SQLException sql) {
            String state = sql.getSQLState();
            // Mã SQLSTATE ổn định; tên FK lấy theo lược đồ chuẩn khi cần phân biệt lỗi.
            if ("23505".equals(state) && sql.getMessage().contains("one_open_period")) return error(409, "OPEN_PERIOD_EXISTS", "Đã có một đợt đang mở.");
            if ("23503".equals(state) && sql.getMessage().contains("section_course_code_fkey")) return error(422, "COURSE_NOT_FOUND", "Học phần không tồn tại.");
            if ("23503".equals(state) && sql.getMessage().contains("section_term_fkey")) return error(422, "REGISTRATION_NOT_FOUND", "Kỳ đăng ký không tồn tại.");
            if (Set.of("40P01", "40001", "55P03", "57014", "53300", "57P01", "08006").contains(state)) return error(503, "SERVICE_UNAVAILABLE", "Dịch vụ đang bận, vui lòng gửi lại yêu cầu.");
        }
        return e;
    }
}
