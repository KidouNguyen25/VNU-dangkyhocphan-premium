package vn.edu.vnu.registration.domain.auth.model;

public record Account(
        long id,
        String code,
        String fullName,
        String passwordHash,
        Role role
) {}
