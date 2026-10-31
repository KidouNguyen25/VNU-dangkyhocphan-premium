package vn.edu.vnu.registration.domain.auth;

import vn.edu.vnu.registration.domain.auth.model.RegistrationPeriod;
import vn.edu.vnu.registration.domain.port.RegistrationPeriodRepository;
import vn.edu.vnu.registration.domain.port.TransactionRunner;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

import static vn.edu.vnu.registration.domain.exception.BusinessException.error;

public class RegistrationPeriodService {
    private final RegistrationPeriodRepository periodRepository;
    private final TransactionRunner transactionRunner;
    private final Clock clock;

    public RegistrationPeriodService(RegistrationPeriodRepository periodRepository, TransactionRunner transactionRunner, Clock clock) {
        this.periodRepository = periodRepository;
        this.transactionRunner = transactionRunner;
        this.clock = clock;
    }

    public Map<String, Object> registration() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("registration", periodRepository.currentPeriod().orElse(null));
        body.put("serverTime", clock.instant());
        return body;
    }

    public RegistrationPeriod savePeriod(RegistrationPeriod input) {
        if (input.loginOpensAt().isAfter(input.opensAt()) || !input.opensAt().isBefore(input.closesAt())) {
            throw error(400, "VALIDATION_ERROR", "Cần loginOpensAt ≤ opensAt < closesAt.");
        }
        transactionRunner.runInTransaction(ctx -> {
            periodRepository.upsertPeriod(input);
            return null;
        });
        return input;
    }
}
