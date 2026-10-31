package vn.edu.vnu.registration.domain;

/**
 * Toàn bộ Service cần một Outcome để trả về HTML.
 * @param status
 * @param body
 * @param replayed
 */
public record Outcome(
        int status,
        Object body,
        boolean replayed
) {
    public Outcome(int status, Object body) {
        this(status, body, false);
    }
}