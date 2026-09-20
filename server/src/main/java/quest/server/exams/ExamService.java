package quest.server.exams;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import quest.api.dto.ApiError;
import quest.api.dto.Stop;
import quest.api.dto.StopCategory;
import quest.server.auth.Principals;
import quest.server.children.ChildRepository;
import quest.server.children.Entities.ChildEntity;
import quest.server.config.ApiException;
import quest.server.content.Entities.LessonEntity;
import quest.server.content.LessonRepository;
import quest.server.grading.Bands;
import quest.server.grading.GradingDto;
import quest.server.grading.GradingService;
import quest.server.platform.SchoolCalendar;
import quest.server.teacher.TeacherDto;
import quest.server.teacher.TeacherLessonService;
import quest.server.tenancy.TeacherScope;

/**
 * `docs/teacher-flow.md` step 10 and teacher prompt §8 — an exam, from the class page to the printable sheet.
 *
 * <p><strong>An exam is a lesson.</strong> Creating one runs the lesson pipeline through
 * {@link TeacherLessonService}: the same sources, the same analysis cache, the same review screens, the same
 * publish. Nothing about generating an exam is different, which is why there is no second pipeline here and why
 * "same file analyzed once, forever" keeps being true across a homework and the exam written from it. What this
 * class owns is the four things §8 adds — the window, the single paper, the single sitting, and a release that a
 * publish does not perform.
 *
 * <p><strong>The score is §7's.</strong> {@link #results} is {@link GradingService#results} with §8's columns laid
 * beside it: who sat it and for how long, who was absent, the distribution over the four bands and which questions
 * were missed. Nothing is re-scored here — a second scorer is a second set of numbers, and a teacher who opens the
 * gradebook and the exam page must see one.
 *
 * <p><strong>Scope.</strong> Every route resolves its exam through {@link TeacherScope#requireLesson} and its class
 * through {@link TeacherScope#requireAssignment} — another school's is a 404, another teacher's is a 403. As in
 * {@link GradingService} there is no separate `/admin/**` alias: `TeacherScope` already lets an ADMIN scoped with
 * `X-School-Id` reach any class of that school, and `permissions.json` grants her the keys, so the Admin dashboard
 * calls exactly these routes.
 */
@Service
public class ExamService {
    /** How long a re-opened exam stays open for that one child when the exam names no duration of its own. */
    static final int DEFAULT_REOPEN_MINUTES = 60;

    private final TeacherScope scope; private final TeacherLessonService teacherLessons; private final GradingService grading;
    private final LessonRepository lessons; private final ChildRepository children; private final ExamPlays papers;
    private final ExamSettingsRepository settings; private final ExamAttemptRepository sittings;
    private final quest.server.tenancy.ClassRepository classes; private final SchoolCalendar calendar;
    private final Clock clock;

    public ExamService(TeacherScope scope, TeacherLessonService teacherLessons, GradingService grading,
                       LessonRepository lessons, ChildRepository children, ExamPlays papers,
                       ExamSettingsRepository settings, ExamAttemptRepository sittings,
                       quest.server.tenancy.ClassRepository classes, SchoolCalendar calendar, Clock clock) {
        this.scope = scope; this.teacherLessons = teacherLessons; this.grading = grading; this.lessons = lessons;
        this.children = children; this.papers = papers; this.settings = settings; this.sittings = sittings;
        this.classes = classes; this.calendar = calendar; this.clock = clock;
    }

    // ---------------------------------------------------------------- create, edit, publish, release (§8)

