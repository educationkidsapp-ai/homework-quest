package quest.server.ai;

import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import quest.server.ai.Entities.AiCallEntity;

/**
 * Writes one {@code ai_calls} row per model attempt, in a physical transaction of its own.
 *
 * <p><strong>{@code REQUIRES_NEW}</strong>, for {@link quest.server.notifications.NotificationRows}' reason taken one
 * step further: the bill is not the caller's to lose. A teacher's save rolls its own transaction back when the answer
 * cannot be stored, and the lesson pipeline abandons a step at its deadline — the provider answered and will invoice
 * for it either way, so the row is committed as soon as the attempt is over and is not undone by whatever the caller
 * decides next.
 *
 * <p><strong>And it never throws.</strong> A ledger that could fail a lesson would be worse than no ledger: a failed
 * write is logged and nothing more. That is also why the table has no foreign key — see
 * {@link quest.server.ai.Entities.AiCallEntity}.
 */
@Component
public class AiCallLedger {
    /** The answer was accepted. */ public static final String OK = "ok";
    /** The model answered and the task's reader rejected it. */ public static final String REJECTED = "rejected";
    /** The provider was busy or unreachable. */ public static final String TRANSIENT = "transient";
    /** The provider failed in a way that is not worth retrying. */ public static final String PROVIDER = "provider";
    /** The model answered and the caller stopped on it — the slides with nothing to practise, say. */ public static final String ABORTED = "aborted";

    private static final Logger log = LoggerFactory.getLogger(AiCallLedger.class);

    private final AiCallRepository calls; private final Clock clock; private final TransactionTemplate own;

    public AiCallLedger(AiCallRepository calls, Clock clock, PlatformTransactionManager transactions) {
        this.calls = calls; this.clock = clock;
        this.own = new TransactionTemplate(transactions);
        this.own.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** One attempt, as numbers. Nothing of the prompt or the answer is passed in, so nothing of them can be stored. */
    public void record(AiTask task, String provider, long inputTokens, long outputTokens, String outcome, long durationMs) {
        var row = new AiCallEntity();
        row.setId(UUID.randomUUID().toString()); row.setSchoolId(task.schoolId()); row.setFeature(task.feature()); row.setTag(task.tag());
        row.setProvider(provider); row.setInputTokens(inputTokens); row.setOutputTokens(outputTokens);
        row.setOutcome(outcome); row.setDurationMs(durationMs); row.setCreatedAt(clock.instant());
        try { own.executeWithoutResult(status -> calls.saveAndFlush(row)); }
        catch (RuntimeException e) { log.warn("could not record the {} AI call: {}", task.feature(), e.toString()); }
    }
}
