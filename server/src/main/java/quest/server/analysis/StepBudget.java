package quest.server.analysis;

import java.time.Duration;
import java.time.Instant;

/**
 * How much of the running step's {@link PipelineDeadlines deadline} is left, on the thread the step body runs on.
 *
 * <p>PR #96 gave every step an outer deadline and left the LLM client's own retry budget alone, and the two did not
 * add up: three DeepSeek attempts of 120 s with 2 s + 4 s of backoff is 374 s inside a generate step bounded at
 * 360 s, so the third attempt could never finish. The step was abandoned as {@code timeout} — a code that says
 * "retry this" — when what had actually happened was a provider that was busy three times in a row, which is
 * {@code model_unavailable} and says "wait a minute". Worse, the third call was started knowing it could not be
 * waited for: a paid request nobody would ever read the answer to.
 *
 * <p>So the budget travels with the step. {@link LessonSteps} sets it on the worker thread before the body runs and
 * clears it afterwards; a client asks {@link #allows(Duration)} before it starts another attempt and gives up when
 * one whole call no longer fits. Outside a bounded step — a unit test, an admin's synchronous call — nothing is set
 * and {@link #remaining()} is null, which every caller reads as "no bound of mine", so the old behaviour stands.
 *
 * <p>A plain {@link ThreadLocal}, not an inheritable one: the value belongs to the one thread that is being waited
 * for, and a thread that body spawns is not bounded by the join the deadline is made of.
 */
public final class StepBudget {
    private static final ThreadLocal<Instant> DEADLINE = new ThreadLocal<>();

    private StepBudget() {}

    /** Runs {@code body} with {@code budget} left, restoring whatever was set before (nested steps nest properly). */
    public static void within(Duration budget, Runnable body) {
        Instant previous = DEADLINE.get();
        DEADLINE.set(Instant.now().plus(budget));
        try { body.run(); } finally { if (previous == null) DEADLINE.remove(); else DEADLINE.set(previous); }
    }

    /** What is left of the step's deadline, or null when this thread is not running a bounded step. */
    public static Duration remaining() {
        Instant deadline = DEADLINE.get();
        return deadline == null ? null : Duration.between(Instant.now(), deadline);
    }

    /** True when {@code needed} still fits — and true whenever nothing bounds this thread at all. */
    public static boolean allows(Duration needed) {
        Duration left = remaining();
        return left == null || left.compareTo(needed) >= 0;
    }
}
