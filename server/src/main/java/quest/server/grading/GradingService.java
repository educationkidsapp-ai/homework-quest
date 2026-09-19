package quest.server.grading;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import quest.api.dto.Stop;
import quest.server.auth.Principals;
import quest.server.children.AttemptRepository;
import quest.server.children.ChildMediaRepository;
import quest.server.children.ChildRepository;
import quest.server.children.ChildService;
import quest.server.children.Entities.AttemptEntity;
import quest.server.children.Entities.ChildEntity;
import quest.server.children.Entities.ChildMediaEntity;
import quest.server.config.ApiException;
import quest.server.config.QuestProperties;
import quest.server.content.Entities.LessonEntity;
import quest.server.content.LessonRepository;
import quest.server.content.LessonStore;
import quest.server.content.PlayRepository;
import quest.server.tenancy.Entities.ClassEntity;
import quest.server.tenancy.TeacherScope;

/**
 * `docs/teacher-flow.md` step 9 and teacher prompt §7: the Results page, the class gradebook, the child page, the
 * marks a teacher saves and the release that lets a parent see any of it.
 *
 * <p><strong>Everything is batched.</strong> The gradebook is children × lessons, so every obvious shape here is an
 * N+1 — a score per cell, the plays per lesson, the marks per child. Each read below loads its rows in a fixed
 * number of statements (the roster, the lessons, the attempts, the marks, the plays, the media) and does the rest in
 * Java through {@link Scoring}, which is why the grid for 30 children × 20 lessons costs the same as for one child
 * and one lesson. `GradebookQueryCountTest` pins that.
 *
 * <p><strong>Scope.</strong> A lesson is resolved through {@link TeacherScope#requireLesson} and a class through
 * {@link TeacherScope#requireClass} — another school's is a 404, another teacher's is a 403 — and the children are
 * then read by that class's own id, never by a parameter. `PUT /teacher/marks` names no class in its path, so every
 * lesson in its body goes through the same check before a single row is written.
 *
 * <p><strong>Release.</strong> §7's toggle is per lesson, and a lesson copy belongs to one section, so releasing it
 * releases it for that whole section at once. While it is released, marking is refused with 409: the score and the
 * comment a parent has already been shown must not change under her. Withdrawing the release (`released: false`) is
 * how a teacher re-opens a lesson she needs to re-mark — one route, two directions, and the parent's view follows.
 */
@Service
public class GradingService {
    /** How far back the gradebook looks when the caller names no window: §7's "the grid for the month". */
    private static final int DEFAULT_WINDOW_DAYS = 30, MAX_WINDOW_DAYS = 366;
    /** The child page's chart: §7's rolling window of homework scores, oldest first on screen. */
    private static final int TREND_LESSONS = Bands.WINDOW;

    private final TeacherScope scope; private final LessonRepository lessons; private final PlayRepository plays;
    private final LessonStore store; private final ChildRepository children; private final ChildService childService;
    private final AttemptRepository attempts; private final TeacherMarkRepository marks; private final ChildMediaRepository media;
    private final quest.server.tenancy.ClassRepository classes; private final String publicUrl;

    public GradingService(TeacherScope scope, LessonRepository lessons, PlayRepository plays, LessonStore store,
                          ChildRepository children, ChildService childService, AttemptRepository attempts,
                          TeacherMarkRepository marks, ChildMediaRepository media,
                          quest.server.tenancy.ClassRepository classes, QuestProperties props) {
        this.scope = scope; this.lessons = lessons; this.plays = plays; this.store = store; this.children = children;
        this.childService = childService; this.attempts = attempts; this.marks = marks; this.media = media;
        this.classes = classes; this.publicUrl = props.publicUrl() == null ? "" : props.publicUrl();
    }

    // ---------------------------------------------------------------- results (§7, step 9)

