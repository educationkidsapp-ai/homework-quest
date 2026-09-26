package quest.server.coordinator;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * The Java mirror of `quest.api.dashboard.Coordinator.kt` (R2, DR1/DR2).
 *
 * <p>Records rather than the Kotlin types, for the reason {@link quest.server.teacher.TeacherDto} is: springdoc
 * derives a real schema from a record and `server/openapi.json` is what the Angular client is generated from, so a
 * route answering the kotlinx encoding as a `String` would be documented as `type: string` and generate nothing
 * usable. `subject`, `curriculum` and `status` are the lower-cased wire strings the enums serialise to, exactly as
 * `TeacherDto` and `ClassDto` carry them.
 *
 * <p>The two lesson routes are the exception and carry `AdminLesson` through the shared kotlinx codec, because a
 * lesson holds `Stop`s and only that codec can write one — see {@link CoordinatorController}.
 */
public final class CoordinatorDto {
    private CoordinatorDto() {}

    /** One line of her scope; `curriculum` null means both tracks. */
    public record Scope(@NotBlank String subject, String curriculum) {}

    /** `GET /coordinator/me`: what she coordinates, and how big it is. */
    public record CoordinatorMe(String userId, String email, String displayName, List<Scope> scopes,
                                int sections, int teachers, int children) {}

    /** One (section, subject) a teacher holds inside her scope. */
    public record AssignmentRef(String classId, String className, String subject) {}

    /** `GET /coordinator/teachers`: read-only — nothing under `/coordinator` edits a teacher. */
    public record CoordinatorTeacher(String userId, String email, String displayName, String photoUrl,
                                     List<String> subjects, List<AssignmentRef> sections) {}

    /** `GET /coordinator/classes`: {@link quest.server.teacher.TeacherDto.TeacherClassCard} plus the teacher's name. */
    public record CoordinatorClass(String classId, String className, String curriculum, int grade, String subject,
                                   String teacherId, String teacherName, int childrenCount,
                                   String todayLessonId, String todayStatus) {}

    public record CalendarLesson(String classId, String className, String subject, String lessonId, String title,
                                 String status, String type, int playedCount, int childrenCount) {}

    public record CalendarDay(String date, boolean schoolDay, List<CalendarLesson> lessons) {}

    /** `GET /coordinator/calendar?from&to`: every class in scope, day by day, in one response. */
    public record CoordinatorCalendar(String from, String to, List<CalendarDay> days) {}

    // ---------------------------------------------------------------- admin (`/admin/coordinators/**`)

    public record CoordinatorAccount(String userId, String email, String fullName, String status, List<Scope> scopes) {}

    /** `POST /admin/coordinators`: at least one scope — a coordinator of nothing could see nothing. */
    public record CreateCoordinatorRequest(@NotBlank @Size(max = 80) String fullName, @NotBlank String email,
                                           @NotEmpty @Valid List<Scope> scopes) {}

    /** 201, with the one and only sight of the password — the contract `TeacherCreated` has. */
    public record CoordinatorCreated(CoordinatorAccount coordinator, String temporaryPassword) {}

    /** `PUT /admin/coordinators/{id}/scopes`: the complete set she should hold afterwards. */
    public record ScopesRequest(@NotEmpty @Valid List<Scope> scopes) {}
}
