package vn.edu.vnu.registration.domain.port;

import vn.edu.vnu.registration.domain.enrollment.model.Registered;

import java.util.List;

public interface EnrollmentRepository {
    List<Registered> registered(long studentId, String term);
}
