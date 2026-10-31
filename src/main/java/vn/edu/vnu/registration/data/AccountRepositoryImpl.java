package vn.edu.vnu.registration.data;

import org.springframework.jdbc.core.JdbcTemplate;
import vn.edu.vnu.registration.data.metrics.QueryTimer;
import vn.edu.vnu.registration.domain.auth.model.Account;
import vn.edu.vnu.registration.domain.auth.model.Role;
import vn.edu.vnu.registration.domain.port.AccountRepository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

import static vn.edu.vnu.registration.domain.exception.BusinessException.error;

public class AccountRepositoryImpl extends GenericRepositoryImpl implements AccountRepository {
    public AccountRepositoryImpl(JdbcTemplate jdbc, QueryTimer timer) {
        super(jdbc, timer);
    }

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

    @Override
    public void lockAccount(long id) {
        if (query("SELECT id FROM account WHERE id = ? FOR UPDATE", (rs, row) -> rs.getLong(1), id).isEmpty()) {
            throw error(401, "UNAUTHORIZED", "Tài khoản không còn tồn tại.");
        }
    }
}
