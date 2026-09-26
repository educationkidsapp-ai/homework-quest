package quest.server.management;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.stereotype.Service;
import quest.api.AdminLesson;
import quest.server.admin.AdminLessonService;
import quest.server.attendance.AttendanceDto;
import quest.server.attendance.AttendanceService;
import quest.server.auth.Principals;
import quest.server.classes.SectionService;
import quest.server.config.ApiException;
import quest.server.coordinator.CoordinatorDto;
import quest.server.coordinator.CoordinatorService;
import quest.server.exams.ExamDto;
import quest.server.exams.ExamService;
import quest.server.grading.GradingDto;
import quest.server.grading.GradingService;
import quest.server.tenancy.CoordinatorScope;
import quest.server.tenancy.Entities.ClassEntity;
import quest.server.tenancy.ManagerScope;

/**
 * The reads behind `/management/**` (RM1, DR5): a department manager's Home, the people she manages, her grades, one
 * calendar across all of them, the lesson rows, and the six numbers screens the coordinator already has.
 *
 * <p><strong>Nothing here decides what she reaches.</strong> {@link ManagerScope} does, once, and every method below
 * starts from {@link ManagerScope#reach} or one of its `require…` checks. Nothing takes a school, a grade or a
 * curriculum from the request: `classId`, `lessonId` and `childId` are the only parameters that name a row and each is
 * resolved before it is used.
 *
 * <p><strong>Nothing here recomputes a number either.</strong> Three screens are the coordinator's own, built by
 * {@link CoordinatorService} from a {@link CoordinatorScope.Reach} this class hands it, and the six results reads
 * delegate to the very services the teacher's screens read — with {@link CoordinatorScope.Subjects#ALL}, because a
 * manager manages <em>every</em> subject of her department (DR5) and is narrowed by track instead. That is what makes
 * "the manager's numbers are the teacher's numbers" a property of the code rather than a test that happens to pass.
 *
 * <p><strong>Why the delegates take the caller.</strong> {@link quest.server.tenancy.TeacherScope} narrows a TEACHER
 * and nobody else, so a MANAGERIAL principal passes through its `requireClass`/`requireLesson` untouched — exactly as
 * a COORDINATOR does in {@code CoordinatorReadsService}. That is safe only because the id has already been checked
 * here; `ManagerScopeArchitectureTest` fails the build if a handler skips that.
 */
@Service
public class ManagementService {
    /** The same cap the coordinator's calendar and attendance windows use. */
    static final int MAX_WINDOW_DAYS = 62;
    /** Both bounds absent on the attendance read is the week ending today. */
    static final int DEFAULT_WINDOW_DAYS = 6;

    private final ManagerScope scope; private final CoordinatorService coordinators; private final AdminLessonService admin;
    private final AttendanceService attendance; private final GradingService grading; private final ExamService exams;
    private final quest.server.auth.UserRepository users;

    public ManagementService(ManagerScope scope, CoordinatorService coordinators, AdminLessonService admin,
                             AttendanceService attendance, GradingService grading, ExamService exams,
                             quest.server.auth.UserRepository users) {
        this.scope = scope; this.coordinators = coordinators; this.admin = admin; this.attendance = attendance;
        this.grading = grading; this.exams = exams; this.users = users;
    }

    // ---------------------------------------------------------------- GET /management/me

    /** Her departments and their five numbers, so the Home screen costs one request. */
    public ManagementDto.ManagerMe me(Principals.User caller) {
        var reach = scope.reach(caller);
        int children = 0;
        for (var count : coordinators.childCounts(reach.sectionIds()).values()) children += count;
        var displayName = users.findById(caller.userId()).map(SectionService::displayName).orElse(caller.email());
        long grades = reach.sections().stream().map(ClassEntity::getGrade).distinct().count();
        return new ManagementDto.ManagerMe(caller.userId(), caller.email(), displayName, scope.departments(caller),
                (int) grades, reach.sections().size(), ManagerScope.teacherIds(reach).size(),
                scope.coordinatorsOf(caller).size(), children);
    }

    // ---------------------------------------------------------------- GET /management/coordinators

    /** The coordinators of her department, with the subjects and tracks that put them there. */
    public List<ManagementDto.ManagerCoordinator> coordinators(Principals.User caller) {
        var sections = scope.sectionsOf(caller);
        var rows = new ArrayList<ManagementDto.ManagerCoordinator>();
        scope.coordinatorsOf(caller).forEach((person, scopes) -> {
            var subjects = scopes.stream().map(CoordinatorScope.Scope::subject).distinct().toList();
            var curricula = scopes.stream().map(CoordinatorScope.Scope::curriculum).distinct().toList();
            // How much of the department she covers, counted off the sections already read: a section is hers when its
            // track is one her rows name (or she names none) — no second query, whatever the size of the school.
            long covered = sections.stream().filter(k -> scopes.stream()
                    .anyMatch(s -> s.curriculum() == null || s.curriculum().equals(ManagerScope.normalise(k.getCurriculum())))).count();
            rows.add(new ManagementDto.ManagerCoordinator(person.getId(), person.getEmail(), SectionService.displayName(person),
                    person.getPhotoUrl(), subjects, curricula, (int) covered));
        });
        return List.copyOf(rows);
    }

    // ---------------------------------------------------------------- GET /management/teachers, /classes, /calendar

    /** Every teacher of the department, with her assignments — the coordinator's own rows, one scope wider. */
    public List<CoordinatorDto.CoordinatorTeacher> teachers(Principals.User caller) {
        return coordinators.teachers(scope.reach(caller));
    }

