package quest.server.children;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import quest.api.dto.AttemptUpload;
import quest.api.dto.Play;
import quest.api.dto.Stop;
import quest.server.config.ApiException;
import quest.server.content.LessonRepository;
import quest.server.content.LessonStore;
import quest.server.grading.AnswerKey;

/**
 * Stores uploaded attempts (idempotent by id) and derives the child's stop / lesson completions,
 * streak and stickers from them — the same rules `FakeContentApi` applies on-device.
 */
@Service
public class AttemptService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AttemptService.class);
    private final AttemptRepository attempts; private final StopCompletionRepository stopCompletions; private final LessonCompletionRepository lessonCompletions;
    private final StreakRepository streaks; private final StickerRepository stickers; private final LessonRepository lessons; private final LessonStore store;
    private final quest.server.exams.ExamAttemptService exams; private final quest.server.config.Json json; private final quest.server.grading.PaperSeals seals;

    public AttemptService(AttemptRepository attempts, StopCompletionRepository stopCompletions, LessonCompletionRepository lessonCompletions, StreakRepository streaks, StickerRepository stickers, LessonRepository lessons, LessonStore store, quest.server.exams.ExamAttemptService exams, quest.server.config.Json json, quest.server.grading.PaperSeals seals) {
        this.attempts = attempts; this.stopCompletions = stopCompletions; this.lessonCompletions = lessonCompletions; this.streaks = streaks; this.stickers = stickers; this.lessons = lessons; this.store = store; this.exams = exams; this.json = json; this.seals = seals;
    }

    /**
     * N4.3 (§8): an upload naming an exam goes through {@link quest.server.exams.ExamAttemptService} first — the
     * window and the one-sitting rule are checked for the whole batch <strong>before</strong> a row is written, so
     * a refused upload leaves nothing behind, and the sitting is handed in afterwards if the paper is now complete.
     * A homework batch does not touch it and costs nothing.
     *
     * <p><strong>B3 (D2): an exam answer is graded here, not on the tablet.</strong> The client's `correct`, `stars`,
     * `attemptNumber` and `mistakes` are ignored for it: {@link AnswerKey} grades the answer against the stop of the
     * paper the server derived, a question with no machine-checkable key is stored unscored for the teacher to mark,
     * a second answer to a question she already answered is dropped (the first answer is the answer — V31's unique
     * `exam_key` holds that across concurrent uploads too), and an answer
     * naming a stop that is not on the paper is not an answer to this exam. A <strong>homework</strong> attempt keeps
     * the app's values — retries, hints and stars-by-mistakes happen on the device and older apps send no answer for
     * some stops — but a single-answer one is cross-checked against the key and a disagreement is logged.
     */
    @Transactional
    public int record(Entities.ChildEntity child, List<AttemptUpload> uploads) {
        int accepted = 0;
        Map<String, Instant> touchedLessons = new HashMap<>();
        var lessonIds = uploads.stream().map(AttemptUpload::getLessonId).distinct().toList();
        for (var lessonId : lessonIds) requireSameSchool(child, lessonId);
        var now = exams.now();
        var sittings = exams.open(child, lessonIds, now);
        Map<String, quest.server.exams.ExamAttemptService.Paper> papers = new HashMap<>();
        Map<String, Map<String, Stop>> homework = new HashMap<>();
        for (var a : uploads) {
            if (attempts.existsById(a.getId())) continue;
            var at = Instant.ofEpochMilli(a.getAnsweredAt());
            var sitting = sittings.get(a.getLessonId());
            if (sitting != null) {
                var paper = papers.computeIfAbsent(a.getLessonId(), id -> exams.paper(child, sitting));
                var stop = paper.stops().get(a.getStopId());
                if (stop == null) continue;
                // the ids of her parent's copy of the paper, whether it was sealed when it was downloaded or not
                var seal = seals.of(child.getParentId(), a.getLessonId());
                var rows = new ArrayList<Entities.AttemptEntity>();
                rows.add(examRow(a, child, stop, a.getId(), a.getAnswerJson(), seal, at));
                // Today's app answers an exit ticket with one attempt on the wrapper and an empty answer. A question
                // whose answer the wrapper carries (`{"<questionId>":"<answer>",…}`) is graded as its own attempt; one
                // it does not carry is stored as PENDING — waiting for the teacher's mark, never a zero for the child.
                if (stop instanceof Stop.ExitTicket ticket) {
                    var perQuestion = questionAnswers(a.getAnswerJson());
                    for (Stop q : ticket.getQuestions())
                        rows.add(examRow(a, child, q, a.getId() + ":" + q.getId(), perQuestion.getOrDefault(q.getId(), AnswerKey.PENDING), seal, at));
                }
                for (var row : rows) {
                    if (!paper.answered().add(row.getStopId()) || attempts.insertExamAnswer(row) == 0) continue;
                    if (row.getId().equals(a.getId())) accepted++;
                    completed(child, row, at, touchedLessons);
                }
            } else {
                crossCheck(a, homework.computeIfAbsent(a.getLessonId(), this::stopsOf));
                var e = new Entities.AttemptEntity();
                e.setId(a.getId()); e.setChildId(child.getId()); e.setStopId(a.getStopId()); e.setLessonId(a.getLessonId()); e.setLevel(a.getLevel());
                e.setAnswerJson(a.getAnswerJson()); e.setAnsweredAt(at);
                e.setCorrect(a.getCorrect()); e.setAttemptNumber(a.getAttemptNumber()); e.setMistakes(a.getMistakes()); e.setStars(a.getStars());
                attempts.save(e); accepted++;
                completed(child, e, at, touchedLessons);
            }
        }
        for (var entry : touchedLessons.entrySet()) { deriveLessonCompletions(child, entry.getKey(), entry.getValue()); touchStreak(child, entry.getValue()); }
        exams.settle(child, sittings, now);
        return accepted;
    }

    /**
     * One exam answer as the server grades it — the app's `correct`, `stars`, `attemptNumber` and `mistakes` play no
     * part — carrying the `exam_key` that makes it the only answer to that question (V31).
     */
    private static Entities.AttemptEntity examRow(AttemptUpload a, Entities.ChildEntity child, Stop stop, String id, String answer, quest.server.grading.PaperSeal seal, Instant at) {
        var graded = AnswerKey.PENDING.equals(answer) ? new AnswerKey.Graded(AnswerKey.Kind.UNKEYED, false, 0, 0) : AnswerKey.grade(stop, answer, seal);
        var e = new Entities.AttemptEntity();
        e.setId(id); e.setChildId(child.getId()); e.setStopId(stop.getId()); e.setLessonId(a.getLessonId()); e.setLevel(a.getLevel());
        e.setAnswerJson(answer == null ? "" : answer); e.setAnsweredAt(at);
        e.setCorrect(graded.correct()); e.setAttemptNumber(1); e.setMistakes(graded.mistakes()); e.setStars(graded.stars());
        e.setExamKey(child.getId() + "|" + a.getLessonId() + "|" + stop.getId());
        return e;
    }

    /** `{"q1":"a","q2":"true"}` → its string values by question id; anything else → none. */
    private Map<String, String> questionAnswers(String answer) {
        Map<String, String> out = new HashMap<>();
        if (answer == null || !answer.trim().startsWith("{")) return out;
        try { json.tree(answer).properties().forEach(f -> { if (f.getValue().isTextual()) out.put(f.getKey(), f.getValue().asText()); }); }
        catch (IllegalArgumentException malformed) { return Map.of(); }
        return out;
    }

    /** The stop's completion and the lesson it touches, after one stored attempt. */
    private void completed(Entities.ChildEntity child, Entities.AttemptEntity e, Instant at, Map<String, Instant> touchedLessons) {
        touchedLessons.merge(e.getLessonId(), at, (x, y) -> x.isAfter(y) ? x : y);
        var sc = stopCompletions.findById(new Entities.StopCompletionId(child.getId(), e.getStopId())).orElse(null);
        if (sc == null || sc.getStars() < e.getStars()) {
            if (sc == null) { sc = new Entities.StopCompletionEntity(); sc.setChildId(child.getId()); sc.setStopId(e.getStopId()); }
            sc.setLessonId(e.getLessonId()); sc.setLevel(e.getLevel()); sc.setStars(e.getStars()); sc.setCompletedAt(at);
            stopCompletions.save(sc);
        }
    }

    /** Every stop of a homework's plays (its "Again" variant included) by id; empty for an id no lesson has. */
    private Map<String, Stop> stopsOf(String lessonId) {
        var all = new ArrayList<Stop>();
        for (var pe : store.plays(lessonId)) all.addAll(store.play(pe).getStops());
        return AnswerKey.index(all);
    }

    /**
     * B3: a homework single-answer attempt whose answer is one of the stop's options is checked against the key, and a
     * disagreement is logged rather than corrected — the data that decides whether homework can be graded on the
     * server too without marking a released app's honest answers wrong.
     */
    private static void crossCheck(AttemptUpload a, Map<String, Stop> stops) {
        if (!(stops.get(a.getStopId()) instanceof Stop.SingleAnswer stop)) return;
        String answer = a.getAnswerJson() == null ? "" : a.getAnswerJson().trim();
        if (!stop.getOptionIds().contains(answer)) return;
        boolean key = stop.getCorrectId().equals(answer);
        if (key != a.getCorrect())
            log.warn("attempt {} on stop {}: the app reports correct={}, the answer key says {}", a.getId(), a.getStopId(), a.getCorrect(), key);
    }

    /**
     * A parent carries no school scope (she scopes by `parent_id`), so nothing but this stops an upload from naming a
     * lesson of another school: the rows would link her child to that school's lesson and be counted into its reports.
     * Checked for the whole batch before anything is written. An id no lesson has is left alone — it is stored and
     * simply derives no completion, as before.
     */
    private void requireSameSchool(Entities.ChildEntity child, String lessonId) {
        var lesson = lessons.findOneById(lessonId).orElse(null);
        if (lesson == null || lesson.getSchoolId() == null || child.getSchoolId() == null) return;
        if (!lesson.getSchoolId().equals(child.getSchoolId())) throw ApiException.forbidden("That lesson belongs to another school.");
    }

    private void deriveLessonCompletions(Entities.ChildEntity child, String lessonId, Instant at) {
        var lesson = lessons.findById(lessonId).orElse(null);
        if (lesson == null) return;
        var mine = attempts.findByChildIdOrderByAnsweredAtDesc(child.getId()).stream().filter(a -> a.getLessonId().equals(lessonId)).toList();
        for (var pe : store.plays(lessonId)) {
            Play play = store.play(pe);
            int stars = 0, twoPlus = 0, n = play.getStops().size(); boolean complete = true;
            for (var stop : play.getStops()) {
                var best = mine.stream().filter(a -> a.getStopId().equals(stop.getId()) && a.getLevel() == play.getLevel()).mapToInt(Entities.AttemptEntity::getStars).max();
                if (best.isEmpty()) { complete = false; break; }
                stars += best.getAsInt(); if (best.getAsInt() >= 2) twoPlus++;
            }
            if (!complete || pe.getVariant() != 0) continue;
            var id = new Entities.LessonCompletionId(child.getId(), lessonId, play.getLevel());
            var lc = lessonCompletions.findById(id).orElseGet(() -> { var x = new Entities.LessonCompletionEntity(); x.setChildId(child.getId()); x.setLessonId(lessonId); x.setLevel(play.getLevel()); x.setCompletedAt(at); return x; });
            boolean first = lc.getStarsTotal() == 0;
            lc.setStarsEarned(Math.max(lc.getStarsEarned(), stars)); lc.setStarsTotal(n * 3); lc.setMostStopsTwoStars(lc.isMostStopsTwoStars() || twoPlus * 2 > n);
            lessonCompletions.save(lc);
            if (first) awardSticker(child, "lesson-" + lessonId + "-L" + play.getLevel(), at);
        }
    }

    private void awardSticker(Entities.ChildEntity child, String key, Instant at) {
        if (stickers.findByChildIdOrderByEarnedAt(child.getId()).stream().anyMatch(s -> s.getStickerKey().equals(key))) return;
        var s = new Entities.StickerEntity(); s.setId(UUID.randomUUID().toString()); s.setChildId(child.getId()); s.setStickerKey(key); s.setEarnedAt(at);
        stickers.save(s);
    }

    private void touchStreak(Entities.ChildEntity child, Instant at) {
        LocalDate day = at.atZone(ZoneOffset.UTC).toLocalDate();
        var s = streaks.findById(child.getId()).orElseGet(() -> { var x = new Entities.StreakEntity(); x.setChildId(child.getId()); return x; });
        var last = s.getLastPlayedDate();
        if (last == null || day.isAfter(last.plusDays(1))) s.setCurrentDays(1);
        else if (day.equals(last.plusDays(1))) s.setCurrentDays(s.getCurrentDays() + 1);
        if (last == null || day.isAfter(last)) s.setLastPlayedDate(day);
        streaks.save(s);
    }
}
