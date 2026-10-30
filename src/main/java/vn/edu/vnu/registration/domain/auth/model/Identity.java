package vn.edu.vnu.registration.domain.auth.model;

public record Identity(long accountId, Role role) {
    public Identity {
        if (accountId <= 0 || role == null) {
            throw new IllegalArgumentException("Invalid identity");
        }
    }
}
