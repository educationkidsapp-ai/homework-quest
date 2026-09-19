package quest.server.analysis;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import quest.api.PipelineStep;
import quest.server.config.ApiException;
import quest.server.content.Entities.LessonStepEntity;
import quest.server.content.LessonRepository;
import quest.server.content.LessonStepRepository;

/**
 * The per-step ledger of a lesson's pipeline (dev prompt: "errors never dead-end a lesson"). Every step row is
 * written in its own transaction so the admin panel sees progress while a job runs.
 *
 * {@link #run} executes one step: skips it when already done, retries transient failures ({@link TransientFailure},
 * an LLM 429/5xx/timeout, a bucket or LibreOffice hiccup) with exponential backoff, and marks permanent ones
 * (unreadable file, schema rejected twice, too large) as {@code error} at once with an actionable message.
 *
 * <p>Every attempt is bounded by the step's {@link PipelineDeadlines deadline}. Past it the step is abandoned and
 * marked {@code error}/{@code timeout} — a step that hangs is not a step that is working, and a lesson left
 * `generating` with nothing running is the one failure the admin panel gives no way out of.
 */
@Service
public class LessonSteps {
    private static final Logger log = LoggerFactory.getLogger(LessonSteps.class);
    /** Steps in pipeline order; SKILLS is the admin's confirmation and is never run by the server. */
    public static final List<PipelineStep> ORDER = List.of(PipelineStep.values());
    public static final int TRANSIENT_ATTEMPTS = 3;

    /** Thrown (or wrapped) by a step body when the failure is worth retrying. */
    public static class TransientFailure extends RuntimeException { public TransientFailure(String m, Throwable c) { super(m, c); } }
    /** Thrown when a step is aborted by an operator action (e.g. the lesson was deleted meanwhile). */
    public static class Stop extends RuntimeException { public Stop(String m) { super(m); } }
    /** Thrown when a step ran past its {@link PipelineDeadlines deadline} and was abandoned. */
    public static class Deadline extends RuntimeException { public Deadline(String m) { super(m); } }
    /** The error code a step that ran past its deadline carries, here and in the watchdog's sweep. */
    public static final String TIMEOUT = "timeout";

    private final LessonStepRepository steps; private final LessonRepository lessons; private final long retryDelayMs; private final PipelineDeadlines deadlines;

    public LessonSteps(LessonStepRepository steps, LessonRepository lessons, @Value("${quest.pipeline.retry-delay-ms:2000}") long retryDelayMs, PipelineDeadlines deadlines) {
        this.steps = steps; this.lessons = lessons; this.retryDelayMs = retryDelayMs; this.deadlines = deadlines;
    }

    public static String stepName(PipelineStep s) { return switch (s) { case UPLOAD -> "upload"; case CONVERT -> "convert"; case ANALYZE -> "analyze"; case SKILLS -> "skills"; case GENERATE_L1 -> "generate_L1"; case GENERATE_L2 -> "generate_L2"; case GENERATE_L3 -> "generate_L3"; case GENERATE_AGAIN -> "generate_again"; case PANEL -> "panel"; }; }
    public static PipelineStep parse(String name) { for (var s : ORDER) if (stepName(s).equals(name)) return s; throw ApiException.badRequest("Unknown step: " + name); }
    private static String id(String lessonId, PipelineStep s) { return lessonId + ":" + stepName(s); }

    public List<LessonStepEntity> list(String lessonId) { return steps.findByLessonIdOrderByPosition(lessonId); }

    /**
     * The ledgers of a whole page of lessons, grouped by lesson id — one query for the All lessons screen (§6 screen
     * 8) instead of one per row. Lessons with no ledger (manual ones) are simply absent from the map.
     */
    public java.util.Map<String, List<LessonStepEntity>> listAll(java.util.Collection<String> lessonIds) {
        if (lessonIds == null || lessonIds.isEmpty()) return java.util.Map.of();
        var out = new java.util.LinkedHashMap<String, List<LessonStepEntity>>();
        for (var step : steps.findByLessonIdInOrderByLessonIdAscPositionAsc(lessonIds))
            out.computeIfAbsent(step.getLessonId(), k -> new java.util.ArrayList<>()).add(step);
        return out;
    }
    public Optional<LessonStepEntity> get(String lessonId, PipelineStep s) { return steps.findById(id(lessonId, s)); }
    public boolean isDone(String lessonId, PipelineStep s) { return get(lessonId, s).map(e -> "done".equals(e.getStatus())).orElse(false); }

