package vn.edu.vnu.registration.domain.port;

public interface PasswordVerifier {
    boolean matches(String raw, String hash);
}
