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
    public record ManagerCoordinator(String userId, String email, String displayName, String photoUrl, String phone,
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

    // ---------------------------------------------------------------- RM5: staff attendance

    /**
     * One person on the staff register. `status` is absent while nobody has marked her — never "present" by default,
     * which is the same rule {@link GradeStats#attendanceRate} follows: an unmarked day is no answer, not a good one.
     */
    public record StaffAttendanceRow(String userId, String email, String displayName, String photoUrl, String role,
                                     String status, String note, String markedBy, Long markedAt) {}

    /**
     * `GET /management/staff-attendance?day=` and the body `PUT` answers: the department's teachers and coordinators
     * for one day. `editable` is false on a day nobody may mark — a non-teaching day, or one still to come.
     */
    public record StaffAttendanceDay(java.time.LocalDate day, boolean schoolDay, boolean editable,
                                     List<StaffAttendanceRow> people, int present, int absent, int late, int leave,
                                     int unmarked) {}

    /**
     * One line of the `PUT` body. `status` is `present`, `absent`, `late` or `leave`; `note` is optional and capped.
     * Checked in {@link StaffAttendanceService} rather than by bean validation, which does not reach inside a list
     * request body without turning a refusal into a 500.
     */
    public record MarkStaffAttendance(String userId, String status, @Size(max = StaffAttendanceService.MAX_NOTE) String note) {}

    /** One person's month: the four counts, the school days nobody marked, and her rate. */
    public record StaffAttendanceSummaryRow(String userId, String email, String displayName, String role,
                                            int present, int absent, int late, int leave, int unmarked, Double rate) {}

    /**
     * `GET /management/staff-attendance/summary?month=YYYY-MM`: a row per person over the school days of that month
     * up to today, so a month still running is not scored as though its remaining days were missed.
     */
    public record StaffAttendanceSummary(String month, java.time.LocalDate from, java.time.LocalDate to,
                                         int schoolDays, List<StaffAttendanceSummaryRow> people) {}

    /** One marked day of one person's history. */
    public record StaffAttendanceMark(java.time.LocalDate day, String status, String note, String markedBy,
                                      long markedAt) {}

    /** `GET /management/staff-attendance/{userId}?from&to`: one person's marked days, newest first. */
    public record StaffAttendanceHistory(String userId, String displayName, String role, java.time.LocalDate from,
                                         java.time.LocalDate to, List<StaffAttendanceMark> marks) {}

    // ---------------------------------------------------------------- RM5: the people directory

    /**
     * `GET /management/people/children`: a child of the department with the contact the school has for her.
     *
     * <p>Two addresses, because the school has two: `parentEmail` is the account a parent actually signed up with
     * (null until she does) and `rosterEmail` is what `children.parent_email` carries from the imported roster. MH1
     * adds `parentPhone` (`parents.phone`, hers to set in the app) and `parentId`, which is null exactly when no
     * parent has registered for this child — the one flag the "message the parent" button needs, and the case
     * `POST /management/chat/threads {childId}` answers 404 `no_parent` to. `placedAt` is the roster row's own
     * creation, which is when the child joined the section — epoch millis, the form every other timestamp takes.
     */
    public record DirectoryChild(String childId, String name, String classId, String className, int grade,
                                 String curriculum, String parentEmail, String rosterEmail, String parentId,
                                 String parentPhone, long placedAt) {}

    /** `GET /management/people/children?classId&q&page&size`. `total` is every match, not the page. */
    public record ChildDirectory(int page, int size, int total, List<DirectoryChild> rows) {}

    /**
     * `GET /management/people/teachers?q&page&size`. The rows are {@link CoordinatorDto.CoordinatorTeacher} — her
     * subjects and her sections are exactly what `/management/teachers` already answers, and a second record with the
     * same fields could only drift away from the first.
     */
    public record TeacherDirectory(int page, int size, int total, List<CoordinatorDto.CoordinatorTeacher> rows) {}

    /** `GET /management/people/coordinators?q&page&size`, in {@link ManagerCoordinator}'s shape for the same reason. */
    public record CoordinatorDirectory(int page, int size, int total, List<ManagerCoordinator> rows) {}

    // ---------------------------------------------------------------- admin (`/admin/managers/**`)

    /** A manager as the Admin's screen sees her, with every department she holds. */
    public record ManagerAccount(String userId, String email, String fullName, String phone, String status, List<String> departments) {}

    /** `POST /admin/managers`: one department to start with, the shape `managers.csv` has. */
    public record CreateManagerRequest(@NotBlank @Size(max = 80) String fullName, @NotBlank String email,
                                       @Size(max = quest.server.auth.DashboardDto.PHONE) String phone, @NotBlank String curriculum) {}

    /** 201, with the one and only sight of the password — the contract `CoordinatorCreated` has. */
    public record ManagerCreated(ManagerAccount manager, String temporaryPassword) {}

    /** `PUT /admin/managers/{id}/scopes`: the complete set of departments she should hold afterwards. */
    public record DepartmentsRequest(@NotEmpty List<@NotBlank String> curricula) {}

    /**
     * `PATCH /admin/managers/{id}` (MA1): only the fields that are present are written — `UpdateCoordinatorRequest`'s
     * mirror, and `UpdateTeacherRequest`'s narrowed to what a manager holds. Her departments are the other route's.
     */
    public record UpdateManagerRequest(@Size(max = 80) String fullName,
                                       @Size(max = quest.server.auth.DashboardDto.PHONE) String phone,
                                       Boolean active) {}
}
