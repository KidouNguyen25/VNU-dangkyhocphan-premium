package vn.edu.vnu.registration.domain.port;

import vn.edu.vnu.registration.domain.auth.model.RegistrationPeriod;

import java.util.Optional;

public interface RegistrationPeriodRepository {
    Optional<RegistrationPeriod> currentPeriod();
}