    public GradingDto.LessonResults results(Principals.User caller, String lessonId) {
        var lesson = scope.requireLesson(caller, lessonId);
        var section = lesson.getClassId() == null ? null : classes.findById(lesson.getClassId()).orElse(null);
        var stopsByLevel = stopsByLevel(List.of(lessonId)).getOrDefault(lessonId, Map.of());
        var lessonAttempts = attempts.findByLessonId(lessonId);
        var roster = rosterFor(lesson, lessonAttempts);
        var byChild = group(lessonAttempts);
        var marksByChild = marksByChild(marks.findByLessonId(lessonId));
        var work = workByChildStop(roster);

        var columns = new ArrayList<GradingDto.ResultStop>();
        int top = stopsByLevel.keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
        for (Stop stop : stopsByLevel.getOrDefault(top, List.of()))
            columns.add(new GradingDto.ResultStop(stop.getId(), stop.getTitle(), stop.getType(), top,
                    stop.getCategory() == quest.api.dto.StopCategory.OPEN));

        var rows = new ArrayList<GradingDto.ChildResult>(roster.size());
        int played = 0, needsMarking = 0; double sum = 0; int scored = 0;
        for (var child : roster) {
            var mine = perStop(marksByChild.getOrDefault(child.getId(), Map.of()), lessonId);
            var score = Scoring.of(child.getId(), lessonId, stopsByLevel, byChild.getOrDefault(child.getId(), List.of()),
                    stopMarks(mine), lessonMark(mine));
            var lessonRow = mine.get(Entities.TeacherMarkEntity.LESSON);
            var childWork = work.getOrDefault(child.getId(), Map.of());
            var stops = score.stops().stream()
                    .map(s -> new GradingDto.ChildStopResult(s.stopId(), s.attempted(), s.firstTryCorrect(), s.stars(),
                            s.attempts(), s.accuracy(), s.score(), s.markStars(), s.markComment(), s.needsMarking(),
                            childWork.get(s.stopId())))
                    .toList();
            if (score.attempted()) played++;
            needsMarking += score.needsMarking();
            if (score.score() != null) { sum += score.score(); scored++; }
            rows.add(new GradingDto.ChildResult(child.getId(), child.getName(), child.getClassId(), score.attempted(),
                    score.levelReached(), score.autoScore(), score.teacherScore(), score.score(), score.band(),
                    score.starsEarned(), score.starsTotal(), score.completion(), score.needsMarking(),
                    lessonRow == null ? null : lessonRow.getComment(), stops));
        }
        return new GradingDto.LessonResults(lessonId, lesson.getTitle(), lesson.getClassId(),
                section == null ? null : section.getName(), lesson.getSubject(), lesson.getDate().toString(),
                lesson.getType(), lesson.getReleasedAt() != null,
                lesson.getReleasedAt() == null ? null : lesson.getReleasedAt().toEpochMilli(),
                scored == 0 ? null : (int) Math.round(sum / scored), played, needsMarking,
                List.copyOf(columns), List.copyOf(rows));
    }

    // ---------------------------------------------------------------- the gradebook (§7)

