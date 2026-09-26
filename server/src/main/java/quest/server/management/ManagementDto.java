package quest.server.management;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import quest.server.coordinator.CoordinatorDto;

/**
 * The Java mirror of `quest.api.dashboard.Management.kt` (RM1, DR5).
 *
 * <p>Records rather than the Kotlin types, for {@link quest.server.teacher.TeacherDto}'s reason: springdoc derives a
 * real schema from a record and `server/openapi.json` is what the Angular client is generated from.
 *
 * <p><strong>Three shapes are deliberately the coordinator's.</strong> `/management/teachers`, the class cards inside
 * {@link GradeGroup} and `/management/calendar` answer {@link CoordinatorDto.CoordinatorTeacher},
 * {@link CoordinatorDto.CoordinatorClass} and {@link CoordinatorDto.CoordinatorCalendar} unchanged, because they are
 * the very same screens with a wider scope — RM3 reuses R5's components against the same generated types, and a second
 * set of records with the same fields could only drift away from the first.
 */
public final class ManagementDto {
    private ManagementDto() {}

    /** `GET /management/me`: her departments, and how big they are — the five numbers Home leads with. */
    public record ManagerMe(String userId, String email, String displayName, List<String> departments,
                            int grades, int sections, int teachers, int coordinators, int children) {}

    /**
     * `GET /management/coordinators`: one of the people she manages. `curricula` carries a `null` entry when a
     * coordinator's row names no track, which DR5 reads as both of them.
     */
    public record ManagerCoordinator(String userId, String email, String displayName, String photoUrl,
                                     List<String> subjects, List<String> curricula, int sections) {}

    /** `GET /management/classes`: the department's grades, each with its section cards. */
    public record GradeGroup(String curriculum, int grade, int sections, int children,
                             List<CoordinatorDto.CoordinatorClass> classes) {}

    // ---------------------------------------------------------------- GET /management/stats

    /** A teacher of the department who published nothing inside the window — `HomeService`'s `teacher.quiet`. */
    public record QuietTeacher(String userId, String email, String displayName) {}

    /**
     * One grade of the department over the window, or the department itself: in {@link ManagementStats#total} the same
     * record carries `grade` 0 and `curriculum` null, meaning "every grade above, added up".
     *
     * <p>`attendanceRate` is null when nothing was marked in the window rather than 100, so an empty register reads as
     * "no answer" instead of a perfect one. `examAverage` and `examPassRate` are null where the grade has no exam with
     * a sitting in it.
     */
    public record GradeStats(String curriculum, int grade, int sections, int children, Double attendanceRate,
                             int lessonsPublished, int lessonsPlayed, int exams, Integer examAverage,
                             Integer examPassRate, List<QuietTeacher> quietTeachers) {}

    /** `GET /management/stats?from&to`: a row per grade and the department's own total, in one response. */
    public record ManagementStats(String from, String to, List<GradeStats> grades, GradeStats total) {}

    // ---------------------------------------------------------------- admin (`/admin/managers/**`)

    /** A manager as the Admin's screen sees her, with every department she holds. */
    public record ManagerAccount(String userId, String email, String fullName, String status, List<String> departments) {}

    /** `POST /admin/managers`: one department to start with, the shape `managers.csv` has. */
    public record CreateManagerRequest(@NotBlank @Size(max = 80) String fullName, @NotBlank String email,
                                       @NotBlank String curriculum) {}

    /** 201, with the one and only sight of the password — the contract `CoordinatorCreated` has. */
    public record ManagerCreated(ManagerAccount manager, String temporaryPassword) {}

    /** `PUT /admin/managers/{id}/scopes`: the complete set of departments she should hold afterwards. */
    public record DepartmentsRequest(@NotEmpty List<@NotBlank String> curricula) {}
}
