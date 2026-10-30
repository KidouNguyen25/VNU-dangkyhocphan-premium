package vn.edu.vnu.registration.data;

import vn.edu.vnu.registration.domain.enrollment.model.Section;
import vn.edu.vnu.registration.domain.enrollment.model.SectionQuery;
import vn.edu.vnu.registration.domain.port.SectionRepository;

import java.util.List;

public class SectionRepositoryImpl implements SectionRepository {
    @Override
    public List<Section> sections(SectionQuery query) {
        return List.of();
    }
}