    /** Creates the ledger for an uploaded lesson (idempotent: existing rows keep their status). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void ensure(String lessonId) {
        int pos = 0;
        for (var s : ORDER) {
            if (steps.findById(id(lessonId, s)).isEmpty()) {
                var e = new LessonStepEntity(); e.setId(id(lessonId, s)); e.setLessonId(lessonId); e.setStep(stepName(s)); e.setPosition(pos); e.setStatus("pending"); e.setAttempt(0); e.setUpdatedAt(Instant.now());
                steps.save(e);
            }
            pos++;
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void mark(String lessonId, PipelineStep s, String status, String code, String message) {
        var e = steps.findById(id(lessonId, s)).orElseGet(() -> { ensure(lessonId); return steps.findById(id(lessonId, s)).orElseThrow(); });
        e.setStatus(status); e.setErrorCode(code); e.setErrorMessage(message); e.setUpdatedAt(Instant.now());
        if ("running".equals(status)) e.setAttempt(e.getAttempt() + 1);
        steps.save(e);
        lessons.findById(lessonId).ifPresent(l -> { l.setCurrentStep("running".equals(status) ? stepName(s) : null); lessons.save(l); });
    }

    public void done(String lessonId, PipelineStep s) { mark(lessonId, s, "done", null, null); }

    /** Resets the given steps (and everything after them in the pipeline) to pending, e.g. when the files are replaced. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void resetFrom(String lessonId, PipelineStep from) {
        ensure(lessonId);
        for (var e : steps.findByLessonIdOrderByPosition(lessonId))
            if (e.getPosition() >= from.ordinal()) { e.setStatus("pending"); e.setErrorCode(null); e.setErrorMessage(null); e.setUpdatedAt(Instant.now()); steps.save(e); }
    }

    /**
     * Runs one step to completion. Returns true when the step is done afterwards (was done, or succeeded now);
     * false when it ended in error — the caller stops the pipeline there.
     */
    public boolean run(String lessonId, PipelineStep s, Runnable body) {
        if (isDone(lessonId, s)) { log.info("lesson {} step {} already done — skipped", lessonId, stepName(s)); return true; }
        for (int attempt = 1; ; attempt++) {
            mark(lessonId, s, "running", null, null);
            try {
                bounded(lessonId, s, body);
                done(lessonId, s);
                return true;
            } catch (Deadline e) {
                log.error("lesson {} step {} ran past its deadline ({}) and was abandoned", lessonId, stepName(s), deadlines.of(s));
                mark(lessonId, s, "error", TIMEOUT, Messages.of(TIMEOUT, null));
                return false;
            } catch (TransientFailure e) {
                if (attempt < TRANSIENT_ATTEMPTS) {
                    long wait = retryDelayMs * (1L << (attempt - 1));
                    log.warn("lesson {} step {} transient failure (attempt {}/{}): {} — retrying in {} ms", lessonId, stepName(s), attempt, TRANSIENT_ATTEMPTS, e.getMessage(), wait);
                    sleep(wait); continue;
                }
                mark(lessonId, s, "error", "model_unavailable", Messages.of("model_unavailable", e.getMessage()));
                return false;
            } catch (ApiException e) {
                mark(lessonId, s, "error", e.error().code(), Messages.of(e.error().code(), e.error().message()));
                return false;
            } catch (Stop e) {
                log.info("lesson {} step {} stopped: {}", lessonId, stepName(s), e.getMessage()); return false;
            } catch (RuntimeException e) {
                log.error("lesson {} step {} crashed", lessonId, stepName(s), e);
                mark(lessonId, s, "error", "model_failed", Messages.of("model_failed", e.getMessage()));
                return false;
            }
        }
    }

