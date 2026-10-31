package vn.edu.vnu.registration.domain.enrollment;

import vn.edu.vnu.registration.domain.auth.model.RegistrationPeriod;
import vn.edu.vnu.registration.domain.enrollment.model.Registered;
import vn.edu.vnu.registration.domain.enrollment.model.Section;
import vn.edu.vnu.registration.domain.exception.BusinessException;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static vn.edu.vnu.registration.domain.exception.BusinessException.sectionError;

public class EnrollmentUtil {
    private EnrollmentUtil() {}

    public static void checkEnrollment(RegistrationPeriod period, List<Section> targets, List<Registered> existing) {
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
}
