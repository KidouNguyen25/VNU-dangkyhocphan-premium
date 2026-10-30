package vn.edu.vnu.registration.domain.enrollment.model;

public record SectionQuery(
        String term,
        String courseCode,
        String q,
        Boolean hasSeats,
        Long cursor,
        int limit
) {}
