package vn.edu.vnu.registration.domain.exception;

public record ErrorBody(
        String code,
        String message,
        Object details
) {}
