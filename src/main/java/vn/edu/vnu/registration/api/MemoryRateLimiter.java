package vn.edu.vnu.registration.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import static vn.edu.vnu.registration.domain.Models.*;

@Component
public class MemoryRateLimiter {
    private record Window(long expires, int count) {}
    private final Map<String, Window> windows = new HashMap<>();
    private final Clock clock;
    private final int ipLimit, accountLimit, loginLimit;
    private long nextCleanup;

    public MemoryRateLimiter(Clock clock, @Value("${app.rate-limit.ip}") int ipLimit,
                             @Value("${app.rate-limit.account}") int accountLimit,
                             @Value("${app.rate-limit.login}") int loginLimit) {
        if (ipLimit < 1 || accountLimit < 1 || loginLimit < 1) throw new IllegalArgumentException("Rate limits must be positive");
        this.clock = clock; this.ipLimit = ipLimit; this.accountLimit = accountLimit; this.loginLimit = loginLimit;
    }
    public void ip(String ip) { take("ip:" + ip, ipLimit); }
    public void account(long id) { take("account:" + id, accountLimit); }
    public void login(String code) { take("login:" + code, loginLimit); }

    // MVP chạy một API instance. Nếu chạy nhiều instance, cần giới hạn dùng chung ở pha sau.
    private synchronized void take(String key, int limit) {
        long now = clock.millis();
        if (now >= nextCleanup) { windows.entrySet().removeIf(e -> e.getValue().expires() <= now); nextCleanup = now + 60_000; }
        var current = windows.get(key);
        if (current == null || current.expires() <= now) {
            if (current == null && windows.size() >= 20_000) throw limited(60);
            windows.put(key, new Window(now + 60_000, 1));
            return;
        }
        if (current.count() >= limit) throw limited((int) Math.max(1, (current.expires() - now + 999) / 1000));
        windows.put(key, new Window(current.expires(), current.count() + 1));
    }
    private BusinessException limited(int seconds) {
        return new BusinessException(429, "RATE_LIMITED", "Vượt giới hạn yêu cầu, vui lòng thử lại sau.", Map.of("retryAfter", seconds));
    }
}
