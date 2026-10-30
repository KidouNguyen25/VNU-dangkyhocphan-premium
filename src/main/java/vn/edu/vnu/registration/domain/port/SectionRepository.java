package vn.edu.vnu.registration.domain.port;

import vn.edu.vnu.registration.domain.enrollment.model.Section;
import vn.edu.vnu.registration.domain.enrollment.model.SectionQuery;

import java.util.List;

public interface SectionRepository {
    List<Section> sections(SectionQuery query);
}