    public GradingDto.Gradebook gradebook(Principals.User caller, String classId, String from, String to) {
        var section = scope.requireClass(caller, classId);
        LocalDate end = parse(to, LocalDate.now()), start = parse(from, end.minusDays(DEFAULT_WINDOW_DAYS));
        if (end.isBefore(start)) throw ApiException.badRequest("to must not be before from");
        if (start.plusDays(MAX_WINDOW_DAYS).isBefore(end)) throw ApiException.badRequest("the window must be at most " + MAX_WINDOW_DAYS + " days");

        var published = lessons.findByClassIdAndDateBetweenOrderByDateAsc(section.getId(), start, end).stream()
                .filter(l -> "published".equals(l.getStatus())).toList();
        var lessonIds = published.stream().map(LessonEntity::getId).toList();
        var roster = children.findByClassIdAndDeletedAtIsNullOrderByNameAsc(section.getId());
        var childIds = roster.stream().map(ChildEntity::getId).toList();

        var stopsByLesson = stopsByLevel(lessonIds);
        var attemptsByChildLesson = new HashMap<String, Map<String, List<AttemptEntity>>>();
        if (!childIds.isEmpty())
            for (var a : attempts.findByChildIdIn(childIds))
                attemptsByChildLesson.computeIfAbsent(a.getChildId(), k -> new HashMap<>())
                        .computeIfAbsent(a.getLessonId(), k -> new ArrayList<>()).add(a);
        var marksByChild = marksByChild(lessonIds.isEmpty() ? List.of() : marks.findByLessonIdIn(lessonIds));

        var perLessonScores = new LinkedHashMap<String, List<Integer>>();
        var perLessonMarking = new LinkedHashMap<String, Integer>();
        var rows = new ArrayList<GradingDto.GradebookChild>(roster.size());
        int needsMarking = 0;
        for (var child : roster) {
            var byLesson = attemptsByChildLesson.getOrDefault(child.getId(), Map.of());
            var childMarks = marksByChild.getOrDefault(child.getId(), Map.of());
            var cells = new ArrayList<GradingDto.GradebookCell>(published.size());
            var newestFirst = new ArrayList<Double>(); var exams = new ArrayList<Boolean>();
            for (int i = published.size() - 1; i >= 0; i--) {                   // newest first: §7 weights toward recent
                var l = published.get(i);
                var mine = perStop(childMarks, l.getId());
                var score = Scoring.of(child.getId(), l.getId(), stopsByLesson.getOrDefault(l.getId(), Map.of()),
                        byLesson.getOrDefault(l.getId(), List.of()), stopMarks(mine), lessonMark(mine));
                if (score.score() != null && newestFirst.size() < Bands.WINDOW) {
                    newestFirst.add((double) score.score()); exams.add("exam".equals(l.getType()));
                }
                if (score.score() != null) perLessonScores.computeIfAbsent(l.getId(), k -> new ArrayList<>()).add(score.score());
                if (score.needsMarking() > 0) perLessonMarking.merge(l.getId(), 1, Integer::sum);
                needsMarking += score.needsMarking();
                cells.add(new GradingDto.GradebookCell(l.getId(), score.attempted(), score.autoScore(),
                        score.teacherScore(), score.score(), score.band(), score.needsMarking() > 0));
            }
            java.util.Collections.reverse(cells);                               // back into the grid's own column order
            var average = Bands.average(newestFirst, exams);
            rows.add(new GradingDto.GradebookChild(child.getId(), child.getName(),
                    average == null ? null : (int) Math.round(average), average == null ? null : Bands.band(average),
                    Bands.trend(newestFirst), List.copyOf(cells)));
        }

        var columns = published.stream().map(l -> {
            var all = perLessonScores.getOrDefault(l.getId(), List.of());
            return new GradingDto.GradebookLesson(l.getId(), l.getTitle(), l.getDate().toString(), l.getSubject(),
                    l.getType(), l.getReleasedAt() != null,
                    all.isEmpty() ? null : (int) Math.round(all.stream().mapToInt(Integer::intValue).average().orElse(0)),
                    perLessonMarking.getOrDefault(l.getId(), 0));
        }).toList();
        return new GradingDto.Gradebook(section.getId(), section.getName(), scope.subjectOf(caller, section),
                start.toString(), end.toString(), columns, List.copyOf(rows), needsMarking);
    }

    // ---------------------------------------------------------------- the child page (§7)

    public GradingDto.ChildReport child(Principals.User caller, String childId) {
        var child = childService.scoped(childId);
        requireTeaches(caller, child);
        var section = child.getClassId() == null ? null : classes.findById(child.getClassId()).orElse(null);
        var published = child.getClassId() == null ? List.<LessonEntity>of()
                : lessons.findByClassIdInAndStatusOrderByDateAsc(List.of(child.getClassId()), "published");
        var scores = scoresOf(child, published);

        var levels = new ArrayList<GradingDto.ChildLevel>();
        var bySubject = new LinkedHashMap<String, List<Scored>>();
        for (var s : scores) if (s.score().score() != null) bySubject.computeIfAbsent(s.lesson().getSubject(), k -> new ArrayList<>()).add(s);
        bySubject.forEach((subject, list) -> {
            var newestFirst = list.stream().sorted(Comparator.comparing((Scored s) -> s.lesson().getDate()).reversed()).toList();
            var values = newestFirst.stream().map(s -> (double) s.score().score()).toList();
            var exams = newestFirst.stream().map(s -> "exam".equals(s.lesson().getType())).toList();
            var average = Bands.average(values, exams);
            levels.add(new GradingDto.ChildLevel(subject, average == null ? null : Bands.band(average),
                    Bands.trend(values), average == null ? null : (int) Math.round(average), values.size()));
        });

        var trend = scores.stream().filter(s -> s.score().score() != null)
                .sorted(Comparator.comparing(s -> s.lesson().getDate()))
                .skip(Math.max(0, scores.stream().filter(s -> s.score().score() != null).count() - TREND_LESSONS))
                .map(s -> new GradingDto.ChildTrendPoint(s.lesson().getId(), s.lesson().getTitle(),
                        s.lesson().getDate().toString(), s.lesson().getSubject(), s.score().score(),
                        s.score().band(), s.lesson().getReleasedAt() != null))
                .toList();

        var titles = published.stream().collect(LinkedHashMap<String, String>::new,
                (m, l) -> m.put(l.getId(), l.getTitle()), Map::putAll);
        var comments = marks.findByChildIdOrderByMarkedAtDesc(child.getId()).stream()
                .filter(m -> m.getComment() != null && !m.getComment().isBlank())
                .map(m -> new GradingDto.ChildComment(m.getLessonId(), titles.get(m.getLessonId()),
                        m.isLessonLevel() ? null : m.getStopId(), m.getStars(), m.getComment(), m.getMarkedAt().toEpochMilli()))
                .toList();
        var work = media.findByChildIdOrderByCreatedAtDesc(child.getId()).stream()
                .map(m -> new GradingDto.ChildWork(m.getId(), publicUrl + "/media/child/" + m.getId(), m.getKind(),
                        m.getStopId(), m.getCreatedAt().toEpochMilli()))
                .toList();
        return new GradingDto.ChildReport(child.getId(), child.getName(), child.getClassId(),
                section == null ? null : section.getName(), child.getAvatarColor(),
                List.copyOf(levels), trend, comments, work);
    }

