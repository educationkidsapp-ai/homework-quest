package quest.server.analysis;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import quest.api.PipelineStep;
import quest.api.dto.LessonStatus;
import quest.server.config.ApiException;
import quest.server.content.Entities.LessonEntity;
import quest.server.content.LessonRepository;
import quest.server.content.SourceFileRepository;

/**
 * The background jobs the admin panel polls for. A lesson is a pipeline of steps ({@link LessonSteps}); every job
 * walks the steps in order, skips the ones already done (cache-first, so a retry never repeats a paid model call)
 * and stops at the first error, which the admin retries from where it failed.
 *
 *   upload → analyze → [admin confirms skills] → (L1 ‖ L2 ‖ L3) → (Again ‖ panel) → review
 *
 * <p>D25: the generate steps run in the two batches above — the levels depend only on the analysis and the
 * confirmed skills, and Again and the panel only on the levels — so the phase costs two step deadlines rather
 * than five. Each step keeps its own ledger row, deadline and retries; see {@link #BATCHES}.
 */
@Service
public class LessonPipeline {
    private static final Logger log = LoggerFactory.getLogger(LessonPipeline.class);
    private final LessonRepository lessons; private final SourceFileRepository files; private final AnalysisService analysis; private final ConversionService conversion; private final GenerationService generation; private final LessonState state; private final LessonSteps steps;
    private final quest.server.content.SkillRepository skills; private final quest.server.content.PlayRepository plays; private final quest.server.content.ParentPanelRepository panels; private final AnalysisCacheRepository analyses; private final quest.server.content.LessonStore store;
    /** Test hook: `quest.pipeline.fail-once-at=generate_L2` makes that step fail the first time it runs for each lesson. */
    private final String failOnceAt; private final Set<String> failed = ConcurrentHashMap.newKeySet();
    /** Test hook: `quest.pipeline.hang-at=generate_L3` makes that step sleep until its deadline interrupts it. */
    private final String hangAt;
    /**
     * The lessons this instance is running a job for, right now. It is the only thing that can answer "is anybody
     * still working on this?", and it answers it for <em>this</em> instance only — which is exactly the question
     * {@link quest.server.admin.AdminLessonService}'s "Wait for the current job to finish" should have been asking.
     * A job whose instance was recycled leaves no entry here, so it is no longer in anybody's way; what remains of
     * it in the database is the watchdog's to clean up.
     */
    private final Set<String> live = ConcurrentHashMap.newKeySet();

    public LessonPipeline(LessonRepository lessons, SourceFileRepository files, AnalysisService analysis, ConversionService conversion, GenerationService generation, LessonState state, LessonSteps steps, @Value("${quest.pipeline.fail-once-at:}") String failOnceAt,
                          @Value("${quest.pipeline.hang-at:}") String hangAt,
                          quest.server.content.SkillRepository skills, quest.server.content.PlayRepository plays, quest.server.content.ParentPanelRepository panels, AnalysisCacheRepository analyses, quest.server.content.LessonStore store) {
        this.lessons = lessons; this.files = files; this.analysis = analysis; this.conversion = conversion; this.generation = generation; this.state = state; this.steps = steps; this.failOnceAt = failOnceAt == null ? "" : failOnceAt.trim(); this.hangAt = hangAt == null ? "" : hangAt.trim();
        this.skills = skills; this.plays = plays; this.panels = panels; this.analyses = analyses; this.store = store;
    }

    /**
     * Lessons created before the step ledger existed have no rows: derive them from what is already there, so a
     * retry resumes at the real failure instead of walking from upload (and asking to confirm the skills again).
     */
    public void backfill(String lessonId) {
        if (!steps.list(lessonId).isEmpty()) return;
        var lesson = lessons.findById(lessonId).orElse(null); if (lesson == null) return;
        steps.ensure(lessonId);
        boolean hasFiles = files.findByLessonIdOrderByCreatedAt(lessonId).stream().anyMatch(f -> f.getDeletedAt() == null);
        boolean analysed = lesson.getSourceHash() != null && analyses.existsById(quest.api.CacheKeys.INSTANCE.analysisKey(lesson.getSourceHash()));
        boolean confirmed = !skills.findByLessonIdAndConfirmedTrueOrderByPosition(lessonId).isEmpty();
        if (hasFiles) steps.done(lessonId, PipelineStep.UPLOAD);
        // CR4: a lesson analysed before the Convert step existed keeps its analysis — it was paid for, and
        // re-converting would not change it — so Convert counts as done exactly when Analyse does.
        boolean converted = files.findByLessonIdOrderByCreatedAt(lessonId).stream().filter(f -> f.getDeletedAt() == null).allMatch(f -> "ready".equals(f.getConvertStatus()));
        if (hasFiles && (analysed || converted)) steps.done(lessonId, PipelineStep.CONVERT);
        if (hasFiles && analysed) steps.done(lessonId, PipelineStep.ANALYZE);
        if (hasFiles && analysed && confirmed) { steps.done(lessonId, PipelineStep.SKILLS); markExistingWork(lessonId); }
        // the recorded error belongs to the first step that is not done
        if (lesson.getErrorCode() != null) for (var e : steps.list(lessonId)) if (!"done".equals(e.getStatus())) { steps.mark(lessonId, LessonSteps.parse(e.getStep()), "error", lesson.getErrorCode(), lesson.getErrorMessage()); break; }
    }

