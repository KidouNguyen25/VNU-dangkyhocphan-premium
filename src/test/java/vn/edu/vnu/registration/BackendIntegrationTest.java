package vn.edu.vnu.registration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import vn.edu.vnu.registration.data.PostgresRepository;
import vn.edu.vnu.registration.domain.RegistrationService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static vn.edu.vnu.registration.domain.Models.*;
import static vn.edu.vnu.registration.domain.Ports.*;

@SpringBootTest(properties = {"app.rate-limit.ip=10000", "app.rate-limit.account=10000", "app.rate-limit.login=10000", "debug=false", "logging.level.root=WARN", "logging.level.org.springframework=WARN", "logging.level.vn.edu.vnu.registration.api.ApiSecurityFilter=WARN"})
@AutoConfigureMockMvc
class BackendIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-25T03:00:00Z");
    private static final String PASSWORD = "demo-test-only";
    private static final String HASH = new BCryptPasswordEncoder(4).encode(PASSWORD);
    private static final EmbeddedPostgres PG = postgres();
    private static final JdbcTemplate OWNER = new JdbcTemplate(PG.getPostgresDatabase());
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired RegistrationService service;
    @Autowired PostgresRepository repository;

    static EmbeddedPostgres postgres() {
        try {
            Files.createDirectories(Path.of("target/tmp"));
            var pg = EmbeddedPostgres.builder().setPort(0).start();
            new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(pg.getPostgresDatabase());
            var owner = new JdbcTemplate(pg.getPostgresDatabase());
            owner.execute("CREATE ROLE api_test LOGIN PASSWORD 'local-test-only'");
            owner.execute("GRANT USAGE ON SCHEMA public TO api_test");
            owner.execute("GRANT SELECT ON account, course TO api_test");
            owner.execute("GRANT UPDATE (id) ON account TO api_test");
            owner.execute("GRANT SELECT, INSERT, UPDATE ON registration_period, section, idempotency_record TO api_test");
            owner.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON enrollment TO api_test");
            owner.execute("GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO api_test");
            return pg;
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }

    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> PG.getJdbcUrl("api_test", "postgres"));
        registry.add("spring.datasource.username", () -> "api_test");
        registry.add("spring.datasource.password", () -> "local-test-only");
    }

    @TestConfiguration static class AuthTestConfig {
        @Bean @Primary Clock testClock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
        // Chỉ nằm trong test classpath, không phải bộ ký/xác minh JWT cho môi trường thật.
        @Bean @Primary TokenGateway testTokens() {
            return new TokenGateway() {
                @Override public Token issue(Identity i) { return new Token("test-" + i.role() + "-" + i.accountId(), "Bearer", 3600); }
                @Override public Identity verify(String token) {
                    var parts = token.split("-");
                    if (parts.length != 3 || !parts[0].equals("test")) throw error(401, "UNAUTHORIZED", "Token kiểm thử không hợp lệ.");
                    return new Identity(Long.parseLong(parts[2]), Role.valueOf(parts[1]));
                }
            };
        }
    }

    @BeforeEach void seed() {
        OWNER.execute("TRUNCATE idempotency_record, enrollment, section, registration_period, course, account RESTART IDENTITY CASCADE");
        for (int i = 1; i <= 24; i++) OWNER.update("INSERT INTO account (id, code, full_name, password_hash, role) VALUES (?, ?, ?, ?, 'STUDENT')", i, "SV" + i, "Sinh viên " + i, HASH);
        OWNER.update("INSERT INTO account (id, code, full_name, password_hash, role) VALUES (99, 'admin', 'Quản trị', ?, 'ADMIN')", HASH);
        OWNER.execute("INSERT INTO course VALUES ('C1', 'Kiến trúc phần mềm', 3, ''), ('C2', 'Cơ sở dữ liệu', 3, 'C1'), ('C3', 'Mạng', 4, ''), ('C4', 'Toán', 10, '')");
        OWNER.update("INSERT INTO registration_period VALUES ('2026-1', ?, ?, ?, 'OPEN', 9)", ts(NOW.minusSeconds(3600)), ts(NOW.minusSeconds(60)), ts(NOW.plusSeconds(3600)));
        OWNER.execute("INSERT INTO section (id, course_code, term, lecturer, capacity, session) VALUES (1,'C1','2026-1','GV1',30,1), (2,'C2','2026-1','GV2',30,2), (3,'C1','2026-1','GV3',30,4), (4,'C3','2026-1','GV4',30,1), (5,'C4','2026-1','GV5',30,8)");
    }
    static java.sql.Timestamp ts(Instant i) { return java.sql.Timestamp.from(i); }
    private MockHttpServletRequestBuilder student(MockHttpServletRequestBuilder request, long id) { return request.header("Authorization", "Bearer test-STUDENT-" + id); }
    private MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder request) { return request.header("Authorization", "Bearer test-ADMIN-99"); }
    private MvcResult perform(MockHttpServletRequestBuilder request) throws Exception { return mvc.perform(request).andReturn(); }
    private MvcResult enroll(long student, String key, long... ids) throws Exception {
        return perform(student(post("/api/v1/enrollments"), student).header("Idempotency-Key", key).contentType("application/json").content(json.writeValueAsString(Map.of("sectionIds", ids))));
    }
    private JsonNode body(MvcResult result) throws Exception { return json.readTree(result.getResponse().getContentAsString()); }
    private void status(MvcResult result, int status) throws Exception { assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(status); }
    private void code(MvcResult result, int status, String code) throws Exception { status(result, status); assertThat(body(result).path("code").asText()).isEqualTo(code); }
    private long enrollmentId(MvcResult result) throws Exception { return body(result).path("items").get(0).path("enrollmentId").asLong(); }
    private void invariants() {
        assertThat(OWNER.queryForObject("SELECT count(*) FROM section s WHERE enrolled_count <> (SELECT count(*) FROM enrollment e WHERE e.section_id=s.id AND e.status='ENROLLED') OR enrolled_count > capacity OR enrolled_count < 0", Long.class)).isZero();
        assertThat(OWNER.queryForObject("SELECT count(*) FROM (SELECT student_id, s.term, s.course_code FROM enrollment e JOIN section s ON s.id=e.section_id WHERE e.status='ENROLLED' GROUP BY student_id,s.term,s.course_code HAVING count(*)>1) t", Long.class)).isZero();
        assertThat(OWNER.queryForObject("SELECT count(*) FROM enrollment a JOIN enrollment b ON a.student_id=b.student_id AND a.id<b.id JOIN section sa ON a.section_id=sa.id JOIN section sb ON b.section_id=sb.id WHERE a.status='ENROLLED' AND b.status='ENROLLED' AND sa.term=sb.term AND (sa.session & sb.session)<>0", Long.class)).isZero();
        assertThat(OWNER.queryForObject("SELECT count(*) FROM (SELECT e.student_id,s.term,p.credit_limit,sum(c.credits) credits FROM enrollment e JOIN section s ON s.id=e.section_id JOIN course c ON c.code=s.course_code JOIN registration_period p ON p.term=s.term WHERE e.status='ENROLLED' GROUP BY e.student_id,s.term,p.credit_limit HAVING sum(c.credits)>p.credit_limit) t", Long.class)).isZero();
        assertThat(OWNER.queryForObject("SELECT count(*) FROM idempotency_record WHERE state <> 'COMPLETED'", Long.class)).isZero();
    }
    @AfterEach void verifyInvariants() { invariants(); }

    @Test void authenticationAuthorizationAndHealthAllowlist() throws Exception {
        code(perform(get("/api/v1/me")), 401, "UNAUTHORIZED");
        code(perform(get("/api/v1/me").header("Authorization", "Bearer expired")), 401, "UNAUTHORIZED");
        code(perform(student(put("/api/v1/admin/registration"), 1).contentType("application/json").content("{}")), 403, "FORBIDDEN");
        code(perform(admin(get("/api/v1/enrollments"))), 403, "FORBIDDEN");
        status(perform(get("/api/v1/health")), 200);
        code(perform(get("/api/v1/health").with(req -> { req.setRemoteAddr("100.64.0.55"); return req; }).header("X-Forwarded-For", "127.0.0.1")), 403, "FORBIDDEN");
        var me = perform(student(get("/api/v1/me"), 1)); status(me, 200);
        assertThat(body(me).path("id").asLong()).isEqualTo(1);
        assertThat(body(me).has("passwordHash")).isFalse();
        assertThat(me.getResponse().getHeader("X-Request-ID")).isNotBlank();
        assertThat(me.getResponse().getHeader("Set-Cookie")).isNull();
    }

    @Test void loginWindowsAndTokenDelegation() throws Exception {
        var request = Map.of("code", "SV1", "password", PASSWORD);
        var login = perform(post("/api/v1/auth/login").contentType("application/json").content(json.writeValueAsString(request)));
        status(login, 200); assertThat(body(login).path("expiresIn").asInt()).isEqualTo(3600);
        code(perform(post("/api/v1/auth/login").contentType("application/json").content(json.writeValueAsString(Map.of("code", "SV1", "password", "wrong")))), 401, "INVALID_CREDENTIALS");
        code(perform(post("/api/v1/auth/login").contentType("application/json").content(json.writeValueAsString(Map.of("code", "admin", "password", PASSWORD)))), 403, "ADMIN_LOGIN_LOCKED");
        OWNER.update("UPDATE registration_period SET opens_at = ?", ts(NOW.plusSeconds(10)));
        status(perform(post("/api/v1/auth/login").contentType("application/json").content(json.writeValueAsString(Map.of("code", "admin", "password", PASSWORD)))), 200);
        OWNER.update("UPDATE registration_period SET closes_at = ?, opens_at = ?", ts(NOW), ts(NOW.minusSeconds(10)));
        code(perform(post("/api/v1/auth/login").contentType("application/json").content(json.writeValueAsString(request))), 403, "LOGIN_NOT_OPEN");
        // Token ADMIN cấp trước vẫn dùng được khi đợt đang mở.
        status(perform(admin(get("/api/v1/me"))), 200);
    }

    @Test void lookupFiltersCursorCacheAndOwnEnrollments() throws Exception {
        var first = perform(student(get("/api/v1/sections").param("term", "2026-1").param("limit", "1"), 1)); status(first, 200);
        assertThat(body(first).path("items").size()).isEqualTo(1);
        String cursor = body(first).path("nextCursor").asText();
        var next = perform(student(get("/api/v1/sections").param("cursor", cursor).param("limit", "1"), 1)); status(next, 200);
        assertThat(body(next).path("items").get(0).path("id").asLong()).isGreaterThan(Long.parseLong(cursor));
        var filtered = perform(student(get("/api/v1/sections").param("courseCode", "C2").param("q", "dữ liệu").param("hasSeats", "true"), 1));
        status(filtered, 200); assertThat(body(filtered).path("items").size()).isEqualTo(1);
        status(perform(student(get("/api/v1/sections").param("limit", "1").param("term", "2026-1"), 1).header("If-None-Match", first.getResponse().getHeader("ETag"))), 304);
        var registration = perform(student(get("/api/v1/registration"), 1)); status(registration, 200);
        assertThat(body(registration).path("serverTime").asText()).isEqualTo(NOW.toString());
        status(perform(student(get("/api/v1/registration"), 1).header("If-None-Match", registration.getResponse().getHeader("ETag"))), 304);
        status(enroll(1, "lookup", 1, 2), 201);
        var own = perform(student(get("/api/v1/enrollments"), 1)); status(own, 200);
        assertThat(body(own).path("creditsByTerm").path("2026-1").asInt()).isEqualTo(6);
        assertThat(body(perform(student(get("/api/v1/enrollments"), 2))).path("items").size()).isZero();
    }

    @Test void validationRejectsUnknownIdentityInvalidIdsAndMissingKey() throws Exception {
        code(perform(student(post("/api/v1/enrollments"), 1).contentType("application/json").content("{\"sectionIds\":[1]}")), 400, "VALIDATION_ERROR");
        for (String payload : List.of("{\"sectionIds\":[1,1]}", "{\"sectionIds\":[0]}", "{\"sectionIds\":[]}", "{\"sectionIds\":[null]}", "{\"sectionIds\":[1.5]}", "{\"sectionIds\":[\"1\"]}", "{\"sectionIds\":[1],\"studentId\":2}")) {
            code(perform(student(post("/api/v1/enrollments"), 1).header("Idempotency-Key", "invalid").contentType("application/json").content(payload)), 400, "VALIDATION_ERROR");
        }
        code(perform(student(get("/api/v1/enrollments").param("studentId", "2"), 1)), 400, "VALIDATION_ERROR");
        code(perform(student(delete("/api/v1/enrollments/-1"), 1)), 400, "VALIDATION_ERROR");
        code(perform(student(get("/api/v1/sections").param("limit", "101"), 1)), 400, "VALIDATION_ERROR");
        code(perform(student(get("/api/v1/sections").param("limit", "1").param("limit", "2"), 1)), 400, "VALIDATION_ERROR");
        assertThat(OWNER.queryForObject("SELECT count(*) FROM idempotency_record", Integer.class)).isZero();
    }

    @Test void rollbackWholeBatchAndReplayBusinessErrors() throws Exception {
        OWNER.execute("UPDATE section SET capacity=1 WHERE id=2");
        status(enroll(2, "fill", 2), 201);
        var result = enroll(1, "atomic", 1, 2); code(result, 409, "SECTION_FULL");
        assertThat(OWNER.queryForObject("SELECT enrolled_count FROM section WHERE id=1", Integer.class)).isZero();
        var replay = enroll(1, "atomic", 2, 1); code(replay, 409, "SECTION_FULL");
        assertThat(replay.getResponse().getContentAsString()).isEqualTo(result.getResponse().getContentAsString());
        assertThat(replay.getResponse().getHeader("X-Idempotent-Replay")).isEqualTo("true");
        code(enroll(1, "atomic", 1), 409, "IDEMPOTENCY_KEY_REUSED");
    }

    @Test void duplicatesCourseSessionCreditsMissingSectionAndTerm() throws Exception {
        status(enroll(1, "first", 1), 201);
        code(enroll(1, "duplicate", 1), 409, "SECTION_ALREADY_ENROLLED");
        code(enroll(1, "course", 3), 409, "COURSE_ALREADY_ENROLLED");
        code(enroll(1, "session", 4), 422, "SESSION_CONFLICT");
        code(enroll(1, "credits", 5), 422, "CREDIT_LIMIT_EXCEEDED");
        code(enroll(1, "missing", 999), 404, "SECTION_NOT_FOUND");
        code(enroll(2, "batch-course", 1, 3), 409, "COURSE_ALREADY_ENROLLED");
        code(enroll(2, "batch-session", 1, 4), 422, "SESSION_CONFLICT");
        OWNER.execute("INSERT INTO registration_period SELECT '2025-1',login_opens_at,opens_at,closes_at,'CLOSED',credit_limit FROM registration_period");
        OWNER.execute("INSERT INTO section (id,course_code,term,lecturer,capacity,session) VALUES (9,'C2','2025-1','GV',10,16)");
        code(enroll(2, "wrong-term", 9), 422, "SECTION_TERM_MISMATCH");
    }

    @Test void cancellationOwnershipRepeatAndRegisterAgain() throws Exception {
        long id = enrollmentId(enroll(1, "create", 1));
        code(perform(student(delete("/api/v1/enrollments/" + id), 2)), 404, "ENROLLMENT_NOT_FOUND");
        status(perform(student(delete("/api/v1/enrollments/" + id), 1)), 204);
        code(perform(student(delete("/api/v1/enrollments/" + id), 1)), 404, "ENROLLMENT_NOT_FOUND");
        status(enroll(1, "new-key", 1), 201);
        assertThat(OWNER.queryForObject("SELECT enrolled_count FROM section WHERE id=1", Integer.class)).isEqualTo(1);
        var oldReplay = enroll(1, "create", 1); status(oldReplay, 201);
        assertThat(enrollmentId(oldReplay)).isEqualTo(id);
    }

    @Test void expiryAndReplayAcrossNewServiceInstance() throws Exception {
        var first = enroll(1, "durable", 1); status(first, 201);
        var tokens = new AuthTestConfig().testTokens();
        var restarted = new RegistrationService(repository, tokens, (raw, hash) -> false, HASH, Clock.fixed(NOW, ZoneOffset.UTC));
        var replay = restarted.enroll(1, "durable", List.of(1L));
        assertThat(replay.replayed()).isTrue(); assertThat(json.<JsonNode>valueToTree(replay.body())).isEqualTo(body(first));
        OWNER.execute("UPDATE idempotency_record SET expires_at=clock_timestamp()-interval '1 second' WHERE idempotency_key='durable'");
        status(enroll(1, "durable", 2), 201);
        assertThat(OWNER.queryForObject("SELECT count(*) FROM idempotency_record WHERE idempotency_key='durable'", Integer.class)).isEqualTo(1);
    }

    @Test void exactTimeBoundariesAndClosedCancellation() throws Exception {
        OWNER.update("UPDATE registration_period SET opens_at=?", ts(NOW));
        var enrolled = enroll(1, "boundary-open", 1); status(enrolled, 201);
        OWNER.update("UPDATE registration_period SET opens_at=?, closes_at=?", ts(NOW.minusSeconds(1)), ts(NOW));
        code(enroll(2, "boundary-close", 1), 403, "REGISTRATION_NOT_OPEN");
        code(perform(student(delete("/api/v1/enrollments/" + enrollmentId(enrolled)), 1)), 403, "REGISTRATION_NOT_OPEN");
        OWNER.execute("UPDATE registration_period SET status='CLOSED'");
        code(enroll(2, "closed", 1), 403, "REGISTRATION_NOT_OPEN");
    }

    @Test void adminUpsertsAndProtectsEnrolledSection() throws Exception {
        var input = new SectionRequestData("C1", "2026-1", "GV mới", 1, 1);
        status(saveSection(10, input), 201);
        status(saveSection(10, input), 200);
        status(enroll(1, "admin-protect", 1), 201);
        status(enroll(2, "admin-protect-2", 1), 201);
        code(saveSection(1, input), 422, "CAPACITY_BELOW_ENROLLED");
        code(saveSection(1, new SectionRequestData("C1", "2026-1", "GV", 30, 2)), 422, "SECTION_HAS_ENROLLMENTS");
        status(saveSection(1, new SectionRequestData("C1", "2026-1", "GV mới", 30, 1)), 200);
        code(saveSection(20, new SectionRequestData("missing", "2026-1", "GV", 10, 1)), 422, "COURSE_NOT_FOUND");
        code(saveSection(21, new SectionRequestData("C1", "missing", "GV", 10, 1)), 422, "REGISTRATION_NOT_FOUND");
        var p = new Period("2026-2", NOW.minusSeconds(120), NOW.minusSeconds(60), NOW.plusSeconds(3600), PeriodStatus.OPEN, 9);
        code(perform(admin(put("/api/v1/admin/registration")).contentType("application/json").content(json.writeValueAsString(p))), 409, "OPEN_PERIOD_EXISTS");
        var closed = new Period(p.term(), p.loginOpensAt(), p.opensAt(), p.closesAt(), PeriodStatus.CLOSED, 9);
        status(perform(admin(put("/api/v1/admin/registration")).contentType("application/json").content(json.writeValueAsString(closed))), 200);
        var invalid = new Period(p.term(), NOW.plusSeconds(1), NOW, NOW.plusSeconds(3600), PeriodStatus.CLOSED, 9);
        code(perform(admin(put("/api/v1/admin/registration")).contentType("application/json").content(json.writeValueAsString(invalid))), 400, "VALIDATION_ERROR");
    }
    private record SectionRequestData(String courseCode, String term, String lecturer, int capacity, int session) {}
    private MvcResult saveSection(long id, SectionRequestData data) throws Exception {
        return perform(admin(put("/api/v1/admin/sections/" + id)).contentType("application/json").content(json.writeValueAsString(data)));
    }

    private List<MvcResult> concurrent(List<Callable<MvcResult>> tasks) throws Exception {
        var executor = Executors.newFixedThreadPool(tasks.size());
        try {
            var start = new CountDownLatch(1);
            var futures = tasks.stream().map(task -> executor.submit(() -> { start.await(); return task.call(); })).toList();
            start.countDown();
            var results = new ArrayList<MvcResult>();
            for (var future : futures) results.add(future.get(30, TimeUnit.SECONDS));
            return results;
        } finally { executor.shutdownNow(); }
    }

    @Test void concurrentLastSeatNeverOversells() throws Exception {
        OWNER.execute("UPDATE section SET capacity=1 WHERE id=1");
        List<Callable<MvcResult>> tasks = new ArrayList<>();
        for (int i=1; i<=20; i++) { int student = i; tasks.add(() -> enroll(student, "last-seat", 1)); }
        var results = concurrent(tasks);
        assertThat(results.stream().filter(r -> r.getResponse().getStatus()==201).count()).isEqualTo(1);
        assertThat(results.stream().filter(r -> r.getResponse().getStatus()==409).count()).isEqualTo(19);
        assertThat(OWNER.queryForObject("SELECT enrolled_count FROM section WHERE id=1", Integer.class)).isEqualTo(1);
    }

    @Test void concurrentSameKeyReplaysAndDifferentPayloadConflicts() throws Exception {
        var same = concurrent(List.of(() -> enroll(1,"same",1,2), () -> enroll(1,"same",2,1)));
        for (var result : same) status(result, 201);
        assertThat(same.get(0).getResponse().getContentAsString()).isEqualTo(same.get(1).getResponse().getContentAsString());
        assertThat(same.stream().filter(r -> "true".equals(r.getResponse().getHeader("X-Idempotent-Replay")))).hasSize(1);
        var different = concurrent(List.of(() -> enroll(2,"different",1), () -> enroll(2,"different",2)));
        assertThat(different.stream().map(r -> r.getResponse().getStatus()).sorted().toList()).containsExactly(201,409);
    }

    @Test void concurrentSameStudentCourseCreditsSessionAndCancellation() throws Exception {
        var sameSection = concurrent(List.of(() -> enroll(1,"section-a",1), () -> enroll(1,"section-b",1)));
        assertThat(sameSection.stream().map(r -> r.getResponse().getStatus()).sorted().toList()).containsExactly(201,409);
        var sameCourse = concurrent(List.of(() -> enroll(2,"course-a",1), () -> enroll(2,"course-b",3)));
        assertThat(sameCourse.stream().map(r -> r.getResponse().getStatus()).sorted().toList()).containsExactly(201,409);
        var sameSession = concurrent(List.of(() -> enroll(3,"session-a",1), () -> enroll(3,"session-b",4)));
        assertThat(sameSession.stream().map(r -> r.getResponse().getStatus()).sorted().toList()).containsExactly(201,422);
        OWNER.execute("UPDATE registration_period SET credit_limit=5");
        var credits = concurrent(List.of(() -> enroll(4,"credits-a",1), () -> enroll(4,"credits-b",2)));
        assertThat(credits.stream().map(r -> r.getResponse().getStatus()).sorted().toList()).containsExactly(201,422);
        long id = OWNER.queryForObject("SELECT id FROM enrollment WHERE student_id=1", Long.class);
        var cancel = concurrent(List.of(() -> perform(student(delete("/api/v1/enrollments/"+id),1)), () -> perform(student(delete("/api/v1/enrollments/"+id),1))));
        assertThat(cancel.stream().map(r -> r.getResponse().getStatus()).sorted().toList()).containsExactly(204,404);
    }

    @Test void technicalFailureRollsBackKeyAndCountersThenRetryWorks() throws Exception {
        OWNER.execute("CREATE FUNCTION fail_test_enrollment() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'synthetic failure'; END $$");
        OWNER.execute("CREATE TRIGGER fail_test BEFORE INSERT ON enrollment FOR EACH ROW EXECUTE FUNCTION fail_test_enrollment()");
        try {
            status(enroll(1,"technical",1), 503);
            assertThat(OWNER.queryForObject("SELECT count(*) FROM idempotency_record", Integer.class)).isZero();
            assertThat(OWNER.queryForObject("SELECT enrolled_count FROM section WHERE id=1", Integer.class)).isZero();
        } finally { OWNER.execute("DROP TRIGGER fail_test ON enrollment"); OWNER.execute("DROP FUNCTION fail_test_enrollment()"); }
        status(enroll(1,"technical",1), 201);
    }

    @Test void swaggerDescribesExactlyTenEndpointsAndNo202() throws Exception {
        var docs = perform(get("/v3/api-docs")); status(docs,200);
        var paths = body(docs).path("paths");
        int operations=0;
        for (var path : paths) {
            for (String method : List.of("get","post","put","delete")) if (path.has(method)) {
                operations++;
                assertThat(path.path(method).path("responses").has("202")).isFalse();
                assertThat(path.path(method).path("responses").has("200") || path.path(method).path("responses").has("201") || path.path(method).path("responses").has("204")).isTrue();
            }
        }
        assertThat(operations).isEqualTo(10);
        assertThat(paths.path("/api/v1/enrollments").path("post").path("responses").has("201")).isTrue();
        assertThat(paths.path("/api/v1/health").path("get").has("security")).isFalse();
        status(perform(get("/swagger-ui/index.html")),200);
        Files.createDirectories(Path.of("target/evidence"));
        Files.writeString(Path.of("target/evidence/openapi.json"), docs.getResponse().getContentAsString());
    }

    @Test void corsPreflightChoPhepOriginDev() throws Exception {
        var ok = mvc.perform(options("/api/v1/enrollments").header("Origin", "http://localhost:5173")
            .header("Access-Control-Request-Method", "POST").header("Access-Control-Request-Headers", "authorization,idempotency-key")).andReturn().getResponse();
        assertThat(ok.getStatus()).isEqualTo(200);
        assertThat(ok.getHeader("Access-Control-Allow-Origin")).isEqualTo("http://localhost:5173");
        var denied = mvc.perform(options("/api/v1/enrollments").header("Origin", "http://evil.example")
            .header("Access-Control-Request-Method", "POST")).andReturn().getResponse();
        assertThat(denied.getStatus()).isEqualTo(403);
        assertThat(denied.getHeader("Access-Control-Allow-Origin")).isNull();
    }
}
