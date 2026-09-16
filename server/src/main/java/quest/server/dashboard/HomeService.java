package quest.server.dashboard;

import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import quest.server.auth.Principals;
import quest.server.auth.UserRepository;
import quest.server.children.ChildRepository;
import quest.server.children.ProgressService;
import quest.server.config.ApiException;
import quest.server.content.Entities.LessonEntity;
import quest.server.content.LessonRepository;
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
 * <p><strong>No N+1.</strong> Every figure is a grouped query over a whole table, never a query per class, child,
 * lesson or school; `HomeQueryCountTest` pins the statement count against a seed that grows. The weakest skills go
 * through {@link ProgressService#weakestSkills}, which reads one group's attempts, plays and skills in three queries
 * rather than running a child's progress report per child.
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
    /** Lists on a Home are a glance, not a screen: the long tail belongs on All lessons / Users. */
    private static final int MAX_ROWS = 10;

    private final EntityManager em; private final TenantContext tenant; private final SchoolRepository schools;
    private final ClassRepository classes; private final LessonRepository lessons; private final ChildRepository children;
    private final UserRepository users; private final ProgressService progress; private final ThemeService themes;

    public HomeService(EntityManager em, TenantContext tenant, SchoolRepository schools, ClassRepository classes,
                       LessonRepository lessons, ChildRepository children, UserRepository users,
                       ProgressService progress, ThemeService themes) {
        this.em = em; this.tenant = tenant; this.schools = schools; this.classes = classes; this.lessons = lessons;
        this.children = children; this.users = users; this.progress = progress; this.themes = themes;
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
        LocalDate today = LocalDate.now(ZoneOffset.UTC);

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
                "SELECT l.id, l.title, l.status, s.name FROM lessons l JOIN schools s ON s.id = l.school_id"
                        + " WHERE l.status IN ('error', 'needs_review')" + (schoolId == null ? "" : " AND l.school_id = :schoolId")
                        + " ORDER BY l.updated_at DESC", scope).setMaxResults(MAX_ROWS))) {
            String status = Reports.text(row[2]);
            needsYou.add(new HomeDto.NeedsYouItem("lesson." + status, title(Reports.text(row[1])),
                    Reports.text(row[3]) + " · " + ("error".equals(status) ? "failed" : "waiting for review"),
                    "/admin/lessons/" + Reports.text(row[0])));
        }
        if (schoolId == null)
            for (var row : Reports.rows(Reports.bind(em,
                    "SELECT s.id, s.name FROM schools s WHERE NOT EXISTS ("
                            + "SELECT 1 FROM users u WHERE u.school_id = s.id AND u.role = 'TEACHER' AND u.status <> 'disabled')"
                            + " ORDER BY s.name", Map.of()).setMaxResults(MAX_ROWS)))
                needsYou.add(new HomeDto.NeedsYouItem("school.noTeacher", Reports.text(row[1]),
                        "No teacher yet", "/admin/schools/" + Reports.text(row[0]) + "/users"));
        for (var row : Reports.rows(Reports.bind(em,
                "SELECT u.id, u.email, u.role FROM users u WHERE u.status = 'invited' AND u.created_at < :cutoff"
                        + (schoolId == null ? "" : " AND u.school_id = :schoolId") + " ORDER BY u.created_at", scope).setMaxResults(MAX_ROWS)))
            needsYou.add(new HomeDto.NeedsYouItem("user.staleInvite", Reports.text(row[1]),
                    "Invited as " + Reports.text(row[2]).toLowerCase(Locale.ROOT) + " over " + STALE_INVITE_DAYS + " days ago",
                    "/admin/users?status=invited"));

        var cards = List.of(
                new HomeDto.HomeCard("schools", schoolId == null ? "Schools" : "School", String.valueOf(schoolCount)),
                new HomeDto.HomeCard("children", "Children", String.valueOf(childCount)),
                new HomeDto.HomeCard("lessonsThisWeek", "Lessons published this week", String.valueOf(publishedThisWeek)));
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
                needsYou.add(new HomeDto.NeedsYouItem("class.noLessonToday", label(info), "No lesson for today yet",
                        "/teacher/lessons/new?classId=" + info.classId() + "&curriculum=" + info.curriculum()
                                + "&grade=" + info.grade() + "&subject=" + info.subject() + "&date=" + today));
        if (!classIds.isEmpty())
            for (var row : Reports.rows(Reports.bind(em,
                    "SELECT l.id, l.title, l.error_message FROM lessons l WHERE l.school_id = :schoolId"
                            + " AND l.class_id IN (:classIds) AND l.status = 'error' ORDER BY l.updated_at DESC", scope).setMaxResults(MAX_ROWS)))
                needsYou.add(new HomeDto.NeedsYouItem("lesson.error", title(Reports.text(row[1])),
                        Reports.text(row[2]) == null ? "Failed" : Reports.text(row[2]), "/teacher/lessons/" + Reports.text(row[0])));

        var weak = weakestSkills(mine, classIds);
        for (var skill : weak)
            needsYou.add(new HomeDto.NeedsYouItem("skill.weak", skill.name(), "Children need another look at this",
                    "/teacher/students?skillId=" + skill.skillId()));

        var cards = List.of(
                new HomeDto.HomeCard("playedYesterday", "Children who played yesterday", String.valueOf(playedYesterday)),
                new HomeDto.HomeCard("lessonsThisWeek", "Lessons published this week", String.valueOf(publishedThisWeek)),
                new HomeDto.HomeCard("needsReview", "Lessons waiting for review", String.valueOf(openReviews)));
        return new HomeDto.HomeResponse("TEACHER", displayName, schoolId, schoolName, logoUrl, platformName,
                cards, List.copyOf(needsYou), classInfos, weak);
    }

    /**
     * The three weakest skills over the children of a teacher's classes. The children are her school's live roll
     * narrowed to the (curriculum, grade) pairs she teaches — §2's rule that a child belongs to a school plus a
     * curriculum and a grade, read from the other end — and the lessons are those classes' published ones.
     */
    private List<HomeDto.WeakSkill> weakestSkills(List<ClassEntity> mine, List<String> classIds) {
        if (classIds.isEmpty()) return List.of();
        Set<String> courses = new HashSet<>();
        for (var k : mine) courses.add(k.getCurriculum().toLowerCase(Locale.ROOT) + "/" + k.getGrade());
        var pupils = children.findByDeletedAtIsNullOrderByCreatedAt().stream()
                .filter(c -> courses.contains(c.getCurriculum().toLowerCase(Locale.ROOT) + "/" + c.getGrade())).toList();
        if (pupils.isEmpty()) return List.of();
        List<LessonEntity> published = lessons.findByClassIdInAndStatusOrderByDateAsc(classIds, "published");
        if (published.isEmpty()) return List.of();
        return progress.weakestSkills(pupils, published, WEAK_SKILLS).stream()
                .map(b -> new HomeDto.WeakSkill(b.skillId(), b.name(), b.band().name())).toList();
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
            needsYou.add(new HomeDto.NeedsYouItem("teacher.quiet",
                    Reports.text(row[1]) == null ? Reports.text(row[2]) : Reports.text(row[1]),
                    "No lesson published in " + QUIET_TEACHER_DAYS + " days", "/management/teachers"));

        var cards = List.of(
                new HomeDto.HomeCard("children", "Children", String.valueOf(childCount)),
                new HomeDto.HomeCard("activeFamilies", "Families active this week", String.valueOf(activeFamilies)),
                new HomeDto.HomeCard("teachers", "Teachers", String.valueOf(teacherCount)));
        return new HomeDto.HomeResponse("MANAGERIAL", displayName, schoolId, schoolName, logoUrl, platformName,
                cards, List.copyOf(needsYou), null, null);
    }

    // ---------------------------------------------------------------- helpers

    private long one(String sql, Map<String, Object> parameters) {
        return Reports.number(Reports.bind(em, sql, parameters).getSingleResult());
    }

    private static String label(HomeDto.TeacherClassInfo info) {
        return capitalise(info.curriculum()) + " · Grade " + info.grade() + " · " + capitalise(info.subject());
    }

    private static String capitalise(String value) {
        return value == null || value.isEmpty() ? "" : Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private static String title(String value) { return value == null || value.isBlank() ? "Untitled lesson" : value; }

    private static LocalDateTime startOfWeek(LocalDate today) { return midnight(Reports.weekOf(today)); }

    private static LocalDateTime midnight(LocalDate day) { return day.atStartOfDay(); }
}