    // ---------------------------------------------------------------- marking (§7)

    @Transactional
    public List<GradingDto.TeacherMark> saveMarks(Principals.User caller, GradingDto.SaveMarksRequest body) {
        var checked = new LinkedHashMap<String, LessonEntity>();
        for (var input : body.marks())
            checked.computeIfAbsent(input.lessonId(), id -> {
                var lesson = scope.requireLesson(caller, id);
                if (lesson.getReleasedAt() != null)
                    throw ApiException.conflict("\"" + lesson.getTitle() + "\" is released — withdraw the release before changing its marks.");
                return lesson;
            });
        var out = new ArrayList<GradingDto.TeacherMark>(body.marks().size());
        for (var input : body.marks()) {
            var lesson = checked.get(input.lessonId());
            var child = childService.scoped(input.childId());
            if (child.getClassId() != null && lesson.getClassId() != null && !child.getClassId().equals(lesson.getClassId()))
                throw ApiException.badRequest("That child is not in the class this lesson was published to.");
            String stopId = input.stopId() == null ? Entities.TeacherMarkEntity.LESSON : input.stopId();
            var existing = marks.findByChildIdAndLessonIdAndStopId(child.getId(), lesson.getId(), stopId).orElse(null);
            if (input.stars() == null && input.score() == null && (input.comment() == null || input.comment().isBlank())) {
                if (existing != null) marks.delete(existing);
                continue;
            }
            var row = existing == null ? new Entities.TeacherMarkEntity() : existing;
            if (existing == null) {
                row.setId(UUID.randomUUID().toString()); row.setSchoolId(lesson.getSchoolId());
                row.setChildId(child.getId()); row.setLessonId(lesson.getId()); row.setStopId(stopId);
            }
            row.setStars(input.stars());
            // A score is an override of the whole lesson, which is what §7 puts on the Results page beside the
            // automatic one. On a stop it would be a second, silent way to move the lesson score, so it is dropped.
            row.setScore(row.isLessonLevel() ? input.score() : null);
            row.setComment(input.comment());
            row.setMarkedBy(caller == null ? null : caller.userId());
            row.setMarkedAt(Instant.now());
            out.add(toDto(marks.save(row)));
        }
        return List.copyOf(out);
    }

    // ---------------------------------------------------------------- release (§7)

    @Transactional
    public GradingDto.LessonRelease release(Principals.User caller, String lessonId, GradingDto.ReleaseRequest body) {
        var lesson = scope.requireLesson(caller, lessonId);
        if (!"published".equals(lesson.getStatus()))
            throw ApiException.conflict("Publish \"" + lesson.getTitle() + "\" before releasing its results.");
        boolean released = body == null || body.released() == null || body.released();
        lesson.setReleasedAt(released ? Instant.now() : null);
        lesson.setUpdatedAt(Instant.now());
        lessons.save(lesson);
        int roster = lesson.getClassId() == null ? 0
                : children.findByClassIdAndDeletedAtIsNullOrderByNameAsc(lesson.getClassId()).size();
        return new GradingDto.LessonRelease(lesson.getId(), released,
                lesson.getReleasedAt() == null ? null : lesson.getReleasedAt().toEpochMilli(), roster);
    }

    // ---------------------------------------------------------------- the parent's side (§7)