    /**
     * §8's "from the Class page → New exam": the same editor as a lesson, plus the settings.
     *
     * <p>The lesson is created first and stamped `type = exam` in the same transaction, so a row can never exist as
     * a homework the pipeline has already started filling. Its date is the day the window opens, which is what puts
     * the exam card on the right day of the teacher's week — read in the school's own clock, for the reason
     * {@link #dayOf} gives.
     */
    @Transactional
    public ExamDto.ExamSettings create(Principals.User caller, String classId, ExamDto.CreateExamRequest body) {
        var section = scope.requireAssignment(caller, classId.trim(), scope.subjectOn(caller, classId.trim()));
        var opensAt = Instant.ofEpochMilli(body.opensAt());
        var closesAt = requireWindow(opensAt, Instant.ofEpochMilli(body.closesAt()));
        String level = ExamLevels.requireLevel(body.level() == null ? ExamLevels.MIXED : body.level());
        String releaseMode = ExamLevels.requireReleaseMode(body.releaseMode() == null ? ExamLevels.AUTO_ON_CLOSE : body.releaseMode());

        var created = teacherLessons.create(caller, new TeacherDto.CreateTeacherLessonRequest(
                section.getId(), scope.subjectOf(caller, section), dayOf(section.getSchoolId(), opensAt).toString(),
                body.source() == null ? "manual" : body.source(), body.title(), body.notes(), body.practiceLength()));
        var lesson = lessons.findOneById(created.getId()).orElseThrow(() -> ApiException.notFound("lesson"));
        lesson.setType("exam");
        lesson.setUpdatedAt(clock.instant());
        lessons.save(lesson);

        var exam = new Entities.ExamSettingsEntity();
        exam.setLessonId(lesson.getId()); exam.setSchoolId(lesson.getSchoolId());
        exam.setOpensAt(opensAt); exam.setClosesAt(closesAt); exam.setLevel(level);
        exam.setDurationMinutes(body.durationMinutes()); exam.setReleaseMode(releaseMode);
        exam.setCreatedAt(clock.instant()); exam.setUpdatedAt(clock.instant());
        return dto(lesson, settings.save(exam));
    }

    /**
     * §8's settings sheet, "while not open". Once the window has opened the paper is in front of children and a
     * level or a window that moved under them would change what they are being marked on, so it is a 409.
     */
    @Transactional
    public ExamDto.ExamSettings update(Principals.User caller, String examId, ExamDto.UpdateExamRequest body) {
        var lesson = requireExam(caller, examId);
        var exam = require(lesson);
        var now = clock.instant();
        if (!now.isBefore(exam.getOpensAt()))
            throw ApiException.conflict(ApiError.EXAM_OPEN, "\"" + title(lesson) + "\" has already opened — its settings are fixed.");
        var opensAt = body.opensAt() == null ? exam.getOpensAt() : Instant.ofEpochMilli(body.opensAt());
        var closesAt = requireWindow(opensAt, body.closesAt() == null ? exam.getClosesAt() : Instant.ofEpochMilli(body.closesAt()));
        exam.setOpensAt(opensAt); exam.setClosesAt(closesAt);
        if (body.level() != null) exam.setLevel(ExamLevels.requireLevel(body.level()));
        if (body.releaseMode() != null) exam.setReleaseMode(ExamLevels.requireReleaseMode(body.releaseMode()));
        if (body.durationMinutes() != null) exam.setDurationMinutes(body.durationMinutes());
        exam.setUpdatedAt(now);
        if (body.title() != null && !body.title().isBlank()) { lesson.setTitle(body.title().trim()); lesson.setUpdatedAt(now); lessons.save(lesson); }
        // The day the window opens is the day the card sits on in her week, so moving the window moves the card.
        var day = dayOf(lesson.getSchoolId(), opensAt);
        if (!day.equals(lesson.getDate())) { lesson.setDate(day); lesson.setUpdatedAt(now); lessons.save(lesson); }
        return dto(lesson, settings.save(exam));
    }

    /**
     * Live — and on the children's maps, but only between open and close: `SchoolLessons.visibleFor` is what keeps
     * that promise, so publishing early is the ordinary way to set an exam up rather than something to avoid.
     *
     * <p>Publishing does <strong>not</strong> release it. §7's "default on for homework" is exactly that, and
     * `GradingService.releaseOnPublish` leaves an exam alone; §8 gives it its own release.
     */
    @Transactional
    public ExamDto.ExamSettings publish(Principals.User caller, String examId) {
        var lesson = requireExam(caller, examId);
        var exam = require(lesson);
        if (lesson.getClassId() == null) throw ApiException.badRequest("That exam belongs to no class.");
        teacherLessons.publish(caller, lesson.getId(), List.of(lesson.getClassId()));
        return dto(lessons.findOneById(examId).orElse(lesson), exam);
    }

    /** §8's release, manual or the sweep's; `released: false` takes it back off the parents' reports. */
    @Transactional
    public ExamDto.ExamSettings release(Principals.User caller, String examId, GradingDto.ReleaseRequest body) {
        var lesson = requireExam(caller, examId);
        var exam = require(lesson);
        grading.release(caller, examId, body);
        return dto(lessons.findOneById(examId).orElse(lesson), exam);
    }

