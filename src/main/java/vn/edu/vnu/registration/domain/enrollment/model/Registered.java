package vn.edu.vnu.registration.domain.enrollment.model;

import java.time.Instant;

public record Registered(
        Section section,
        long enrollmentId,
        Instant createdAt
) {}