    @Async public void analyzeAsync(String lessonId) { runFrom(lessonId, PipelineStep.UPLOAD, true); }
    @Async public void generateAsync(String lessonId) { runFrom(lessonId, PipelineStep.GENERATE_L1, true); }
    /** Retry and continue: from the first step in error (else the first pending one) through the rest. */
    @Async public void retryAsync(String lessonId) { runFrom(lessonId, firstToRun(lessonId), true); }
    /** Retry this step only: the remaining steps wait for "Retry and continue". */
    @Async public void retryStepAsync(String lessonId, PipelineStep step) { runFrom(lessonId, step, false); }

    /** The step a retry starts from; throws when there is nothing to run. */
    public PipelineStep firstToRun(String lessonId) {
        backfill(lessonId);
        steps.ensure(lessonId);
        var list = steps.list(lessonId);
        for (var e : list) if ("error".equals(e.getStatus())) return LessonSteps.parse(e.getStep());
        for (var e : list) if (!"done".equals(e.getStatus())) return LessonSteps.parse(e.getStep());
        throw ApiException.badRequest("Every step is already done.");
    }

    /** True while a job for this lesson is running <em>in this instance</em>. */
    public boolean isLive(String lessonId) { return live.contains(lessonId); }

    private void runFrom(String lessonId, PipelineStep from, boolean continueAfter) {
        steps.ensure(lessonId);
        var lesson = lessons.findById(lessonId).orElse(null);
        if (lesson == null) return;
        live.add(lessonId);
        try {
            // "Retry this step only" is one step and never a batch: the strip's other rows stay where they are.
            if (!continueAfter) { if (!runOne(lessonId, from)) return; finish(lessonId, false); return; }
            for (PipelineStep step : LessonSteps.ORDER) {
                if (step.ordinal() < from.ordinal()) continue;
                var batch = BATCHES.stream().filter(b -> b.contains(step)).findFirst().orElse(null);
                if (batch == null) { if (!runOne(lessonId, step)) return; continue; }
                if (step != batch.getFirst() && step.ordinal() > from.ordinal()) continue;      // its batch already ran
                var pending = batch.stream().filter(s -> s.ordinal() >= from.ordinal() && !steps.isDone(lessonId, s)).toList();
                if (pending.isEmpty()) continue;
                state.set(lessonId, LessonStatus.GENERATING);
                var failed = runBatch(lessonId, pending);
                if (failed != null) { var row = steps.get(lessonId, failed).orElseThrow(); state.fail(lessonId, row.getErrorCode(), row.getErrorMessage()); return; }
            }
            finish(lessonId, true);
        } catch (LessonSteps.Stop e) { log.info("pipeline for {} stopped: {}", lessonId, e.getMessage()); }
        catch (RuntimeException e) { log.error("pipeline for {} crashed", lessonId, e); state.fail(lessonId, "model_failed", LessonSteps.Messages.of("model_failed", e.getMessage())); }
        finally { live.remove(lessonId); }
    }

    /**
     * D25: the generate phase in two batches. L1, L2 and L3 read the same analysis and the same confirmed skills and
     * write three different plays, so nothing orders them; Again needs the stored Level 1 (it excludes its ids) and
     * the panel needs all three, so they wait — and then run together, being independent of each other in turn. The
     * worst case for the phase is two step deadlines instead of five.
     */
    static final List<List<PipelineStep>> BATCHES = List.of(
            List.of(PipelineStep.GENERATE_L1, PipelineStep.GENERATE_L2, PipelineStep.GENERATE_L3),
            List.of(PipelineStep.GENERATE_AGAIN, PipelineStep.PANEL));

