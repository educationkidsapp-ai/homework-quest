package quest.server.teacher;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import quest.api.dto.Stop;
import quest.api.dto.StopCategory;
import quest.api.progress.Band;
import quest.api.progress.ProgressBands;
import quest.server.auth.Principals;
import quest.server.children.AttemptRepository;
import quest.server.children.ChildMediaRepository;
import quest.server.children.ChildRepository;
import quest.server.children.ChildService;
import quest.server.children.Entities.AttemptEntity;
import quest.server.children.Entities.ChildEntity;
import quest.server.children.LessonCompletionRepository;
import quest.server.config.ApiException;
import quest.server.config.QuestProperties;
import quest.server.content.Entities.LessonEntity;
import quest.server.content.Entities.PlayEntity;
import quest.server.content.Entities.SkillEntity;
import quest.server.content.LessonRepository;
import quest.server.content.LessonStore;
import quest.server.content.PlayRepository;
import quest.server.content.SkillRepository;
import quest.server.tenancy.ClassRepository;
import quest.server.tenancy.Entities.ClassEntity;

/**
 * §6 screen 15, "My students": per class, each child's stars this week, level reached and weak skills; and per
 * child, a timeline of what she played with the retells and drawings she saved.
 *
 * <p><strong>Fixed query count.</strong> The obvious shape — loop the children and ask `ProgressService` for each —
 * is a query per child, which is fine in a fixture and unusable in a school of four hundred. Everything here is
 * batched instead: the class's children (1), the class's published lessons (2), every attempt of those children
 * (1), every completion of them (1), the plays (1) and the confirmed skills (1) of those lessons. Seven statements
 * for one child or for four hundred, which is what `TeacherQueryCountTest` pins.
 *
 * <p><strong>Scope.</strong> The class is resolved through {@link TeacherAccess} — another school's is a 404 (the
 * `school` filter never returns it), another teacher's is a 403 — and the children are then read by that class's
 * `school_id` and course, never by a parameter. The timeline goes through {@link ChildService#scoped}, the one gate
 * every child-owned row already sits behind.
 */
@Service
public class TeacherStudentService {
    /** "This week" is the last seven days, the same rolling window the Home cards count. */
    private static final Duration WEEK = Duration.ofDays(7);
    /** A timeline is a screen, not an export; the window defaults to a month and is capped at a year. */
    private static final int DEFAULT_TIMELINE_DAYS = 30, MAX_TIMELINE_DAYS = 366;

    private final ChildRepository children; private final ChildService childService; private final AttemptRepository attempts;
    private final LessonCompletionRepository completions; private final ChildMediaRepository media;
    private final ClassRepository classes; private final LessonRepository lessons; private final PlayRepository plays;
    private final SkillRepository skills; private final LessonStore store; private final TeacherAccess access;
    private final TeacherQuestionService questions; private final String publicUrl;

    public TeacherStudentService(ChildRepository children, ChildService childService, AttemptRepository attempts,
                                 LessonCompletionRepository completions, ChildMediaRepository media,
                                 ClassRepository classes, LessonRepository lessons, PlayRepository plays,
                                 SkillRepository skills, LessonStore store, TeacherAccess access,
                                 TeacherQuestionService questions, QuestProperties props) {
        this.children = children; this.childService = childService; this.attempts = attempts;
        this.completions = completions; this.media = media; this.classes = classes; this.lessons = lessons;
        this.plays = plays; this.skills = skills; this.store = store; this.access = access; this.questions = questions;
        this.publicUrl = props.publicUrl() == null ? "" : props.publicUrl();
    }

    // ---------------------------------------------------------------- the class (§6 screen 15)

    public List<TeacherDto.ClassStudent> students(Principals.User caller, String classId) {
        var klass = access.readableClass(caller, classId);
        var roster = children.findBySchoolIdAndCurriculumAndGradeAndDeletedAtIsNullOrderByNameAsc(
                klass.getSchoolId(), klass.getCurriculum(), klass.getGrade());
        if (roster.isEmpty()) return List.of();

        var childIds = roster.stream().map(ChildEntity::getId).toList();
        var published = publishedOf(klass);
        var lessonIds = published.stream().map(LessonEntity::getId).toList();

        var attemptsByChild = new LinkedHashMap<String, List<AttemptEntity>>();
        for (var attempt : attempts.findByChildIdIn(childIds))
            attemptsByChild.computeIfAbsent(attempt.getChildId(), k -> new ArrayList<>()).add(attempt);

        var levelByChild = new HashMap<String, Integer>();
        for (var completion : completions.findByChildIdIn(childIds))
            levelByChild.merge(completion.getChildId(), completion.getLevel(), Math::max);

        var singleStops = singleAnswerStopIds(lessonIds);
        var skillsByLesson = confirmedSkills(lessonIds);
        var lessonOrder = published.stream().sorted(Comparator.comparing(LessonEntity::getDate)).toList();

        Instant since = Instant.now().minus(WEEK);
        var out = new ArrayList<TeacherDto.ClassStudent>(roster.size());
        for (var child : roster) {
            var mine = attemptsByChild.getOrDefault(child.getId(), List.of());
            out.add(new TeacherDto.ClassStudent(child.getId(), child.getName(), child.getAvatarColor(),
                    starsSince(mine, since), levelByChild.getOrDefault(child.getId(), 0),
                    weakSkills(mine, lessonOrder, singleStops, skillsByLesson),
                    mine.stream().map(AttemptEntity::getAnsweredAt).max(Comparator.naturalOrder()).map(Instant::toEpochMilli).orElse(null)));
        }
        return List.copyOf(out);
    }

