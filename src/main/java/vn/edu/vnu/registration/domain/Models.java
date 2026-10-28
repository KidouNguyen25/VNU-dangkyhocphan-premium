package vn.edu.vnu.registration.domain;

import java.time.Instant;
import java.util.Map;

public final class Models {
    private Models() {}

    public enum Role { STUDENT, ADMIN }
    public enum PeriodStatus { OPEN, CLOSED }
    public record Identity(long accountId, Role role) {
        public Identity {
            if (accountId <= 0 || role == null) throw new IllegalArgumentException("Invalid identity");
        }
    }
    public record Account(long id, String code, String fullName, String passwordHash, Role role) {}
    public record Period(String term, Instant loginOpensAt, Instant opensAt, Instant closesAt,
                         PeriodStatus status, int creditLimit) {}
    public record SectionInput(String courseCode, String term, String lecturer, int capacity, int session) {}
    public record Section(long id, String courseCode, String term, String lecturer, int capacity,
                          int enrolledCount, int session, String courseName, int credits, String prerequisite) {}
    public record Registered(Section section, long enrollmentId, Instant createdAt) {}
    public record Enrollment(long enrollmentId, long sectionId, Instant createdAt) {}
    public record SectionQuery(String term, String courseCode, String q, Boolean hasSeats, Long cursor, int limit) {}
    public record Outcome(int status, Object body, boolean replayed) {
        public Outcome(int status, Object body) { this(status, body, false); }
    }
    public record SavedResponse(String requestHash, String state, Integer responseStatus, Object responseBody) {}
    public record Cancellation(long sectionId, String term) {}
    public record Token(String accessToken, String tokenType, long expiresIn) {}
    public record ErrorBody(String code, String message, Object details) {}

    public static class BusinessException extends RuntimeException {
        private final int status;
        private final ErrorBody body;

        public BusinessException(int status, String code, String message) { this(status, code, message, null); }

        public BusinessException(int status, String code, String message, Object details) {
            super(message);
            this.status = status;
            this.body = new ErrorBody(code, message, details);
        }

        public int status() { return status; }

        public ErrorBody body() { return body; }
    }

    public static BusinessException error(int status, String code, String message) {
        return new BusinessException(status, code, message);
    }

    public static BusinessException sectionError(int status, String code, String message, long id) {
        return new BusinessException(status, code, message, Map.of("sectionId", id));
    }
}