    /**
     * What the app's parent mode may show for one child: her score, band and the teacher's comment on every lesson
     * whose results have been <strong>released</strong>, and nothing at all for the rest. Child mode never reads it
     * — §6's rule is that the child sees stars and never a number.
     *
     * <p>Three statements whatever her history is: her attempts, the plays of her released lessons, and the marks.
     */
    public List<quest.api.dto.ReleasedResult> releasedFor(ChildEntity child, List<LessonEntity> published) {
        var released = published.stream().filter(l -> l.getReleasedAt() != null)
                .sorted(Comparator.comparing(LessonEntity::getDate)).toList();
        if (released.isEmpty()) return List.of();
        var ids = released.stream().map(LessonEntity::getId).toList();
        var stopsByLesson = stopsByLevel(ids);
        var byLesson = new HashMap<String, List<AttemptEntity>>();
        for (var a : attempts.findByChildIdOrderByAnsweredAtDesc(child.getId()))
            byLesson.computeIfAbsent(a.getLessonId(), k -> new ArrayList<>()).add(a);
        var mine = marksByChild(marks.findByLessonIdIn(ids)).getOrDefault(child.getId(), Map.of());

        var out = new ArrayList<quest.api.dto.ReleasedResult>(released.size());
        for (var lesson : released) {
            var perStop = perStop(mine, lesson.getId());
            var score = Scoring.of(child.getId(), lesson.getId(), stopsByLesson.getOrDefault(lesson.getId(), Map.of()),
                    byLesson.getOrDefault(lesson.getId(), List.of()), stopMarks(perStop), lessonMark(perStop));
            if (!score.attempted()) continue;
            var lessonRow = perStop.get(Entities.TeacherMarkEntity.LESSON);
            out.add(new quest.api.dto.ReleasedResult(lesson.getId(), lesson.getTitle(),
                    new kotlinx.datetime.LocalDate(lesson.getDate().getYear(), lesson.getDate().getMonthValue(), lesson.getDate().getDayOfMonth()),
                    quest.api.dto.Subject.valueOf(lesson.getSubject().toUpperCase()), score.score(), score.band(),
                    lessonRow == null ? null : lessonRow.getComment(), lesson.getReleasedAt().toEpochMilli()));
        }
        return List.copyOf(out);
    }

    // ---------------------------------------------------------------- shared batching

    /** A lesson with one child's score on it, so the child page can sort and group without recomputing. */
    private record Scored(LessonEntity lesson, Scoring.Score score) {}

    private List<Scored> scoresOf(ChildEntity child, List<LessonEntity> published) {
        if (published.isEmpty()) return List.of();
        var ids = published.stream().map(LessonEntity::getId).toList();
        var stopsByLesson = stopsByLevel(ids);
        var byLesson = new HashMap<String, List<AttemptEntity>>();
        for (var a : attempts.findByChildIdOrderByAnsweredAtDesc(child.getId()))
            byLesson.computeIfAbsent(a.getLessonId(), k -> new ArrayList<>()).add(a);
        var mine = marksByChild(marks.findByLessonIdIn(ids)).getOrDefault(child.getId(), Map.of());
        var out = new ArrayList<Scored>(published.size());
        for (var lesson : published) {
            var perStop = perStop(mine, lesson.getId());
            out.add(new Scored(lesson, Scoring.of(child.getId(), lesson.getId(),
                    stopsByLesson.getOrDefault(lesson.getId(), Map.of()), byLesson.getOrDefault(lesson.getId(), List.of()),
                    stopMarks(perStop), lessonMark(perStop))));
        }
        return List.copyOf(out);
    }

    /** Every lesson's main (variant 0) plays, level → the stops a level is scored over, in one query for all of them. */
    private Map<String, Map<Integer, List<Stop>>> stopsByLevel(List<String> lessonIds) {
        var out = new LinkedHashMap<String, Map<Integer, List<Stop>>>();
        if (lessonIds.isEmpty()) return out;
        for (var entity : plays.findByLessonIdInOrderByLessonIdAscLevelAscVariantAsc(lessonIds)) {
            if (entity.getVariant() != 0) continue;                             // the "Again" variant is practice, not homework
            var play = store.play(entity);
            out.computeIfAbsent(entity.getLessonId(), k -> new LinkedHashMap<>())
                    .put(play.getLevel(), Scoring.scorable(play.getStops()));
        }
        return out;
    }

    /**
     * The children a lesson's results are about: the section's roster, plus anyone who has attempted it and sits in
     * no section yet — a child the app registered with a school code alone is counted under the class whose copy she
     * played, or her work would be invisible to the only teacher who could mark it.
     */
    private List<ChildEntity> rosterFor(LessonEntity lesson, List<AttemptEntity> lessonAttempts) {
        var roster = new LinkedHashMap<String, ChildEntity>();
        if (lesson.getClassId() != null)
            for (var c : children.findByClassIdAndDeletedAtIsNullOrderByNameAsc(lesson.getClassId())) roster.put(c.getId(), c);
        var extra = new LinkedHashSet<>(lessonAttempts.stream().map(AttemptEntity::getChildId).toList());
        extra.removeAll(roster.keySet());
        if (!extra.isEmpty())
            for (var c : children.findAllById(extra))
                if (c.getDeletedAt() == null && c.getClassId() == null) roster.put(c.getId(), c);
        return roster.values().stream().sorted(Comparator.comparing(ChildEntity::getName)).toList();
    }

