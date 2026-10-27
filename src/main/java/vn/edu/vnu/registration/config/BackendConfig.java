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

/**
 * Cấu hình backend của hệ thống
 */
@Configuration
public class BackendConfig {

    /**
     * Khởi tạo Bean {@link Clock} làm nguồn cung cấp thời gian thực chuẩn theo múi giờ UTC.
     * <p>
     * Sử dụng {@link ConditionalOnMissingBean} để cho phép ghi đè bằng một {@code Clock} giả lập
     * trong các bài kiểm thử tự động (Unit/Integration Test).
     *
     * @return đối tượng {@link Clock} theo múi giờ UTC
     */
    @Bean
    @ConditionalOnMissingBean
    public Clock clock() { return Clock.systemUTC(); }

    /**
     * Khởi tạo repository
     * @param jdbc đối tượng {@link JdbcTemplate} để thực thi các câu lệnh SQL
     * @param ds nguồn dữ liệu {@link DataSource} kết nối đến db
     * @param json trình xử lý {@link ObjectMapper} để serialize/deserialize dữ liệu JSON
     * @return instance của {@link PostgresRepository} được tích hợp cùng {@link TransactionTemplate}
     */
    @Bean
    public PostgresRepository repository(JdbcTemplate jdbc, DataSource ds, ObjectMapper json) {
        return new PostgresRepository(jdbc, new TransactionTemplate(new DataSourceTransactionManager(ds)), json);
    }

    /**
     * Khởi tạo Bean dịch vụ nghiệp vụ chính {@link RegistrationService}.
     * <p>
     * Cấu hình dịch vụ sử dụng thuật toán {@link BCryptPasswordEncoder} (strength = 10) để Encrypt.
     * kết hợp với chuỗi băm ngẫu nhiên {@code dummyHash} khi tài khoản không tồn tại.
     *
     * @param repo {@link PostgresRepository}
     * @param tokens {@link TokenGateway}
     * @param clock đồng hồ hệ thống {@link Clock}
     * @return đối tượng {@link RegistrationService} xử lý logic đăng ký học phần
     */
    @Bean
    public RegistrationService registrationService(PostgresRepository repo, ObjectProvider<TokenGateway> tokens, Clock clock) {
        var encoder = new BCryptPasswordEncoder(10);
        return new RegistrationService(repo,
                tokens.getIfAvailable(BackendConfig::missingTokenGateway),
                (raw, hash) -> hash.equals(raw) || encoder.matches(raw, hash),
                encoder.encode(UUID.randomUUID().toString()),
                clock
        );
    }

    /**
     * Trong trường hợp khi hệ thống chưa tích hợp Module xử lý JWT hoàn chỉnh:
     * <p>
     * Mọi lời gọi phương thức cấp phát hoặc xác thực trên TokenGateway này
     * sẽ ném ra lỗi {@link BusinessException} với mã HTTP 503 (AUTH_NOT_CONFIGURED).
     *
     * @return đối tượng {@link TokenGateway} dạng stub ném lỗi khi sử dụng
     */
    public static TokenGateway missingTokenGateway() {
        return new TokenGateway() {
            private BusinessException unavailable() { return error(503, "AUTH_NOT_CONFIGURED", "Chưa tích hợp module JWT của nhóm."); }
            @Override public Token issue(Identity identity) { throw unavailable(); }
            @Override public Identity verify(String token) { throw unavailable(); }
        };
    }

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI().info(new Info().title("VNU Đăng ký học phần — K5").version("1.0")
            .description("JSON camelCase; thời gian UTC. JWT do module của đồng đội cung cấp. Chỗ trống chỉ có giá trị tham khảo."))
            .components(new Components().addSecuritySchemes("bearerAuth", new SecurityScheme()
                .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")));
    }
}
