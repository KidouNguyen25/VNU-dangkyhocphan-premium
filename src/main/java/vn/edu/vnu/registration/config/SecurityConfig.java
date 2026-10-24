package vn.edu.vnu.registration.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import vn.edu.vnu.registration.api.ApiSecurityFilter;
import vn.edu.vnu.registration.api.MemoryRateLimiter;
import vn.edu.vnu.registration.data.PostgresRepository;
import static vn.edu.vnu.registration.domain.Models.*;
import static vn.edu.vnu.registration.domain.Ports.*;

@Configuration
public class SecurityConfig {
    @Bean public SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectProvider<TokenGateway> tokens, MemoryRateLimiter limiter,
                                                       ObjectMapper json, PostgresRepository repository,
                                                       @Value("${app.health-allowed-ips}") String healthIps) throws Exception {
        return http.csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .requestCache(c -> c.disable()).formLogin(c -> c.disable()).httpBasic(c -> c.disable()).logout(c -> c.disable())
            .authorizeHttpRequests(a -> a
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/health").permitAll()
                .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                .requestMatchers("/api/v1/enrollments", "/api/v1/enrollments/**").hasRole("STUDENT")
                .requestMatchers("/api/v1/**").authenticated().anyRequest().denyAll())
            .exceptionHandling(e -> e
                .authenticationEntryPoint((req, res, ex) -> ApiSecurityFilter.writeError(res, 401, new ErrorBody("UNAUTHORIZED", "Cần Bearer token hợp lệ.", null), json))
                .accessDeniedHandler((req, res, ex) -> ApiSecurityFilter.writeError(res, 403, new ErrorBody("FORBIDDEN", "Không có quyền thực hiện thao tác.", null), json)))
            .addFilterBefore(new ApiSecurityFilter(tokens.getIfAvailable(BackendConfig::missingTokenGateway), limiter, json, healthIps, repository), UsernamePasswordAuthenticationFilter.class)
            .build();
    }
}
