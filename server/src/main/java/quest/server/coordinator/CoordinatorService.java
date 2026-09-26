package quest.server.coordinator;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import quest.api.AdminLesson;
import quest.api.LessonFilter;
import quest.api.dto.LessonStatus;
import quest.server.admin.AdminLessonService;
import quest.server.analysis.LessonState;
import quest.server.auth.Entities.UserEntity;
import quest.server.auth.Principals;
import quest.server.auth.UserRepository;
import quest.server.children.AttemptRepository;
import quest.server.children.ChildRepository;
import quest.server.classes.SectionService;
import quest.server.config.ApiException;
import quest.server.content.Entities.LessonEntity;
import quest.server.content.LessonRepository;
import quest.server.platform.SchoolCalendar;
import quest.server.teacher.TeacherDto;
import quest.server.tenancy.CoordinatorScope;
import quest.server.tenancy.Entities.ClassEntity;
import quest.server.tenancy.Entities.TeachingAssignmentEntity;
import quest.server.tenancy.TenantContext;

/**
 * The five reads behind `/coordinator/**` (DR2): her scope summary, the teachers she supervises, their classes, one
 * calendar across all of them, and the teacher's own lesson rows narrowed to her subject.
 *
 * <p><strong>Nothing here decides what she reaches.</strong> {@link CoordinatorScope} does, once, and everything
 * below starts from {@link CoordinatorScope#reach} or one of its `require…` checks. Nothing takes a school, a subject
 * or a curriculum from the request: `classId` is the only parameter that names a row, and it is resolved through
 * {@link CoordinatorScope#requireSection} before it is used.
 *
 * <p><strong>Bulk, not per row.</strong> A coordinator's school is the whole school, so the obvious shape — a request
 * per class, or a lookup per lesson — is thirty of them on the full seed. Every method here reads in bulk the way
 * {@link quest.server.teacher.TeacherWeekService} does: the scope, the sections, the assignments, the lessons of the
 * window, the class sizes, the players and the teachers' names, and the count does not move with the number of
 * classes.
 *
 * <p><strong>Read-only by construction.</strong> The lesson routes delegate to {@link AdminLessonService}'s readers
 * ({@code list}, {@code toAdmin}) and this class holds no `@Transactional` write and no repository `save` at all.
 */
@Service
public class CoordinatorService {
    /** `GET /coordinator/calendar`: a month and a half is the widest the screen can draw; beyond it is a 400. */
    static final int MAX_WINDOW_DAYS = 62;

    private final CoordinatorScope scope; private final UserRepository users; private final ChildRepository children;
    private final LessonRepository lessons; private final AttemptRepository attempts; private final SchoolCalendar calendar;
    private final AdminLessonService admin; private final TenantContext tenant;

    public CoordinatorService(CoordinatorScope scope, UserRepository users, ChildRepository children,
                              LessonRepository lessons, AttemptRepository attempts, SchoolCalendar calendar,
                              AdminLessonService admin, TenantContext tenant) {
        this.scope = scope; this.users = users; this.children = children; this.lessons = lessons;
        this.attempts = attempts; this.calendar = calendar; this.admin = admin; this.tenant = tenant;
    }

    // ---------------------------------------------------------------- GET /coordinator/me

    /** Her scope and its three numbers, so the Home screen costs one request. */
    public CoordinatorDto.CoordinatorMe me(Principals.User caller) {
        var reach = scope.reach(caller);
        var teachers = new LinkedHashSet<String>();
        for (var a : reach.assignments()) teachers.add(a.getTeacherId());
        int pupils = 0;
        for (var count : childCounts(reach.sectionIds()).values()) pupils += count;
        var displayName = users.findById(caller.userId()).map(SectionService::displayName).orElse(caller.email());
        return new CoordinatorDto.CoordinatorMe(caller.userId(), caller.email(), displayName,
                reach.scopes().stream().map(s -> new CoordinatorDto.Scope(s.subject(), s.curriculum())).toList(),
                reach.sections().size(), teachers.size(), pupils);
    }

