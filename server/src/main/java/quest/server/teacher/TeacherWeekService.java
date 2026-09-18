package quest.server.teacher;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import quest.api.dto.LessonStatus;
import quest.server.analysis.LessonState;
import quest.server.auth.Principals;
import quest.server.children.AttemptRepository;
import quest.server.children.ChildRepository;
import quest.server.config.ApiException;
import quest.server.content.Entities.LessonEntity;
import quest.server.content.LessonRepository;
import quest.server.platform.SchoolCalendar;
import quest.server.tenancy.ClassRepository;
import quest.server.tenancy.Entities.ClassEntity;
import quest.server.tenancy.Entities.TeachingAssignmentEntity;
import quest.server.tenancy.TeacherScope;
import quest.server.tenancy.TenantContext;

/**
 * `docs/teacher-flow.md` §4 "This week" and §7 "My classes" — the two screens a teacher lands on.
 *
 * <p><strong>One response, six statements.</strong> The obvious shape of §4 is a request per assignment per day,
 * which is thirty-five for one teacher of seven classes and is what makes a grid feel broken. Everything here is
 * read in bulk instead: her assignments, the sections they name, the lessons of those sections inside the week, the
 * children in each of them, how many children have played each lesson, and the school's own week and timezone. The
 * count does not move between one assignment and ten, which `TeacherWeekQueryCountTest` asserts by measuring it.
 *
 * <p><strong>The week is the school's.</strong> Its days and its first day come from {@link SchoolCalendar}, so the
 * grid is Sunday–Thursday for the Gulf schools and whatever a Monday–Friday school configures for that one —
 * `start` is snapped back to the week's first teaching day, so the dashboard's prev/next can move by seven days
 * without knowing where a week begins. "Today" is read in the school's zone, which is what decides whether an empty
 * school day is a gap the teacher has to fix or a day that has not happened yet.
 */
@Service
public class TeacherWeekService {
    private final TeacherScope scope; private final ClassRepository classes; private final LessonRepository lessons;
    private final ChildRepository children; private final AttemptRepository attempts; private final SchoolCalendar calendar;
    private final TenantContext tenant;

    public TeacherWeekService(TeacherScope scope, ClassRepository classes, LessonRepository lessons,
                              ChildRepository children, AttemptRepository attempts, SchoolCalendar calendar,
                              TenantContext tenant) {
        this.scope = scope; this.classes = classes; this.lessons = lessons; this.children = children;
        this.attempts = attempts; this.calendar = calendar; this.tenant = tenant;
    }

    /** §4: every assignment she holds against the school week `start` falls in (this week when `start` is absent). */
    public TeacherDto.TeacherWeek week(Principals.User caller, String start) {
        var schoolId = tenant.writeSchoolId();
        var week = calendar.of(schoolId);
        var today = LocalDate.now(week.zone());
        var first = week.startOf(start == null || start.isBlank() ? today : date(start));
        var days = week.datesFrom(first);
        var data = load(caller, schoolId, first, first.plusDays(6));

        var rows = new ArrayList<TeacherDto.WeekRow>(data.assignments().size());
        var gaps = new ArrayList<TeacherDto.WeekGap>();
        for (var assignment : data.assignments()) {
            var section = data.sections().get(assignment.getClassId());
            if (section == null) continue;                                  // a legacy row is not a section (V7)
            var cells = new ArrayList<TeacherDto.WeekCell>(days.size());
            for (LocalDate day : days) {
                var lesson = data.lesson(assignment.getClassId(), assignment.getSubject(), day);
                cells.add(new TeacherDto.WeekCell(day.toString(), lesson == null ? null : data.card(lesson), null));
                // A school day is only a gap once it has arrived: colouring the rest of the week red would make
                // every Sunday morning look like a failure.
                if (lesson == null && !day.isAfter(today)) gaps.add(new TeacherDto.WeekGap(section.getId(), section.getName(), day.toString()));
            }
            rows.add(new TeacherDto.WeekRow(section.getId(), section.getName(), section.getCurriculum(),
                    section.getGrade(), assignment.getSubject(), List.copyOf(cells)));
        }
        return new TeacherDto.TeacherWeek(first.toString(), days.stream().map(LocalDate::toString).toList(),
                List.copyOf(rows), new TeacherDto.WeekSummary(List.copyOf(gaps), List.of(), 0));
    }

