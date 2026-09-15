package quest.server.analysis;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
 *   upload → analyze → [admin confirms skills] → generate L1 → L2 → L3 → Again → panel → review
 */
@Service
public class LessonPipeline {
    private static final Logger log = LoggerFactory.getLogger(LessonPipeline.class);
    private final LessonRepository lessons; private final SourceFileRepository files; private final AnalysisService analysis; private final GenerationService generation; private final LessonState state; private final LessonSteps steps;
    /** Test hook: `quest.pipeline.fail-once-at=generate_L2` makes that step fail the first time it runs for each lesson. */
    private final String failOnceAt; private final Set<String> failed = ConcurrentHashMap.newKeySet();

    public LessonPipeline(LessonRepository lessons, SourceFileRepository files, AnalysisService analysis, GenerationService generation, LessonState state, LessonSteps steps, @Value("${quest.pipeline.fail-once-at:}") String failOnceAt) {
        this.lessons = lessons; this.files = files; this.analysis = analysis; this.generation = generation; this.state = state; this.steps = steps; this.failOnceAt = failOnceAt == null ? "" : failOnceAt.trim();
    }

    @Async public void analyzeAsync(String lessonId) { runFrom(lessonId, PipelineStep.UPLOAD, true); }
    @Async public void generateAsync(String lessonId) { runFrom(lessonId, PipelineStep.GENERATE_L1, true); }
    /** Retry and continue: from the first step in error (else the first pending one) through the rest. */
    @Async public void retryAsync(String lessonId) { runFrom(lessonId, firstToRun(lessonId), true); }
    /** Retry this step only: the remaining steps wait for "Retry and continue". */
    @Async public void retryStepAsync(String lessonId, PipelineStep step) { runFrom(lessonId, step, false); }

    /** The step a retry starts from; throws when there is nothing to run. */
    public PipelineStep firstToRun(String lessonId) {
        steps.ensure(lessonId);
        var list = steps.list(lessonId);
        for (var e : list) if ("error".equals(e.getStatus())) return LessonSteps.parse(e.getStep());
        for (var e : list) if (!"done".equals(e.getStatus())) return LessonSteps.parse(e.getStep());
        throw ApiException.badRequest("Every step is already done.");
    }

    private void runFrom(String lessonId, PipelineStep from, boolean continueAfter) {
        steps.ensure(lessonId);
        var lesson = lessons.findById(lessonId).orElse(null);
        if (lesson == null) return;
        try {
            for (PipelineStep step : LessonSteps.ORDER) {
                if (step.ordinal() < from.ordinal()) continue;
                if (steps.isDone(lessonId, step)) { if (!continueAfter && step == from) { finish(lessonId, false); return; } continue; }
                if (step == PipelineStep.SKILLS) { state.set(lessonId, LessonStatus.NEEDS_REVIEW); return; }   // the admin's turn
                state.set(lessonId, step.ordinal() <= PipelineStep.ANALYZE.ordinal() ? LessonStatus.ANALYZING : LessonStatus.GENERATING);
                final LessonEntity current = lessons.findById(lessonId).orElseThrow(() -> new LessonSteps.Stop("lesson deleted"));
                boolean ok = steps.run(lessonId, step, () -> { failOnce(lessonId, step); body(current, step); });
                if (!ok) { var row = steps.get(lessonId, step).orElseThrow(); state.fail(lessonId, row.getErrorCode(), row.getErrorMessage()); return; }
                if (step == PipelineStep.ANALYZE && !steps.isDone(lessonId, PipelineStep.SKILLS)) { state.set(lessonId, LessonStatus.NEEDS_REVIEW); return; }
                if (!continueAfter) { finish(lessonId, false); return; }
            }
            finish(lessonId, true);
        } catch (LessonSteps.Stop e) { log.info("pipeline for {} stopped: {}", lessonId, e.getMessage()); }
        catch (RuntimeException e) { log.error("pipeline for {} crashed", lessonId, e); state.fail(lessonId, "model_failed", LessonSteps.Messages.of("model_failed", e.getMessage())); }
    }

    private void body(LessonEntity lesson, PipelineStep step) {
        switch (step) {
            case UPLOAD -> { if (files.findByLessonIdOrderByCreatedAt(lesson.getId()).stream().noneMatch(f -> f.getDeletedAt() == null)) throw ApiException.badRequest("Upload the slides first."); }
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

    private void failOnce(String lessonId, PipelineStep step) {
        if (!failOnceAt.isEmpty() && failOnceAt.equals(LessonSteps.stepName(step)) && failed.add(lessonId + ":" + step)) throw new ApiException(org.springframework.http.HttpStatus.BAD_GATEWAY, "model_failed", "injected failure at " + failOnceAt);
    }

    /** Manual lessons: Prompt A on the admin's text, then the missing levels and the panel (no step ledger — one job). */
    @Async
    public void generateFromTextAsync(String lessonId, String text) {
        try {
            var lesson = lessons.findById(lessonId).orElseThrow();
            analysis.analyzeText(lesson, text);
            generation.generateMissing(lessons.findById(lessonId).orElseThrow());
            state.set(lessonId, LessonStatus.REVIEW);
        } catch (ApiException e) { log.warn("text generation for {} failed: {}", lessonId, e.getMessage()); state.fail(lessonId, e.error().code(), LessonSteps.Messages.of(e.error().code(), e.error().message())); }
        catch (LessonSteps.TransientFailure e) { state.fail(lessonId, "model_unavailable", LessonSteps.Messages.of("model_unavailable", e.getMessage())); }
        catch (Exception e) { log.error("text generation for {} crashed", lessonId, e); state.fail(lessonId, "model_failed", LessonSteps.Messages.of("model_failed", e.getMessage())); }
    }
}
