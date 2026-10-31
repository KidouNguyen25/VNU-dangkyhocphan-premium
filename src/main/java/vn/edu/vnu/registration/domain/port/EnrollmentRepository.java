package vn.edu.vnu.registration.domain.port;

import vn.edu.vnu.registration.domain.enrollment.model.Cancellation;
import vn.edu.vnu.registration.domain.enrollment.model.Enrollment;
import vn.edu.vnu.registration.domain.enrollment.model.Registered;

import java.util.List;
import java.util.Optional;

public interface EnrollmentRepository {
    List<Registered> registered(long studentId, String term);
    Enrollment enroll(long studentId, long sectionId);
    Optional<Cancellation> enrollment(long studentId, long enrollmentId);
    void cancel(long studentId, long enrollmentId, long sectionId);
}
