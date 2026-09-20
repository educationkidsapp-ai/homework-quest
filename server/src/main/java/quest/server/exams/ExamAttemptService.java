package quest.server.exams;

import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import quest.api.dto.ApiError;
import quest.api.dto.Stop;
import quest.server.children.AttemptRepository;
import quest.server.children.Entities.ChildEntity;
import quest.server.config.ApiException;
import quest.server.content.Entities.LessonEntity;
import quest.server.content.LessonRepository;

/**
 * §8's rules for the child's side of an exam, applied where the app already writes: `POST /children/{id}/attempts`.
 *
 * <p><strong>There is no "start the exam" call.</strong> The app has one write path for a played stop and §8's rule
 * is about the sitting rather than about a button, so the first upload naming an exam <em>is</em> the start and
 * creates the row. That also makes the rule impossible to skip: a player that forgot to announce itself would still
 * have to send the answers through here.
 *
 * <p>Three refusals, all 409 with a code the app can act on:
 * <ul>
 *   <li>{@link ApiError#EXAM_CLOSED} — before `opensAt` or after `closesAt`. The window is the server's clock, not
 *       the device's, because a child's tablet is the one clock a school cannot set.</li>
 *   <li>{@link ApiError#EXAM_ALREADY_TAKEN} — she has submitted it once. §8 allows exactly one sitting, and the
 *       teacher's re-opening is the only way back in. The same code answers the two-device race that
 *       {@link #begin} catches: the second tablet is told the paper is already being sat.</li>
 *   <li>nothing at all for a sitting already under way: §8's "if the child leaves mid-exam the attempt resumes where
 *       it stopped", which here means the same row taking more answers until the paper is complete.</li>
 * </ul>
 *
 * <p><strong>What "submitted" means.</strong> Not a button either: the sitting is submitted the moment every stop of
 * the paper {@link ExamPlays} derives has an attempt on it. That is the same completeness §7 scores over, so a
 * child whose exam the teacher can mark is exactly a child the results page calls submitted, and the time taken is
 * the honest span from her first answer to her last.
 */
@Service
public class ExamAttemptService {
    private final ExamSettingsRepository settings; private final ExamAttemptRepository sittings;
    private final LessonRepository lessons; private final AttemptRepository attempts; private final ExamPlays papers;
    private final java.time.Clock clock;

    public ExamAttemptService(ExamSettingsRepository settings, ExamAttemptRepository sittings, LessonRepository lessons,
                              AttemptRepository attempts, ExamPlays papers, java.time.Clock clock) {
        this.settings = settings; this.sittings = sittings; this.lessons = lessons; this.attempts = attempts;
        this.papers = papers; this.clock = clock;
    }

    /** The server's clock, which is the only one an exam window is read against — never the device's. */
    public Instant now() { return clock.instant(); }

    /** One exam a child may be in: the settings, her sitting (created if this is her first answer) and the lesson. */
    public record Sitting(LessonEntity lesson, Entities.ExamSettingsEntity exam, Entities.ExamAttemptEntity row) {}

    /**
     * Checked and opened <strong>before</strong> a single attempt row is written, for every exam the batch names —
     * a batch that would be refused must leave nothing behind, and an upload can carry answers to more than one
     * lesson.
     */
    @Transactional
    public Map<String, Sitting> open(ChildEntity child, Collection<String> lessonIds, Instant now) {
        var out = new LinkedHashMap<String, Sitting>();
        for (String lessonId : lessonIds) {
            var lesson = lessons.findOneById(lessonId).orElse(null);
            if (!ExamPlays.isExam(lesson)) continue;
            var exam = settings.findOneByLessonId(lessonId).orElse(null);
            if (exam == null) continue;                                          // an exam whose settings row is gone is a plain lesson
            var row = sittings.findOne(child.getId(), lessonId).orElse(null);
            if (!isOpenFor(exam, row, now))
                throw ApiException.conflict(ApiError.EXAM_CLOSED,
                        "\"" + title(lesson) + "\" is not open right now.");
            if (row != null && row.isSubmitted())
                throw ApiException.conflict(ApiError.EXAM_ALREADY_TAKEN,
                        "\"" + title(lesson) + "\" has already been handed in.");
            if (row == null) row = begin(lesson, child, now);
            row.setLastSeenAt(now);
            out.put(lessonId, new Sitting(lesson, exam, sittings.save(row)));
        }
        return out;
    }

