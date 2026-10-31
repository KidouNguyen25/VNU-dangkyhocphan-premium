package vn.edu.vnu.registration.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import vn.edu.vnu.registration.domain.RegistrationService;
import vn.edu.vnu.registration.domain.auth.model.Identity;
import vn.edu.vnu.registration.domain.auth.model.RegistrationPeriod;
import vn.edu.vnu.registration.domain.auth.model.Token;
import vn.edu.vnu.registration.domain.enrollment.model.SectionQuery;
import vn.edu.vnu.registration.domain.exception.ErrorBody;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.*;

import static vn.edu.vnu.registration.api.ApiDtos.*;
import static vn.edu.vnu.registration.domain.exception.BusinessException.error;

@RestController
@RequestMapping(value = "/api/v1", produces = "application/json")
@ApiResponses({
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR", content = @Content(schema = @Schema(implementation = ErrorBody.class))),
    @ApiResponse(responseCode = "401", description = "UNAUTHORIZED / INVALID_CREDENTIALS", content = @Content(schema = @Schema(implementation = ErrorBody.class))),
    @ApiResponse(responseCode = "403", description = "FORBIDDEN / LOGIN_NOT_OPEN / ADMIN_LOGIN_LOCKED / REGISTRATION_NOT_OPEN", content = @Content(schema = @Schema(implementation = ErrorBody.class))),
    @ApiResponse(responseCode = "429", description = "RATE_LIMITED; header Retry-After tính bằng giây", content = @Content(schema = @Schema(implementation = ErrorBody.class))),
    @ApiResponse(responseCode = "500", description = "INTERNAL_ERROR; transaction rollback", content = @Content(schema = @Schema(implementation = ErrorBody.class))),
    @ApiResponse(responseCode = "503", description = "AUTH_NOT_CONFIGURED / SERVICE_UNAVAILABLE; có thể gửi lại", content = @Content(schema = @Schema(implementation = ErrorBody.class)))
})
public class ApiController {
    private final RegistrationService service;
    private final MemoryRateLimiter limiter;
    private final ObjectMapper json;
    private final Clock clock;

    public ApiController(RegistrationService service, MemoryRateLimiter limiter, ObjectMapper json, Clock clock) {
        this.service = service; this.limiter = limiter; this.json = json; this.clock = clock;
    }

