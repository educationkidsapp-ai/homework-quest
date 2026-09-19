package quest.server.dashboard;

import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import quest.api.progress.Band;
import quest.api.progress.ProgressBands;
import quest.server.auth.Principals;
import quest.server.auth.UserRepository;
import quest.server.children.ProgressService;
import quest.server.config.ApiException;
import quest.server.platform.ThemeService;
import quest.server.tenancy.ClassRepository;
import quest.server.tenancy.Entities.ClassEntity;
import quest.server.tenancy.SchoolRepository;
import quest.server.tenancy.TenantContext;

/**
 * §6 screen 2: "Every Home shows the person's name, the school logo, three big number cards that count up on load,
 * and a 'what needs you' list." One endpoint, three shapes.
 *
 * <ul>
 *   <li><strong>ADMIN</strong> — schools, children and lessons published this week; needs you: lessons in error or
 *       still awaiting review, schools with no teacher, accounts still `invited` after a week. An Admin who has
 *       picked a school with `X-School-Id` gets the same Home narrowed to it (D6), logo and school name included.</li>
 *   <li><strong>TEACHER</strong> — her classes with today's lesson per class, children who played yesterday, lessons
 *       she published this week, open `needs_review`; needs you: a class with no lesson dated today (whose `href`
 *       opens New lesson with that class already chosen), her lessons in error, and the three weakest skills across
 *       her classes.</li>
 *   <li><strong>MANAGERIAL</strong> — children, families active this week, teachers; needs you: teachers who have
 *       published nothing for a week. Complaints are phase 5: those fields are <em>absent</em> here, not zero, so a
 *       dashboard cannot draw an empty inbox that does not exist yet.</li>
 * </ul>
 *
 * <p><strong>Bounded in statements and in rows.</strong> Every figure is a grouped query, never a query per class,
 * child, lesson or school, and every list is capped — `HomeQueryCountTest` pins the statement count against a seed
 * that grows and `HomeRowVolumeTest` pins that growing it does not materialise more entities. The weakest-skills
 * aggregate is the one that had to be rewritten for the second half of that; see {@link #weakestSkills}.
 *
 * <p>Timestamps are bound as UTC {@link LocalDateTime}s, not {@link java.time.Instant}s: the columns are
 * `TIMESTAMP` without a zone and `hibernate.jdbc.time_zone=UTC` is what wrote them, so a UTC wall time is exactly
 * what is stored and no driver gets to apply an offset of its own to a native parameter.
 */
@Service
public class HomeService {
    /** How long an invitation may sit unaccepted before the Admin's Home mentions it (§5 invites live seven days). */
    private static final int STALE_INVITE_DAYS = 7;
    /** How long a teacher may go without publishing before her school's Managerial Home mentions it. */
    private static final int QUIET_TEACHER_DAYS = 7;
    /** §6 screen 11: "the three weakest skills across her classes". */
    private static final int WEAK_SKILLS = 3;
    /** How far back the weakest-skills aggregate reads attempts: what her class is struggling with now. */
    private static final int WEAK_SKILL_DAYS = 28;
    /** …and how recently a lesson must have been published to be part of it. Roughly a term. */
    private static final int RECENT_LESSON_DAYS = 90;
    /** First tries a skill needs before it may be called weak, so one child's one wrong answer is not a headline. */
    private static final int MIN_TRIES = 5;
    /** Lists on a Home are a glance, not a screen: the long tail belongs on All lessons / Users. */
    private static final int MAX_ROWS = 10;

    private final EntityManager em; private final TenantContext tenant; private final SchoolRepository schools;
    private final ClassRepository classes; private final UserRepository users; private final ThemeService themes;
    private final Clock clock;

    public HomeService(EntityManager em, TenantContext tenant, SchoolRepository schools, ClassRepository classes,
                       UserRepository users, ThemeService themes, Clock clock) {
        this.em = em; this.tenant = tenant; this.schools = schools; this.classes = classes;
        this.users = users; this.themes = themes; this.clock = clock;
    }

