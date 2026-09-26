package quest.server.management;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import quest.api.dto.LessonStatus;
import quest.server.analysis.LessonState;
import quest.server.attendance.AttendanceRepository;
import quest.server.auth.Principals;
import quest.server.children.AttemptRepository;
import quest.server.classes.SectionService;
import quest.server.config.ApiException;
import quest.server.content.Entities.LessonEntity;
import quest.server.content.LessonRepository;
import quest.server.coordinator.CoordinatorService;
import quest.server.exams.ExamPlays;
import quest.server.grading.Bands;
import quest.server.platform.SchoolCalendar;
import quest.server.tenancy.CoordinatorScope;
import quest.server.tenancy.Entities.ClassEntity;
import quest.server.tenancy.ManagerScope;
import quest.server.tenancy.TenantContext;

/**
 * `GET /management/stats?from&to` (RM1, DR5): "sees statistics for all grades of the department" — a row per grade and
 * the department's own total, in one request.
 *
 * <p><strong>Seven statements, whatever the size of the department.</strong> Every number below is folded out of a
 * grouped read, never out of a loop over children or a request per grade:
 *
 * <ol>
 *   <li>{@link ManagerScope#reach} — the sections of the department and the assignments inside them (two).</li>
 *   <li>{@link CoordinatorService#childCounts} — `[section -> live children]` (one).</li>
 *   <li>{@link LessonRepository#findByClassIdInAndDateBetweenOrderByDateAsc} — every lesson of the window (one), which
 *       gives `lessonsPublished`, which teacher published one (the quiet-teacher pass) and which of them are exams.</li>
 *   <li>{@link AttendanceRepository#countByStatusInWindow} — `[section, status, rows]` (one). The rate is
 *       `(present + late) / marked`, {@link quest.server.attendance.AttendanceService}'s own formula, so a grade's
 *       percentage agrees with the register the teacher took.</li>
 *   <li>{@link AttemptRepository#countPlayersByLessonIdIn} — the players of each published lesson (one), which gives
 *       `lessonsPlayed`: the published lessons at least one child has answered a stop of.</li>
 *   <li>{@link AttemptRepository#bestStarsByLessonIdIn} — the best stars per (exam, child, stop) (one), which gives
 *       the exam average and the pass rate.</li>
 * </ol>
 *
 * <p><strong>What the exam average is, exactly.</strong> A child's percentage on a paper is the mean of
 * {@link Bands#forStars} over the stops she answered — §7's "3 stars = 100, 2 = 70, 1 = 40" — the grade's average is
 * the mean over her children, and the pass rate is the share of them at {@link Bands#SECURE_AT} or better, which is
 * this product's own "secure" line rather than an invented 50 %. It is a statistics screen's number, not a report
 * card's: {@link quest.server.grading.Scoring} additionally reads a teacher's mark for an open stop, a single-answer
 * stop's first try and a lesson-level override, none of which a grouped query can reach, so an exam with hand-marked
 * questions can sit a point or two from the released class average. The exact number is one click away —
 * `GET /management/exams/{id}/results` is the very body the teacher reads.
 */
@Service
public class ManagementStatsService {
    /** A term is the widest window worth a grade's statistics; beyond it is a 400. */
    static final int MAX_WINDOW_DAYS = 186;
    /** Both bounds absent is the month ending today, which is what the Home screen opens on. */
    static final int DEFAULT_WINDOW_DAYS = 29;
    /** The four words `attendance.status` holds; `EXCUSED` is marked but not present (`AttendanceService`). */
    private static final Set<String> PRESENT = Set.of("PRESENT", "LATE");

    private final ManagerScope scope; private final CoordinatorService coordinators; private final LessonRepository lessons;
    private final AttendanceRepository attendance; private final AttemptRepository attempts;
    private final quest.server.auth.UserRepository users; private final SchoolCalendar calendar; private final TenantContext tenant;

    public ManagementStatsService(ManagerScope scope, CoordinatorService coordinators, LessonRepository lessons,
                                 AttendanceRepository attendance, AttemptRepository attempts,
                                 quest.server.auth.UserRepository users, SchoolCalendar calendar, TenantContext tenant) {
        this.scope = scope; this.coordinators = coordinators; this.lessons = lessons; this.attendance = attendance;
        this.attempts = attempts; this.users = users; this.calendar = calendar; this.tenant = tenant;
    }

