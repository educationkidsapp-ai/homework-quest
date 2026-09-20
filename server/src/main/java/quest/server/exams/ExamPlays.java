package quest.server.exams;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import quest.api.dto.Play;
import quest.api.dto.PublishedLesson;
import quest.api.dto.Stop;
import quest.server.content.Entities.LessonEntity;
import quest.server.content.LessonStore;

/**
 * The one paper an exam is sat over, and the only place that decides what it contains.
 *
 * <p>§8 gives an exam "one level only — the teacher picks 1, 2 or 3, or *mixed* = the teacher assembles stops from
 * the three generated levels". A single-level exam is that level's play, unchanged. A <strong>mixed</strong> one is
 * assembled here, round-robin across the levels the pipeline generated, up to the lesson's own practice length —
 * so a mixed paper is the same length as a homework rather than three homeworks, and it opens on a Level 1 question
 * rather than on the hardest thing in the lesson.
 *
 * <p><strong>Nothing is stored.</strong> The paper is derived from the plays the pipeline already wrote, every time
 * it is asked for, which is what lets a teacher change `level` from `2` to `mixed` while the window is shut without
 * the generated levels having been overwritten and lost. The derivation is deterministic — the same settings and
 * the same plays give the same stops in the same order — and that is what makes it safe for the scorer to ask the
 * same question independently of the app: {@link #paperStops} is called by the player's download and by
 * `GradingService` when it works out what an exam's score is over, and there is exactly one answer.
 *
 * <p>The stops keep the ids their own level gave them. They are unique across a lesson already (`stops.id` is a
 * primary key), so an attempt on a mixed paper lands on the row the scorer is looking at, and a child who sat the
 * exam and a teacher reading her results are talking about the same question.
 */
@Component
public class ExamPlays {
    private final ExamSettingsRepository settings; private final LessonStore store;

    public ExamPlays(ExamSettingsRepository settings, LessonStore store) { this.settings = settings; this.store = store; }

    /** The settings of an exam lesson, or null — a homework, or an exam whose row has not been written yet. */
    public Entities.ExamSettingsEntity of(LessonEntity lesson) {
        return lesson == null || !isExam(lesson) ? null : settings.findOneByLessonId(lesson.getId()).orElse(null);
    }

    public static boolean isExam(LessonEntity lesson) { return lesson != null && "exam".equals(lesson.getType()); }

    /**
     * {@link PublishedLesson} with §8's four exam fields filled in: the type, the two "off" switches the player
     * must honour, and the paper itself. A homework is returned exactly as it was assembled.
     */
    public PublishedLesson decorate(PublishedLesson published, LessonEntity lesson) {
        var exam = of(lesson);
        if (exam == null) return published;
        var byLevel = new LinkedHashMap<Integer, List<Stop>>();
        for (var play : published.getPlays()) byLevel.put(play.getLevel(), play.getStops());
        var stops = paperStops(exam.getLevel(), lesson.getPracticeLength(), byLevel);
        var base = published.getPlays().isEmpty() ? null : published.getPlays().get(0);
        var paper = new Play(paperLevel(exam.getLevel()), 0, published.getKind(),
                base == null ? published.getTheme() : base.getTheme(), stops, lesson.getId() + ":exam");
        return new PublishedLesson(published.getId(), published.getVersion(), published.getCourse(), published.getSubject(),
                published.getDate(), published.getTitle(), published.getKind(), published.getTheme(), published.getSkills(),
                published.getPlays(), published.getVariant(), published.getParentPanel(), published.getPageImages(),
                "exam", exam.isHintsOff(), exam.isNumbersOff(), paper);
    }

    /**
     * The stops of the paper, in the order the child meets them.
     *
     * <p>A single-level exam is that level's stops as generated. A mixed one takes one stop from each level in turn
     * — 1, 2, 3, 1, 2, 3 … — until it has `practiceLength` of them or the levels run out, which keeps the three
     * difficulties in proportion however unevenly the pipeline filled them.
     *
     * <p>A level the pipeline never produced is simply absent, so an exam pinned to a level that is missing falls
     * back to the paper a mixed one would give rather than to nothing: a teacher whose Level 3 failed to generate
     * gets a shorter exam, not an empty one.
     */
    public static List<Stop> paperStops(String level, int practiceLength, Map<Integer, List<Stop>> byLevel) {
        Integer one = ExamLevels.levelOf(level);
        if (one != null && !byLevel.getOrDefault(one, List.of()).isEmpty()) return byLevel.get(one);
        int cap = practiceLength <= 0 ? Integer.MAX_VALUE : practiceLength;
        var levels = byLevel.keySet().stream().sorted().toList();
        var out = new ArrayList<Stop>();
        for (int i = 0; out.size() < cap; i++) {
            boolean any = false;
            for (var l : levels) {
                var stops = byLevel.getOrDefault(l, List.of());
                if (i >= stops.size()) continue;
                any = true;
                out.add(stops.get(i));
                if (out.size() >= cap) break;
            }
            if (!any) break;
        }
        return List.copyOf(out);
    }

    /**
     * The level number the exam's own stops are filed under for the scorer.
     *
     * <p>`Scoring` describes a child by the hardest level she attempted, and an exam has exactly one level to
     * attempt — so the paper is handed to it as a single entry and this is its key. A mixed paper reports 1,
     * because a paper drawn from all three is not a level and 1 is the one number that never overstates it.
     */
    public static int paperLevel(String level) {
        Integer one = ExamLevels.levelOf(level);
        return one == null ? 1 : one;
    }

    /**
     * What the scorer scores an exam over: the paper, as one level, replacing the three the pipeline generated.
     *
     * <p>Without this a mixed paper would be scored as whichever level the child's last answer happened to come
     * from — `Scoring` takes the highest level with an attempt on it — and a child who answered every question
     * would be marked on a third of them.
     */
    public static Map<Integer, List<Stop>> paper(Entities.ExamSettingsEntity exam, int practiceLength,
                                                 Map<Integer, List<Stop>> byLevel) {
        var stops = paperStops(exam.getLevel(), practiceLength, byLevel);
        return stops.isEmpty() ? Map.of() : Map.of(paperLevel(exam.getLevel()), stops);
    }

    /** The exam's paper read straight from the stored plays — what the results page and the PDF sheet count over. */
    public List<Stop> paperOf(LessonEntity lesson, Entities.ExamSettingsEntity exam) {
        var byLevel = new LinkedHashMap<Integer, List<Stop>>();
        for (var entity : store.plays(lesson.getId())) {
            if (entity.getVariant() != 0) continue;                             // the "Again" variant is practice, never an exam
            var play = store.play(entity);
            byLevel.put(play.getLevel(), play.getStops());
        }
        return paperStops(exam.getLevel(), lesson.getPracticeLength(), byLevel);
    }
}
