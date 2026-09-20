package quest.server.grading;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import quest.api.dto.Stop;
import quest.api.dto.StopCategory;
import quest.server.children.Entities.AttemptEntity;

/**
 * §7's `HomeworkScore`: one child's 0–100 on one lesson, derived from her attempts and the teacher's marks.
 *
 * <p><strong>Nothing here is stored.</strong> §7 asks for the score to be "recomputed whenever new attempts arrive",
 * and the shortest way to have that be true of every reader is to have no second copy of it: a score is a pure
 * function of the attempts, the lesson's stops and the marks, all three of which the gradebook already loads in
 * bulk. A materialised `homework_scores` table would need a hook on the upload path, a backfill, and a way to notice
 * that a mark changed — three chances to serve a number the attempts no longer support.
 *
 * <p><strong>The rules</strong>, straight from §7 and named in {@link Bands}:
 * <ul>
 *   <li>a <em>single-answer</em> stop scores by first-try correctness: her first attempt, not her best;</li>
 *   <li>a <em>multi-answer</em> stop (multi-select, select-all, match, order, trace) scores by the stars it earned,
 *       and there her best attempt counts — the player lets her fix a mistake and §7 scores the mistakes, not the
 *       order she made them in;</li>
 *   <li>an <em>open</em> stop (retell, open answer, free writing, a drawing) is complete but unscored until the
 *       teacher marks it: until then it is counted in {@link Score#needsMarking} and left out of the average, so a
 *       child is never penalised for work nobody has looked at yet;</li>
 *   <li>an <em>information</em> stop is not a question. It counts toward completion and never toward the score.</li>
 * </ul>
 *
 * <p><strong>One level.</strong> The score is about the hardest level she actually attempted — a child who played
 * Level 1 and then half of Level 2 is described by Level 2, with {@link Score#completion} carrying the half. The
 * average is taken over the stops she answered rather than over every stop of the play, for the same reason: on a
 * lesson she has not finished, "how well did she do" and "how much did she do" are two numbers, and flattening them
 * into one would report a child who is mid-lesson as a child who failed. {@link Score#scoredLevel} says which level
 * that was, so a caller drawing a row of a multi-level lesson knows which of its columns the row is about.
 *
 * <p><strong>An exam is a fixed paper</strong> (N4.5 D5), and `fixedPaper` switches the rule above off for it: a
 * question the child never reached is a question she did not answer, so it scores 0 rather than leaving the
 * average. Without it a child two questions into a five-question paper with both right reports 100 — a number the
 * results page hides but every export and every API consumer believes. An open stop she <em>did</em> answer and
 * nobody has marked is still left out of both, because a child is never penalised for work nobody has looked at.
 */
public final class Scoring {
    private Scoring() {}

    /** One stop as the Results page shows it: what she did, what it scored, and whether it is waiting for a mark. */
    public record StopOutcome(String stopId, String title, String type, int level, boolean open, boolean attempted,
                              Boolean firstTryCorrect, int stars, int attempts, Integer accuracy, Integer score,
                              Integer markStars, String markComment, boolean needsMarking) {}

    /**
     * §7's `HomeworkScore(childId, lessonId, autoScore, level reached, starsTotal, completion, computedAt)`.
     *
     * <p>`answered` of `total` are the stops of {@link #scoredLevel} she has and has not reached — the two numbers
     * `completion` is the ratio of, carried as well as the percentage so a caller can write "3 of 5" without
     * multiplying a rounded percentage back out.
     */
    public record Score(String childId, String lessonId, boolean attempted, int levelReached, int scoredLevel,
                        Integer autoScore, Integer teacherScore, Integer score, String band, int starsEarned,
                        int starsTotal, int answered, int total, int completion, int needsMarking,
                        List<StopOutcome> stops) {}

    /** A teacher's mark as the scorer needs it, so this class depends on no entity but the attempt. */
    public record Mark(Integer stars, Integer score, String comment) {}

    /**
     * @param stopsByLevel the lesson's main (variant 0) plays, level → stops, exit-ticket questions already flattened
     *                     by {@link #scorable}
     * @param attempts     this child's attempts on this lesson, in any order
     * @param marks        her marks on this lesson by stop id; the lesson-level override is `lessonMark`
     */
    public static Score of(String childId, String lessonId, Map<Integer, List<Stop>> stopsByLevel,
                           List<AttemptEntity> attempts, Map<String, Mark> marks, Mark lessonMark) {
        return of(childId, lessonId, stopsByLevel, attempts, marks, lessonMark, false);
    }

