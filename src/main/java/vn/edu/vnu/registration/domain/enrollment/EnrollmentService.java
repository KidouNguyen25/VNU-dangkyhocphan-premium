package vn.edu.vnu.registration.domain.enrollment;

import vn.edu.vnu.registration.domain.Outcome;
import vn.edu.vnu.registration.domain.auth.model.PeriodStatus;
import vn.edu.vnu.registration.domain.auth.model.RegistrationPeriod;
import vn.edu.vnu.registration.domain.enrollment.model.Enrollment;
import vn.edu.vnu.registration.domain.enrollment.model.Section;
import vn.edu.vnu.registration.domain.idempotency.IdempotencyService;
import vn.edu.vnu.registration.domain.port.AccountRepository;
import vn.edu.vnu.registration.domain.port.EnrollmentRepository;
import vn.edu.vnu.registration.domain.port.RegistrationPeriodRepository;
import vn.edu.vnu.registration.domain.port.SectionRepository;
import vn.edu.vnu.registration.domain.port.TransactionRunner;

import java.time.Clock;
import java.util.*;

import static vn.edu.vnu.registration.domain.GeneralUtil.requestHash;
import static vn.edu.vnu.registration.domain.GeneralUtil.within;
import static vn.edu.vnu.registration.domain.enrollment.EnrollmentUtil.checkEnrollment;
import static vn.edu.vnu.registration.domain.exception.BusinessException.error;

public class EnrollmentService {
    private final EnrollmentRepository enrollmentRepository;
    private final SectionRepository sectionRepository;
    private final RegistrationPeriodRepository periodRepository;
    private final AccountRepository accountRepository;
    private final TransactionRunner transactionRunner;
    private final IdempotencyService idempotencyService;
    private final Clock clock;

    public EnrollmentService(
            EnrollmentRepository enrollmentRepository,
            SectionRepository sectionRepository,
            RegistrationPeriodRepository periodRepository,
            AccountRepository accountRepository,
            TransactionRunner transactionRunner,
            IdempotencyService idempotencyService,
            Clock clock
    ) {
        this.enrollmentRepository = enrollmentRepository;
        this.sectionRepository = sectionRepository;
        this.periodRepository = periodRepository;
        this.accountRepository = accountRepository;
        this.transactionRunner = transactionRunner;
        this.idempotencyService = idempotencyService;
        this.clock = clock;
    }

    public Map<String, Object> enrollments(long studentId, String term) {
        var rows = enrollmentRepository.registered(studentId, term);
        Map<String, Integer> credits = new TreeMap<>();
        var items = rows.stream().map(r -> {
            credits.merge(r.section().term(), r.section().credits(), Integer::sum);
            var item = sectionBody(r.section());
            item.put("enrollmentId", r.enrollmentId());
            item.put("createdAt", r.createdAt());
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

        return idempotencyService.execute(studentId, key, hash, () -> accountRepository.lockAccount(studentId), () -> {
            var period = requireOpen(periodRepository.currentPeriod());
            var targets = sectionRepository.lockSections(sorted);
            requireOpen(periodRepository.currentPeriod());
            if (targets.size() != sorted.size()) {
                throw error(404, "SECTION_NOT_FOUND", "Có lớp học phần không tồn tại.");
            }
            checkEnrollment(period, targets, enrollmentRepository.registered(studentId, period.term()));
            var items = new ArrayList<Enrollment>();
            for (var target : targets) {
                items.add(enrollmentRepository.enroll(studentId, target.id()));
            }
            return new Outcome(201, Map.of("items", items));
        });
    }

    public void cancel(long studentId, long enrollmentId) {
        transactionRunner.runInTransaction(ctx -> {
            accountRepository.lockAccount(studentId);
            var period = requireOpen(periodRepository.currentPeriod());
            var enrollment = enrollmentRepository.enrollment(studentId, enrollmentId)
                    .orElseThrow(() -> error(404, "ENROLLMENT_NOT_FOUND", "Không tìm thấy lượt đăng ký."));
            if (!period.term().equals(enrollment.term())) {
                throw error(403, "REGISTRATION_NOT_OPEN", "Đợt của lớp đã đóng.");
            }
            sectionRepository.lockSections(List.of(enrollment.sectionId()));
            requireOpen(periodRepository.currentPeriod());
            enrollmentRepository.cancel(studentId, enrollmentId, enrollment.sectionId());
            return null;
        });
    }

    public static Map<String, Object> sectionBody(Section s) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", s.id());
        result.put("courseCode", s.courseCode());
        result.put("term", s.term());
        result.put("lecturer", s.lecturer());
        result.put("capacity", s.capacity());
        result.put("enrolledCount", s.enrolledCount());
        result.put("session", s.session());
        result.put("courseName", s.courseName());
        result.put("credits", s.credits());
        result.put("prerequisite", s.prerequisite());
        result.put("availableSeats", s.capacity() - s.enrolledCount());
        return result;
    }

    private RegistrationPeriod requireOpen(Optional<RegistrationPeriod> period) {
        return period.filter(p -> p.status() == PeriodStatus.OPEN && within(clock.instant(), p.opensAt(), p.closesAt()))
                .orElseThrow(() -> error(403, "REGISTRATION_NOT_OPEN", "Đợt đăng ký chưa mở hoặc đã đóng."));
    }
}
