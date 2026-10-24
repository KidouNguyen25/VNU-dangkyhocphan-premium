package vn.edu.vnu.registration.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import vn.edu.vnu.registration.config.BackendConfig;
import vn.edu.vnu.registration.data.PostgresRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ApiSecurityFilterTest {
    private final ObjectMapper json = new ObjectMapper();
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-25T00:00:00Z"), ZoneOffset.UTC);

    @Test void rateLimitedHttpResponseHasRetryAfterAndErrorEnvelope() throws Exception {
        var filter = new ApiSecurityFilter(BackendConfig.missingTokenGateway(), new MemoryRateLimiter(clock, 1, 1, 1), json, "127.0.0.1", mock(PostgresRepository.class));
        var first = new MockHttpServletResponse();
        filter.doFilter(request(null), first, (req, res) -> ((jakarta.servlet.http.HttpServletResponse) res).setStatus(204));
        assertThat(first.getStatus()).isEqualTo(204);
        var second = new MockHttpServletResponse();
        filter.doFilter(request(null), second, (req, res) -> fail("Over-limit request must not reach controller"));
        assertThat(second.getStatus()).isEqualTo(429);
        assertThat(second.getHeader("Retry-After")).isEqualTo("60");
        assertThat(json.readTree(second.getContentAsString()).path("code").asText()).isEqualTo("RATE_LIMITED");
        assertThat(second.getHeader("X-Request-ID")).isNotBlank();
    }

    @Test void unconfiguredGatewayFailsClosedWithNoSecurityIdentity() throws Exception {
        SecurityContextHolder.clearContext();
        try {
            var filter = new ApiSecurityFilter(BackendConfig.missingTokenGateway(), new MemoryRateLimiter(clock, 10, 10, 10), json, "127.0.0.1", mock(PostgresRepository.class));
            var response = new MockHttpServletResponse();
            filter.doFilter(request("Bearer anything"), response, (req, res) -> fail("Unverified bearer must not reach controller"));
            assertThat(response.getStatus()).isEqualTo(503);
            assertThat(json.readTree(response.getContentAsString()).path("code").asText()).isEqualTo("AUTH_NOT_CONFIGURED");
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        } finally { SecurityContextHolder.clearContext(); }
    }

    private MockHttpServletRequest request(String authorization) {
        var request = new MockHttpServletRequest("GET", "/api/v1/me");
        request.setRemoteAddr("127.0.0.1");
        if (authorization != null) request.addHeader("Authorization", authorization);
        return request;
    }
}
