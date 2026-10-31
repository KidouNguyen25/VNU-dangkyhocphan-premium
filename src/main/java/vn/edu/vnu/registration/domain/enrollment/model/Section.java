package vn.edu.vnu.registration.domain.enrollment.model;

public record Section(
        long id,
        String courseCode,
        String term,
        String lecturer,
        int capacity,
        int enrolledCount,
        int session,
        String courseName,
        int credits,
        String prerequisite
) {}
