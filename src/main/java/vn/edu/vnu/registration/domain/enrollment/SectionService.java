package vn.edu.vnu.registration.domain.enrollment;

import vn.edu.vnu.registration.domain.Outcome;
import vn.edu.vnu.registration.domain.enrollment.model.Section;
import vn.edu.vnu.registration.domain.enrollment.model.SectionInput;
import vn.edu.vnu.registration.domain.enrollment.model.SectionQuery;
import vn.edu.vnu.registration.domain.port.SectionRepository;
import vn.edu.vnu.registration.domain.port.TransactionRunner;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static vn.edu.vnu.registration.domain.exception.BusinessException.error;

public class SectionService {
    private final SectionRepository sectionRepository;
    private final TransactionRunner transactionRunner;

    public SectionService(SectionRepository sectionRepository, TransactionRunner transactionRunner) {
        this.sectionRepository = sectionRepository;
        this.transactionRunner = transactionRunner;
    }

    public Map<String, Object> sections(SectionQuery query) {
        if (query.limit() < 1 || query.limit() > 100) {
            throw error(400, "VALIDATION_ERROR", "limit phải từ 1 đến 100.");
        }
        var rows = sectionRepository.sections(new SectionQuery(query.term(), query.courseCode(), query.q(), query.hasSeats(), query.cursor(), query.limit() + 1));
        var items = rows.stream().limit(query.limit()).map(EnrollmentService::sectionBody).toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("nextCursor", rows.size() > query.limit() ? rows.get(query.limit() - 1).id() : null);
        return body;
    }

    public Outcome saveSection(long id, SectionInput input) {
        return transactionRunner.runInTransaction(ctx -> {
            sectionRepository.lockSectionAdmin(id);
            var current = sectionRepository.lockSections(List.of(id)).stream().findFirst();
            if (current.filter(s -> s.enrolledCount() > input.capacity()).isPresent()) {
                throw error(422, "CAPACITY_BELOW_ENROLLED", "Sĩ số mới thấp hơn số đã đăng ký.");
            }
            if (current.filter(s -> s.enrolledCount() > 0 && (!s.courseCode().equals(input.courseCode()) || !s.term().equals(input.term()) || s.session() != input.session())).isPresent()) {
                throw error(422, "SECTION_HAS_ENROLLMENTS", "Không đổi học phần, kỳ hoặc ca của lớp đã có sinh viên.");
            }
            sectionRepository.upsertSection(id, input);
            return new Outcome(current.isPresent() ? 200 : 201, EnrollmentService.sectionBody(sectionRepository.lockSections(List.of(id)).get(0)));
        });
    }
}
