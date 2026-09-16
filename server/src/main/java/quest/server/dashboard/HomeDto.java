package quest.server.dashboard;

import java.util.List;
import java.util.Map;

/**
 * §6 screen 2: the Java mirror of the Home half of `quest.api.dashboard` — one shape for all three roles, with the
 * sections a role does not have left null rather than empty, so the dashboard can tell "no classes" from "not a
 * teacher".
 *
 * <p><strong>No prose crosses this boundary.</strong> §6 requires the dashboard to switch EN/AR without a reload and
 * the server has no `Accept-Language`, no `MessageSource` and no idea which language the tab is in — so a card and a
 * "needs you" row carry a message <em>id</em> ({@code key} / {@code kind}) and the values that message needs
 * ({@code params}), never an assembled sentence. The dashboard's Transloco catalogue holds both languages.
 *
 * <h2>The contract P3.1 implements</h2>
 * Every `key` resolves as {@code home.card.<key>} and every `kind` as {@code home.needs.<kind>}; `params` are
 * interpolated into it. A param marked {@code ?} may be absent, and absent is not empty: {@code lessonTitle} is
 * left out for a lesson that has no title yet, because the server has no wording of its own to put there and an
 * English "Untitled lesson" would be printed verbatim into an Arabic page — the catalogue resolves a variant that
 * reads without it. `params` values are strings because that is what an ICU message takes — counts are decimal,
 * dates are ISO {@code yyyy-MM-dd}. Unknown keys must render as nothing rather than as the raw id: a later phase will
 * add rows this dashboard build has no string for.
 *
 * <table>
 *   <caption>Home cards</caption>
 *   <tr><th>role</th><th>key</th><th>value</th></tr>
 *   <tr><td>ADMIN</td><td>{@code schools}, {@code children}, {@code lessonsThisWeek}</td><td>the count</td></tr>
 *   <tr><td>TEACHER</td><td>{@code playedYesterday}, {@code lessonsThisWeek}, {@code needsReview}</td><td>the count</td></tr>
 *   <tr><td>MANAGERIAL</td><td>{@code children}, {@code activeFamilies}, {@code teachers}</td><td>the count</td></tr>
 * </table>
 *
 * <table>
 *   <caption>"What needs you" rows</caption>
 *   <tr><th>kind</th><th>targetId</th><th>params</th></tr>
 *   <tr><td>{@code lesson.error}</td><td>lesson id</td><td>{@code lessonTitle}?, {@code errorCode}?, {@code schoolName}? (Admin)</td></tr>
 *   <tr><td>{@code lesson.needs_review}</td><td>lesson id</td><td>{@code lessonTitle}?, {@code schoolName}? (Admin)</td></tr>
 *   <tr><td>{@code school.noTeacher}</td><td>school id</td><td>{@code schoolName}</td></tr>
 *   <tr><td>{@code user.staleInvite}</td><td>user id</td><td>{@code email}, {@code role}, {@code days}</td></tr>
 *   <tr><td>{@code class.noLessonToday}</td><td>class id</td><td>{@code curriculum}, {@code grade}, {@code subject}, {@code date}</td></tr>
 *   <tr><td>{@code skill.weak}</td><td>skill id</td><td>{@code skillName}, {@code band}</td></tr>
 *   <tr><td>{@code teacher.quiet}</td><td>user id</td><td>{@code teacherName}, {@code days}</td></tr>
 * </table>
 *
 * <p>The only strings that are not message ids are the ones that <em>are</em> the data: a school's name, a lesson's
 * title, a person's name, a skill's name. Those are what the school typed and are not the server's to translate.
 */
public final class HomeDto {
    private HomeDto() {}

    /**
     * One of the three big numbers the Home counts up on load. `key` is the message id; `value` is the number, typed
     * rather than pre-formatted because the count-up animation has to interpolate it and Arabic digits are the
     * browser's business.
     */
    public record HomeCard(String key, long value) {}

    /**
     * One row of "what needs you". `kind` is the message id and what the dashboard groups and icons by; `targetId`
     * is the row's subject (a lesson, a school, a user, a class, a skill); `params` are the values the message
     * interpolates; `href` is the route that does something about it, already carrying whatever the target screen
     * needs preselected. See the table on {@link HomeDto}.
     */
    public record NeedsYouItem(String kind, String targetId, Map<String, String> params, String href) {}

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
