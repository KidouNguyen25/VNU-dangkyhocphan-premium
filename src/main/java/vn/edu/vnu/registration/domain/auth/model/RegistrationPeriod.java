package vn.edu.vnu.registration.domain.auth.model;

import java.time.Instant;

public record RegistrationPeriod(
        String term,
        Instant loginOpensAt,
        Instant opensAt,
        Instant closesAt,
        PeriodStatus status,
        int creditLimit
) {}