package vn.edu.vnu.registration.data;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import vn.edu.vnu.registration.data.metrics.QueryTimer;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static vn.edu.vnu.registration.domain.exception.BusinessException.error;

public abstract class GenericRepositoryImpl {
    protected final JdbcTemplate jdbc;
    protected final QueryTimer timer = new QueryTimer();

    protected GenericRepositoryImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    protected <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
        return timer.measured(() -> jdbc.query(sql, mapper, args));
    }

    protected int update(String sql, Object... args) {
        return timer.measured(() -> jdbc.update(sql, args));
    }

    protected <T> Optional<T> first(List<T> rows) {
        return rows.stream().findFirst();
    }

    protected RuntimeException translate(DataAccessException e) {
        Throwable cause = e;
        while (cause.getCause() != null) cause = cause.getCause();
        if (cause instanceof SQLException sql) {
            String state = sql.getSQLState();
            if ("23505".equals(state) && sql.getMessage().contains("one_open_period"))
                return error(409, "OPEN_PERIOD_EXISTS", "Đã có một đợt đang mở.");
            if ("23503".equals(state) && sql.getMessage().contains("fk_section_course_code"))
                return error(422, "COURSE_NOT_FOUND", "Học phần không tồn tại.");
            if ("23503".equals(state) && sql.getMessage().contains("fk_section_term"))
                return error(422, "REGISTRATION_NOT_FOUND", "Kỳ đăng ký không tồn tại.");
            if (Set.of("40P01", "40001", "55P03", "57014", "53300", "57P01", "08006").contains(state))
                return error(503, "SERVICE_UNAVAILABLE", "Dịch vụ đang bận, vui lòng gửi lại yêu cầu.");
        }
        return e;
    }
}