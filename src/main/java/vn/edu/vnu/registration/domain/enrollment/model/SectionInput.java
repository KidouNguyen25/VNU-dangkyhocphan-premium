package vn.edu.vnu.registration.domain.enrollment.model;

public record SectionInput(
        String courseCode,
        String term,
        String lecturer,
        int capacity,
        int session
) {}