    // ---------------------------------------------------------------- GET /coordinator/teachers

    /** Every teacher holding an assignment in scope, with the subjects and sections that put her there. */
    public List<CoordinatorDto.CoordinatorTeacher> teachers(Principals.User caller) {
        var reach = scope.reach(caller);
        var refs = new LinkedHashMap<String, List<CoordinatorDto.AssignmentRef>>();
        var subjects = new LinkedHashMap<String, LinkedHashSet<String>>();
        for (var a : reach.assignments()) {
            var section = reach.byId().get(a.getClassId());
            refs.computeIfAbsent(a.getTeacherId(), k -> new ArrayList<>())
                    .add(new CoordinatorDto.AssignmentRef(section.getId(), section.getName(), a.getSubject()));
            subjects.computeIfAbsent(a.getTeacherId(), k -> new LinkedHashSet<>()).add(a.getSubject());
        }
        if (refs.isEmpty()) return List.of();
        var rows = new ArrayList<CoordinatorDto.CoordinatorTeacher>(refs.size());
        for (var user : sortedByName(refs.keySet()))
            rows.add(new CoordinatorDto.CoordinatorTeacher(user.getId(), user.getEmail(), SectionService.displayName(user),
                    user.getPhotoUrl(), List.copyOf(subjects.getOrDefault(user.getId(), new LinkedHashSet<>())),
                    List.copyOf(refs.getOrDefault(user.getId(), List.of()))));
        return List.copyOf(rows);
    }

    // ---------------------------------------------------------------- GET /coordinator/classes

    /** One card per (section, subject) in scope, with today's lesson and how many have played it. */
    public List<CoordinatorDto.CoordinatorClass> classes(Principals.User caller) {
        var reach = scope.reach(caller);
        var today = calendar.today(tenant.writeSchoolId());
        var lessonsToday = byCell(reach.sectionIds(), today, today);
        var sizes = childCounts(reach.sectionIds());
        var names = new LinkedHashMap<String, String>();
        for (var user : sortedByName(reach.assignments().stream().map(TeachingAssignmentEntity::getTeacherId).toList()))
            names.put(user.getId(), SectionService.displayName(user));
        var cards = new ArrayList<CoordinatorDto.CoordinatorClass>(reach.assignments().size());
        for (var a : reach.assignments()) {
            var section = reach.byId().get(a.getClassId());
            var lesson = lessonsToday.get(cell(a.getClassId(), a.getSubject(), today));
            cards.add(new CoordinatorDto.CoordinatorClass(section.getId(), section.getName(), section.getCurriculum(),
                    section.getGrade(), a.getSubject(), a.getTeacherId(), names.get(a.getTeacherId()),
                    sizes.getOrDefault(section.getId(), 0), lesson == null ? null : lesson.getId(),
                    lesson == null ? TeacherDto.NONE : quest.server.teacher.TeacherWeekService.status(lesson)));
        }
        return List.copyOf(cards);
    }

    // ---------------------------------------------------------------- GET /coordinator/calendar