    // ---------------------------------------------------------------- one child (§6 screen 15, the timeline)

    /**
     * What she played in the window, and the retells and drawings she saved.
     *
     * <p>The media are `/media/child/{id}` URLs, not bytes: that route is authenticated and
     * {@link quest.server.files.MediaAccess#requireChild} already lets a dashboard user of the child's school read
     * it (`ChildService.scoped` is the same gate used here), so the dashboard fetches each with its own bearer
     * token and nothing new has to be opened up.
     */
    public TeacherDto.StudentTimeline timeline(Principals.User caller, String childId, String from, String to) {
        var child = childService.scoped(childId);
        requireTeaches(caller, child);
        LocalDate end = parse(to, LocalDate.now());
        LocalDate start = parse(from, end.minusDays(DEFAULT_TIMELINE_DAYS));
        if (end.isBefore(start)) throw ApiException.badRequest("to must not be before from");
        if (start.plusDays(MAX_TIMELINE_DAYS).isBefore(end)) throw ApiException.badRequest("the window must be at most " + MAX_TIMELINE_DAYS + " days");
        Instant after = start.atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        Instant before = end.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();

        var entries = new ArrayList<TeacherDto.StudentTimelineEntry>();

        var done = completions.findByChildId(child.getId()).stream()
                .filter(c -> within(c.getCompletedAt(), after, before)).toList();
        var titles = titlesOf(done.stream().map(c -> c.getLessonId()).distinct().toList());
        for (var completion : done)
            entries.add(new TeacherDto.StudentTimelineEntry("lesson.completed", completion.getCompletedAt().toEpochMilli(),
                    titles.getOrDefault(completion.getLessonId(), null), completion.getLessonId(), completion.getLevel(),
                    completion.getStarsEarned(), completion.getStarsTotal(), null, null, null, null));

        var answers = questions.answersOf(child.getId()).stream().filter(a -> within(a.getAnsweredAt(), after, before)).toList();
        var questionTitles = new HashMap<String, String>();
        for (var question : questions.questionsById(answers.stream().map(Entities.TeacherQuestionAnswerEntity::getQuestionId).distinct().toList()))
            questionTitles.put(question.getId(), question.getTitle());
        for (var answer : answers)
            entries.add(new TeacherDto.StudentTimelineEntry("question.answered", answer.getAnsweredAt().toEpochMilli(),
                    questionTitles.get(answer.getQuestionId()), null, null, null, null,
                    answer.getQuestionId(), answer.getStopId(), answer.isCorrect(), answer.getStars()));

        entries.sort(Comparator.comparingLong(TeacherDto.StudentTimelineEntry::at).reversed());

        var saved = media.findByChildIdOrderByCreatedAtDesc(child.getId()).stream()
                .filter(m -> within(m.getCreatedAt(), after, before))
                .map(m -> new TeacherDto.StudentMedia(m.getId(), publicUrl + "/media/child/" + m.getId(),
                        m.getKind(), m.getStopId(), m.getCreatedAt().toEpochMilli()))
                .toList();

        return new TeacherDto.StudentTimeline(child.getId(), child.getName(), start.toString(), end.toString(),
                List.copyOf(entries), saved);
    }

    // ---------------------------------------------------------------- scope

    /**
     * A TEACHER may open the timeline of a child in one of her own classes, and no other. The child is already
     * proven to be of the caller's school by {@link ChildService#scoped}; this is the rule inside the school, the
     * same one {@link TeacherAccess#readableClass} applies to a class in the path.
     */
    private void requireTeaches(Principals.User caller, ChildEntity child) {
        if (!access.isTeacher(caller)) return;
        boolean mine = classes.findBySchoolIdAndCurriculumAndGrade(child.getSchoolId(), child.getCurriculum(), child.getGrade())
                .stream().anyMatch(k -> caller.userId().equals(k.getTeacherId()));
        if (!mine) throw ApiException.forbidden("That child is not in one of your classes.");
    }

