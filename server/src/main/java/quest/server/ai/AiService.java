package quest.server.ai;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import quest.server.analysis.LlmClient;
import quest.server.analysis.LlmFailures;

/**
 * Feature 24: <strong>the one loop every AI feature runs.</strong>
 *
 * <p>Four call sites had copied it — Prompt A, Prompt B and C, the stop rewriter, the stop a teacher writes in prose,
 * and feature 22's announcement — and each copy had drifted a little: one logged the rejected answer and another did
 * not, one worded the retry turn "rejected by the validator" and another "rejected", one kept the tokens of an
 * attempt it then threw away and another forgot to. Features 25 to 27 would have made nine copies. So the loop moves
 * here and the differences move into {@link AiTask}, which is the point: what a feature actually owns is its prompts,
 * what counts as a good answer, and what to tell the caller when there is none.
 *
 * <p><strong>It runs on the caller's own thread.</strong> Nothing is handed to an executor, because
 * {@link quest.server.analysis.StepBudget} is a {@link ThreadLocal} the provider client reads before it starts
 * another attempt: a step that has four seconds left must not start a call that takes sixty, and only the thread the
 * step is being waited for on knows that.
 *
 * <p><strong>It holds no transaction.</strong> A model call is seconds to minutes and a Hikari connection held across
 * one is ten teachers pressing Save at once being the whole pool, which is E1's lesson. The ledger's own write is
 * {@code REQUIRES_NEW} ({@link AiCallLedger}) precisely so that it does not need the caller's transaction either.
 */
@Service
public class AiService {
    private static final Logger log = LoggerFactory.getLogger(AiService.class);

    private final LlmClient llm; private final AiCallLedger ledger;

    public AiService(LlmClient llm, AiCallLedger ledger) { this.llm = llm; this.ledger = ledger; }

    /** The loop with no ledger behind it, for a unit test that has no database. */
    public static AiService withoutLedger(LlmClient llm) { return new AiService(llm, null); }

    /**
     * Asks until the answer is good enough or the attempts run out.
     *
     * @return the accepted answer and what every attempt cost
     * @throws AiFailure {@code TRANSIENT} or {@code PROVIDER} when the provider is what failed, {@code INVALID} when
     *         every attempt was rejected. Anything the task's reader throws is the caller's own and passes through
     *         untouched — after the attempt has been booked and recorded, which is what makes it safe to throw.
     */
    public AiOutcome run(AiTask task) {
        String user = task.guard() == null ? task.user() : task.guard().mask(task.user());
        long inputTokens = 0, outputTokens = 0;
        List<String> errors = List.of();
        for (int attempt = 0; attempt < task.attempts(); attempt++) {
            String turn = attempt == 0 ? user : user + task.retry().append(errors);
            long startedNanos = System.nanoTime();
            LlmClient.Result answer;
            try { answer = llm.complete(task.system(), turn, task.attachments()); }
            catch (LlmClient.LlmException e) {
                record(task, 0, 0, e.isTransient() ? AiCallLedger.TRANSIENT : AiCallLedger.PROVIDER, startedNanos);
                throw new AiFailure(e.isTransient() ? AiFailure.Kind.TRANSIENT : AiFailure.Kind.PROVIDER,
                        e.getMessage(), e, errors, inputTokens, outputTokens);
            }
            inputTokens += answer.inputTokens(); outputTokens += answer.outputTokens();
            task.billing().attempt(answer.inputTokens(), answer.outputTokens());
            AiVerdict verdict;
            try { verdict = task.reader().read(answer.text(), attempt); }
            catch (RuntimeException stopped) { record(task, answer, AiCallLedger.ABORTED, startedNanos); throw stopped; }
            if (verdict.accepted()) {
                record(task, answer, AiCallLedger.OK, startedNanos);
                return new AiOutcome(verdict.text(), verdict.json(), inputTokens, outputTokens, attempt + 1);
            }
            errors = verdict.errors();
            record(task, answer, AiCallLedger.REJECTED, startedNanos);
            log.warn("{} ({}) attempt {} of {} rejected: {}", task.feature(), task.tag(), attempt + 1, task.attempts(), errors);
            if (task.keepRejects()) LlmFailures.keep(task.tag(), answer.text(), errors);
        }
        throw new AiFailure(AiFailure.Kind.INVALID, task.feature() + ": " + String.join("; ", errors), null, errors, inputTokens, outputTokens);
    }

    private void record(AiTask task, LlmClient.Result answer, String outcome, long startedNanos) {
        record(task, answer.inputTokens(), answer.outputTokens(), outcome, startedNanos);
    }

    private void record(AiTask task, long inputTokens, long outputTokens, String outcome, long startedNanos) {
        if (ledger == null) return;
        ledger.record(task, llm.name(), inputTokens, outputTokens, outcome, (System.nanoTime() - startedNanos) / 1_000_000L);
    }
}