    /**
     * The step body, bounded by its deadline. It runs on a virtual thread of its own so that the deadline can
     * actually end it: interrupting the worker unblocks the HTTP call or the process wait it is sitting in, so the
     * job stops instead of waking up later and writing `done` over the error the teacher was just shown.
     */
    private void bounded(String lessonId, PipelineStep s, Runnable body) {
        var thrown = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread worker = Thread.ofVirtual().name("pipeline-" + stepName(s) + "-" + lessonId)
                .unstarted(() -> { try { body.run(); } catch (Throwable t) { thrown.set(t); } });
        worker.start();
        boolean finished;
        try { finished = worker.join(deadlines.of(s)); }
        catch (InterruptedException e) { worker.interrupt(); Thread.currentThread().interrupt(); throw new Stop("the pipeline thread was interrupted"); }
        if (!finished) { worker.interrupt(); throw new Deadline(stepName(s) + " ran past " + deadlines.of(s)); }
        Throwable t = thrown.get();
        if (t == null) return;
        if (t instanceof RuntimeException e) throw e;
        if (t instanceof Error e) throw e;
        throw new IllegalStateException(t.getMessage(), t);
    }

    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }

    /** Error codes → what the admin should do next. */
    public static final class Messages {
        public static String of(String code, String detail) {
            String d = detail == null || detail.isBlank() ? "" : " (" + (detail.length() > 160 ? detail.substring(0, 160) + "…" : detail) + ")";
            return switch (code == null ? "" : code) {
                case "unreadable_file" -> "I can't read this file. If it is a scanned image with no text, try a PDF exported from PowerPoint, or upload the pages as images." + d;
                case "no_teaching_content" -> "These pages don't contain anything to practise. Check that you uploaded the lesson slides, not a cover page or a worksheet key.";
                case "too_large" -> "The file is too big (25 MB per file). Export a smaller PDF or upload the pages as images.";
                case "model_failed" -> "The AI returned an invalid answer twice. Retry, or edit Level 1 by hand and press Generate the other levels." + d;
                case "model_unavailable" -> "The AI service didn't answer after three tries (busy or unreachable). Wait a minute and press Retry and continue." + d;
                case "network" -> "The network dropped while talking to the AI service. Press Retry and continue." + d;
                // CR4: the Convert step. Each one names the way out the teacher has on the file itself.
                case "encrypted" -> "This file is password-protected, so we can't read the text out of it. Save it again without the password, or paste the lesson's text with \"Type the text instead\"." + d;
                case "unsupported" -> "We can't read this kind of file. Upload a PDF, PowerPoint, Word, Excel or CSV file, a photo of the pages, or paste the text with \"Type the text instead\"." + d;
                case "malformed" -> "This file looks damaged, so we couldn't read it. Open it, save a fresh copy and upload that — or paste the text with \"Type the text instead\"." + d;
                case "needs_ocr" -> "There is no text in this file, only pictures of it. Press \"Read the pictures\" to read it with OCR, or paste the text with \"Type the text instead\"." + d;
                case "ocr_failed" -> "We tried to read the pictures and found no words. Upload a clearer scan, or paste the text with \"Type the text instead\"." + d;
                case "tool_missing" -> "The document converter isn't installed on this server. An operator needs to check QUEST_ANYDOC_BIN and QUEST_TESSERACT_BIN." + d;
                case "io" -> "The document converter stopped before it finished. Press Retry and continue; if it happens again, paste the text with \"Type the text instead\"." + d;
                case "timeout" -> "This step took too long. Retry it." + d;
                case "markdown_missing" -> "This lesson's files haven't been converted to text yet. Press Retry and continue to convert them." + d;
                default -> (detail == null || detail.isBlank() ? "Something went wrong at this step. Press Retry and continue." : detail);
            };
        }
    }
}