    /**
     * Every class in scope, day by day. Both bounds absent is the school week today falls in, read in the school's
     * own timezone, so the coordinator of a Gulf school opens on Sunday–Thursday like the teacher's grid.
     */
    public CoordinatorDto.CoordinatorCalendar calendar(Principals.User caller, String from, String to) {
        var week = calendar.of(tenant.writeSchoolId());
        var today = LocalDate.now(week.zone());
        LocalDate start = from == null || from.isBlank() ? week.startOf(today) : date(from, "from");
        LocalDate end = to == null || to.isBlank() ? start.plusDays(6) : date(to, "to");
        if (end.isBefore(start)) throw ApiException.badRequest("`to` is before `from`.");
        if (start.plusDays(MAX_WINDOW_DAYS).isBefore(end))
            throw ApiException.badRequest("That window is longer than " + MAX_WINDOW_DAYS + " days — ask for a shorter one.");

        var reach = scope.reach(caller);
        var byCell = byCell(reach.sectionIds(), start, end);
        var sizes = childCounts(reach.sectionIds());
        var players = players(byCell.values());
        var days = new ArrayList<CoordinatorDto.CalendarDay>();
        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
            var onThisDay = new ArrayList<CoordinatorDto.CalendarLesson>();
            for (var a : reach.assignments()) {
                var lesson = byCell.get(cell(a.getClassId(), a.getSubject(), day));
                if (lesson == null) continue;
                var section = reach.byId().get(a.getClassId());
                onThisDay.add(new CoordinatorDto.CalendarLesson(section.getId(), section.getName(), a.getSubject(),
                        lesson.getId(), lesson.getTitle(), quest.server.teacher.TeacherWeekService.status(lesson),
                        lesson.getType(), players.getOrDefault(lesson.getId(), 0), sizes.getOrDefault(section.getId(), 0)));
            }
            days.add(new CoordinatorDto.CalendarDay(day.toString(), week.isSchoolDay(day), List.copyOf(onThisDay)));
        }
        return new CoordinatorDto.CoordinatorCalendar(start.toString(), end.toString(), List.copyOf(days));
    }

    // ---------------------------------------------------------------- GET /coordinator/lessons

    /**
     * The teacher's own "All lessons" rows, reduced to the (section, subject) pairs her scope covers — the same
     * reduction {@link quest.server.teacher.TeacherLessonService#list} applies for a teacher, read from one
     * statement rather than a lookup per row. A `classId` outside her scope is a 403 before anything is listed.
     */
    public List<AdminLesson> lessons(Principals.User caller, String classId, String status, String from, String to) {
        if (classId != null && !classId.isBlank()) scope.requireSection(caller, classId);
        boolean all = !scope.isCoordinator(caller);                               // an ADMIN needs no slot set built
        var slots = all ? java.util.Set.<String>of() : scope.reach(caller).slots();
        var filter = new LessonFilter(null, null, null, iso(from, "from"), iso(to, "to"), null,
                classId == null || classId.isBlank() ? null : classId);
        var rows = admin.list(filter, l -> all || (l.getClassId() != null && slots.contains(CoordinatorScope.slot(l.getClassId(), l.getSubject()))));
        String wanted = coarse(status);
        return wanted == null ? rows : rows.stream().filter(l -> wanted.equals(coarseOf(l.getStatus()))).toList();
    }

    /** The read-only lesson view: the same body `GET /teacher/lessons/{id}` answers, resolved through her scope. */
    public AdminLesson lesson(Principals.User caller, String lessonId) {
        return admin.toAdmin(scope.requireLesson(caller, lessonId), true);
    }

    /**
     * E1's poll answer for a lesson of hers — the same {@link quest.api.LessonStatusView} the teacher's and the
     * Admin's `/status` routes give the progress strip, so a coordinator's read-only lesson page never has to tick
     * against a `/teacher/**` route her role is refused at the matcher.
     *
     * <p>{@link AdminLessonService#status} rather than {@link AdminLessonService#toAdmin}, for that method's own
     * reason: four small reads, no play decoding and no ledger backfill, which is what a 2.5 s tick may cost.
     */
    public quest.api.LessonStatusView lessonStatus(Principals.User caller, String lessonId) {
        return admin.status(scope.requireLesson(caller, lessonId));
    }

    // ---------------------------------------------------------------- the statements

    /** `[classId -> live children]` for every section in scope: one statement, never one per class. */
    private Map<String, Integer> childCounts(List<String> sectionIds) {
        var out = new LinkedHashMap<String, Integer>();
        if (sectionIds.isEmpty()) return out;
        for (Object[] row : children.countByClassIdIn(sectionIds)) out.put((String) row[0], ((Number) row[1]).intValue());
        return out;
    }

    /** `[classId -> how many children answered a stop of it]`, in one statement for the whole window. */
    private Map<String, Integer> players(java.util.Collection<LessonEntity> rows) {
        var out = new HashMap<String, Integer>();
        var ids = rows.stream().map(LessonEntity::getId).distinct().toList();
        if (ids.isEmpty()) return out;
        for (Object[] row : attempts.countPlayersByLessonIdIn(ids)) out.put((String) row[0], ((Number) row[1]).intValue());
        return out;
    }

    /**
     * The lessons of every section in scope inside the window, one per (class, subject, day) — the published one,
     * and the newest otherwise, which is {@link quest.server.teacher.TeacherWeekService}'s rule for the same cell.
     */
    private Map<String, LessonEntity> byCell(List<String> sectionIds, LocalDate from, LocalDate to) {
        var out = new HashMap<String, LessonEntity>();
        if (sectionIds.isEmpty()) return out;
        for (var lesson : lessons.findByClassIdInAndDateBetweenOrderByDateAsc(sectionIds, from, to)) {
            String key = cell(lesson.getClassId(), lesson.getSubject(), lesson.getDate());
            var current = out.get(key);
            if (current == null || better(lesson, current)) out.put(key, lesson);
        }
        return out;
    }

    /**
     * Two lessons on one day for one subject is legal; the cell shows the published one, and the newest otherwise,
     * because that is the one being worked on. `TeacherWeekService` applies the same rule to the teacher's grid.
     */
    private static boolean better(LessonEntity candidate, LessonEntity current) {
        boolean live = LessonState.status(candidate) == LessonStatus.PUBLISHED;
        if (live != (LessonState.status(current) == LessonStatus.PUBLISHED)) return live;
        return candidate.getCreatedAt() != null && current.getCreatedAt() != null
                && candidate.getCreatedAt().isAfter(current.getCreatedAt());
    }

    /** The teachers named, in display-name order, in one statement. */
    private List<UserEntity> sortedByName(java.util.Collection<String> userIds) {
        var ids = userIds.stream().distinct().toList();
        if (ids.isEmpty()) return List.of();
        var rows = new ArrayList<UserEntity>();
        users.findAllById(ids).forEach(rows::add);
        rows.sort(java.util.Comparator.comparing(SectionService::displayName, String.CASE_INSENSITIVE_ORDER));
        return rows;
    }

    private static String cell(String classId, String subject, LocalDate date) {
        return CoordinatorScope.slot(classId, subject) + "\u0000" + date;
    }

    /** `draft` | `ready` | `published`, or null when no filter was asked for; anything else is a 400. */
    private static String coarse(String status) {
        if (status == null || status.isBlank()) return null;
        String wanted = status.trim().toLowerCase(java.util.Locale.ROOT);
        if (!List.of(TeacherDto.DRAFT, TeacherDto.READY, TeacherDto.PUBLISHED).contains(wanted))
            throw ApiException.badRequest("`status` is draft, ready or published.");
        return wanted;
    }

    private static String coarseOf(LessonStatus status) {
        return switch (status) {
            case PUBLISHED -> TeacherDto.PUBLISHED;
            case REVIEW -> TeacherDto.READY;
            default -> TeacherDto.DRAFT;
        };
    }

    private static LocalDate date(String value, String field) {
        if (value == null || value.isBlank()) return null;
        try { return LocalDate.parse(value.trim()); }
        catch (java.time.format.DateTimeParseException e) { throw ApiException.badRequest("`" + field + "` is not a date — use yyyy-MM-dd."); }
    }

    /** The same day as a `kotlinx.datetime.LocalDate`, which is what a shared {@link LessonFilter} is made of. */
    private static kotlinx.datetime.LocalDate iso(String value, String field) {
        var day = date(value, field);
        return day == null ? null : new kotlinx.datetime.LocalDate(day.getYear(), day.getMonthValue(), day.getDayOfMonth());
    }
}
