package vn.edu.vnu.registration.data;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.edu.vnu.registration.data.metrics.QueryTimer;
import vn.edu.vnu.registration.domain.auth.model.PeriodStatus;
import vn.edu.vnu.registration.domain.auth.model.RegistrationPeriod;
import vn.edu.vnu.registration.domain.port.RegistrationPeriodRepository;

import java.sql.Timestamp;
import java.util.Optional;

public class RegistrationPeriodRepositoryImpl extends GenericRepositoryImpl implements RegistrationPeriodRepository {
    private static final String PERIOD = "term, login_opens_at, opens_at, closes_at, status, credit_limit";

    public RegistrationPeriodRepositoryImpl(JdbcTemplate jdbc, QueryTimer timer) {
        super(jdbc, timer);
    }

    @Override
    public Optional<RegistrationPeriod> currentPeriod() {
        return readPeriod(false);
    }

    @Override
    public void upsertPeriod(RegistrationPeriod p) {
        try {
            update("""
                    INSERT INTO registration_period (term, login_opens_at, opens_at, closes_at, status, credit_limit)
                    VALUES (?, ?, ?, ?, ?::registration_status, ?) ON CONFLICT (term) DO UPDATE SET
                      login_opens_at = EXCLUDED.login_opens_at, opens_at = EXCLUDED.opens_at,
                      closes_at = EXCLUDED.closes_at, status = EXCLUDED.status, credit_limit = EXCLUDED.credit_limit
                    """, p.term(), Timestamp.from(p.loginOpensAt()), Timestamp.from(p.opensAt()), Timestamp.from(p.closesAt()), p.status().name(), p.creditLimit());
        } catch (DataAccessException e) {
            throw translate(e);
        }
    }

    private Optional<RegistrationPeriod> readPeriod(boolean lock) {
        return first(query("SELECT " + PERIOD + " FROM registration_period WHERE status = 'OPEN'" + (lock ? " FOR SHARE" : ""), (rs, row) ->
                new RegistrationPeriod(
                        rs.getString("term"),
                        rs.getTimestamp("login_opens_at").toInstant(),
                        rs.getTimestamp("opens_at").toInstant(),
                        rs.getTimestamp("closes_at").toInstant(),
                        PeriodStatus.valueOf(rs.getString("status")),
                        rs.getInt("credit_limit")
                )
        ));
    }
}
