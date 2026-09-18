package quest.server.teacher;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * The Java mirror of `quest.api.dashboard.Teacher.kt` (§5, §6 screens 11–16).
 *
 * <p>Records rather than the Kotlin types, for the same reason `SchoolDataDto` is: springdoc derives a real schema
 * from a record, and `server/openapi.json` is what the Angular client is generated from — a route that returned the
 * kotlinx encoding as a `String` would be documented as `type: string` and generate nothing usable.
 *
 * <p>The one exception is `stops`, which is a {@link JsonNode}: a §5 `Stop` is a Kotlin sealed interface with a
 * `type` discriminator and 22 branches, so only the shared kotlinx codec can read or write one. The node carries
 * exactly the bytes that codec produces, and {@link TeacherQuestionService} decodes it with
 * `Stop.Companion.serializer()` before anything is stored — so the wire shape is the contract's, whichever side
 * wrote it.
 */
public final class TeacherDto {
    private TeacherDto() {}

    // ---------------------------------------------------------------- profile and options

    /** The public half of a TEACHER account (§5): what the dashboard, the teacher island and the school page show. */
    public record TeacherProfile(String userId, String email, String displayName, String photoUrl,
                                 List<String> subjects, String curriculum, List<Integer> grades,
                                 String bioEn, String bioAr) {}

    /** Only the fields that are present are written, so one section of the form can be saved on its own. */
    public record UpdateTeacherProfileRequest(@Size(max = 120) String displayName, String photoUrl,
                                              List<String> subjects, String curriculum, List<Integer> grades,
                                              @Size(max = 2000) String bioEn, @Size(max = 2000) String bioAr) {}

    /** §6 screen 13: what the New lesson chooser may offer, and nothing else. */
    public record TeacherOptions(String curriculum, List<Integer> grades, List<String> subjects,
                                 List<quest.server.dashboard.SchoolDataDto.SchoolClass> classes, boolean complete) {}

    // ---------------------------------------------------------------- my lessons (§6 screen 12)

    /**
     * One day of a class's month. `schoolDay` is what makes `gap` meaningful, and since N2.1 it comes from the
     * school's own week ({@link quest.server.platform.SchoolCalendar}) rather than a Sunday–Thursday constant.
     * `status` is the week grid's coarse vocabulary — see {@link #DRAFT} — so one screen's words are every screen's.
     */
    public record ClassCalendarDay(String date, String lessonId, String title, String status, String type,
                                   int playedCount, boolean schoolDay, boolean gap) {}

    /**
     * The month, with the section named (N2.3b): `className` is `1A`, and `subject` is the one the caller teaches
     * in it — a section carries its subjects on its teaching assignments since V7, so reading `classes.subject`
     * answered null for every section the Admin API created. Null only for a section nobody teaches.
     */
    public record ClassCalendar(String classId, String className, String curriculum, int grade, String subject,
                                int year, int month, List<ClassCalendarDay> days, int gaps) {}


    // ---------------------------------------------------------------- this week and my classes (N2.1, §4, §7)

    /**
     * The three words §4 gives a lesson, which is coarser than `LessonStatus` on purpose: `draft` is everything
     * still being made (draft, uploading, analyzing, needs_review, generating, error, paused), `ready` is `review`
     * — made and waiting to be published — and `published` is live. `none` is an empty cell.
     */
    public static final String NONE = "none", DRAFT = "draft", READY = "ready", PUBLISHED = "published";

    /** The lesson in one cell; `playedCount` of `childrenCount` is §4's "12/24 played", with no second request. */
    public record WeekLesson(String id, String title, String status, String type, int playedCount, int childrenCount,
                             int version) {}

    /** The exam window a cell falls in (N4.3). Always null today; the field exists so the grid is not reshaped. */
    public record WeekExam(String lessonId, String opensAt, String closesAt) {}

    public record WeekCell(String date, WeekLesson lesson, WeekExam exam) {}

    /** One row of the grid: one teaching assignment, one cell per teaching day. */
    public record WeekRow(String classId, String className, String curriculum, int grade, String subject,
                          List<WeekCell> cells) {}

    /** A school day of one of her classes with nothing on it, named so the strip can say "1B · Tuesday". */
    public record WeekGap(String classId, String className, String date) {}

    /** The strip under the grid; `examsClosing` and `marksWaiting` stay empty until N4. */
    public record WeekSummary(List<WeekGap> gaps, List<WeekGap> examsClosing, int marksWaiting) {}