    /**
     * Run once the attempts are stored: a sitting whose paper is now complete is handed in, with the time it took.
     *
     * <p>A paper that has no stops at all (an exam published before its plays were written) is never submitted —
     * "she answered all nothing of it" is not a finished exam, and the teacher's re-open is there for that case.
     */
    @Transactional
    public void settle(ChildEntity child, Map<String, Sitting> open, Instant now) {
        for (var sitting : open.values()) {
            var row = sitting.row();
            if (row.isSubmitted()) continue;
            var paper = papers.paperOf(sitting.lesson(), sitting.exam());
            if (paper.isEmpty()) continue;
            var answered = new HashSet<String>();
            for (var a : attempts.findByChildIdAndLessonIdIn(child.getId(), List.of(sitting.lesson().getId())))
                answered.add(a.getStopId());
            if (!paper.stream().map(Stop::getId).allMatch(answered::contains)) continue;
            row.setState(Entities.ExamAttemptEntity.SUBMITTED);
            row.setSubmittedAt(now);
            row.setSecondsTaken((int) Math.max(0, java.time.Duration.between(row.getStartedAt(), now).toSeconds()));
            sittings.save(row);
        }
    }

    /**
     * The row a sitting starts as, written and flushed at once.
     *
     * <p><strong>The flush is the point.</strong> Two devices can reach {@link #open} for the same child and the
     * same exam within the same millisecond; both read no row, both insert one, and the unique index on
     * `(child_id, lesson_id)` refuses the second — which is exactly what it is for. Without the flush that refusal
     * arrives while the transaction is committing, long after the handler has decided the upload was fine, and the
     * child's tablet gets a 500 with a constraint name in it. Flushing here turns the race into §8's own answer:
     * the exam is already being sat, on the device that won.
     *
     * <p>Package-private so `ExamApiTest` can force the losing side of the race, which is the only way to reach
     * this branch without two threads.
     */
    Entities.ExamAttemptEntity begin(LessonEntity lesson, ChildEntity child, Instant now) {
        var row = new Entities.ExamAttemptEntity();
        row.setId(UUID.randomUUID().toString()); row.setSchoolId(lesson.getSchoolId());
        row.setLessonId(lesson.getId()); row.setChildId(child.getId());
        row.setState(Entities.ExamAttemptEntity.STARTED); row.setStartedAt(now); row.setLastSeenAt(now);
        try {
            return sittings.saveAndFlush(row);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw ApiException.conflict(ApiError.EXAM_ALREADY_TAKEN,
                    "\"" + title(lesson) + "\" is already being sat on another device.");
        }
    }

    /**
     * The window as it applies to <em>this</em> child: the exam's own, extended by the teacher's re-opening when
     * she has been given one. A re-opening never moves the start — an exam nobody may sit yet is not opened early
     * for one child — only the end.
     */
    public static boolean isOpenFor(Entities.ExamSettingsEntity exam, Entities.ExamAttemptEntity row, Instant now) {
        if (now.isBefore(exam.getOpensAt())) return false;
        Instant closes = exam.getClosesAt();
        if (row != null && row.getReopenClosesAt() != null && row.getReopenClosesAt().isAfter(closes))
            closes = row.getReopenClosesAt();
        return now.isBefore(closes);
    }

    private static String title(LessonEntity lesson) {
        return lesson.getTitle() == null || lesson.getTitle().isBlank() ? "That exam" : lesson.getTitle();
    }
}