    /**
     * §8's "Re-open for this child": one more sitting, for a child who was absent or whose exam was cut off.
     *
     * <p>Once per child, which is what makes it a second chance rather than a second exam — a second call is a 409.
     * It extends only the end of the window, never the start, and clears the hand-in so her existing answers are
     * still there when she comes back: §8's resume, applied to a sitting the teacher re-opened rather than to one
     * the child walked away from.
     *
     * <p><strong>The clock starts again.</strong> `startedAt` and `secondsTaken` are reset to this moment, so the
     * time the results page reports is the time the re-sitting took. Keeping the original start would have counted
     * the days between a child's absence and her second chance as time spent on the paper — a "47 hours" in the
     * time-taken column, and a `durationMinutes` that had expired before she opened it.
     */
    @Transactional
    public ExamDto.ExamReopen reopen(Principals.User caller, String examId, String childId) {
        var lesson = requireExam(caller, examId);
        var exam = require(lesson);
        var child = requireChild(lesson, childId);
        var now = clock.instant();
        var row = sittings.findOne(child.getId(), examId).orElse(null);
        if (row != null && row.isReopened())
            throw ApiException.conflict(ApiError.EXAM_ALREADY_REOPENED,
                    child.getName() + " has already been given another sitting of \"" + title(lesson) + "\".");
        if (row == null) {
            row = new Entities.ExamAttemptEntity();
            row.setId(UUID.randomUUID().toString()); row.setSchoolId(lesson.getSchoolId());
            row.setLessonId(examId); row.setChildId(child.getId());
            row.setStartedAt(now); row.setLastSeenAt(now);
        }
        int minutes = exam.getDurationMinutes() == null ? DEFAULT_REOPEN_MINUTES : exam.getDurationMinutes();
        var closes = now.plusSeconds(minutes * 60L);
        row.setState(Entities.ExamAttemptEntity.STARTED);
        row.setSubmittedAt(null); row.setSecondsTaken(null);
        row.setStartedAt(now); row.setLastSeenAt(now);
        row.setReopenedAt(now); row.setReopenedBy(caller == null ? null : caller.userId());
        row.setReopenClosesAt(closes);
        sittings.save(row);
        return new ExamDto.ExamReopen(examId, child.getId(), closes.toEpochMilli(), now.toEpochMilli());
    }

    // ---------------------------------------------------------------- the results page (§8)

    /**
     * §8's results page: §7's per-child scores, plus who sat it and for how long, the distribution over the four
     * bands, which questions were missed, and the children who were never there.
     *
     * <p>Two statements more than the lesson results it is built on: the sittings of this exam, and nothing else —
     * the paper comes from the plays {@link GradingService} has already read.
     *
     * <p><strong>`sat` counts children who answered at least one question</strong> (N4.5 D3), not children with a
     * sitting row: `reopen` writes one for an absent child before she has touched the paper, and a metric card that
     * counted it contradicted the table underneath it. She stays in `absentees` with her row reading `reopened`
     * until she answers, which is exactly what the teacher needs to see.
     */
    public ExamDto.ExamResults results(Principals.User caller, String examId) {
        var lesson = requireExam(caller, examId);
        var exam = require(lesson);
        var base = grading.results(caller, examId);
        var rows = new LinkedHashMap<String, Entities.ExamAttemptEntity>();
        for (var row : sittings.findByLessonId(examId)) rows.put(row.getChildId(), row);

        var results = new ArrayList<ExamDto.ExamChildResult>(base.children().size());
        var absentees = new ArrayList<ExamDto.ExamChildResult>();
        var bands = new LinkedHashMap<String, Integer>();
        for (var band : List.of(Bands.EMERGING, Bands.DEVELOPING, Bands.SECURE, Bands.EXCEEDING)) bands.put(band, 0);
        int paperStops = base.stops().size();
        int sat = 0, submitted = 0, needsMarking = 0, scored = 0; double sum = 0;
        for (var child : base.children()) {
            var row = rows.get(child.childId());
            var state = stateOf(child, row);
            var result = new ExamDto.ExamChildResult(child.childId(), child.name(), state, child.starsEarned(),
                    child.starsTotal(), child.score(), child.band(), child.answered(), paperStops,
                    row == null ? null : row.getSecondsTaken(),
                    row == null ? null : row.getStartedAt().toEpochMilli(),
                    row == null ? null : row.getLastSeenAt().toEpochMilli(),
                    row == null || row.getSubmittedAt() == null ? null : row.getSubmittedAt().toEpochMilli(),
                    child.needsMarking(), row != null && row.isReopened(), child.comment());
            results.add(result);
            // N4.5 D3: a sitting is a child who answered something. `reopen` writes a `started` row for a child who
            // has answered nothing — that is the teacher giving her a second chance, not the child taking it — and
            // counting it made the card read "Sat it 2 of 19" beside a table with one score in it. `sat` and
            // `absent` are the two halves of the roster, so the cards always add up; her own row still reads
            // `reopened`, which is what tells the teacher the second chance is already given.
            if (child.attempted()) sat++; else absentees.add(result);
            if ("submitted".equals(state)) submitted++;
            needsMarking += child.needsMarking();
            if (child.score() != null) { sum += child.score(); scored++; bands.merge(Bands.band(child.score()), 1, Integer::sum); }
        }
        var distribution = bands.entrySet().stream().map(e -> new ExamDto.ExamBand(e.getKey(), e.getValue())).toList();
        return new ExamDto.ExamResults(examId, lesson.getTitle(), lesson.getClassId(), base.className(),
                lesson.getSubject(), lesson.getDate().toString(), dto(lesson, exam),
                lesson.getReleasedAt() != null, lesson.getReleasedAt() == null ? null : lesson.getReleasedAt().toEpochMilli(),
                base.children().size(), sat, submitted, absentees.size(),
                scored == 0 ? null : (int) Math.round(sum / scored), needsMarking,
                distribution, difficulty(base), List.copyOf(results), List.copyOf(absentees));
    }