    /** `GET /teacher/week`: `start` is the requested day snapped back to the week's first teaching day. */
    public record TeacherWeek(String start, List<String> days, List<WeekRow> rows, WeekSummary summary) {}

    /** `GET /teacher/classes` (§7): one card per assignment. */
    public record TeacherClassCard(String classId, String className, String curriculum, int grade, String subject,
                                   String todayLessonId, String todayStatus, int childrenCount, int playedToday) {}

    // ---------------------------------------------------------------- her lessons (N2.1, §8)

    /** `(classId, subject)` must be one of her assignments — 403 otherwise, whatever the chooser offered. */
    public record CreateTeacherLessonRequest(@NotBlank String classId, @NotBlank String subject, @NotBlank String date,
                                             @NotBlank String source, @Size(max = 200) String title,
                                             @Size(max = 2000) String notes, Integer practiceLength) {}

    /** Moving a lesson to another day. 409 once it is published: unpublish it first. */
    public record MoveLessonRequest(@NotBlank String date) {}

    /** A full copy into another class of the same grade and subject she teaches. */
    public record CopyLessonRequest(@NotBlank String classId) {}

    /**
     * The complete set of classes the lesson should be live in. The lesson's own class is not implied — name it to
     * publish it, leave it out to publish only the copies.
     */
    public record PublishToClassesRequest(List<String> classIds) {}

    /** One result of a publish: the class, the lesson row live in it, and the version that publish produced. */
    public record PublishedCopy(String classId, String lessonId, int version) {}

    // ---------------------------------------------------------------- questions to students (§6 screen 14)

    public record CreateTeacherQuestionRequest(@NotBlank @Size(max = 120) String title, JsonNode stops,
                                               List<String> classIds, String from, String to) {}

    /** Every field optional: an absent one is left as it is. Refused with 409 once the question has been sent. */
    public record UpdateTeacherQuestionRequest(@Size(max = 120) String title, JsonNode stops,
                                               List<String> classIds, String from, String to) {}

    public record TeacherQuestion(String id, String schoolId, String teacherId, String teacherName, String title,
                                  JsonNode stops, List<String> classIds, String from, String to,
                                  long createdAt, Long sentAt,
                                  int totalChildren, int answeredChildren, Double firstTryAccuracy) {}

    public record TeacherQuestionStopResult(String stopId, boolean answered, boolean correct, int stars, Long answeredAt) {}

    public record TeacherQuestionChildResult(String childId, String name, String classId, int answered, int stars,
                                             List<TeacherQuestionStopResult> stops) {}

    public record TeacherQuestionResults(String questionId, String title, List<String> stopIds,
                                         List<TeacherQuestionChildResult> children, Double firstTryAccuracy) {}

    // ---------------------------------------------------------------- announcements (§6 screen 16)

    public record CreateAnnouncementRequest(@NotBlank String classId, @NotBlank @Size(max = 1000) String bodyEn,
                                            @Size(max = 1000) String bodyAr, Long expiresAt) {}

    public record Announcement(String id, String schoolId, String teacherId, String teacherName, String classId,
                               String curriculum, Integer grade, String subject, String bodyEn, String bodyAr,
                               long publishedAt, Long expiresAt, long createdAt) {}

    // ---------------------------------------------------------------- my students (§6 screen 15)

    /** A skill the child is weakest at, banded by `ProgressBands` — words, never a percentage. */
    public record WeakSkill(String skillId, String name, String band) {}

    /** `classId`/`className` are the section she sits in — the one in the path, never her whole grade (N2.3b). */
    public record ClassStudent(String childId, String name, String classId, String className, String avatarColor,
                               int starsThisWeek, int levelReached, List<WeakSkill> weakSkills, Long lastPlayed) {}

    public record StudentTimelineEntry(String kind, long at, String title, String lessonId, Integer level,
                                       Integer starsEarned, Integer starsTotal, String questionId, String stopId,
                                       Boolean correct, Integer stars) {}

    public record StudentMedia(String id, String url, String kind, String stopId, long createdAt) {}

    public record StudentTimeline(String childId, String name, String from, String to,
                                  List<StudentTimelineEntry> entries, List<StudentMedia> media) {}

    // The app side (`TeacherQuestionPlay`, `TeacherAnswerUpload`, `ParentAnnouncement`) has no mirror here: those
    // routes hang off `/children/**` and are encoded with the shared kotlinx codec like every other `ContentApi`
    // route, because a teacher's question is a list of `Stop`s and only that codec can write one.
}