    // ---------------------------------------------------------------- the numbers

    /**
     * Stars this week: the best a child did on each stop she answered inside the window, summed. Counting every
     * attempt would reward a child for answering the same stop twice, and counting only completions would miss the
     * lesson she is halfway through — this is the same "best per stop" rule `stop_completions` is derived with.
     */
    private static int starsSince(List<AttemptEntity> mine, Instant since) {
        var best = new HashMap<String, Integer>();
        for (var attempt : mine)
            if (!attempt.getAnsweredAt().isBefore(since)) best.merge(attempt.getStopId(), attempt.getStars(), Math::max);
        return best.values().stream().mapToInt(Integer::intValue).sum();
    }

    /**
     * The skills she is weakest at, banded by `ProgressBands` over first tries on single-answer stops — the same
     * computation `ProgressService` does per child, run here over the batch so it costs no extra query.
     */
    private static List<TeacherDto.WeakSkill> weakSkills(List<AttemptEntity> mine, List<LessonEntity> lessonsByDate,
                                                         Set<String> singleStops, Map<String, List<SkillEntity>> skillsByLesson) {
        var out = new ArrayList<TeacherDto.WeakSkill>();
        var seen = new HashSet<String>();
        for (var lesson : lessonsByDate) {
            var firstTries = mine.stream()
                    .filter(a -> a.getLessonId().equals(lesson.getId()) && a.getAttemptNumber() == 1 && singleStops.contains(a.getStopId()))
                    .sorted(Comparator.comparing(AttemptEntity::getAnsweredAt).reversed())
                    .map(AttemptEntity::isCorrect).toList();
            var accuracy = ProgressBands.INSTANCE.accuracy(firstTries);
            if (accuracy == null || ProgressBands.INSTANCE.band(accuracy) != Band.NEEDS_ANOTHER_LOOK) continue;
            for (var skill : skillsByLesson.getOrDefault(lesson.getId(), List.of()))
                if (seen.add(skill.getId()))
                    out.add(new TeacherDto.WeakSkill(skill.getId(), skill.getName(), Band.NEEDS_ANOTHER_LOOK.name()));
        }
        return List.copyOf(out);
    }

    // ---------------------------------------------------------------- batched lookups

    /** The class's published lessons: its own, plus its school's other classes for the same course (§2's rule). */
    private List<LessonEntity> publishedOf(ClassEntity klass) {
        var classIds = classes.findBySchoolIdAndCurriculumAndGrade(klass.getSchoolId(), klass.getCurriculum(), klass.getGrade())
                .stream().map(ClassEntity::getId).toList();
        return classIds.isEmpty() ? List.of() : lessons.findByClassIdInAndStatusOrderByDateAsc(classIds, "published");
    }

    private Set<String> singleAnswerStopIds(List<String> lessonIds) {
        var ids = new HashSet<String>();
        if (lessonIds.isEmpty()) return ids;
        for (PlayEntity entity : plays.findByLessonIdInOrderByLessonIdAscLevelAscVariantAsc(lessonIds))
            for (Stop stop : store.play(entity).getStops()) {
                if (stop.getCategory() == StopCategory.SINGLE) ids.add(stop.getId());
                if (stop instanceof Stop.ExitTicket ticket)
                    for (var question : ticket.getQuestions()) if (question.getCategory() == StopCategory.SINGLE) ids.add(question.getId());
            }
        return ids;
    }

    private Map<String, List<SkillEntity>> confirmedSkills(List<String> lessonIds) {
        var out = new LinkedHashMap<String, List<SkillEntity>>();
        if (lessonIds.isEmpty()) return out;
        for (var skill : skills.findByLessonIdInAndConfirmedTrueOrderByLessonIdAscPositionAsc(lessonIds))
            out.computeIfAbsent(skill.getLessonId(), k -> new ArrayList<>()).add(skill);
        return out;
    }

    private Map<String, String> titlesOf(List<String> lessonIds) {
        var out = new HashMap<String, String>();
        if (lessonIds.isEmpty()) return out;
        for (var lesson : lessons.findAllById(lessonIds)) out.put(lesson.getId(), lesson.getTitle());
        return out;
    }

    private static boolean within(Instant at, Instant after, Instant before) {
        return at != null && !at.isBefore(after) && at.isBefore(before);
    }

    private static LocalDate parse(String value, LocalDate fallback) {
        if (value == null || value.isBlank()) return fallback;
        try { return LocalDate.parse(value.trim()); }
        catch (Exception e) { throw ApiException.badRequest("from/to must be ISO dates (yyyy-MM-dd)"); }
    }
}
