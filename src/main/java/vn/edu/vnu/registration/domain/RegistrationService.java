package vn.edu.vnu.registration.domain;

import vn.edu.vnu.registration.domain.auth.AuthService;
import vn.edu.vnu.registration.domain.auth.RegistrationPeriodService;
import vn.edu.vnu.registration.domain.auth.model.Identity;
import vn.edu.vnu.registration.domain.auth.model.RegistrationPeriod;
import vn.edu.vnu.registration.domain.auth.model.Token;
import vn.edu.vnu.registration.domain.enrollment.EnrollmentService;
import vn.edu.vnu.registration.domain.enrollment.EnrollmentUtil;
import vn.edu.vnu.registration.domain.enrollment.SectionService;
import vn.edu.vnu.registration.domain.enrollment.model.Registered;
import vn.edu.vnu.registration.domain.enrollment.model.Section;
import vn.edu.vnu.registration.domain.enrollment.model.SectionInput;
import vn.edu.vnu.registration.domain.enrollment.model.SectionQuery;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public class RegistrationService {
    private final AuthService authService;
    private final EnrollmentService enrollmentService;
    private final SectionService sectionService;
    private final RegistrationPeriodService periodService;
    private final Clock clock;

    public RegistrationService(
            AuthService authService,
            EnrollmentService enrollmentService,
            SectionService sectionService,
            RegistrationPeriodService periodService,
            Clock clock
    ) {
        this.authService = authService;
        this.enrollmentService = enrollmentService;
        this.sectionService = sectionService;
        this.periodService = periodService;
        this.clock = clock;
    }

    public Token login(String code, String password) {
        return authService.login(code, password);
    }

    public Map<String, Object> me(Identity identity) {
        return authService.me(identity);
    }

    public Map<String, Object> registration() {
        return periodService.registration();
    }

    public Map<String, Object> sections(SectionQuery query) {
        return sectionService.sections(query);
    }

    public Map<String, Object> enrollments(long studentId, String term) {
        return enrollmentService.enrollments(studentId, term);
    }

    public Outcome enroll(long studentId, String key, List<Long> ids) {
        return enrollmentService.enroll(studentId, key, ids);
    }

    public void cancel(long studentId, long enrollmentId) {
        enrollmentService.cancel(studentId, enrollmentId);
    }

    public Outcome saveSection(long id, SectionInput input) {
        return sectionService.saveSection(id, input);
    }

    public RegistrationPeriod savePeriod(RegistrationPeriod input) {
        return periodService.savePeriod(input);
    }

    public Map<String, Object> health() {
        periodService.ping();
        return Map.of("status", "ok", "serverTime", clock.instant());
    }

    public static boolean within(Instant now, Instant from, Instant until) {
        return GeneralUtil.within(now, from, until);
    }

    public static void checkEnrollment(RegistrationPeriod period, List<Section> targets, List<Registered> existing) {
        EnrollmentUtil.checkEnrollment(period, targets, existing);
    }
}
