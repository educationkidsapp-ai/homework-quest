package quest.server.dashboard;

import java.util.List;

/**
 * §6 screen 2: the Java mirror of the Home half of `quest.api.dashboard` — one shape for all three roles, with the
 * sections a role does not have left null rather than empty, so the dashboard can tell "no classes" from "not a
 * teacher".
 */
public final class HomeDto {
    private HomeDto() {}

    /** One of the three big numbers the Home counts up on load. `value` is formatted here, not in the browser. */
    public record HomeCard(String key, String label, String value) {}

    /**
     * One row of "what needs you". `kind` is what the dashboard groups and icons by; `href` is the route that does
     * something about it, already carrying whatever the target screen needs preselected.
     */
    public record NeedsYouItem(String kind, String title, String subtitle, String href) {}

    /** A teacher's class with the state of today's lesson for it (§6 screen 11). */
    public record TeacherClassInfo(String classId, String curriculum, int grade, String subject,
                                   String todayLessonId, String todayStatus) {}

    /** A skill the children are weakest at, banded by `ProgressBands` — words, never a percentage. */
    public record WeakSkill(String skillId, String name, String band) {}

    /** `GET /me/home`. `classes` and `weakSkills` are TEACHER-only and null for the other two roles. */
    public record HomeResponse(String role, String displayName, String schoolId, String schoolName, String schoolLogoUrl,
                               String platformName, List<HomeCard> cards, List<NeedsYouItem> needsYou,
                               List<TeacherClassInfo> classes, List<WeakSkill> weakSkills) {}
}
