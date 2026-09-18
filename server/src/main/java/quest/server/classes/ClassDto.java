package quest.server.classes;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

/**
 * The Java mirror of `quest/api/dashboard/Classes.kt` — sections, teaching assignments and class rosters
 * (`docs/teacher-flow.md` §1–§2). Records, serialised by Jackson exactly as `SchoolDataDto` is, so the field names
 * here and the Kotlin ones are the contract `OpenApiContractTest` holds the server to.
 */
public final class ClassDto {
    private ClassDto() {}

    /** A section, with the two counts the Admin's Classes screen shows. `subject`/`teacherId` are the pre-V7 shape. */
    public record SchoolClass(String id, String schoolId, String curriculum, int grade, String subject,
                              String teacherId, String teacherName, long createdAt, String name, String joinCode,
                              boolean active, boolean joinCodeEnabled, int children, int assignments) {}

    public record CreateSectionRequest(@NotBlank String curriculum, int grade, @NotBlank String name) {}

    /** Only the present fields are written; `active` false retires the section without deleting anything. */
    public record UpdateSectionRequest(String name, Boolean active, Boolean joinCodeEnabled) {}

    public record TeachingAssignment(String id, String classId, String className, String curriculum, int grade,
                                     String subject, String teacherId, String teacherName) {}

    public record CreateTeacherRequest(@NotBlank String fullName, @NotBlank String email, List<String> subjects,
                                       String curriculum, String photoUrl) {}

    /** The one and only sight of a new teacher's password. It is never stored in clear, logged or answered twice. */
    public record TeacherCreated(TeacherAccount teacher, String temporaryPassword) {}

    public record TemporaryPassword(String temporaryPassword) {}

    public record UpdateTeacherRequest(String fullName, List<String> subjects, String curriculum, String photoUrl,
                                       Boolean active) {}

    public record TeacherAccount(String userId, String email, String fullName, String photoUrl, String status,
                                 List<String> subjects, String curriculum, List<TeachingAssignment> assignments) {}

    public record AssignmentInput(@NotBlank String classId, @NotBlank String subject) {}

    /** The complete set the teacher should hold afterwards, not a delta. */
    public record AssignmentsRequest(List<AssignmentInput> assignments) {}

    public record RosterChild(String id, String classId, String name, String parentEmail, String photoUrl,
                              boolean active, boolean hasParent) {}

    public record CreateRosterChildRequest(@NotBlank String name, String parentEmail, String photoUrl) {}

    public record UpdateRosterChildRequest(String name, String parentEmail, String photoUrl, Boolean active,
                                           String classId) {}

    /** One line of an uploaded roster; `line` is the 1-based row in the file, header included. */
    public record ImportRow(int line, String name, String parentEmail, String status, String reason) {}

    public record ImportSummary(int total, int added, int duplicate, int invalid) {}

    public record ImportPreview(boolean dryRun, List<ImportRow> rows, ImportSummary summary) {}

    /** The body of `POST /classes/lookup`: a join code is a credential, so it travels in a body, never in a URL. */
    public record ClassLookupRequest(@NotBlank String code) {}

    /** What a parent sees after typing a join code, and nothing more: no roster, no teacher, no school id. */
    public record ClassLookup(String classId, String name, int grade, String curriculum, String schoolName) {}

    /** The three states an imported line can be in; `new` is the only one a commit inserts. */
    public static final String NEW = "new", DUPLICATE = "duplicate", INVALID = "invalid";
}
