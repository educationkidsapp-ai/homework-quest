package quest.server.analysis;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import quest.api.PipelineStep;
import quest.api.dto.LessonStatus;
import quest.server.content.Entities.LessonEntity;
import quest.server.content.Entities.LessonStepEntity;
import quest.server.content.LessonRepository;
import quest.server.content.LessonStepRepository;

/**
 * What finishes a job whose thread is gone.
 *
 * <p>{@link LessonSteps} can end a step that hangs, because it is holding it. Nothing in this process can end a step
 * whose process no longer exists — and on Cloud Run that is the ordinary case, not the exotic one: the service
 * scales to zero, an instance is recycled, a deploy rolls, and the `@Async` job goes with it. All that is left is a
 * `lesson_steps` row saying `running` and a lesson saying `generating`, which is what the admin panel polls, what
 * `Retry` refuses to touch and what `Delete` answers 409 to. The teacher has no way out of it, which is how one QA
 * lesson sat at `generate_L3` for three quarters of an hour.
 *
 * <p>So every {@code quest.pipeline.watchdog.interval-seconds} (and once at startup, where the leftovers of the
 * instance that just died are) this sweep looks for two shapes and ends both the same way a hung step ends — status
 * `error`, code {@link LessonSteps#TIMEOUT}, and the message that tells the teacher to retry:
 *
 * <ol>
 *   <li>a step marked `running` whose {@code updated_at} is older than its {@link PipelineDeadlines deadline};</li>
 *   <li>a lesson in {@code analyzing} or {@code generating} with no running step at all — the job died between two
 *       steps, so there is no row to age out, only a status nobody will ever move.</li>
 * </ol>
 *
 * <p><strong>It never takes work away from a live job.</strong> A lesson this instance is running is skipped outright
 * ({@link LessonPipeline#isLive}); one another instance may be running is protected by the deadline plus
 * {@link PipelineDeadlines#grace()}, which is longer than any healthy step takes to write its next row.
 *
 * <p><strong>Scope.</strong> The sweep has no caller and therefore no school ({@link quest.server.tenancy.TenantContext}
 * returns null on the scheduler's thread and the Hibernate filter stays off), exactly as `UploadRetention` does: a
 * stuck lesson has to be recovered whichever school it belongs to. It reads by status and writes only the two columns
 * above, never anything a tenant could name.
 */
@Component
public class PipelineWatchdog {
    private static final Logger log = LoggerFactory.getLogger(PipelineWatchdog.class);
    /** The statuses a job — and only a job — moves a lesson out of. */
    static final Set<String> TRANSIENT = Set.of(LessonState.name(LessonStatus.ANALYZING), LessonState.name(LessonStatus.GENERATING));

    private final LessonRepository lessons; private final LessonStepRepository stepRows; private final LessonSteps steps;
    private final LessonState state; private final LessonPipeline pipeline; private final PipelineDeadlines deadlines; private final boolean enabled;

    public PipelineWatchdog(LessonRepository lessons, LessonStepRepository stepRows, LessonSteps steps, LessonState state,
                            LessonPipeline pipeline, PipelineDeadlines deadlines,
                            @org.springframework.beans.factory.annotation.Value("${quest.pipeline.watchdog.enabled:true}") boolean enabled) {
        this.lessons = lessons; this.stepRows = stepRows; this.steps = steps; this.state = state;
        this.pipeline = pipeline; this.deadlines = deadlines; this.enabled = enabled;
    }

    /** The leftovers of the instance this one replaced. */
    @EventListener(ApplicationReadyEvent.class)
    public void sweepAtStartup() {
        if (!enabled) return;
        int n = sweep();
        if (n > 0) log.warn("startup: recovered {} lesson(s) left running by a previous instance", n);
    }

    @Scheduled(fixedDelayString = "${quest.pipeline.watchdog.interval-seconds:60}s", initialDelayString = "${quest.pipeline.watchdog.interval-seconds:60}s")
    public void sweepPeriodically() { if (enabled) sweep(); }

    /**
     * Marks every job that is provably dead as `error`/`timeout`; returns how many lessons it recovered. The
     * {@code enabled} switch is on the two entry points above, not here: turning the schedule off is an operator's
     * decision about when this runs, and a caller that asks for a sweep outright gets one.
     */
    public int sweep() {
        int recovered = 0;
        for (var row : stepRows.findByStatus("running")) if (expired(row)) { recover(row.getLessonId(), LessonSteps.parse(row.getStep())); recovered++; }
        for (var lesson : lessons.findByStatusIn(TRANSIENT)) if (stalled(lesson)) { recover(lesson.getId(), null); recovered++; }
        return recovered;
    }

    /**
     * True when this lesson has been left behind: the {@link quest.server.admin.AdminLessonService} guard asks this before refusing an
     * edit with "Wait for the current job to finish", so a teacher is never told to wait for a job that is gone. It
     * is the same test the sweep uses, which is the point — within one interval the sweep will have said so in the
     * ledger too.
     */
    public boolean looksStuck(LessonEntity lesson) {
        if (lesson == null || !TRANSIENT.contains(lesson.getStatus())) return false;
        if (pipeline.isLive(lesson.getId())) return false;
        var running = running(lesson.getId());
        return running == null ? older(lesson.getUpdatedAt(), deadlines.longest()) : expired(running);
    }

    // ---------------------------------------------------------------- internals

    private boolean expired(LessonStepEntity row) {
        return !pipeline.isLive(row.getLessonId()) && older(row.getUpdatedAt(), deadlines.of(LessonSteps.parse(row.getStep())));
    }

    /** A lesson stuck between two steps: the job died with nothing marked `running` to age out. */
    private boolean stalled(LessonEntity lesson) {
        return !pipeline.isLive(lesson.getId()) && running(lesson.getId()) == null && older(lesson.getUpdatedAt(), deadlines.longest());
    }

    private LessonStepEntity running(String lessonId) {
        List<LessonStepEntity> rows = stepRows.findByLessonIdOrderByPosition(lessonId);
        for (var r : rows) if ("running".equals(r.getStatus())) return r;
        return null;
    }

    private boolean older(Instant at, java.time.Duration deadline) {
        return at != null && at.isBefore(Instant.now().minus(deadline).minus(deadlines.grace()));
    }

    /**
     * The way out, identical to the one a step that overran its deadline in this process takes: the step (when there
     * is one) and then the lesson, so the strip shows where it stopped and the lesson is editable again.
     */
    private void recover(String lessonId, PipelineStep step) {
        String message = LessonSteps.Messages.of(LessonSteps.TIMEOUT, null);
        if (step != null) steps.mark(lessonId, step, "error", LessonSteps.TIMEOUT, message);
        state.fail(lessonId, LessonSteps.TIMEOUT, message);
        log.warn("watchdog: lesson {} was stuck at {} and is now error/timeout", lessonId, step == null ? "no step" : LessonSteps.stepName(step));
    }
}
