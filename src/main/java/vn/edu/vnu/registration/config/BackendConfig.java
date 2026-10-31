package vn.edu.vnu.registration.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import vn.edu.vnu.registration.data.*;
import vn.edu.vnu.registration.data.metrics.QueryTimer;
import vn.edu.vnu.registration.domain.RegistrationService;
import vn.edu.vnu.registration.domain.auth.AuthService;
import vn.edu.vnu.registration.domain.auth.RegistrationPeriodService;
import vn.edu.vnu.registration.domain.auth.model.Identity;
import vn.edu.vnu.registration.domain.auth.model.Token;
import vn.edu.vnu.registration.domain.enrollment.EnrollmentService;
import vn.edu.vnu.registration.domain.enrollment.SectionService;
import vn.edu.vnu.registration.domain.exception.BusinessException;
import vn.edu.vnu.registration.domain.idempotency.IdempotencyService;
import vn.edu.vnu.registration.domain.port.*;

import javax.sql.DataSource;
import java.time.Clock;
import java.util.UUID;

import static vn.edu.vnu.registration.domain.exception.BusinessException.error;

@Configuration
public class BackendConfig {
    @Bean
    @ConditionalOnMissingBean
    public Clock clock() { return Clock.systemUTC(); }

    @Bean
    @ConditionalOnMissingBean
    public QueryTimer queryTimer() { return new QueryTimer(); }

    @Bean
    @Primary
    public PostgresRepository repository(JdbcTemplate jdbc, DataSource ds, ObjectMapper json) {
        return new PostgresRepository(jdbc, new TransactionTemplate(new DataSourceTransactionManager(ds)), json);
    }

    @Bean
    public AccountRepository accountRepository(JdbcTemplate jdbc, QueryTimer timer) {
        return new AccountRepositoryImpl(jdbc, timer);
    }

    @Bean
    public RegistrationPeriodRepository registrationPeriodRepository(JdbcTemplate jdbc, QueryTimer timer) {
        return new RegistrationPeriodRepositoryImpl(jdbc, timer);
    }

    @Bean
    public SectionRepository sectionRepository(JdbcTemplate jdbc, QueryTimer timer) {
        return new SectionRepositoryImpl(jdbc, timer);
    }

    @Bean
    public EnrollmentRepository enrollmentRepository(JdbcTemplate jdbc, QueryTimer timer) {
        return new EnrollmentRepositoryImpl(jdbc, timer);
    }

    @Bean
    public IdempotencyRepository idempotencyRepository(JdbcTemplate jdbc, QueryTimer timer, ObjectMapper json) {
        return new IdempotencyRepositoryImpl(jdbc, timer, json);
    }

    @Bean
    public TransactionRunner transactionRunner(JdbcTemplate jdbc, DataSource ds, QueryTimer timer) {
        return new TransactionRunnerImpl(jdbc, timer, new TransactionTemplate(new DataSourceTransactionManager(ds)));
    }

    @Bean
    public IdempotencyService idempotencyService(IdempotencyRepository repo, TransactionRunner txRunner) {
        return new IdempotencyService(repo, txRunner);
    }

    @Bean
    public AuthService authService(
            AccountRepository aRepo,
            RegistrationPeriodRepository rRepo,
            ObjectProvider<TokenGateway> tokens,
            Clock clock
    ) {
        var encoder = new BCryptPasswordEncoder(10);
        return new AuthService(aRepo, rRepo, tokens.getIfAvailable(BackendConfig::missingTokenGateway), encoder::matches, encoder.encode(UUID.randomUUID().toString()), clock);
    }

    @Bean
    public EnrollmentService enrollmentService(
            EnrollmentRepository enrollmentRepo,
            SectionRepository sectionRepo,
            RegistrationPeriodRepository periodRepo,
            AccountRepository accountRepo,
            TransactionRunner txRunner,
            IdempotencyService idempotencyService,
            Clock clock
    ) {
        return new EnrollmentService(enrollmentRepo, sectionRepo, periodRepo, accountRepo, txRunner, idempotencyService, clock);
    }

    @Bean
    public SectionService sectionService(SectionRepository sectionRepo, TransactionRunner txRunner) {
        return new SectionService(sectionRepo, txRunner);
    }

    @Bean
    public RegistrationPeriodService registrationPeriodService(RegistrationPeriodRepository periodRepo, TransactionRunner txRunner, Clock clock) {
        return new RegistrationPeriodService(periodRepo, txRunner, clock);
    }

    @Bean
    public RegistrationService registrationService(
            AuthService authService,
            EnrollmentService enrollmentService,
            SectionService sectionService,
            RegistrationPeriodService periodService,
            JdbcTemplate jdbc,
            Clock clock
    ) {
        return new RegistrationService(authService, enrollmentService, sectionService, periodService, jdbc, clock);
    }

    public static TokenGateway missingTokenGateway() {
        return new TokenGateway() {
            private BusinessException unavailable() {
                return error(503, "AUTH_NOT_CONFIGURED", "Chưa cấu hình JWT_SECRET.");
            }

            @Override
            public Token issue(Identity identity) { throw unavailable(); }

            @Override
            public Identity verify(String token) { throw unavailable(); }
        };
    }

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI().info(new Info().title("VNU Đăng ký học phần").version("1.0")
            .description("JSON camelCase; thời gian ISO 8601 UTC. Lỗi có dạng {code, message, details}. Bearer JWT HS256, hết hạn sau 60 phút. Chỗ trống chỉ có giá trị tham khảo."))
            .components(new Components().addSecuritySchemes("bearerAuth", new SecurityScheme()
                .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")));
    }
}