    /** §7: the same rows reduced to today — today's lesson, the class size, and how many have played it. */
    public List<TeacherDto.TeacherClassCard> classes(Principals.User caller) {
        var schoolId = tenant.writeSchoolId();
        var today = LocalDate.now(calendar.of(schoolId).zone());
        var data = load(caller, schoolId, today, today);
        var cards = new ArrayList<TeacherDto.TeacherClassCard>(data.assignments().size());
        for (var assignment : data.assignments()) {
            var section = data.sections().get(assignment.getClassId());
            if (section == null) continue;
            var lesson = data.lesson(assignment.getClassId(), assignment.getSubject(), today);
            cards.add(new TeacherDto.TeacherClassCard(section.getId(), section.getName(), section.getCurriculum(),
                    section.getGrade(), assignment.getSubject(), lesson == null ? null : lesson.getId(),
                    lesson == null ? TeacherDto.NONE : status(lesson), data.childrenIn(section.getId()),
                    lesson == null ? 0 : data.playersOf(lesson.getId())));
        }
        return List.copyOf(cards);
    }

    // ---------------------------------------------------------------- the six statements

    /** Everything both screens need, read in bulk: assignments, sections, lessons, class sizes, players. */
    private Week load(Principals.User caller, String schoolId, LocalDate from, LocalDate to) {
        var assignments = scope.assignmentsOf(caller);
        var sections = new LinkedHashMap<String, ClassEntity>();
        for (var section : classes.findBySchoolIdAndNameIsNotNullOrderByCurriculumAscGradeAscNameAsc(schoolId)) sections.put(section.getId(), section);
        var classIds = assignments.stream().map(TeachingAssignmentEntity::getClassId).filter(sections::containsKey).distinct().toList();
        if (classIds.isEmpty()) return new Week(assignments, sections, Map.of(), Map.of(), Map.of());

        var byKey = new HashMap<String, LessonEntity>();
        var lessonRows = lessons.findByClassIdInAndDateBetweenOrderByDateAsc(classIds, from, to);
        for (var lesson : lessonRows) {
            // Two lessons on one day for one subject is legal; the cell shows the published one, and the newest
            // otherwise, because that is the one she is working on.
            String key = key(lesson.getClassId(), lesson.getSubject(), lesson.getDate());
            var current = byKey.get(key);
            if (current == null || better(lesson, current)) byKey.put(key, lesson);
        }
        var sizes = new HashMap<String, Integer>();
        for (Object[] row : children.countByClassIdIn(classIds)) sizes.put((String) row[0], ((Number) row[1]).intValue());
        var players = new HashMap<String, Integer>();
        var lessonIds = lessonRows.stream().map(LessonEntity::getId).toList();
        if (!lessonIds.isEmpty()) for (Object[] row : attempts.countPlayersByLessonIdIn(lessonIds)) players.put((String) row[0], ((Number) row[1]).intValue());
        return new Week(assignments, sections, byKey, sizes, players);
    }

    private record Week(List<TeachingAssignmentEntity> assignments, Map<String, ClassEntity> sections,
                        Map<String, LessonEntity> lessons, Map<String, Integer> sizes, Map<String, Integer> players) {
        LessonEntity lesson(String classId, String subject, LocalDate date) { return lessons.get(key(classId, subject, date)); }
        int childrenIn(String classId) { return sizes.getOrDefault(classId, 0); }
        int playersOf(String lessonId) { return players.getOrDefault(lessonId, 0); }
        TeacherDto.WeekLesson card(LessonEntity l) {
            return new TeacherDto.WeekLesson(l.getId(), l.getTitle(), status(l), l.getType(), playersOf(l.getId()),
                    childrenIn(l.getClassId()), l.getVersion());
        }
    }

    private static String key(String classId, String subject, LocalDate date) { return classId + " " + subject + " " + date; }

    /** §4's three words; see {@link TeacherDto#DRAFT}. */
    public static String status(LessonEntity lesson) {
        return switch (LessonState.status(lesson)) {
            case PUBLISHED -> TeacherDto.PUBLISHED;
            case REVIEW -> TeacherDto.READY;
            default -> TeacherDto.DRAFT;
        };
    }

    static boolean better(LessonEntity candidate, LessonEntity current) {
        boolean candidatePublished = LessonState.status(candidate) == LessonStatus.PUBLISHED;
        if (candidatePublished != (LessonState.status(current) == LessonStatus.PUBLISHED)) return candidatePublished;
        return candidate.getCreatedAt() != null && current.getCreatedAt() != null && candidate.getCreatedAt().isAfter(current.getCreatedAt());
    }

    static LocalDate date(String iso) {
        try { return LocalDate.parse(iso.trim()); }
        catch (DateTimeParseException e) { throw ApiException.badRequest("`" + iso + "` is not a date — use yyyy-MM-dd."); }
    }
}
