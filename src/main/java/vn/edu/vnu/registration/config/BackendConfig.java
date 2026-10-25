package vn.edu.vnu.registration.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import vn.edu.vnu.registration.data.PostgresRepository;
import vn.edu.vnu.registration.domain.RegistrationService;

import javax.sql.DataSource;
import java.time.Clock;
import java.util.UUID;
import static vn.edu.vnu.registration.domain.Models.*;
import static vn.edu.vnu.registration.domain.Ports.*;

@Configuration
public class BackendConfig {
    @Bean @ConditionalOnMissingBean public Clock clock() { return Clock.systemUTC(); }
    @Bean public PostgresRepository repository(JdbcTemplate jdbc, DataSource ds, ObjectMapper json) {
        return new PostgresRepository(jdbc, new TransactionTemplate(new DataSourceTransactionManager(ds)), json);
    }
    @Bean public RegistrationService registrationService(PostgresRepository repo, ObjectProvider<TokenGateway> tokens, Clock clock) {
        var encoder = new BCryptPasswordEncoder(10);
        return new RegistrationService(repo,
                tokens.getIfAvailable(BackendConfig::missingTokenGateway),
                (raw, hash) -> hash.equals(raw) || encoder.matches(raw, hash), // TODO: Xoá cái check plain text(e tù lm r)
                encoder.encode(UUID.randomUUID().toString()),
                clock
        );
    }
    public static TokenGateway missingTokenGateway() {
        return new TokenGateway() {
            private BusinessException unavailable() { return error(503, "AUTH_NOT_CONFIGURED", "Chưa tích hợp module JWT của nhóm."); }
            @Override public Token issue(Identity identity) { throw unavailable(); }
            @Override public Identity verify(String token) { throw unavailable(); }
        };
    }
    @Bean public OpenAPI openAPI() {
        return new OpenAPI().info(new Info().title("VNU Đăng ký học phần — K5").version("1.0")
            .description("JSON camelCase; thời gian UTC. JWT do module của đồng đội cung cấp. Chỗ trống chỉ có giá trị tham khảo."))
            .components(new Components().addSecuritySchemes("bearerAuth", new SecurityScheme()
                .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")));
    }
}