    public ManagementDto.ManagementStats stats(Principals.User caller, String from, String to) {
        LocalDate today = LocalDate.now(calendar.of(tenant.writeSchoolId()).zone());
        LocalDate end = date(to, "to", today), start = date(from, "from", end.minusDays(DEFAULT_WINDOW_DAYS));
        if (end.isBefore(start)) throw ApiException.badRequest("`to` is before `from`.");
        if (start.plusDays(MAX_WINDOW_DAYS).isBefore(end))
            throw ApiException.badRequest("That window is longer than " + MAX_WINDOW_DAYS + " days — ask for a shorter one.");

        var reach = scope.reach(caller);
        var sectionIds = reach.sectionIds();
        var sizes = coordinators.childCounts(sectionIds);
        var window = sectionIds.isEmpty() ? List.<LessonEntity>of()
                : lessons.findByClassIdInAndDateBetweenOrderByDateAsc(sectionIds, start, end);
        var published = window.stream().filter(l -> LessonState.status(l) == LessonStatus.PUBLISHED).toList();
        var marks = markedCounts(sectionIds, start, end);
        var players = players(published);
        var percents = examPercents(published.stream().filter(ExamPlays::isExam).toList());
        var teachersBySection = teachersBySection(reach);
        var teacherBySlot = teacherBySlot(reach);
        var namesOf = names(ManagerScope.teacherIds(reach));

        // The grades of the department, in the order the sections already came back in (curriculum, grade, name).
        var grades = new LinkedHashMap<String, List<ClassEntity>>();
        for (var section : reach.sections())
            grades.computeIfAbsent(ManagementService.gradeKey(section), k -> new ArrayList<>()).add(section);

        var rows = new ArrayList<ManagementDto.GradeStats>(grades.size());
        grades.forEach((key, sections) -> rows.add(row(ManagerScope.normalise(sections.get(0).getCurriculum()), sections.get(0).getGrade(), sections,
                sizes, published, marks, players, percents, teachersBySection, teacherBySlot, namesOf)));
        var total = row(null, 0, reach.sections(), sizes, published, marks, players, percents, teachersBySection, teacherBySlot, namesOf);
        return new ManagementDto.ManagementStats(start.toString(), end.toString(), List.copyOf(rows), total);
    }

    // ---------------------------------------------------------------- one grade, or the department

    /** One row, folded out of the reads above — no statement of its own, so a grade costs nothing to add. */
    private ManagementDto.GradeStats row(String curriculum, int grade, List<ClassEntity> sections,
                                         Map<String, Integer> sizes, List<LessonEntity> published,
                                         Map<String, long[]> marks, Map<String, Integer> players,
                                         Map<String, List<Integer>> percents, Map<String, Set<String>> teachersBySection,
                                         Map<String, String> teacherBySlot, Map<String, String[]> namesOf) {
        var ids = sections.stream().map(ClassEntity::getId).collect(java.util.stream.Collectors.toSet());
        int children = sections.stream().mapToInt(k -> sizes.getOrDefault(k.getId(), 0)).sum();

        long present = 0, marked = 0;
        for (var id : ids) { var counts = marks.get(id); if (counts != null) { present += counts[0]; marked += counts[1]; } }

        var mine = published.stream().filter(l -> ids.contains(l.getClassId())).toList();
        int playedCount = 0, exams = 0;
        var scores = new ArrayList<Integer>();
        var active = new LinkedHashSet<String>();
        for (var lesson : mine) {
            if (players.getOrDefault(lesson.getId(), 0) > 0) playedCount++;
            // Who published it is the (section, subject) assignment rather than `lessons.teacher_id`, which the
            // pipeline leaves null for a lesson an Admin made — the same join `HomeService`'s `teacher.quiet` does.
            var author = teacherBySlot.get(CoordinatorScope.slot(lesson.getClassId(), lesson.getSubject()));
            if (author != null) active.add(author);
            else if (lesson.getTeacherId() != null) active.add(lesson.getTeacherId());
            var byChild = percents.get(lesson.getId());
            if (ExamPlays.isExam(lesson)) { exams++; if (byChild != null) scores.addAll(byChild); }
        }
        var quiet = new ArrayList<ManagementDto.QuietTeacher>();
        var staff = new LinkedHashSet<String>();
        for (var id : ids) staff.addAll(teachersBySection.getOrDefault(id, Set.of()));
        for (var teacherId : staff) {
            if (active.contains(teacherId)) continue;
            var name = namesOf.get(teacherId);
            if (name != null) quiet.add(new ManagementDto.QuietTeacher(teacherId, name[1], name[0]));
        }
        quiet.sort(java.util.Comparator.comparing(ManagementDto.QuietTeacher::displayName, String.CASE_INSENSITIVE_ORDER));

        Double rate = marked == 0 ? null : Math.round(present * 1000.0 / marked) / 10.0;
        Integer average = scores.isEmpty() ? null : (int) Math.round(scores.stream().mapToInt(Integer::intValue).average().orElse(0));
        Integer passRate = scores.isEmpty() ? null
                : (int) Math.round(scores.stream().filter(s -> s >= Bands.SECURE_AT).count() * 100.0 / scores.size());
        return new ManagementDto.GradeStats(curriculum, grade, sections.size(), children, rate, mine.size(), playedCount,
                exams, average, passRate, List.copyOf(quiet));
    }

