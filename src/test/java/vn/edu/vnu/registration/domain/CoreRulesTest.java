package vn.edu.vnu.registration.domain;

import org.junit.jupiter.api.Test;
import vn.edu.vnu.registration.api.MemoryRateLimiter;
import vn.edu.vnu.registration.config.BackendConfig;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static vn.edu.vnu.registration.domain.Models.*;

class CoreRulesTest {
    @Test void openIntervalIncludesStartButExcludesEnd() {
        var start = Instant.parse("2026-10-25T00:00:00Z");
        var end = start.plusSeconds(60);
        assertThat(RegistrationService.within(start, start, end)).isTrue();
        assertThat(RegistrationService.within(start.minusNanos(1), start, end)).isFalse();
        assertThat(RegistrationService.within(end, start, end)).isFalse();
    }

    @Test void sessionSupportsMultipleDaysAndDoesNotCheckPrerequisites() {
        var now = Instant.parse("2026-10-25T00:00:00Z");
        var period = new Period("2026-1", now, now, now.plusSeconds(60), PeriodStatus.OPEN, 9);
        var mondayAndWednesday = new Section(1, "C1", "2026-1", "GV", 10, 0, 1025, "A", 3, "Chưa xác minh tiên quyết");
        var wednesday = new Section(2, "C2", "2026-1", "GV", 10, 0, 1024, "B", 3, "");
        assertThatCode(() -> RegistrationService.checkEnrollment(period, List.of(mondayAndWednesday), List.of())).doesNotThrowAnyException();
        assertThatThrownBy(() -> RegistrationService.checkEnrollment(period, List.of(mondayAndWednesday, wednesday), List.of()))
            .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.body().code()).isEqualTo("SESSION_CONFLICT"));
    }

    @Test void rateLimitHasRetryAfterAndExpires() {
        var clock = new MutableClock();
        var limiter = new MemoryRateLimiter(clock, 2, 1, 1);
        limiter.ip("127.0.0.1"); limiter.ip("127.0.0.1");
        assertThatThrownBy(() -> limiter.ip("127.0.0.1")).isInstanceOfSatisfying(BusinessException.class, e -> {
            assertThat(e.status()).isEqualTo(429);
            assertThat(e.body().details()).isEqualTo(java.util.Map.of("retryAfter", 60));
        });
        limiter.account(1);
        assertThatThrownBy(() -> limiter.account(1)).isInstanceOf(BusinessException.class);
        limiter.login("SV1");
        assertThatThrownBy(() -> limiter.login("SV1")).isInstanceOf(BusinessException.class);
        clock.now = clock.now.plusSeconds(60);
        assertThatCode(() -> limiter.ip("127.0.0.1")).doesNotThrowAnyException();
    }

    @Test void missingJwtNeverAcceptsBearerOrIssuesToken() {
        var gateway = BackendConfig.missingTokenGateway();
        assertThatThrownBy(() -> gateway.verify("anything")).isInstanceOfSatisfying(BusinessException.class, e -> {
            assertThat(e.status()).isEqualTo(503); assertThat(e.body().code()).isEqualTo("AUTH_NOT_CONFIGURED");
        });
        assertThatThrownBy(() -> gateway.issue(new Identity(1, Role.STUDENT))).isInstanceOf(BusinessException.class);
    }

    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-25T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
