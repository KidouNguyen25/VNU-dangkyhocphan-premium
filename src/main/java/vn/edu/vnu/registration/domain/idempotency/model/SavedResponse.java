package vn.edu.vnu.registration.domain.idempotency.model;

public record SavedResponse(
        String requestHash,
        String state,
        Integer responseStatus,
        Object responseBody
) {}
