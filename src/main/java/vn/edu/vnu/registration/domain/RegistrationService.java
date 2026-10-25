package vn.edu.vnu.registration.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import static vn.edu.vnu.registration.domain.Models.*;
import static vn.edu.vnu.registration.domain.Ports.*;

public class RegistrationService {
    private final Repository repo;
    private final TokenGateway tokens;
    private final PasswordVerifier passwords;
    private final String dummyHash;
    private final Clock clock;

    public RegistrationService(Repository repo, TokenGateway tokens, PasswordVerifier passwords, String dummyHash, Clock clock) {
        this.repo = repo;
        this.tokens = tokens;
        this.passwords = passwords;
        this.dummyHash = dummyHash;
        this.clock = clock;
    }

    public Token login(String code, String password) {
        var account = repo.accountByCode(code);
        boolean valid = passwords.matches(password, account.map(Account::passwordHash).orElse(dummyHash));
        if (account.isEmpty() || !valid) throw error(401, "INVALID_CREDENTIALS", "Mã đăng nhập hoặc mật khẩu không đúng.");
        var a = account.get();
        var period = repo.currentPeriod();
        var now = clock.instant();
        if (a.role() == Role.STUDENT && (period.isEmpty() || !within(now, period.get().loginOpensAt(), period.get().closesAt()))) {
            throw error(403, "LOGIN_NOT_OPEN", "Chưa trong thời gian đăng nhập của sinh viên.");
        }
        if (a.role() == Role.ADMIN && period.filter(p -> within(now, p.opensAt(), p.closesAt())).isPresent()) {
            throw error(403, "ADMIN_LOGIN_LOCKED", "Tạm khóa đăng nhập quản trị trong thời gian đăng ký.");
        }
        return tokens.issue(new Identity(a.id(), a.role()));
    }

    public Map<String, Object> me(Identity identity) {
        var a = repo.accountById(identity.accountId()).orElseThrow(() -> error(401, "UNAUTHORIZED", "Tài khoản không còn tồn tại."));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", a.id()); body.put("code", a.code()); body.put("fullName", a.fullName()); body.put("role", identity.role());
        body.put("creditLimit", repo.currentPeriod().map(Period::creditLimit).orElse(null));
        return body;
    }

