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
     * One day of a class's month. `schoolDay` is what makes `gap` meaningful — see
     * {@link TeacherCalendarService#SCHOOL_WEEK} for the Sunday–Thursday assumption and how to lift it.
     */
    public record ClassCalendarDay(String date, String lessonId, String status, boolean schoolDay, boolean gap) {}

    public record ClassCalendar(String classId, String curriculum, int grade, String subject, int year, int month,
                                List<ClassCalendarDay> days, int gaps) {}

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

    public record ClassStudent(String childId, String name, String avatarColor, int starsThisWeek, int levelReached,
                               List<WeakSkill> weakSkills, Long lastPlayed) {}

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
