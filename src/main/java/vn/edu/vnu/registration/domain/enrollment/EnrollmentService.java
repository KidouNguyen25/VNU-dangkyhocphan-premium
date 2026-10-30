package vn.edu.vnu.registration.domain.enrollment;

import vn.edu.vnu.registration.domain.Models;
import vn.edu.vnu.registration.domain.auth.model.PeriodStatus;
import vn.edu.vnu.registration.domain.auth.model.RegistrationPeriod;
import vn.edu.vnu.registration.domain.enrollment.model.Enrollment;
import vn.edu.vnu.registration.domain.enrollment.model.Outcome;
import vn.edu.vnu.registration.domain.enrollment.model.Section;
import vn.edu.vnu.registration.domain.exception.BusinessException;
import vn.edu.vnu.registration.domain.port.EnrollmentRepository;
import vn.edu.vnu.registration.domain.port.SectionRepository;

import java.time.Clock;
import java.util.*;

import static vn.edu.vnu.registration.domain.GeneralUtil.requestHash;
import static vn.edu.vnu.registration.domain.GeneralUtil.within;
import static vn.edu.vnu.registration.domain.enrollment.EnrollmentUtil.checkEnrollment;
import static vn.edu.vnu.registration.domain.exception.BusinessException.error;

public class EnrollmentService {
    private final EnrollmentRepository enrollmentRepositiory;
    private final SectionRepository sectionRepository;
    private final Clock clock;

    public EnrollmentService(EnrollmentRepository enrollmentRepositiory, SectionRepository sectionRepository, Clock clock) {
        this.enrollmentRepositiory = enrollmentRepositiory;
        this.sectionRepository = sectionRepository;
        this.clock = clock;
    }

    public Map<String, Object> enrollments(long studentId, String term) {
        var rows = enrollmentRepositiory.registered(studentId, term);
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

        /*
        TODO: idempotency bs, i will do it later
         */
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

    private Map<String, Object> sectionBody(Section s) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", s.id()); result.put("courseCode", s.courseCode()); result.put("term", s.term());
        result.put("lecturer", s.lecturer()); result.put("capacity", s.capacity()); result.put("enrolledCount", s.enrolledCount());
        result.put("session", s.session()); result.put("courseName", s.courseName()); result.put("credits", s.credits());
        result.put("prerequisite", s.prerequisite()); result.put("availableSeats", s.capacity() - s.enrolledCount());
        return result;
    }

    private RegistrationPeriod requireOpen(Optional<RegistrationPeriod> period) {
        return period.filter(p -> p.status() == PeriodStatus.OPEN && within(clock.instant(), p.opensAt(), p.closesAt()))
                .orElseThrow(() -> error(403, "REGISTRATION_NOT_OPEN", "Đợt đăng ký chưa mở hoặc đã đóng."));
    }
}
