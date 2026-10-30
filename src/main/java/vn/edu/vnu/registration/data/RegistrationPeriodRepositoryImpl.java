package vn.edu.vnu.registration.data;

import org.springframework.jdbc.core.JdbcTemplate;
import vn.edu.vnu.registration.domain.auth.model.PeriodStatus;
import vn.edu.vnu.registration.domain.auth.model.RegistrationPeriod;
import vn.edu.vnu.registration.domain.port.RegistrationPeriodRepository;

import java.util.Optional;

public class RegistrationPeriodRepositoryImpl extends GenericRepositoryImpl implements RegistrationPeriodRepository {
    private static final String PERIOD = "term, login_opens_at, opens_at, closes_at, status, credit_limit";
    protected RegistrationPeriodRepositoryImpl(JdbcTemplate jdbc) {
        super(jdbc);
    }

    @Override
    public Optional<RegistrationPeriod> currentPeriod() {
        return readPeriod(false);
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
                )
        );
    }
}