    public Map<String, Object> registration() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("registration", repo.currentPeriod().orElse(null));
        body.put("serverTime", clock.instant());
        return body;
    }

    public Map<String, Object> sections(SectionQuery query) {
        if (query.limit() < 1 || query.limit() > 100) throw error(400, "VALIDATION_ERROR", "limit phải từ 1 đến 100.");
        var rows = repo.sections(new SectionQuery(query.term(), query.courseCode(), query.q(), query.hasSeats(), query.cursor(), query.limit() + 1));
        var items = rows.stream().limit(query.limit()).map(this::sectionBody).toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("nextCursor", rows.size() > query.limit() ? rows.get(query.limit() - 1).id() : null);
        return body;
    }

    public Map<String, Object> enrollments(long studentId, String term) {
        var rows = repo.registered(studentId, term);
        Map<String, Integer> credits = new TreeMap<>();
        var items = rows.stream().map(r -> {
            credits.merge(r.section().term(), r.section().credits(), Integer::sum);
            var item = sectionBody(r.section());
            item.put("enrollmentId", r.enrollmentId()); item.put("createdAt", r.createdAt());
            return item;
        }).toList();
        return Map.of("items", items, "creditsByTerm", credits);
    }

    public Outcome enroll(long studentId, String key, List<Long> ids) {
        if (key == null || key.isEmpty() || key.length() > 128) {
            throw error(400, "VALIDATION_ERROR", "Idempotency-Key cần 1–128 ký tự.");
        }
        if (ids == null || ids.isEmpty() || ids.size() > 100 || ids.stream().anyMatch(i -> i == null || i <= 0) || new HashSet<>(ids).size() != ids.size()) {
            throw error(400, "VALIDATION_ERROR", "sectionIds cần 1–100 id nguyên dương khác nhau.");
        }
        var sorted = ids.stream().sorted().toList();
        var hash = requestHash(sorted);
        return repo.transaction(tx -> {
            // Khóa trước INSERT để tránh hai FK KEY SHARE cùng nâng lên FOR UPDATE gây deadlock.
            tx.lockAccount(studentId);
            if (!tx.claimKey(studentId, key, hash)) {
                var saved = tx.lockKey(studentId, key);
                if (!Objects.equals(saved.requestHash(), hash)) throw error(409, "IDEMPOTENCY_KEY_REUSED", "Khóa đã được dùng với nội dung khác.");
                if (!"COMPLETED".equals(saved.state()) || saved.responseStatus() == null) throw new IllegalStateException("Incomplete idempotency record");
                return new Outcome(saved.responseStatus(), saved.responseBody(), true);
            }
            Object savepoint = tx.savepoint();
            Outcome result;
            try {
                var period = requireOpen(tx.currentPeriod());
                var targets = tx.lockSections(sorted);
                requireOpen(Optional.of(period));
                if (targets.size() != sorted.size()) throw error(404, "SECTION_NOT_FOUND", "Có lớp học phần không tồn tại.");
                checkEnrollment(period, targets, tx.registered(studentId, period.term()));
                var items = new ArrayList<Enrollment>();
                for (var target : targets) items.add(tx.enroll(studentId, target.id()));
                result = new Outcome(201, Map.of("items", items));
            } catch (BusinessException e) {
                if (e.status() >= 500) throw e;
                tx.rollbackTo(savepoint);
                result = new Outcome(e.status(), e.body());
            }
            tx.completeKey(studentId, key, result);
            // Cùng dạng JSONB cho lần đầu và replay; chỉ trả sau khi transaction đã commit.
            return new Outcome(result.status(), tx.lockKey(studentId, key).responseBody());
        });
    }

    public void cancel(long studentId, long enrollmentId) {
        repo.transaction(tx -> {
            tx.lockAccount(studentId);
            var period = requireOpen(tx.currentPeriod());
            var enrollment = tx.enrollment(studentId, enrollmentId).orElseThrow(() -> error(404, "ENROLLMENT_NOT_FOUND", "Không tìm thấy lượt đăng ký."));
            if (!period.term().equals(enrollment.term())) throw error(403, "REGISTRATION_NOT_OPEN", "Đợt của lớp đã đóng.");
            tx.lockSections(List.of(enrollment.sectionId()));
            requireOpen(Optional.of(period));
            tx.cancel(studentId, enrollmentId, enrollment.sectionId());
            return null;
        });
    }

    public Outcome saveSection(long id, SectionInput input) {
        return repo.transaction(tx -> {
            tx.lockSectionAdmin(id);
            var current = tx.lockSections(List.of(id)).stream().findFirst();
            if (current.filter(s -> s.enrolledCount() > input.capacity()).isPresent()) {
                throw error(422, "CAPACITY_BELOW_ENROLLED", "Sĩ số mới thấp hơn số đã đăng ký.");
            }
            if (current.filter(s -> s.enrolledCount() > 0 && (!s.courseCode().equals(input.courseCode()) || !s.term().equals(input.term()) || s.session() != input.session())).isPresent()) {
                throw error(422, "SECTION_HAS_ENROLLMENTS", "Không đổi học phần, kỳ hoặc ca của lớp đã có sinh viên.");
            }
            tx.upsertSection(id, input);
            return new Outcome(current.isPresent() ? 200 : 201, sectionBody(tx.lockSections(List.of(id)).get(0)));
        });
    }

    public Period savePeriod(Period input) {
        if (input.loginOpensAt().isAfter(input.opensAt()) || !input.opensAt().isBefore(input.closesAt())) {
            throw error(400, "VALIDATION_ERROR", "Cần loginOpensAt ≤ opensAt < closesAt.");
        }
        repo.transaction(tx -> { tx.upsertPeriod(input); return null; });
        return input;
    }

    public Map<String, Object> health() { repo.ping(); return Map.of("status", "ok", "serverTime", clock.instant()); }

    private Period requireOpen(Optional<Period> period) {
        return period.filter(p -> p.status() == PeriodStatus.OPEN && within(clock.instant(), p.opensAt(), p.closesAt()))
            .orElseThrow(() -> error(403, "REGISTRATION_NOT_OPEN", "Đợt đăng ký chưa mở hoặc đã đóng."));
    }

    static boolean within(Instant now, Instant from, Instant until) { return !now.isBefore(from) && now.isBefore(until); }

    static void checkEnrollment(Period period, List<Section> targets, List<Registered> existing) {
        var sections = existing.stream().map(r -> r.section().id()).collect(Collectors.toSet());
        var courses = existing.stream().map(r -> r.section().courseCode()).collect(Collectors.toSet());
        int sessions = existing.stream().mapToInt(r -> r.section().session()).reduce(0, (a, b) -> a | b);
        int credits = existing.stream().mapToInt(r -> r.section().credits()).sum();
        for (var s : targets) {
            if (!s.term().equals(period.term())) throw sectionError(422, "SECTION_TERM_MISMATCH", "Lớp không thuộc đợt đang mở.", s.id());
            if (sections.contains(s.id())) throw sectionError(409, "SECTION_ALREADY_ENROLLED", "Đã đăng ký lớp học phần này.", s.id());
            if (!courses.add(s.courseCode())) throw sectionError(409, "COURSE_ALREADY_ENROLLED", "Đã chọn một lớp của học phần trong kỳ.", s.id());
            if ((sessions & s.session()) != 0) throw sectionError(422, "SESSION_CONFLICT", "Các lớp bị trùng ca học.", s.id());
            sessions |= s.session(); credits += s.credits();
        }
        if (credits > period.creditLimit()) throw new BusinessException(422, "CREDIT_LIMIT_EXCEEDED", "Vượt giới hạn tín chỉ.", Map.of("totalCredits", credits, "creditLimit", period.creditLimit()));
    }

    private Map<String, Object> sectionBody(Section s) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", s.id()); result.put("courseCode", s.courseCode()); result.put("term", s.term());
        result.put("lecturer", s.lecturer()); result.put("capacity", s.capacity()); result.put("enrolledCount", s.enrolledCount());
        result.put("session", s.session()); result.put("courseName", s.courseName()); result.put("credits", s.credits());
        result.put("prerequisite", s.prerequisite()); result.put("availableSeats", s.capacity() - s.enrolledCount());
        return result;
    }

    private String requestHash(List<Long> sorted) {
        try {
            var canonical = "{\"sectionIds\":[" + sorted.stream().map(String::valueOf).collect(Collectors.joining(",")) + "]}";
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
