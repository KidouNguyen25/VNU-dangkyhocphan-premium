package vn.edu.vnu.registration.domain.enrollment.model;

import java.time.Instant;

public record Enrollment(
        long enrollmentId,
        long sectionId,
        Instant createdAt
) {}