    /**
     * §8's "which questions most children missed", over the children who <strong>reached</strong> the question.
     *
     * <p>`missedPercent` is `100 − correct ÷ answered` for a question with a right answer and is left null for an
     * open stop, which has none; an open stop reports the teacher's average stars instead. Out of `answered` rather
     * than out of the roster on purpose: a question the class ran out of time before is not a question the class
     * got wrong, and a difficulty list that could not tell the two apart would send a teacher back over the wrong
     * material.
     */
    private static List<ExamDto.ExamQuestion> difficulty(GradingDto.LessonResults base) {
        var out = new ArrayList<ExamDto.ExamQuestion>(base.stops().size());
        for (var stop : base.stops()) {
            int answered = 0, correct = 0, stars = 0, starred = 0;
            for (var child : base.children()) {
                var mine = child.stops().stream().filter(s -> s.stopId().equals(stop.stopId())).findFirst().orElse(null);
                if (mine == null || !mine.attempted()) continue;
                answered++;
                if (stop.open()) { if (mine.markStars() != null) { stars += mine.markStars(); starred++; } }
                else if (Boolean.TRUE.equals(mine.firstTryCorrect())) correct++;
            }
            out.add(new ExamDto.ExamQuestion(stop.stopId(), stop.title(), stop.type(), stop.open(), answered, correct,
                    starred == 0 ? null : Math.round(stars * 100.0 / starred) / 100.0,
                    stop.open() || answered == 0 ? null : (int) Math.round(100.0 * (answered - correct) / answered)));
        }
        return List.copyOf(out);
    }

    /** `absent`, `started`, `submitted`, or `reopened` for a child still inside the sitting the teacher gave back. */
    private static String stateOf(GradingDto.ChildResult child, Entities.ExamAttemptEntity row) {
        if (row == null) return child.attempted() ? Entities.ExamAttemptEntity.STARTED : "absent";
        if (row.isSubmitted()) return Entities.ExamAttemptEntity.SUBMITTED;
        return row.isReopened() ? "reopened" : Entities.ExamAttemptEntity.STARTED;
    }

    // ---------------------------------------------------------------- the printable sheet (§8)

    /** The stops of the paper, for the per-child PDF: the same list the child sat and the results page counts. */
    public List<Stop> paper(LessonEntity lesson) { return papers.paperOf(lesson, require(lesson)); }