    /**
     * The department's grades, each with its section cards: what the Classes screen draws, grouped as it draws it.
     *
     * <p>The groups follow {@link ManagerScope#reach}'s own order — curriculum, then grade, then section name — rather
     * than the order the cards come back in, which is by class id because a card is per (section, subject).
     */
    public List<ManagementDto.GradeGroup> classes(Principals.User caller) {
        var reach = scope.reach(caller);
        var sizes = coordinators.childCounts(reach.sectionIds());
        var byClass = new LinkedHashMap<String, List<CoordinatorDto.CoordinatorClass>>();
        for (var card : coordinators.classes(reach)) byClass.computeIfAbsent(card.classId(), k -> new ArrayList<>()).add(card);
        var grades = new LinkedHashMap<String, List<ClassEntity>>();
        for (var section : reach.sections()) grades.computeIfAbsent(gradeKey(section), k -> new ArrayList<>()).add(section);
        var out = new ArrayList<ManagementDto.GradeGroup>(grades.size());
        grades.forEach((key, sections) -> {
            var cards = new ArrayList<CoordinatorDto.CoordinatorClass>();
            int children = 0;
            for (var section : sections) {
                cards.addAll(byClass.getOrDefault(section.getId(), List.of()));
                children += sizes.getOrDefault(section.getId(), 0);
            }
            out.add(new ManagementDto.GradeGroup(ManagerScope.normalise(sections.get(0).getCurriculum()),
                    sections.get(0).getGrade(), sections.size(), children, List.copyOf(cards)));
        });
        return List.copyOf(out);
    }

    /** The (track, grade) a section belongs to — the unit `GET /management/classes` and `/management/stats` group by. */
    static String gradeKey(ClassEntity section) {
        return ManagerScope.normalise(section.getCurriculum()) + "\u0000" + section.getGrade();
    }

    /** Every class of the department, day by day. Both bounds absent is this school week; the window is capped. */
    public CoordinatorDto.CoordinatorCalendar calendar(Principals.User caller, String from, String to) {
        return coordinators.calendar(scope.reach(caller), from, to);
    }

    // ---------------------------------------------------------------- GET /management/lessons

    /** The teacher's own lesson rows, reduced to the department. A `classId` outside it is a 403 before anything runs. */
    public List<AdminLesson> lessons(Principals.User caller, String classId, String status, String from, String to) {
        if (classId != null && !classId.isBlank()) scope.requireSection(caller, classId);
        var slots = scope.isManager(caller) ? scope.reach(caller).slots() : java.util.Set.<String>of();
        return coordinators.lessonsIn(slots, classId, status, from, to);
    }

    /** The read-only lesson view: the body `GET /teacher/lessons/{id}` answers, resolved through her department. */
    public AdminLesson lesson(Principals.User caller, String lessonId) {
        return admin.toAdmin(scope.requireLesson(caller, lessonId), true);
    }

    /** E1's poll for her read-only lesson page — four small reads, no play decoding, as the coordinator's does. */
    public quest.api.LessonStatusView lessonStatus(Principals.User caller, String lessonId) {
        return admin.status(scope.requireLesson(caller, lessonId));
    }

    // ---------------------------------------------------------------- the six delegated reads

    /** `GET /management/classes/{id}/attendance` — the teacher's per-day register, once per day of the window. */
    public List<AttendanceDto.ClassAttendanceResponse> attendance(Principals.User caller, String classId, String from, String to) {
        var section = scope.requireSection(caller, classId);
        LocalDate end = date(to, "to", LocalDate.now()), start = date(from, "from", end.minusDays(DEFAULT_WINDOW_DAYS));
        if (end.isBefore(start)) throw ApiException.badRequest("`to` is before `from`.");
        if (start.plusDays(MAX_WINDOW_DAYS).isBefore(end))
            throw ApiException.badRequest("That window is longer than " + MAX_WINDOW_DAYS + " days — ask for a shorter one.");
        return attendance.classAttendanceWindow(section, start, end);
    }

    /** §7's gradebook grid, every subject of the section — a manager is narrowed by track, not by subject. */
    public GradingDto.Gradebook gradebook(Principals.User caller, String classId, String from, String to) {
        return grading.gradebook(caller, scope.requireSection(caller, classId).getId(), from, to, CoordinatorScope.Subjects.ALL);
    }

    /** §7's per-lesson results for a lesson of her department. */
    public GradingDto.LessonResults lessonResults(Principals.User caller, String lessonId) {
        return grading.results(caller, scope.requireLesson(caller, lessonId).getId());
    }

    /** §7's child page for a child placed in a section of her department, whole — every subject she is taught. */
    public GradingDto.ChildReport child(Principals.User caller, String childId) {
        return grading.child(caller, scope.requireChild(caller, childId).getId(), CoordinatorScope.Subjects.ALL);
    }

    /** §8's Exams tab for a section of her department. */
    public List<ExamDto.ExamRow> classExams(Principals.User caller, String classId) {
        return exams.ofClass(caller, scope.requireSection(caller, classId).getId(), CoordinatorScope.Subjects.ALL);
    }

    /** §8's exam results and band distribution, for an exam of her department. */
    public ExamDto.ExamResults examResults(Principals.User caller, String examId) {
        return exams.results(caller, scope.requireLesson(caller, examId).getId());
    }

    private static LocalDate date(String value, String field, LocalDate fallback) {
        if (value == null || value.isBlank()) return fallback;
        try { return LocalDate.parse(value.trim()); }
        catch (java.time.format.DateTimeParseException e) { throw ApiException.badRequest("`" + field + "` is not a date — use yyyy-MM-dd."); }
    }
}
