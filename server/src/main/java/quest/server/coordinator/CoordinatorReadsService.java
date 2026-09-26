package quest.server.coordinator;

import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;
import quest.server.attendance.AttendanceDto;
import quest.server.attendance.AttendanceService;
import quest.server.auth.Principals;
import quest.server.config.ApiException;
import quest.server.exams.ExamDto;
import quest.server.exams.ExamService;
import quest.server.grading.GradingDto;
import quest.server.grading.GradingService;
import quest.server.tenancy.CoordinatorScope;

/**
 * R3 (DR2): the coordinator's numbers are <strong>the teacher's numbers</strong>. Every method here does two things
 * and nothing else — it resolves what the path names through {@link CoordinatorScope}, and it hands the resolved row
 * to the service that already answers the teacher's own screen. There is no second scoring pass, no second gradebook
 * and no second attendance rate to drift out of step with §7's, which is what makes R6's "the numbers equal the
 * teacher's for the same class" a property of the code rather than a test that happens to pass.
 *
 * <p><strong>Why the delegates take the caller.</strong> {@link quest.server.tenancy.TeacherScope} narrows a TEACHER
 * and nobody else, so a COORDINATOR principal passes through its `requireClass`/`requireLesson` untouched and the
 * grading and exam services need no coordinator-shaped overload. That is safe only because the id has already been
 * checked here: {@link CoordinatorScope#requireSection} for a section, {@link CoordinatorScope#requireLesson} for a
 * lesson or an exam, {@link CoordinatorScope#requireChild} for a child — 404 for another school's row, 403 for
 * another subject's or another track's. `CoordinatorScopeArchitectureTest` fails the build if a handler skips it.
 *
 * <p><strong>Scoped twice, because a section is not a subject.</strong> `requireSection` only proves that somebody
 * teaches one of her subjects in a section; the three reads that answer for a whole section — the gradebook, the
 * Exams tab and the child page — are narrowed again by {@link CoordinatorScope#subjectsIn} and hand the delegate a
 * {@link CoordinatorScope.Subjects}, so a section that teaches maths and english shows the maths coordinator neither
 * the english columns, nor the english exam her own {@link #examResults} would refuse, nor an english level on the
 * child page. Every other caller passes {@link CoordinatorScope.Subjects#ALL} and reads exactly what it read before.
 *
 * <p>Attendance is the one delegate that could not take the caller: the teacher's route resolves its class through
 * `TeacherScope`, which would ask a coordinator which classes she teaches. {@link
 * AttendanceService#classAttendanceWindow} takes the section this class has already resolved instead, and builds the
 * teacher's per-day body once per day of the window from two statements.
 */
@Service
public class CoordinatorReadsService {
    /** A term of attendance is more than a screen can draw; the same cap the calendar uses (`CoordinatorService`). */
    static final int MAX_WINDOW_DAYS = CoordinatorService.MAX_WINDOW_DAYS;
    /** Both bounds absent is the week ending today — what the Attendance screen opens on. */
    static final int DEFAULT_WINDOW_DAYS = 6;

    private final CoordinatorScope scope; private final AttendanceService attendance;
    private final GradingService grading; private final ExamService exams;

    public CoordinatorReadsService(CoordinatorScope scope, AttendanceService attendance, GradingService grading,
                                   ExamService exams) {
        this.scope = scope; this.attendance = attendance; this.grading = grading; this.exams = exams;
    }

    /** `GET /coordinator/classes/{id}/attendance` — the teacher's per-day view, once per day of the window. */
    public List<AttendanceDto.ClassAttendanceResponse> attendance(Principals.User caller, String classId, String from, String to) {
        var section = scope.requireSection(caller, classId);
        LocalDate end = date(to, "to", LocalDate.now()), start = date(from, "from", end.minusDays(DEFAULT_WINDOW_DAYS));
        if (end.isBefore(start)) throw ApiException.badRequest("`to` is before `from`.");
        if (start.plusDays(MAX_WINDOW_DAYS).isBefore(end))
            throw ApiException.badRequest("That window is longer than " + MAX_WINDOW_DAYS + " days — ask for a shorter one.");
        return attendance.classAttendanceWindow(section, start, end);
    }

    /**
     * `GET /coordinator/classes/{id}/results` — §7's gradebook grid, for her subjects of a section she supervises.
     *
     * <p>{@link CoordinatorScope#requireSection} only proves somebody teaches one of her subjects there, so the grid
     * is narrowed a second time by {@link CoordinatorScope#subjectsIn} — otherwise a section that teaches maths and
     * english hands the maths coordinator the english columns, and labels the body with whichever subject the section
     * happens to have been assigned first.
     */
    public GradingDto.Gradebook gradebook(Principals.User caller, String classId, String from, String to) {
        var section = scope.requireSection(caller, classId);
        return grading.gradebook(caller, section.getId(), from, to, scope.subjectsIn(caller, section));
    }

    /** `GET /coordinator/lessons/{id}/results` — §7's per-lesson results body, her subject only. */
    public GradingDto.LessonResults lessonResults(Principals.User caller, String lessonId) {
        return grading.results(caller, scope.requireLesson(caller, lessonId).getId());
    }

    /**
     * `GET /coordinator/children/{id}` — §7's child page: her placed section, released scores, exam results.
     *
     * <p>Narrowed to the subjects she coordinates in the section the child is placed in, because the page rolls a
     * level and a trend line <em>per subject</em>.
     */
    public GradingDto.ChildReport child(Principals.User caller, String childId) {
        var child = scope.requireChild(caller, childId);
        // A child on no roster is refused for a coordinator and reachable by the platform ADMIN (N2.3b), and she has
        // no section to take subjects from, so that caller reads the page whole — as she does on `/teacher/children`.
        var subjects = child.getClassId() == null ? CoordinatorScope.Subjects.ALL
                : scope.subjectsIn(caller, scope.requireSection(caller, child.getClassId()));
        return grading.child(caller, child.getId(), subjects);
    }

    /**
     * `GET /coordinator/classes/{id}/exams` — §8's Exams tab: a row per exam with its state and three counts.
     *
     * <p>Narrowed to her subjects, so the tab lists exactly the exams {@link #examResults} will open: an english exam
     * in a section the maths coordinator supervises is absent here and a 403 there, rather than a row whose counts she
     * can read and whose results she cannot.
     */
    public List<ExamDto.ExamRow> classExams(Principals.User caller, String classId) {
        var section = scope.requireSection(caller, classId);
        return exams.ofClass(caller, section.getId(), scope.subjectsIn(caller, section));
    }

    /**
     * `GET /coordinator/exams/{id}/results` — §8's results and distribution.
     *
     * <p>Resolved through {@link CoordinatorScope#requireLesson} rather than `requireSection` on the exam's class: an
     * exam is a lesson, and the strict rule is the one `/coordinator/lessons/{id}` already applies — an english exam
     * sitting in a section the maths coordinator supervises is still not hers.
     */
    public ExamDto.ExamResults examResults(Principals.User caller, String examId) {
        return exams.results(caller, scope.requireLesson(caller, examId).getId());
    }

    private static LocalDate date(String value, String field, LocalDate fallback) {
        if (value == null || value.isBlank()) return fallback;
        try { return LocalDate.parse(value.trim()); }
        catch (java.time.format.DateTimeParseException e) { throw ApiException.badRequest("`" + field + "` is not a date — use yyyy-MM-dd."); }
    }
}
