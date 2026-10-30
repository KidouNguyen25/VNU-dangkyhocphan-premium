package vn.edu.vnu.registration.domain.auth.model;

public record Token(
        String accessToken,
        String tokenType,
        long expiresIn
) {}
