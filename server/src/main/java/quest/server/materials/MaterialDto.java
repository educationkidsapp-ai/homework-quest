package quest.server.materials;

import java.util.List;

/**
 * The Java mirror of `quest.api.dashboard.TeachingMaterials.kt` (feature 31). Weeks are the first teaching day of
 * the week the material is <em>for</em> (`yyyy-MM-dd`, the school's week and zone), `dueAt` and the times are
 * ISO-8601 instants, and `timeZone` says which zone a client renders them in.
 */
public final class MaterialDto {
    private MaterialDto() {}

    /** A row's one status, computed from the deadline and the review — never stored. */
    public static final String ON_TIME = "ON_TIME", LATE = "LATE", MISSING = "MISSING", REVIEWED = "REVIEWED";

    public record MaterialRow(String submissionId, String classId, String className, String subject, String status,
                              String attachmentId, String attachmentName, String note, String submittedAt,
                              String reviewedBy, String reviewedByName, String reviewedAt, String reviewComment) {}

    /** The four cards; each is the number of rows its filter shows. */
    public record MaterialCounts(int onTime, int late, int missing, int reviewed) {}

    /** `GET /teacher/materials?week=`. */
    public record TeacherMaterials(String week, String dueAt, String timeZone, MaterialCounts counts,
                                   List<MaterialRow> rows) {}

    /** One teacher on the leaders' board, expandable to her (class, subject) rows. */
    public record MaterialBoardRow(String teacherId, String fullName, MaterialCounts counts, List<MaterialRow> rows) {}

    /** `GET /materials/board?week=`; `.csv` is the same rows as a file. `scope` is feature 17's. */
    public record MaterialBoard(String week, String dueAt, String timeZone, String scope, MaterialCounts counts,
                                List<MaterialBoardRow> rows) {}

    /** `PUT /teacher/materials/{classId}/{subject}?week=`: at least one of the two is required. */
    public record SubmitMaterialRequest(String attachmentId, String note) {}

    /** `POST /materials/{id}/review`. */
    public record ReviewMaterialRequest(String comment) {}

    /** `GET`/`PUT /admin/material-settings`. `dueWeekday` is a `DayOfWeek` name and `dueTime` is `HH:mm`. */
    public record MaterialSettings(String dueWeekday, String dueTime, int remindHoursBefore, String timeZone) {}

    public record UpdateMaterialSettingsRequest(String dueWeekday, String dueTime, Integer remindHoursBefore) {}
}