    /** One child's row out of a results read, or 404 — the PDF names a child and must not answer about another. */
    public ExamDto.ExamChildResult childResult(ExamDto.ExamResults results, String childId) {
        return results.children().stream().filter(c -> c.childId().equals(childId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("child"));
    }

    public LessonEntity exam(Principals.User caller, String examId) { return requireExam(caller, examId); }

    /** Whether one stop of the paper is an open one, for the sheet's per-question table. */
    public static boolean isOpen(Stop stop) { return stop.getCategory() == StopCategory.OPEN; }

    // ---------------------------------------------------------------- rules

    /** The exam a route names: scoped like any lesson, and a 404 when the lesson it names is a homework. */
    private LessonEntity requireExam(Principals.User caller, String examId) {
        var lesson = scope.requireLesson(caller, examId);
        if (!ExamPlays.isExam(lesson)) throw ApiException.notFound("exam");
        return lesson;
    }

    private Entities.ExamSettingsEntity require(LessonEntity lesson) {
        return settings.findOneByLessonId(lesson.getId()).orElseThrow(() -> ApiException.notFound("exam"));
    }

    /** A child of the exam's own class; anybody else is a 404, so a roster cannot be probed through this route. */
    private ChildEntity requireChild(LessonEntity lesson, String childId) {
        var child = children.findOneById(childId).filter(c -> c.getDeletedAt() == null)
                .orElseThrow(() -> ApiException.notFound("child"));
        if (lesson.getClassId() != null && child.getClassId() != null && !lesson.getClassId().equals(child.getClassId()))
            throw ApiException.notFound("child");
        return child;
    }

    /**
     * The lesson day an exam window falls on, <strong>in the school's timezone</strong>.
     *
     * <p>This used to be `opensAt.atZone(UTC)`, and the bug it caused is the whole of this method. A teacher in
     * Riyadh setting an exam for Sunday at 02:14 names an instant that is still Saturday in UTC, so the day handed
     * to {@link TeacherLessonService} was a Saturday, the school does not teach on Saturdays, and a perfectly
     * ordinary Sunday exam came back 409 `not_teaching_day`. The same three hours run the other way at the end of
     * the week: a Thursday 23:30 window is a Friday in UTC, and Friday is not a teaching day either.
     *
     * <p>{@link SchoolCalendar} is the one authority on where a school's day turns over (its own zone, else the
     * platform's, else UTC) and is already what {@code requireTeachingDay} checks the day against, so reading the
     * date through it is what makes the two agree.
     */
    private LocalDate dayOf(String schoolId, Instant opensAt) {
        return opensAt.atZone(calendar.of(schoolId).zone()).toLocalDate();
    }

    private static Instant requireWindow(Instant opensAt, Instant closesAt) {
        if (!closesAt.isAfter(opensAt)) throw ApiException.badRequest("An exam must close after it opens.");
        return closesAt;
    }

    private static String title(LessonEntity lesson) {
        return lesson.getTitle() == null || lesson.getTitle().isBlank() ? "That exam" : lesson.getTitle();
    }

    ExamDto.ExamSettings dto(LessonEntity lesson, Entities.ExamSettingsEntity exam) {
        var section = lesson.getClassId() == null ? null : classes.findById(lesson.getClassId()).orElse(null);
        return dto(lesson, exam, section == null ? null : section.getName());
    }

    /** The same, with the class's name already in hand — a list reads it once rather than once per row. */
    ExamDto.ExamSettings dto(LessonEntity lesson, Entities.ExamSettingsEntity exam, String className) {
        return new ExamDto.ExamSettings(lesson.getId(), lesson.getTitle(), lesson.getClassId(),
                className, lesson.getSubject(), lesson.getDate().toString(),
                exam.getOpensAt().toEpochMilli(), exam.getClosesAt().toEpochMilli(), exam.getLevel(),
                exam.getDurationMinutes(), exam.isSingleAttempt(), exam.isHintsOff(), exam.isNumbersOff(),
                exam.getReleaseMode(), lesson.getStatus(), lesson.getReleasedAt() != null,
                lesson.getReleasedAt() == null ? null : lesson.getReleasedAt().toEpochMilli(),
                exam.isOpenAt(clock.instant()));
    }

    // ---------------------------------------------------------------- the class page's Exams tab (§8)

    /**
     * Every exam of a class, newest first — what the class page's Exams tab lists, each row carrying its state and
     * the three numbers the tab draws beside it.
     *
     * <p><strong>Why the numbers come from here.</strong> The dashboard used to fetch `/teacher/exams/{id}/results`
     * once per row to find `roster`, `sat` and `needsMarking` — a full scoring pass, its plays, its attempts and
     * its marks, six times over, for three integers. {@link #countsOf} does all of them in four statements for the
     * whole tab, and `ExamListQueryCountTest` pins that against the shape growing back.
     */
    public List<ExamDto.ExamRow> ofClass(Principals.User caller, String classId) {
        var section = scope.requireClass(caller, classId);
        var mine = lessons.findByClassIdInAndDateBetweenOrderByDateAsc(List.of(section.getId()),
                LocalDate.now(clock).minusYears(1), LocalDate.now(clock).plusYears(1)).stream()
                .filter(ExamPlays::isExam).toList();
        if (mine.isEmpty()) return List.of();
        var byLesson = new LinkedHashMap<String, Entities.ExamSettingsEntity>();
        for (var e : settings.findByLessonIdIn(mine.stream().map(LessonEntity::getId).toList())) byLesson.put(e.getLessonId(), e);
        var made = mine.stream().filter(lesson -> byLesson.containsKey(lesson.getId())).toList();
        var counts = countsOf(section.getId(), made);
        var out = new ArrayList<ExamDto.ExamRow>(made.size());
        for (var lesson : made) out.add(row(lesson, byLesson.get(lesson.getId()), counts, section.getName()));
        out.sort((a, b) -> Long.compare(b.opensAt(), a.opensAt()));
        return List.copyOf(out);
    }

    /**
     * `GET /teacher/exams/{id}` — one row of that list: the settings sheet, the state and the same three counts.
     *
     * <p>The settings card and the results header both open on one exam and both want all of it; reading the row
     * they are already looking at is a cheaper and more honest answer than a results page thrown away after three
     * fields have been taken out of it.
     */
    public ExamDto.ExamRow one(Principals.User caller, String examId) {
        var lesson = requireExam(caller, examId);
        var exam = require(lesson);
        var section = lesson.getClassId() == null ? null : classes.findById(lesson.getClassId()).orElse(null);
        return row(lesson, exam, countsOf(lesson.getClassId(), List.of(lesson)), section == null ? null : section.getName());
    }

    private ExamDto.ExamRow row(LessonEntity lesson, Entities.ExamSettingsEntity exam, Counts counts, String className) {
        return ExamDto.ExamRow.of(dto(lesson, exam, className), stateOf(lesson, exam, clock.instant()), counts.roster(),
                counts.sat().getOrDefault(lesson.getId(), 0), counts.needsMarking().getOrDefault(lesson.getId(), 0));
    }

    /**
     * The one word the State column says, by exactly the rule the dashboard's own `examStateOf` applies — the two
     * are a contract, and a tab whose server and client disagreed about what "open" means is worse than either.
     *
     * <p>Released beats the window: the parents have the scores, whatever the clock says. Draft beats everything
     * else, including a published exam whose window is missing or inverted — nobody can have sat a paper that
     * never opened, so calling it `closed` would file it under the one word a teacher never looks at twice.
     */
    static String stateOf(LessonEntity lesson, Entities.ExamSettingsEntity exam, Instant now) {
        if (!"published".equals(lesson.getStatus())) return ExamLevels.DRAFT;
        if (lesson.getReleasedAt() != null) return ExamLevels.RELEASED;
        var opensAt = exam.getOpensAt(); var closesAt = exam.getClosesAt();
        if (opensAt == null || closesAt == null || !closesAt.isAfter(opensAt)) return ExamLevels.DRAFT;
        if (now.isBefore(opensAt)) return ExamLevels.SCHEDULED;
        return now.isBefore(closesAt) ? ExamLevels.OPEN : ExamLevels.CLOSED;
    }

    /** The register, the sittings and the open stops still to mark, for a whole tab of exams at once. */
    private record Counts(int roster, java.util.Map<String, Integer> sat, java.util.Map<String, Integer> needsMarking) {}

    /**
     * Four statements, whatever the number of exams: the register is counted in the database rather than loaded,
     * the sittings are counted per exam in one grouped query, and
     * {@link GradingService#needsMarkingByLesson} reads the plays, the attempts and the marks of all of them in
     * three more. A row that asked its own questions would be the N+1 the Exams tab already had, moved from the
     * dashboard into the server.
     */
    private Counts countsOf(String classId, List<LessonEntity> forExams) {
        if (forExams.isEmpty()) return new Counts(0, java.util.Map.of(), java.util.Map.of());
        int roster = classId == null ? 0 : (int) children.countByClassIdAndDeletedAtIsNull(classId);
        var sat = new LinkedHashMap<String, Integer>();
        for (var pair : sittings.countByLessonIdIn(forExams.stream().map(LessonEntity::getId).toList()))
            sat.put((String) pair[0], ((Number) pair[1]).intValue());
        return new Counts(roster, sat, grading.needsMarkingByLesson(forExams));
    }
}
