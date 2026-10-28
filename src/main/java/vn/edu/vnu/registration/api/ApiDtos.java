package vn.edu.vnu.registration.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import static vn.edu.vnu.registration.domain.Models.*;

public final class ApiDtos {
    private ApiDtos() {}
    public record LoginRequest(@NotBlank @Size(max = 20) String code, @NotBlank @Size(max = 72) String password) {}

    public record EnrollRequest(@NotNull @Size(min = 1, max = 100) List<@NotNull @Positive Long> sectionIds) {}

    public record SectionRequest(@NotBlank @Size(max = 12) String courseCode,
                                 @NotBlank @Size(max = 10) String term,
                                 @NotBlank @Size(max = 100) String lecturer,
                                 @NotNull @Min(1) @Max(32767) Integer capacity,
                                 @NotNull @Min(1) @Max(268435455) Integer session
    ) {
        public SectionInput toDomain() { return new SectionInput(courseCode, term, lecturer, capacity, session); }
    }
    public record PeriodRequest(@NotBlank @Size(max = 10) String term,
                                @NotNull Instant loginOpensAt,
                                @NotNull Instant opensAt,
                                @NotNull Instant closesAt,
                                @NotNull PeriodStatus status,
                                @NotNull @Min(1) @Max(32767) Integer creditLimit
    ) {
        public Period toDomain() { return new Period(term, loginOpensAt, opensAt, closesAt, status, creditLimit); }
    }

    public record MeResponse(long id, String code, String fullName, Role role, Integer creditLimit) {}
    public record PeriodResponse(@Schema(nullable = true) Period registration, Instant serverTime) {}
    public record SectionResponse(long id, String courseCode, String term, String lecturer, int capacity,
                                  int enrolledCount, int session, String courseName, int credits, String prerequisite, int availableSeats) {}
    public record SectionsResponse(List<SectionResponse> items, @Schema(nullable = true) Long nextCursor) {}
    public record EnrollmentResponse(long enrollmentId, long sectionId, Instant createdAt) {}
    public record EnrollResponse(List<EnrollmentResponse> items) {}
    public record RegisteredResponse(long id, String courseCode, String term, String lecturer, int capacity,
                                     int enrolledCount, int session, String courseName, int credits, String prerequisite,
                                     int availableSeats, long enrollmentId, Instant createdAt) {}
    public record EnrollmentsResponse(List<RegisteredResponse> items, Map<String, Integer> creditsByTerm) {}
    public record HealthResponse(String status, Instant serverTime) {}
}
