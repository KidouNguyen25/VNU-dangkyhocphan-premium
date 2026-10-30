package vn.edu.vnu.registration.domain.enrollment.model;

public record Outcome(
        int status,
        Object body,
        boolean replayed
) {
    public Outcome(int status, Object body) { this(status, body, false); }
}