    /** `/media/child/{id}` per (child, stop) — the saved retell or drawing the Results page links each open stop to. */
    private Map<String, Map<String, String>> workByChildStop(List<ChildEntity> roster) {
        var out = new HashMap<String, Map<String, String>>();
        if (roster.isEmpty()) return out;
        for (var m : media.findByChildIdIn(roster.stream().map(ChildEntity::getId).toList()))
            out.computeIfAbsent(m.getChildId(), k -> new HashMap<>())
                    .merge(m.getStopId(), publicUrl + "/media/child/" + m.getId(), (older, newer) -> newer);
        return out;
    }

    private static Map<String, List<AttemptEntity>> group(List<AttemptEntity> all) {
        var out = new HashMap<String, List<AttemptEntity>>();
        for (var a : all) out.computeIfAbsent(a.getChildId(), k -> new ArrayList<>()).add(a);
        return out;
    }

    /** child → (lesson + "/" + stop) → mark, so one pass over the rows serves every child and every lesson. */
    private static Map<String, Map<String, Entities.TeacherMarkEntity>> marksByChild(List<Entities.TeacherMarkEntity> all) {
        var out = new HashMap<String, Map<String, Entities.TeacherMarkEntity>>();
        for (var m : all) out.computeIfAbsent(m.getChildId(), k -> new HashMap<>()).put(m.getLessonId() + "/" + m.getStopId(), m);
        return out;
    }

    /** One lesson's marks out of a child's whole set, keyed by stop id (the lesson-level row keeps its `""`). */
    private static Map<String, Entities.TeacherMarkEntity> perStop(Map<String, Entities.TeacherMarkEntity> childMarks, String lessonId) {
        var out = new HashMap<String, Entities.TeacherMarkEntity>();
        String prefix = lessonId + "/";
        childMarks.forEach((key, mark) -> { if (key.startsWith(prefix)) out.put(key.substring(prefix.length()), mark); });
        return out;
    }

    private static Map<String, Scoring.Mark> stopMarks(Map<String, Entities.TeacherMarkEntity> perStop) {
        var out = new HashMap<String, Scoring.Mark>();
        perStop.forEach((stopId, m) -> { if (!Entities.TeacherMarkEntity.LESSON.equals(stopId)) out.put(stopId, new Scoring.Mark(m.getStars(), m.getScore(), m.getComment())); });
        return out;
    }

    private static Scoring.Mark lessonMark(Map<String, Entities.TeacherMarkEntity> perStop) {
        var m = perStop.get(Entities.TeacherMarkEntity.LESSON);
        return m == null ? null : new Scoring.Mark(m.getStars(), m.getScore(), m.getComment());
    }

    private static GradingDto.TeacherMark toDto(Entities.TeacherMarkEntity m) {
        return new GradingDto.TeacherMark(m.getChildId(), m.getLessonId(), m.isLessonLevel() ? null : m.getStopId(),
                m.getStars(), m.getScore(), m.getComment(), m.getMarkedBy(), m.getMarkedAt().toEpochMilli());
    }

    /** The §2 rule {@link quest.server.teacher.TeacherStudentService} applies to a timeline, applied to a child page. */
    private void requireTeaches(Principals.User caller, ChildEntity child) {
        if (!scope.isTeacher(caller)) return;
        boolean teaches = child.getClassId() != null
                && scope.classesOf(caller).stream().anyMatch(k -> k.getId().equals(child.getClassId()));
        if (!teaches) throw ApiException.forbidden("That child is not in one of your classes.");
    }

    private static LocalDate parse(String value, LocalDate fallback) {
        if (value == null || value.isBlank()) return fallback;
        try { return LocalDate.parse(value); } catch (RuntimeException e) { throw ApiException.badRequest(value + " is not a date (yyyy-MM-dd)"); }
    }

    /** The section a class id names, for the export headers; null when the lesson belongs to no section. */
    ClassEntity sectionOf(String classId) { return classId == null ? null : classes.findById(classId).orElse(null); }
}
