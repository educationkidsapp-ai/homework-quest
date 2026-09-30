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

    /**
     * MH1 (owner's item 4): a coordinator whose scope covers one of this teacher's (section, subject) slots, and the
     * subject that put her there — so the Teachers screen can say "maths · Lina" and open a thread with her without a
     * second request. Answered on `/management/teachers` and the manager's people directory only; `/coordinator/teachers`
     * leaves it empty, because a coordinator looking at her own teachers is the person on this list.
     */
    public record TeacherCoordinator(String userId, String displayName, String subject) {}

    /**
     * `GET /coordinator/teachers`: read-only — nothing under `/coordinator` edits a teacher. MH1 adds `phone` (the
     * mobile number the owner asked for beside every name) and `coordinators`.
     */
    public record CoordinatorTeacher(String userId, String email, String displayName, String photoUrl, String phone,
                                     List<String> subjects, List<AssignmentRef> sections,
                                     List<TeacherCoordinator> coordinators) {}

    /** `GET /coordinator/classes`: {@link quest.server.teacher.TeacherDto.TeacherClassCard} plus the teacher's name. */
    public record CoordinatorClass(String classId, String className, String curriculum, int grade, String subject,
                                   String teacherId, String teacherName, int childrenCount,
                                   String todayLessonId, String todayStatus) {}

    public record CalendarLesson(String classId, String className, String subject, String lessonId, String title,
                                 String status, String type, int playedCount, int childrenCount) {}

    public record CalendarDay(String date, boolean schoolDay, List<CalendarLesson> lessons) {}

    /** `GET /coordinator/calendar?from&to`: every class in scope, day by day, in one response. */
    public record CoordinatorCalendar(String from, String to, List<CalendarDay> days) {}

    // ---------------------------------------------------------------- communication (R4, DR3/DR4)

    /**
     * `POST /coordinator/chat/threads`: the manager the coordinator wants to talk to, validated against her own
     * department. The teacher's route sends `StaffDto.OpenStaffThreadRequest` instead since T1b — hers may name a
     * coordinator as well, and required-ness is then a rule about the pair rather than about one field.
     *
     * <p><strong>Named for the document.</strong> springdoc keys a schema by the record's simple name, and
     * `ManagementChatController.StaffThreadRequest` is a different shape with the same one — so the generated client
     * got whichever of the two springdoc happened to resolve last. The manager's keeps `StaffThreadRequest`, the name
     * `server/openapi.json` already carries, and this one says whose door it opens.
     */
    @io.swagger.v3.oas.annotations.media.Schema(name = "OpenManagerThreadRequest")
    public record StaffThreadRequest(@NotBlank String managerUserId) {}

    /** `PATCH /coordinator/chat/threads/{id}/status`: `open` or `resolved`, the two words the column holds. */
    public record ThreadStatusRequest(@NotBlank String status) {}

    /**
     * `POST /coordinator/announcements`: the same note a teacher posts (`bodyEn` required, `bodyAr` optional), sent to
     * the classes named — or, when `classIds` is absent, to every section in her scope. One `announcements` row per
     * class, so the parent's existing read needs no change at all (DR4).
     *
     * <p><strong>Named for the document</strong> (RM1 addendum): this record and {@link
     * quest.server.teacher.TeacherDto.CreateAnnouncementRequest} are different shapes — `classIds` against `classId` —
     * with the same simple name, and springdoc keys `components/schemas` by simple name, so `openapi.json` exported the
     * teacher's body for this route and the generated client sent the wrong field. `OpenApiContractTest` now asserts
     * the two schemas are distinct.
     */
    @io.swagger.v3.oas.annotations.media.Schema(name = "CoordinatorCreateAnnouncementRequest")
    public record CreateAnnouncementRequest(List<String> classIds, @NotBlank @Size(max = 1000) String bodyEn,
                                            @Size(max = 1000) String bodyAr, Long expiresAt) {}

    // ---------------------------------------------------------------- admin (`/admin/coordinators/**`)

    public record CoordinatorAccount(String userId, String email, String fullName, String phone, String status, List<Scope> scopes) {}

    /** `POST /admin/coordinators`: at least one scope — a coordinator of nothing could see nothing. */
    public record CreateCoordinatorRequest(@NotBlank @Size(max = 80) String fullName, @NotBlank String email,
                                           @Size(max = quest.server.auth.DashboardDto.PHONE) String phone, @NotEmpty @Valid List<Scope> scopes) {}

    /** 201, with the one and only sight of the password — the contract `TeacherCreated` has. */
    public record CoordinatorCreated(CoordinatorAccount coordinator, String temporaryPassword) {}

    /** `PUT /admin/coordinators/{id}/scopes`: the complete set she should hold afterwards. */
    public record ScopesRequest(@NotEmpty @Valid List<Scope> scopes) {}

    /**
     * `PATCH /admin/coordinators/{id}` (MA1): only the fields that are present are written — the contract
     * `UpdateTeacherRequest` has, narrowed to what a coordinator holds. Her scope is the other route's.
     */
    public record UpdateCoordinatorRequest(@Size(max = 80) String fullName,
                                           @Size(max = quest.server.auth.DashboardDto.PHONE) String phone,
                                           Boolean active) {}
}