    @PostMapping("/auth/login")
    @Operation(summary = "Đăng nhập và nhận JWT")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = Token.class)))
    public Token login(
            @Valid
            @RequestBody
            LoginRequest request
    ) {
        limiter.login(request.code());
        if (request.password().getBytes(StandardCharsets.UTF_8).length > 72) throw error(400, "VALIDATION_ERROR", "Mật khẩu không được dài hơn 72 byte UTF-8.");
        return service.login(request.code(), request.password());
    }

    @GetMapping("/me")
    @Operation(summary = "Tài khoản hiện tại", security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = MeResponse.class)))
    public Map<String, Object> me(@Parameter(hidden = true) @AuthenticationPrincipal Identity identity) { return service.me(identity); }

    @GetMapping("/registration")
    @Operation(summary = "Đợt OPEN hiện tại và giờ máy chủ; công khai trong mạng Tailscale")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = PeriodResponse.class)))
    @ApiResponse(responseCode = "304", description = "ETag khớp; giờ hiện tại ở X-Server-Time", content = @Content)
    public ResponseEntity<?> registration(HttpServletRequest request) {
        allowedQueries(request, Set.of());
        var body = service.registration();
        return cached(request, body, body.get("registration"));
    }

    @GetMapping("/sections")
    @Operation(summary = "Tra cứu lớp công khai trong mạng Tailscale; cursor là id cuối trang trước, limit mặc định 20")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = SectionsResponse.class)))
    @ApiResponse(responseCode = "304", description = "ETag khớp", content = @Content)
    public ResponseEntity<?> sections(@RequestParam(required = false) @Size(min = 1, max = 10) String term,
                                      @RequestParam(required = false) @Size(min = 1, max = 12) String courseCode,
                                      @RequestParam(required = false) @Size(min = 1, max = 150) String q,
                                      @RequestParam(required = false) Boolean hasSeats,
                                      @RequestParam(required = false) @Positive Long cursor,
                                      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
                                      HttpServletRequest request) {
        allowedQueries(request, Set.of("term", "courseCode", "q", "hasSeats", "cursor", "limit"));
        var body = service.sections(new SectionQuery(term, courseCode, q, hasSeats, cursor, limit));
        return cached(request, body, body);
    }

    @GetMapping("/enrollments")
    @Operation(summary = "Lượt ENROLLED của sinh viên từ JWT", security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = EnrollmentsResponse.class)))
    public Map<String, Object> enrollments(@Parameter(hidden = true) @AuthenticationPrincipal Identity identity,
                                           @RequestParam(required = false) @Size(min = 1, max = 10) String term, HttpServletRequest request) {
        allowedQueries(request, Set.of("term"));
        return service.enrollments(identity.accountId(), term);
    }

    @PostMapping("/enrollments")
    @Operation(summary = "Đăng ký 1–100 lớp, tất cả hoặc không",
        description = "Idempotency-Key bắt buộc, phạm vi sinh viên, TTL 24 giờ. Hash theo sectionIds đã sắp xếp. Cùng key/hash phát lại status/body; khác hash 409. Lỗi nghiệp vụ được lưu; 5xx rollback toàn bộ.",
        security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponse(responseCode = "201", content = @Content(schema = @Schema(implementation = EnrollResponse.class)))
    @ApiResponse(responseCode = "404", description = "SECTION_NOT_FOUND", content = @Content(schema = @Schema(implementation = ErrorBody.class)))
    @ApiResponse(responseCode = "409", description = "SECTION_FULL / SECTION_ALREADY_ENROLLED / COURSE_ALREADY_ENROLLED / IDEMPOTENCY_KEY_REUSED", content = @Content(schema = @Schema(implementation = ErrorBody.class)))
    @ApiResponse(responseCode = "422", description = "SECTION_TERM_MISMATCH / SESSION_CONFLICT / CREDIT_LIMIT_EXCEEDED", content = @Content(schema = @Schema(implementation = ErrorBody.class)))
    public ResponseEntity<?> enroll(@Parameter(hidden = true) @AuthenticationPrincipal Identity identity,
                                     @RequestHeader("Idempotency-Key") @Size(min = 1, max = 128) String key,
                                     @Valid @RequestBody EnrollRequest request) {
        var result = service.enroll(identity.accountId(), key, request.sectionIds());
        return ResponseEntity.status(result.status()).header("X-Idempotent-Replay", String.valueOf(result.replayed())).body(result.body());
    }

    @DeleteMapping("/enrollments/{enrollmentId}")
    @Operation(summary = "Hủy của chính mình trong đợt mở; hủy lặp trả 404", security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponse(responseCode = "204", description = "Đã hủy, hoàn chỗ đúng một lần", content = @Content)
    @ApiResponse(responseCode = "404", description = "ENROLLMENT_NOT_FOUND, gồm cả id của người khác", content = @Content(schema = @Schema(implementation = ErrorBody.class)))
    public ResponseEntity<Void> cancel(@Parameter(hidden = true) @AuthenticationPrincipal Identity identity, @PathVariable @Positive long enrollmentId) {
        service.cancel(identity.accountId(), enrollmentId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/admin/sections/{sectionId}")
    @Operation(summary = "ADMIN: tạo/cập nhật lớp; không thay identity hoặc lịch của lớp đã có đăng ký", security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = SectionResponse.class)))
    @ApiResponse(responseCode = "201", content = @Content(schema = @Schema(implementation = SectionResponse.class)))
    @ApiResponse(responseCode = "422", description = "CAPACITY_BELOW_ENROLLED / SECTION_HAS_ENROLLMENTS / COURSE_NOT_FOUND / REGISTRATION_NOT_FOUND", content = @Content(schema = @Schema(implementation = ErrorBody.class)))
    public ResponseEntity<?> saveSection(@PathVariable @Positive long sectionId, @Valid @RequestBody SectionRequest request) {
        var outcome = service.saveSection(sectionId, request.toDomain());
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    @PutMapping("/admin/registration")
    @Operation(summary = "ADMIN: upsert đợt theo term; tối đa một OPEN", security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = RegistrationPeriod.class)))
    @ApiResponse(responseCode = "409", description = "OPEN_PERIOD_EXISTS", content = @Content(schema = @Schema(implementation = ErrorBody.class)))
    public RegistrationPeriod savePeriod(@Valid @RequestBody PeriodRequest request) { return service.savePeriod(request.toDomain()); }

    @GetMapping("/health")
    @Operation(summary = "SELECT 1, chỉ IP được phép; không cần JWT")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = HealthResponse.class)))
    public HealthResponse health() {
        var body = service.health();
        return new HealthResponse((String) body.get("status"), (java.time.Instant) body.get("serverTime"));
    }

    private void allowedQueries(HttpServletRequest request, Set<String> allowed) {
        if (!allowed.containsAll(request.getParameterMap().keySet()) || request.getParameterMap().values().stream().anyMatch(v -> v.length != 1)) {
            throw error(400, "VALIDATION_ERROR", "Tham số truy vấn không được hỗ trợ hoặc bị lặp.");
        }
    }
    private ResponseEntity<?> cached(HttpServletRequest request, Object body, Object version) {
        try {
            String etag = "\"" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsString(version).getBytes(StandardCharsets.UTF_8))) + "\"";
            var headers = new HttpHeaders();
            headers.setETag(etag); headers.setCacheControl("private, max-age=0, must-revalidate");
            headers.setVary(List.of("Authorization")); headers.set("X-Server-Time", clock.instant().toString());
            String condition = request.getHeader("If-None-Match");
            boolean matches = condition != null && Arrays.stream(condition.split(",")).map(String::trim).anyMatch(v -> v.equals("*") || v.replaceFirst("^W/", "").equals(etag));
            return new ResponseEntity<>(matches ? null : body, headers, matches ? 304 : 200);
        } catch (NoSuchAlgorithmException | JsonProcessingException e) { throw new IllegalStateException(e); }
    }
}
