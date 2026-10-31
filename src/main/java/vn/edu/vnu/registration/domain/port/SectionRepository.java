package vn.edu.vnu.registration.domain.port;

import vn.edu.vnu.registration.domain.enrollment.model.Section;
import vn.edu.vnu.registration.domain.enrollment.model.SectionInput;
import vn.edu.vnu.registration.domain.enrollment.model.SectionQuery;

import java.util.List;

public interface SectionRepository {
    List<Section> sections(SectionQuery query);
    List<Section> lockSections(List<Long> ids);
    void lockSectionAdmin(long id);
    void upsertSection(long id, SectionInput s);
}
