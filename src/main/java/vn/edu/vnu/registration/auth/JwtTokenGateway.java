package vn.edu.vnu.registration.auth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import static vn.edu.vnu.registration.domain.Models.*;
import static vn.edu.vnu.registration.domain.Ports.*;

/** JWT HS256, 60 phút, mang account.id (sub) và role. Không refresh token, không lưu phiên. */
@Component
@ConditionalOnExpression("!'${app.jwt.secret:}'.isBlank()")
public class JwtTokenGateway implements TokenGateway {
    static final long TTL_SECONDS = 3600;
    private static final String ISSUER = "vnu-course-registration";
    private static final int MIN_SECRET_BYTES = 32;
    private final byte[] key;
    private final Clock clock;

    public JwtTokenGateway(@Value("${app.jwt.secret}") String secret, Clock clock) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_SECRET_BYTES) throw new IllegalArgumentException("JWT_SECRET phải dài ít nhất 32 byte.");
        if (secret.startsWith("change-me")) throw new IllegalArgumentException("JWT_SECRET vẫn là giá trị mẫu, hãy sinh khóa mới.");
        this.key = bytes;
        this.clock = clock;
    }

    @Override public Token issue(Identity identity) {
        try {
            Instant now = clock.instant();
            var claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject(Long.toString(identity.accountId()))
                .claim("role", identity.role().name())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(TTL_SECONDS)))
                .build();
            var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(key));
            return new Token(jwt.serialize(), "Bearer", TTL_SECONDS);
        } catch (Exception e) {
            throw error(500, "INTERNAL_ERROR", "Không phát được token.");
        }
    }

    @Override public Identity verify(String bearerToken) {
        try {
            var jwt = SignedJWT.parse(bearerToken);
            if (!JWSAlgorithm.HS256.equals(jwt.getHeader().getAlgorithm())) throw invalid();
            if (!jwt.verify(new MACVerifier(key))) throw invalid();
            var claims = jwt.getJWTClaimsSet();
            Date exp = claims.getExpirationTime();
            if (exp == null || !exp.toInstant().isAfter(clock.instant())) throw invalid();
            if (!ISSUER.equals(claims.getIssuer())) throw invalid();
            return new Identity(Long.parseLong(claims.getSubject()), Role.valueOf(claims.getStringClaim("role")));
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw invalid();
        }
    }

    private static BusinessException invalid() {
        return error(401, "UNAUTHORIZED", "Token không hợp lệ hoặc đã hết hạn.");
    }
}