    /**
     * One step of the sequential part of the walk. False means "stop here": the step is the admin's to do, or the
     * analysis is waiting for her, or it failed — and the lesson's status already says which.
     */
    private boolean runOne(String lessonId, PipelineStep step) {
        if (steps.isDone(lessonId, step)) return true;
        if (step == PipelineStep.SKILLS) { state.set(lessonId, LessonStatus.NEEDS_REVIEW); return false; }   // the admin's turn
        state.set(lessonId, step.ordinal() <= PipelineStep.ANALYZE.ordinal() ? LessonStatus.ANALYZING : LessonStatus.GENERATING);
        if (!run(lessonId, step)) { var row = steps.get(lessonId, step).orElseThrow(); state.fail(lessonId, row.getErrorCode(), row.getErrorMessage()); return false; }
        if (step == PipelineStep.ANALYZE && !steps.isDone(lessonId, PipelineStep.SKILLS)) { state.set(lessonId, LessonStatus.NEEDS_REVIEW); return false; }
        return true;
    }

    private boolean run(String lessonId, PipelineStep step) {
        final LessonEntity current = lessons.findById(lessonId).orElseThrow(() -> new LessonSteps.Stop("lesson deleted"));
        return steps.run(lessonId, step, () -> { failOnce(lessonId, step); hang(step); body(current, step); });
    }

    /**
     * Runs a batch to the end and answers the first step (in pipeline order) that failed, or null. Every step keeps
     * its own ledger row, deadline, budget and retries — {@link LessonSteps#run} is unchanged and still bounds each
     * one on a virtual thread of its own — so an injected failure or hang inside a batch ends that step exactly as
     * it would alone, while the others finish.
     *
     * <p>Nothing of the caller's context is carried into these threads, and that is the point: a pipeline job has no
     * request behind it, {@link quest.server.tenancy.TenantContext} is a plain (non-inheritable) ThreadLocal that
     * nothing sets on the `@Async` thread, and so the job already runs with no school filter —
     * `TenantContext.unfilteredAllowed()` is what permits it. The batch threads inherit that same emptiness, and the
     * step bodies were running on threads of their own (`LessonSteps.bounded`) before this change anyway.
     */
    private PipelineStep runBatch(String lessonId, List<PipelineStep> batch) {
        if (batch.size() == 1) return run(lessonId, batch.getFirst()) ? null : batch.getFirst();
        var failed = ConcurrentHashMap.<PipelineStep>newKeySet();
        var crashed = new AtomicReference<RuntimeException>();
        var threads = new ArrayList<Thread>(batch.size());
        for (PipelineStep step : batch) threads.add(Thread.ofVirtual().name("pipeline-batch-" + LessonSteps.stepName(step) + "-" + lessonId)
                .start(() -> { try { if (!run(lessonId, step)) failed.add(step); } catch (RuntimeException e) { crashed.compareAndSet(null, e); } }));
        for (Thread t : threads)
            try { t.join(); } catch (InterruptedException e) { threads.forEach(Thread::interrupt); Thread.currentThread().interrupt(); throw new LessonSteps.Stop("the pipeline thread was interrupted"); }
        if (failed.isEmpty() && crashed.get() != null) throw crashed.get();
        return batch.stream().filter(failed::contains).findFirst().orElse(null);
    }

    private void body(LessonEntity lesson, PipelineStep step) {
        switch (step) {
            case UPLOAD -> { if (files.findByLessonIdOrderByCreatedAt(lesson.getId()).stream().noneMatch(f -> f.getDeletedAt() == null)) throw ApiException.badRequest("Upload the slides first."); }
            // CR4 §4: every active file becomes Markdown before Prompt A sees anything. One file's refusal fails the
            // step with that file's own code, which is the message the editor shows beside it.
            case CONVERT -> {
                int reused = 0, n = 0;
                for (var f : files.findByLessonIdOrderByCreatedAt(lesson.getId())) if (f.getDeletedAt() == null) { n++; if (conversion.convert(f).cacheHit()) reused++; }
                log.info("lesson {} converted {} file(s) to Markdown, {} reused from an identical upload", lesson.getId(), n, reused);
            }
            case ANALYZE -> analysis.analyze(lesson);
            case SKILLS -> { }
            case GENERATE_L1 -> generation.generatePlay(lesson, 1, 0);
            case GENERATE_L2 -> generation.generatePlay(lesson, 2, 0);
            case GENERATE_L3 -> generation.generatePlay(lesson, 3, 0);
            case GENERATE_AGAIN -> generation.generatePlay(lesson, 1, 1);
            case PANEL -> generation.generatePanel(lesson);
        }
    }