    // ---------------------------------------------------------------- the statements

    /** `[sectionId -> {present + late, marked}]` for the window, from one grouped read of `attendance`. */
    private Map<String, long[]> markedCounts(List<String> sectionIds, LocalDate from, LocalDate to) {
        var out = new HashMap<String, long[]>();
        if (sectionIds.isEmpty()) return out;
        for (Object[] row : attendance.countByStatusInWindow(sectionIds, from, to)) {
            var counts = out.computeIfAbsent((String) row[0], k -> new long[2]);
            long rows = ((Number) row[2]).longValue();
            if (PRESENT.contains(String.valueOf(row[1]))) counts[0] += rows;
            counts[1] += rows;
        }
        return out;
    }

    /** `[lessonId -> how many children answered a stop of it]`, in one statement for the whole window. */
    private Map<String, Integer> players(List<LessonEntity> published) {
        var out = new HashMap<String, Integer>();
        var ids = published.stream().map(LessonEntity::getId).toList();
        if (ids.isEmpty()) return out;
        for (Object[] row : attempts.countPlayersByLessonIdIn(ids)) out.put((String) row[0], ((Number) row[1]).intValue());
        return out;
    }

    /**
     * `[examId -> a percentage per child who sat it]`, from one grouped read of the best stars per (exam, child, stop).
     * A child's percentage is the mean of {@link Bands#forStars} over her answered stops — see the class comment for
     * what that is and is not.
     */
    private Map<String, List<Integer>> examPercents(List<LessonEntity> exams) {
        var out = new LinkedHashMap<String, List<Integer>>();
        var ids = exams.stream().map(LessonEntity::getId).toList();
        if (ids.isEmpty()) return out;
        var sums = new LinkedHashMap<String, int[]>();                           // "examId\0childId" -> {points, stops}
        for (Object[] row : attempts.bestStarsByLessonIdIn(ids)) {
            var totals = sums.computeIfAbsent(row[0] + "\u0000" + row[1], k -> new int[2]);
            totals[0] += Bands.forStars(((Number) row[3]).intValue()); totals[1]++;
        }
        sums.forEach((key, totals) -> out.computeIfAbsent(key.substring(0, key.indexOf('\u0000')), k -> new ArrayList<>())
                .add(Math.round((float) totals[0] / totals[1])));
        return out;
    }

    /** `[(section, subject) -> the teacher who holds it]`: who a published lesson of that cell is credited to. */
    private static Map<String, String> teacherBySlot(CoordinatorScope.Reach reach) {
        var out = new LinkedHashMap<String, String>();
        for (var a : reach.assignments()) out.putIfAbsent(CoordinatorScope.slot(a.getClassId(), a.getSubject()), a.getTeacherId());
        return out;
    }

    /** `[sectionId -> the teachers assigned to it]`, off the assignments {@link ManagerScope#reach} already read. */
    private static Map<String, Set<String>> teachersBySection(CoordinatorScope.Reach reach) {
        var out = new LinkedHashMap<String, Set<String>>();
        for (var a : reach.assignments()) out.computeIfAbsent(a.getClassId(), k -> new LinkedHashSet<>()).add(a.getTeacherId());
        return out;
    }

    /** `[userId -> {display name, email}]` for the quiet-teacher rows: one statement for the whole department. */
    private Map<String, String[]> names(List<String> teacherIds) {
        var out = new LinkedHashMap<String, String[]>();
        if (teacherIds.isEmpty()) return out;
        users.findAllById(teacherIds).forEach(u -> out.put(u.getId(), new String[] {SectionService.displayName(u), u.getEmail()}));
        return out;
    }

    private static LocalDate date(String value, String field, LocalDate fallback) {
        if (value == null || value.isBlank()) return fallback;
        try { return LocalDate.parse(value.trim()); }
        catch (java.time.format.DateTimeParseException e) { throw ApiException.badRequest("`" + field + "` is not a date — use yyyy-MM-dd."); }
    }
}
