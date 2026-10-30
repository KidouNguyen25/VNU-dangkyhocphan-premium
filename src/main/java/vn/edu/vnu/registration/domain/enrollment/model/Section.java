package vn.edu.vnu.registration.domain.enrollment.model;

public record Section(
        long id,
        String courseCode,
        String courseName,
        String term,
        String lecturer,
        String prerequisite,
        int capacity,
        int enrolledCount,
        int session,
        int credits
) {}