    @Transactional(readOnly = true)
    public HomeDto.HomeResponse home(Principals.User caller) {
        if (caller == null) throw ApiException.unauthorized("Sign in first.");
        String schoolId = tenant.schoolId();                                    // null only for an Admin who picked none
        var school = schoolId == null ? null : schools.findById(schoolId).orElse(null);
        String schoolName = school == null ? null : school.getName();
        String logoUrl = school == null ? null : themes.themeOf(school).logoUrl();
        String platformName = themes.displayName(schoolId);
        String displayName = users.findById(caller.userId()).map(u -> u.getDisplayName() == null || u.getDisplayName().isBlank()
                ? u.getEmail() : u.getDisplayName()).orElse(caller.email());
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));

        return switch (caller.role() == null ? "" : caller.role()) {
            case "TEACHER" -> teacherHome(caller, displayName, schoolId, schoolName, logoUrl, platformName, today);
            case "MANAGERIAL" -> managerialHome(displayName, schoolId, schoolName, logoUrl, platformName, today);
            case "ADMIN" -> adminHome(displayName, schoolId, schoolName, logoUrl, platformName, today);
            default -> throw ApiException.forbidden("That account has no dashboard home.");
        };
    }

    // ---------------------------------------------------------------- ADMIN

    private HomeDto.HomeResponse adminHome(String displayName, String schoolId, String schoolName, String logoUrl,
                                           String platformName, LocalDate today) {
        var scope = Map.<String, Object>of("schoolId", schoolId == null ? "" : schoolId,
                "weekStart", startOfWeek(today), "cutoff", midnight(today.minusDays(STALE_INVITE_DAYS)));
        String schoolClause = schoolId == null ? "" : " AND school_id = :schoolId";

        long schoolCount = schoolId != null ? 1 : one("SELECT COUNT(*) FROM schools", scope);
        long childCount = one("SELECT COUNT(*) FROM children WHERE deleted_at IS NULL" + schoolClause, scope);
        long publishedThisWeek = one("SELECT COUNT(*) FROM lessons WHERE status = 'published' AND published_at >= :weekStart" + schoolClause, scope);

        var needsYou = new ArrayList<HomeDto.NeedsYouItem>();
        for (var row : Reports.rows(Reports.bind(em,
                "SELECT l.id, l.title, l.status, s.name, l.error_code FROM lessons l JOIN schools s ON s.id = l.school_id"
                        + " WHERE l.status IN ('error', 'needs_review')" + (schoolId == null ? "" : " AND l.school_id = :schoolId")
                        + " ORDER BY l.updated_at DESC", scope).setMaxResults(MAX_ROWS))) {
            var params = new LinkedHashMap<String, String>();
            putTitle(params, Reports.text(row[1]));
            params.put("schoolName", Reports.text(row[3]));
            if (Reports.text(row[4]) != null) params.put("errorCode", Reports.text(row[4]));
            needsYou.add(new HomeDto.NeedsYouItem("lesson." + Reports.text(row[2]), Reports.text(row[0]),
                    Map.copyOf(params), "/admin/lessons/" + Reports.text(row[0])));
        }
        if (schoolId == null)
            for (var row : Reports.rows(Reports.bind(em,
                    "SELECT s.id, s.name FROM schools s WHERE NOT EXISTS ("
                            + "SELECT 1 FROM users u WHERE u.school_id = s.id AND u.role = 'TEACHER' AND u.status <> 'disabled')"
                            + " ORDER BY s.name", Map.of()).setMaxResults(MAX_ROWS)))
                needsYou.add(new HomeDto.NeedsYouItem("school.noTeacher", Reports.text(row[0]),
                        Map.of("schoolName", Reports.text(row[1])), "/admin/schools/" + Reports.text(row[0]) + "/users"));
        for (var row : Reports.rows(Reports.bind(em,
                "SELECT u.id, u.email, u.role FROM users u WHERE u.status = 'invited' AND u.created_at < :cutoff"
                        + (schoolId == null ? "" : " AND u.school_id = :schoolId") + " ORDER BY u.created_at", scope).setMaxResults(MAX_ROWS)))
            needsYou.add(new HomeDto.NeedsYouItem("user.staleInvite", Reports.text(row[0]),
                    Map.of("email", Reports.text(row[1]), "role", Reports.text(row[2]),
                            "days", String.valueOf(STALE_INVITE_DAYS)),
                    "/admin/users?status=invited"));

        var cards = List.of(
                new HomeDto.HomeCard("schools", schoolCount),
                new HomeDto.HomeCard("children", childCount),
                new HomeDto.HomeCard("lessonsThisWeek", publishedThisWeek));
        return new HomeDto.HomeResponse("ADMIN", displayName, schoolId, schoolName, logoUrl, platformName,
                cards, List.copyOf(needsYou), null, null);
    }

    // ---------------------------------------------------------------- TEACHER

    private HomeDto.HomeResponse teacherHome(Principals.User caller, String displayName, String schoolId, String schoolName,
                                             String logoUrl, String platformName, LocalDate today) {
        var mine = classes.findBySchoolIdAndTeacherIdOrderByCurriculumAscGradeAscSubjectAsc(schoolId, caller.userId());
        var classIds = mine.stream().map(ClassEntity::getId).toList();
        var scope = Map.<String, Object>of("schoolId", schoolId, "classIds", classIds.isEmpty() ? List.of("") : classIds,
                "onDay", java.sql.Date.valueOf(today), "weekStart", startOfWeek(today),
                "dayStart", midnight(today.minusDays(1)), "dayEnd", midnight(today));

        // One query for today's lesson of every class, not one per class.
        var todayByClass = new LinkedHashMap<String, String[]>();
        if (!classIds.isEmpty())
            for (var row : Reports.rows(Reports.bind(em,
                    "SELECT l.class_id, l.id, l.status FROM lessons l WHERE l.school_id = :schoolId"
                            + " AND l.class_id IN (:classIds) AND l.date = :onDay ORDER BY l.updated_at DESC", scope)))
                todayByClass.putIfAbsent(Reports.text(row[0]), new String[] {Reports.text(row[1]), Reports.text(row[2])});

        var classInfos = mine.stream().map(k -> {
            var lesson = todayByClass.get(k.getId());
            return new HomeDto.TeacherClassInfo(k.getId(), k.getCurriculum(), k.getGrade(), k.getSubject(),
                    lesson == null ? null : lesson[0], lesson == null ? null : lesson[1]);
        }).toList();

        long playedYesterday = classIds.isEmpty() ? 0 : one(
                "SELECT COUNT(DISTINCT a.child_id) FROM attempts a JOIN lessons l ON l.id = a.lesson_id"
                        + " WHERE l.school_id = :schoolId AND l.class_id IN (:classIds)"
                        + " AND a.answered_at >= :dayStart AND a.answered_at < :dayEnd", scope);
        long publishedThisWeek = classIds.isEmpty() ? 0 : one(
                "SELECT COUNT(*) FROM lessons WHERE school_id = :schoolId AND class_id IN (:classIds)"
                        + " AND status = 'published' AND published_at >= :weekStart", scope);
        long openReviews = classIds.isEmpty() ? 0 : one(
                "SELECT COUNT(*) FROM lessons WHERE school_id = :schoolId AND class_id IN (:classIds) AND status = 'needs_review'", scope);

        var needsYou = new ArrayList<HomeDto.NeedsYouItem>();
        for (var info : classInfos)
            if (info.todayLessonId() == null)
                needsYou.add(new HomeDto.NeedsYouItem("class.noLessonToday", info.classId(),
                        Map.of("curriculum", info.curriculum(), "grade", String.valueOf(info.grade()),
                                "subject", info.subject(), "date", today.toString()),
                        "/teacher/lessons/new?classId=" + info.classId() + "&curriculum=" + info.curriculum()
                                + "&grade=" + info.grade() + "&subject=" + info.subject() + "&date=" + today));
        if (!classIds.isEmpty())
            for (var row : Reports.rows(Reports.bind(em,
                    "SELECT l.id, l.title, l.error_code FROM lessons l WHERE l.school_id = :schoolId"
                            + " AND l.class_id IN (:classIds) AND l.status = 'error' ORDER BY l.updated_at DESC", scope).setMaxResults(MAX_ROWS))) {
                var params = new LinkedHashMap<String, String>();
                putTitle(params, Reports.text(row[1]));
                if (Reports.text(row[2]) != null) params.put("errorCode", Reports.text(row[2]));
                needsYou.add(new HomeDto.NeedsYouItem("lesson.error", Reports.text(row[0]), Map.copyOf(params),
                        "/teacher/lessons/" + Reports.text(row[0])));
            }

        // The three weakest are reported whatever their band, so the Home can show how the class is doing; only the
        // ones that actually need another look become something that needs *her*.
        var weak = weakestSkills(schoolId, classIds, today);
        for (var skill : weak)
            if (Band.NEEDS_ANOTHER_LOOK.name().equals(skill.band()))
                needsYou.add(new HomeDto.NeedsYouItem("skill.weak", skill.skillId(),
                        Map.of("skillName", skill.name(), "band", skill.band()),
                        "/teacher/students?skillId=" + skill.skillId()));

        var cards = List.of(
                new HomeDto.HomeCard("playedYesterday", playedYesterday),
                new HomeDto.HomeCard("lessonsThisWeek", publishedThisWeek),
                new HomeDto.HomeCard("needsReview", openReviews));
        return new HomeDto.HomeResponse("TEACHER", displayName, schoolId, schoolName, logoUrl, platformName,
                cards, List.copyOf(needsYou), classInfos, weak);
    }

    /**
     * The three skills her classes are weakest at, as <strong>one bounded aggregate</strong>.
     *
     * <p>This is the one figure on a Home that could grow without limit, and it is on the route `/` redirects every
     * teacher to. An earlier shape loaded the school's whole roll, every published lesson of her classes and every
     * attempt those children had ever made as JPA entities, to end up printing three names: constant in statements
     * and linear in rows, which on a 600-child school a year in is hundreds of thousands of entities. So the whole
     * thing is a single grouped query that windows both ends and returns at most {@value #WEAK_SKILLS} rows:
     *
     * <ul>
     *   <li>attempts from the last {@value #WEAK_SKILL_DAYS} days — a teacher wants to know what her class is
     *       struggling with <em>now</em>, not what last autumn's class found hard;</li>
     *   <li>lessons published in the last {@value #RECENT_LESSON_DAYS} days — roughly a term;</li>
     *   <li>at least {@value #MIN_TRIES} first tries before a skill may be called weak, so one child's one wrong
     *       answer does not top the list and teach her to ignore the panel.</li>
     * </ul>
     *
     * <p>Two deliberate differences from a child's own progress ({@link ProgressService}), which is unchanged and
     * still the rule parents see. Accuracy is pooled over the date window rather than over
     * {@link ProgressBands#WINDOW} attempts — "the last 14" is a recency rule for one child and means nothing once
     * many children are pooled — and it counts practice stops (`stops.category = SINGLE`), not the single-answer
     * questions nested inside an exit ticket, which have no `stops` row of their own to join to. The band
     * thresholds are {@link ProgressBands}' own, so "needs another look" means the same thing on both screens.
     */
    private List<HomeDto.WeakSkill> weakestSkills(String schoolId, List<String> classIds, LocalDate today) {
        if (classIds.isEmpty()) return List.of();
        var scope = Map.<String, Object>of("schoolId", schoolId, "classIds", classIds,
                "attemptsSince", midnight(today.minusDays(WEAK_SKILL_DAYS)),
                "lessonsSince", midnight(today.minusDays(RECENT_LESSON_DAYS)));

        var rows = Reports.rows(Reports.bind(em,
                "SELECT sk.id, sk.name, COUNT(*) AS tries, SUM(CASE WHEN a.correct THEN 1 ELSE 0 END) AS correct"
                        + " FROM attempts a"
                        + " JOIN stops s ON s.id = a.stop_id"
                        + " JOIN lessons l ON l.id = a.lesson_id"
                        + " JOIN children ch ON ch.id = a.child_id"
                        + " JOIN skills sk ON sk.lesson_id = l.id"
                        + " WHERE l.school_id = :schoolId AND l.class_id IN (:classIds) AND l.status = 'published'"
                        + " AND l.published_at >= :lessonsSince"
                        + " AND ch.school_id = :schoolId AND ch.deleted_at IS NULL"
                        + " AND sk.confirmed = TRUE AND UPPER(s.category) = 'SINGLE' AND a.attempt_number = 1"
                        + " AND a.answered_at >= :attemptsSince"
                        + " GROUP BY sk.id, sk.name"
                        + " HAVING COUNT(*) >= " + MIN_TRIES
                        + " ORDER BY (SUM(CASE WHEN a.correct THEN 1 ELSE 0 END) * 1.0) / COUNT(*) ASC, sk.name ASC",
                scope).setMaxResults(WEAK_SKILLS));

        var out = new ArrayList<HomeDto.WeakSkill>(rows.size());
        for (var row : rows) {
            double accuracy = (double) Reports.number(row[3]) / Reports.number(row[2]);
            out.add(new HomeDto.WeakSkill(Reports.text(row[0]), Reports.text(row[1]),
                    ProgressBands.INSTANCE.band(accuracy).name()));
        }
        return List.copyOf(out);
    }

    // ---------------------------------------------------------------- MANAGERIAL

    private HomeDto.HomeResponse managerialHome(String displayName, String schoolId, String schoolName, String logoUrl,
                                                String platformName, LocalDate today) {
        var scope = Map.<String, Object>of("schoolId", schoolId, "weekStart", startOfWeek(today),
                "cutoff", midnight(today.minusDays(QUIET_TEACHER_DAYS)));

        long childCount = one("SELECT COUNT(*) FROM children WHERE school_id = :schoolId AND deleted_at IS NULL", scope);
        long activeFamilies = one("SELECT COUNT(DISTINCT c.parent_id) FROM children c JOIN attempts a ON a.child_id = c.id"
                + " WHERE c.school_id = :schoolId AND c.deleted_at IS NULL AND a.answered_at >= :weekStart", scope);
        // `active`, not "not disabled": someone who has not accepted their invitation yet is not staff, and nagging a
        // Managerial user that they have published nothing would be noise rather than something that needs her.
        long teacherCount = one("SELECT COUNT(*) FROM users WHERE school_id = :schoolId AND role = 'TEACHER' AND status = 'active'", scope);

        var needsYou = new ArrayList<HomeDto.NeedsYouItem>();
        for (var row : Reports.rows(Reports.bind(em,
                "SELECT u.id, u.display_name, u.email FROM users u WHERE u.school_id = :schoolId AND u.role = 'TEACHER'"
                        + " AND u.status = 'active' AND NOT EXISTS (SELECT 1 FROM lessons l JOIN classes k ON k.id = l.class_id"
                        + " WHERE k.teacher_id = u.id AND l.school_id = :schoolId AND l.status = 'published' AND l.published_at >= :cutoff)"
                        + " ORDER BY u.email", scope).setMaxResults(MAX_ROWS)))
            needsYou.add(new HomeDto.NeedsYouItem("teacher.quiet", Reports.text(row[0]),
                    Map.of("teacherName", Reports.text(row[1]) == null ? Reports.text(row[2]) : Reports.text(row[1]),
                            "days", String.valueOf(QUIET_TEACHER_DAYS)),
                    "/management/teachers"));

        var cards = List.of(
                new HomeDto.HomeCard("children", childCount),
                new HomeDto.HomeCard("activeFamilies", activeFamilies),
                new HomeDto.HomeCard("teachers", teacherCount));
        return new HomeDto.HomeResponse("MANAGERIAL", displayName, schoolId, schoolName, logoUrl, platformName,
                cards, List.copyOf(needsYou), null, null);
    }

    // ---------------------------------------------------------------- helpers

    private long one(String sql, Map<String, Object> parameters) {
        return Reports.number(Reports.bind(em, sql, parameters).getSingleResult());
    }

    /**
     * A lesson with no title yet contributes <em>no</em> `lessonTitle` param at all, rather than an English
     * "Untitled lesson" the dashboard would print untranslated into an Arabic page. `NeedsYouItem.params` carries
     * data, not prose (see its doc), so the missing-title wording belongs to the dashboard's own catalogue —
     * `home.needs.lesson.error` resolves a variant without the title when the param is absent.
     */
    private static void putTitle(Map<String, String> params, String value) {
        if (value != null && !value.isBlank()) params.put("lessonTitle", value);
    }

    private static Instant startOfWeek(LocalDate today) { return midnight(Reports.weekOf(today)); }

    private static Instant midnight(LocalDate day) { return Reports.startOf(day); }
}
