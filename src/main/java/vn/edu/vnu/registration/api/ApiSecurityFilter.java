package vn.edu.vnu.registration.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import vn.edu.vnu.registration.data.PostgresRepository;

import java.io.IOException;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import static vn.edu.vnu.registration.domain.Models.*;
import static vn.edu.vnu.registration.domain.Ports.*;

public class ApiSecurityFilter extends OncePerRequestFilter {
    private static final Logger LOG = LoggerFactory.getLogger(ApiSecurityFilter.class);
    private final TokenGateway tokens;
    private final MemoryRateLimiter limiter;
    private final ObjectMapper json;
    private final Set<String> healthIps;
    private final PostgresRepository repository;

    public ApiSecurityFilter(TokenGateway tokens, MemoryRateLimiter limiter, ObjectMapper json, String healthIps, PostgresRepository repository) {
        this.tokens = tokens; this.limiter = limiter; this.json = json; this.repository = repository;
        this.healthIps = Arrays.stream(healthIps.split(",")).map(String::trim).filter(s -> !s.isBlank()).map(ApiSecurityFilter::normalizedIp).collect(Collectors.toSet());
    }
    private static String normalizedIp(String ip) {
        if (!ip.matches("[0-9a-fA-F:.]+")) throw new IllegalArgumentException("HEALTH_ALLOWED_IPS must contain literal IP addresses");
        try { return InetAddress.getByName(ip).getHostAddress(); }
        catch (Exception e) { throw new IllegalArgumentException("Invalid IP address", e); }
    }

    @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        long start = System.nanoTime();
        String requestId = UUID.randomUUID().toString();
        res.setHeader("X-Request-ID", requestId);
        res.setHeader("Cache-Control", "no-store");
        MDC.put("requestId", requestId);
        repository.resetTiming();
        try {
            String path = req.getRequestURI();
            if (path.startsWith("/api/v1/")) {
                limiter.ip(req.getRemoteAddr());
                boolean health = path.equals("/api/v1/health");
                if (health && !healthIps.contains(normalizedIp(req.getRemoteAddr()))) throw error(403, "FORBIDDEN", "Địa chỉ không được phép kiểm tra dịch vụ.");
                if (!health && !path.equals("/api/v1/auth/login")) {
                    String header = req.getHeader("Authorization");
                    if (header != null) {
                        if (!header.regionMatches(true, 0, "Bearer ", 0, 7) || header.substring(7).isBlank() || header.length() > 8192) throw error(401, "UNAUTHORIZED", "Bearer token không hợp lệ.");
                        Identity identity;
                        try { identity = tokens.verify(header.substring(7)); }
                        catch (BusinessException e) { throw e; }
                        catch (RuntimeException e) { throw error(401, "UNAUTHORIZED", "Token không hợp lệ hoặc đã hết hạn."); }
                        if (identity == null) throw error(401, "UNAUTHORIZED", "Token không hợp lệ.");
                        limiter.account(identity.accountId());
                        var authentication = UsernamePasswordAuthenticationToken.authenticated(identity, null, List.of(new SimpleGrantedAuthority("ROLE_" + identity.role())));
                        SecurityContextHolder.getContext().setAuthentication(authentication);
                    }
                }
            }
            chain.doFilter(req, res);
        } catch (BusinessException e) {
            writeError(res, e.status(), e.body(), json);
        } finally {
            LOG.info("requestId={} method={} status={} apiMs={} dbMs={}", requestId, req.getMethod(), res.getStatus(), (System.nanoTime() - start) / 1_000_000, repository.queryMillis());
            repository.resetTiming();
            MDC.remove("requestId");
        }
    }

    public static void writeError(HttpServletResponse res, int status, ErrorBody body, ObjectMapper json) throws IOException {
        res.setStatus(status); res.setContentType("application/json"); res.setCharacterEncoding("UTF-8");
        res.setHeader("Cache-Control", "no-store");
        if (status == 401) res.setHeader("WWW-Authenticate", "Bearer");
        if (status == 429 && body.details() instanceof java.util.Map<?, ?> details) res.setHeader("Retry-After", String.valueOf(details.get("retryAfter")));
        json.writeValue(res.getOutputStream(), body);
    }
}