    /** All steps done → review; a single step retried with more to go → paused (the strip shows what is left). */
    private void finish(String lessonId, boolean ranToEnd) {
        boolean allDone = steps.list(lessonId).stream().allMatch(e -> "done".equals(e.getStatus()));
        state.set(lessonId, allDone ? LessonStatus.REVIEW : LessonStatus.PAUSED);
    }

    /** The hang the deadline exists for: a step that never returns on its own, ended only by the interrupt. */
    private void hang(PipelineStep step) {
        if (hangAt.isEmpty() || !hangAt.equals(LessonSteps.stepName(step))) return;
        // The interrupt the deadline sends is the only way out, and the step stops there rather than carrying on
        // into the real work — which is what an abandoned job must do.
        try { Thread.sleep(java.time.Duration.ofMinutes(30)); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new LessonSteps.Stop("abandoned at " + LessonSteps.stepName(step)); }
    }

    private void failOnce(String lessonId, PipelineStep step) {
        if (!failOnceAt.isEmpty() && failOnceAt.equals(LessonSteps.stepName(step)) && failed.add(lessonId + ":" + step)) throw new ApiException(org.springframework.http.HttpStatus.BAD_GATEWAY, "model_failed", "injected failure at " + failOnceAt);
    }

    /** The levels and the panel a lesson already has, marked done — nothing regenerates work that is already there. */
    private void markExistingWork(String lessonId) {
        for (var p : store.plays(lessonId)) if (!store.play(p).getStops().isEmpty()) {
            PipelineStep s = p.getVariant() == 1 ? PipelineStep.GENERATE_AGAIN : switch (p.getLevel()) { case 1 -> PipelineStep.GENERATE_L1; case 2 -> PipelineStep.GENERATE_L2; default -> PipelineStep.GENERATE_L3; };
            steps.done(lessonId, s);
        }
        if (panels.findById(lessonId).isPresent()) steps.done(lessonId, PipelineStep.PANEL);
    }

    /**
     * Manual lessons: Prompt A on the admin's text, then the missing levels and the panel.
     *
     * <p>This used to be the one job with no ledger and no deadline — which made it the last way left to strand a
     * lesson in `generating`: nothing bounded the model calls as a whole, and the watchdog could not age out a row
     * that was never written. It walks the same steps as an uploaded lesson now, so every call is inside a step
     * deadline and the admin panel's strip shows where it stopped. Upload and Convert are done by definition (the
     * admin typed the text, there is no file), the skills need no confirming because she chose the content herself,
     * and a level she wrote by hand is marked done so it is kept rather than regenerated, which is what the old
     * `generateMissing` did by hand.
     *
     * <p>A hand-written lesson still has no <em>Retry</em> — {@link quest.server.admin.AdminLessonService#retryStep}
     * refuses one, and that is unchanged. What the ledger buys is that the lesson leaves `generating` at all, so
     * pressing <em>Generate from text</em> again works, and the levels already written are not paid for twice.
     */
    @Async
    public void generateFromTextAsync(String lessonId, String text) {
        live.add(lessonId);
        try {
            steps.ensure(lessonId);
            steps.resetFrom(lessonId, PipelineStep.ANALYZE);              // this is new text: analyse it again
            steps.done(lessonId, PipelineStep.UPLOAD); steps.done(lessonId, PipelineStep.CONVERT);
            state.set(lessonId, LessonStatus.ANALYZING);
            if (!steps.run(lessonId, PipelineStep.ANALYZE, () -> analysis.analyzeText(lessons.findById(lessonId).orElseThrow(), text))) {
                var row = steps.get(lessonId, PipelineStep.ANALYZE).orElseThrow();
                state.fail(lessonId, row.getErrorCode(), row.getErrorMessage());
                return;
            }
            steps.done(lessonId, PipelineStep.SKILLS);
            markExistingWork(lessonId);
            // …and the levels and the panel are the ordinary pipeline, step by step and deadline by deadline.
            runFrom(lessonId, PipelineStep.GENERATE_L1, true);
        } catch (ApiException e) { log.warn("text generation for {} failed: {}", lessonId, e.getMessage()); state.fail(lessonId, e.error().code(), LessonSteps.Messages.of(e.error().code(), e.error().message())); }
        catch (RuntimeException e) { log.error("text generation for {} crashed", lessonId, e); state.fail(lessonId, "model_failed", LessonSteps.Messages.of("model_failed", e.getMessage())); }
        finally { live.remove(lessonId); }
    }
}
