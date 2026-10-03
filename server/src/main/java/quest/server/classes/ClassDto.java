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

    /**
     * A section, with the two counts the Admin's Classes screen shows. `subject`/`teacherId` are the pre-V7 shape.
     *
     * <p><strong>Named `SectionClass` in the document.</strong> springdoc keys components by simple class name, and
     * {@link quest.server.dashboard.SchoolDataDto.SchoolClass} — the pre-V7 (curriculum, grade, subject) row the
     * School page still serves — has the same one. Without this the two records collide in `server/openapi.json`:
     * one wins, the other's routes are generated against the wrong shape, and N1.2 found the Classes screen missing
     * `name`, `joinCode` and both counts. The Kotlin contract keeps calling it `SchoolClass`; only the document's
     * component name differs, and only until the pre-V7 record goes.
     */
    @io.swagger.v3.oas.annotations.media.Schema(name = "SectionClass")
    public record SchoolClass(String id, String schoolId, String curriculum, int grade, String subject,
                              String teacherId, String teacherName, long createdAt, String name, String joinCode,
                              boolean active, boolean joinCodeEnabled, int children, int assignments) {}

    public record CreateSectionRequest(@NotBlank String curriculum, int grade, @NotBlank String name) {}

    /** Only the present fields are written; `active` false retires the section without deleting anything. */
    public record UpdateSectionRequest(String name, Boolean active, Boolean joinCodeEnabled) {}

    public record TeachingAssignment(String id, String classId, String className, String curriculum, int grade,
                                     String subject, String teacherId, String teacherName) {}

    public record CreateTeacherRequest(@NotBlank String fullName, @NotBlank String email, List<String> subjects,
                                       String curriculum, String photoUrl, String phone) {}

    /** The one and only sight of a new teacher's password. It is never stored in clear, logged or answered twice. */
    public record TeacherCreated(TeacherAccount teacher, String temporaryPassword) {}

    public record TemporaryPassword(String temporaryPassword) {}

    public record UpdateTeacherRequest(String fullName, List<String> subjects, String curriculum, String photoUrl,
                                       Boolean active, String phone) {}

    public record TeacherAccount(String userId, String email, String fullName, String photoUrl, String phone, String status,
                                 List<String> subjects, String curriculum, List<TeachingAssignment> assignments) {}

    public record AssignmentInput(@NotBlank String classId, @NotBlank String subject) {}

    /** The complete set the teacher should hold afterwards, not a delta. */
    public record AssignmentsRequest(List<AssignmentInput> assignments) {}

    /** `parentName` (S1) is filled on the PATCH's own answer; the list that shows it is {@link FamilyRow}'s. */
    public record RosterChild(String id, String classId, String name, String parentEmail, String photoUrl,
                              boolean active, boolean hasParent, String parentName) {}

    public record CreateRosterChildRequest(@NotBlank String name, String parentEmail, String photoUrl) {}

    public record UpdateRosterChildRequest(String name, String parentEmail, String photoUrl, Boolean active,
                                           String classId, String parentPhone, String parentName, String parentPassword) {
        public UpdateRosterChildRequest(String name, String parentEmail, String photoUrl, Boolean active,
                                        String classId, String parentPhone, String parentName) {
            this(name, parentEmail, photoUrl, active, classId, parentPhone, parentName, null);
        }
    }

    // ------------------------------------------------------------------ MA1: children and their parents' accounts

    /**
     * `POST /admin/children` (the owner's item 5): a child and the parent account that goes with her, typed in one
     * form. `grade` and `curriculum` are the section's own and may be sent for confirmation only — a disagreement is
     * a 400 rather than a child placed in a class written for another syllabus.
     */
    public record AdmitChildRequest(@NotBlank String name, Integer grade, String curriculum, @NotBlank String classId,
                                    @NotBlank String parentName, String parentPhone, @NotBlank String parentEmail,
                                    @NotBlank String parentInitialPassword) {}

    /**
     * What was written. `parentCreated` is false when the address already had a parent row; `passwordApplied` is true
     * only when this call minted the Firebase login with the `parentInitialPassword` that was typed — a login that
     * already existed keeps the password its owner chose, and the screen has to say so.
     */
    public record ChildAdmission(String childId, String parentId, boolean parentCreated, boolean passwordApplied,
                                 String parentName) {}

    /** One line of the Children & parents page: the child, her class, and the parent the school can reach. */
    public record FamilyRow(String childId, String name, int grade, String curriculum, String classId, String className,
                            String parentId, String parentName, String parentEmail, String parentPhone, boolean active) {}

    /** A page of {@link FamilyRow}; `total` is every row the search matches, not the ones on this page. */
    public record FamilyPage(int page, int size, int total, List<FamilyRow> rows) {}

    /** `POST …/classes/{classId}/roster/attach`: a child who already exists, put onto this section's roster. */
    public record AttachChildRequest(@NotBlank String childId) {}

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
