package quest.server.analysis;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import quest.api.PipelineStep;

/**
 * How long one step may run before it is called dead.
 *
 * <p>A step has two bounds and needs both. The inner one is the LLM client's own read timeout
 * ({@link quest.server.config.QuestProperties.Llm#timeout()}): it ends a call that hangs. The outer one is here, and
 * it ends a <em>step</em> that is not coming back for any other reason — three transient retries of two model calls
 * each, a converter that never exits, or (the case no in-process timeout can catch) a Cloud Run instance recycled
 * mid-step, which leaves the row `running` in a database no thread is attached to any more.
 *
 * <p>The numbers are the observed work plus room: a generate step is two model calls with retries between them, an
 * analyse step one, and Convert is local work whose own per-file timeout is 120 s. They are configurable because the
 * model that answers in a minute today may not tomorrow, and a deadline that is too tight fails healthy lessons.
 */
@Component
public class PipelineDeadlines {
    private final Duration generate; private final Duration analyze; private final Duration convert; private final Duration grace;

    public PipelineDeadlines(@Value("${quest.pipeline.deadline.generate-seconds:360}") long generateSeconds,
                             @Value("${quest.pipeline.deadline.analyze-seconds:240}") long analyzeSeconds,
                             @Value("${quest.pipeline.deadline.convert-seconds:180}") long convertSeconds,
                             @Value("${quest.pipeline.watchdog.grace-seconds:60}") long graceSeconds) {
        this.generate = seconds(generateSeconds, 360); this.analyze = seconds(analyzeSeconds, 240);
        this.convert = seconds(convertSeconds, 180); this.grace = seconds(graceSeconds, 60);
    }

    /** The deadline of one step. UPLOAD and SKILLS do no work of their own and take the shortest one. */
    public Duration of(PipelineStep step) {
        return switch (step) {
            case GENERATE_L1, GENERATE_L2, GENERATE_L3, GENERATE_AGAIN, PANEL -> generate;
            case ANALYZE -> analyze;
            case UPLOAD, CONVERT, SKILLS -> convert;
        };
    }

    /**
     * What the sweep waits for on top of the deadline before it takes a row away from whoever may still hold it —
     * another instance mid-step, or a clock a second or two off ours.
     */
    public Duration grace() { return grace; }

    /** The longest any one step may take: the bound on a lesson that names no step at all. */
    public Duration longest() { return generate.compareTo(analyze) >= 0 ? generate : analyze; }

    private static Duration seconds(long value, long fallback) { return Duration.ofSeconds(value > 0 ? value : fallback); }
}