    /**
     * The same, with `fixedPaper` for an exam: every question of the paper counts, so one she never reached scores
     * 0 instead of being left out of the average.
     */
    public static Score of(String childId, String lessonId, Map<Integer, List<Stop>> stopsByLevel,
                           List<AttemptEntity> attempts, Map<String, Mark> marks, Mark lessonMark, boolean fixedPaper) {
        var byStop = new LinkedHashMap<String, List<AttemptEntity>>();
        for (var a : attempts) byStop.computeIfAbsent(a.getStopId(), k -> new ArrayList<>()).add(a);
        byStop.values().forEach(list -> list.sort(Comparator.comparingInt(AttemptEntity::getAttemptNumber)
                .thenComparing(AttemptEntity::getAnsweredAt)));

        int scoredLevel = 0, levelReached = 0;
        for (var level : stopsByLevel.keySet()) {
            var stops = stopsByLevel.get(level);
            if (stops.stream().anyMatch(s -> byStop.containsKey(s.getId()))) scoredLevel = Math.max(scoredLevel, level);
            if (!stops.isEmpty() && stops.stream().allMatch(s -> byStop.containsKey(s.getId()))) levelReached = Math.max(levelReached, level);
        }
        Integer override = lessonMark == null ? null : lessonMark.score();
        if (scoredLevel == 0)
            return new Score(childId, lessonId, false, 0, 0, null, override, override,
                    override == null ? null : Bands.band(override), 0, 0, 0, 0, 0, 0, List.of());

        var outcomes = new ArrayList<StopOutcome>();
        int starsEarned = 0, starsTotal = 0, answered = 0, total = 0, needsMarking = 0;
        double sum = 0; int scored = 0;
        for (Stop stop : stopsByLevel.get(scoredLevel)) {
            var mine = byStop.getOrDefault(stop.getId(), List.of());
            boolean attemptedIt = !mine.isEmpty();
            int best = mine.stream().mapToInt(AttemptEntity::getStars).max().orElse(0);
            total++; starsTotal += 3; starsEarned += best;
            if (attemptedIt) answered++;
            if (stop.getCategory() == StopCategory.INFO) continue;             // not a question: completion only

            boolean open = stop.getCategory() == StopCategory.OPEN;
            var mark = marks.get(stop.getId());
            Boolean firstTry = attemptedIt && stop.getCategory() == StopCategory.SINGLE ? mine.getFirst().isCorrect() : null;
            Integer stopScore = null;
            boolean waiting = false;
            if (open) {
                if (mark != null && mark.stars() != null) stopScore = Bands.forStars(mark.stars());
                else if (attemptedIt) waiting = true;
            } else if (attemptedIt) {
                stopScore = stop.getCategory() == StopCategory.SINGLE
                        ? (Boolean.TRUE.equals(firstTry) ? Bands.CORRECT : Bands.WRONG)
                        : Bands.forStars(best);
            }
            // A paper's unanswered question is a zero; a homework's is simply not part of "how well did she do".
            if (stopScore == null && !waiting && fixedPaper) stopScore = Bands.WRONG;
            if (waiting) needsMarking++;
            if (stopScore != null) { sum += stopScore; scored++; }
            outcomes.add(new StopOutcome(stop.getId(), stop.getTitle(), stop.getType(), scoredLevel, open, attemptedIt,
                    firstTry, best, mine.size(), attemptedIt ? Bands.forStars(best) : null, stopScore,
                    mark == null ? null : mark.stars(), mark == null ? null : mark.comment(), waiting));
        }
        Integer auto = scored == 0 ? null : (int) Math.round(sum / scored);
        Integer effective = override != null ? override : auto;
        return new Score(childId, lessonId, true, levelReached, scoredLevel, auto, override, effective,
                effective == null ? null : Bands.band(effective), starsEarned, starsTotal, answered, total,
                total == 0 ? 0 : (int) Math.round(answered * 100.0 / total), needsMarking, List.copyOf(outcomes));
    }

    /**
     * The stops a level is scored over: its own, with an exit ticket replaced by the questions inside it. The ticket
     * is a container — the child answers its questions and the attempts carry their ids, so scoring the wrapper
     * would score a stop nobody ever answers.
     */
    public static List<Stop> scorable(List<Stop> stops) {
        var out = new ArrayList<Stop>();
        for (Stop s : stops) {
            if (s instanceof Stop.ExitTicket ticket) out.addAll(ticket.getQuestions());
            else out.add(s);
        }
        return out;
    }
}
