package vn.edu.vnu.registration.auth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;

import vn.edu.vnu.registration.domain.auth.model.Identity;
import vn.edu.vnu.registration.domain.auth.model.Role;
import vn.edu.vnu.registration.domain.exception.BusinessException;
import static org.assertj.core.api.Assertions.*;

class JwtTokenGatewayTest {
    private static final String SECRET = "test-only-secret-0123456789-abcdefghij";
    private static final Instant NOW = Instant.parse("2026-10-25T03:00:00Z");

    private static JwtTokenGateway gateway(String secret, Instant at) {
        return new JwtTokenGateway(secret, Clock.fixed(at, ZoneOffset.UTC));
    }
    private static void assertUnauthorized(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BusinessException.class, e -> {
            assertThat(e.status()).isEqualTo(401);
            assertThat(e.body().code()).isEqualTo("UNAUTHORIZED");
        });
    }

    @Test
    void issuesTokenWithIdentityAndOneHourLifetime() {
        var gw = gateway(SECRET, NOW);
        var token = gw.issue(new Identity(42, Role.STUDENT));
        assertThat(token.tokenType()).isEqualTo("Bearer");
        assertThat(token.expiresIn()).isEqualTo(3600);
        assertThat(gw.verify(token.accessToken())).isEqualTo(new Identity(42, Role.STUDENT));
    }

    @Test
    void keepsAdminRole() {
        var gw = gateway(SECRET, NOW);
        assertThat(gw.verify(gw.issue(new Identity(99, Role.ADMIN)).accessToken()).role()).isEqualTo(Role.ADMIN);
    }

    @Test
    void rejectsTokenAtAndAfterExpiry() {
        var token = gateway(SECRET, NOW).issue(new Identity(1, Role.STUDENT)).accessToken();
        assertThat(gateway(SECRET, NOW.plusSeconds(3599)).verify(token).accountId()).isEqualTo(1);
        assertUnauthorized(() -> gateway(SECRET, NOW.plusSeconds(3600)).verify(token));
        assertUnauthorized(() -> gateway(SECRET, NOW.plusSeconds(7200)).verify(token));
    }

    @Test
    void rejectsTokenSignedWithAnotherKey() {
        var token = gateway("another-secret-0123456789-abcdefghij", NOW).issue(new Identity(1, Role.STUDENT)).accessToken();
        assertUnauthorized(() -> gateway(SECRET, NOW).verify(token));
    }

    @Test
    void rejectsTamperedPayload() {
        var gw = gateway(SECRET, NOW);
        var parts = gw.issue(new Identity(1, Role.STUDENT)).accessToken().split("\\.");
        var forged = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
            "{\"sub\":\"99\",\"role\":\"ADMIN\",\"iss\":\"vnu-course-registration\",\"exp\":9999999999}".getBytes(StandardCharsets.UTF_8));
        assertUnauthorized(() -> gw.verify(parts[0] + "." + forged + "." + parts[2]));
    }

    @Test
    void rejectsUnsignedAndMalformedTokens() {
        var gw = gateway(SECRET, NOW);
        var none = java.util.Base64.getUrlEncoder().withoutPadding();
        var unsigned = none.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8)) + "."
            + none.encodeToString("{\"sub\":\"1\",\"role\":\"ADMIN\"}".getBytes(StandardCharsets.UTF_8)) + ".";
        assertUnauthorized(() -> gw.verify(unsigned));
        assertUnauthorized(() -> gw.verify("not-a-jwt"));
        assertUnauthorized(() -> gw.verify("a.b.c"));
    }

    @Test
    void rejectsOtherIssuerAndUnknownRole() {
        var gw = gateway(SECRET, NOW);
        assertUnauthorized(() -> gw.verify(signed("other-issuer", "1", "STUDENT", NOW.plusSeconds(60))));
        assertUnauthorized(() -> gw.verify(signed("vnu-course-registration", "1", "TEACHER", NOW.plusSeconds(60))));
        assertUnauthorized(() -> gw.verify(signed("vnu-course-registration", "abc", "STUDENT", NOW.plusSeconds(60))));
        assertUnauthorized(() -> gw.verify(signed("vnu-course-registration", "0", "STUDENT", NOW.plusSeconds(60))));
        assertUnauthorized(() -> gw.verify(signed("vnu-course-registration", "1", "STUDENT", null)));
    }

    @Test
    void rejectsWeakOrSampleSecret() {
        assertThatThrownBy(() -> gateway("short", NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> gateway("change-me-at-least-32-random-bytes", NOW)).isInstanceOf(IllegalArgumentException.class);
    }

    private static String signed(String issuer, String subject, String role, Instant exp) {
        try {
            var builder = new JWTClaimsSet.Builder().issuer(issuer).subject(subject).claim("role", role);
            if (exp != null) builder.expirationTime(Date.from(exp));
            var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), builder.build());
            jwt.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
