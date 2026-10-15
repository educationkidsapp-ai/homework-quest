package quest.server.ai;

import java.util.List;
import quest.server.analysis.LlmClient;

/**
 * One AI call, start to finish: what it is for, what to send, how to read the answer, and how to ask again.
 *
 * <p>Built with {@link #of} and narrowed with the {@code with}-style methods below, so a call site reads as the one
 * thing it is doing:
 *
 * <pre>{@code
 * ai.run(AiTask.of("lessonAnalysis", "A", lesson.getSchoolId(), Prompts.SYSTEM_A, user)
 *         .reading((raw, attempt) -> …)
 *         .keepingRejects()
 *         .billing((in, out) -> used[0] += in + out));
 * }</pre>
 *
 * <p>{@code feature} and {@code tag} are what the ledger and {@link quest.server.analysis.LlmFailures} are filed
 * under; {@code tag} is the very tag each call site already used, because a tuned prompt is read back against the
 * answers it was tuned on.
 */
public record AiTask(String feature, String tag, String schoolId, String system, String user,
                     List<LlmClient.Attachment> attachments, int attempts, Retry retry, Reader reader,
                     boolean keepRejects, Billing billing, AiInputGuard guard) {

    /** Reads one raw answer. {@code attempt} is zero-based, for the few readers that number what they repair. */
    @FunctionalInterface public interface Reader { AiVerdict read(String raw, int attempt); }

    /** What is appended to the user turn after a rejected answer — leading newlines and all. */
    @FunctionalInterface public interface Retry {
        String append(List<String> errors);

        /** The wording four of the five lesson prompts use. */
        Retry VALIDATOR = saying("Your previous answer was rejected by the validator:", "Answer again with corrected JSON only.");

        /** `\n\n<opening>\n- <errors…>\n<closing>` — the shape every call site's retry turn has. */
        static Retry saying(String opening, String closing) {
            return errors -> "\n\n" + opening + "\n- " + String.join("\n- ", errors) + "\n" + closing;
        }
    }

    /**
     * Told the tokens of each answered attempt, as soon as it is answered.
     *
     * <p>The lesson builder books whatever the provider has already been paid for even when the next attempt never
     * comes back, and it books it against the lesson, which {@link AiService} knows nothing about. So the numbers are
     * handed over per attempt rather than only in {@link AiOutcome}: a caller that must bill a step it then abandons
     * keeps its own running total, exactly as it did before.
     */
    @FunctionalInterface public interface Billing {
        void attempt(long inputTokens, long outputTokens);
        Billing NONE = (in, out) -> { };
    }

    /** Two attempts, the common retry wording, nothing kept, nothing billed, no guard — then narrow what differs. */
    public static AiTask of(String feature, String tag, String schoolId, String system, String user) {
        return new AiTask(feature, tag, schoolId, system, user, List.of(), 2, Retry.VALIDATOR,
                (raw, attempt) -> AiVerdict.ok(raw), false, Billing.NONE, null);
    }

    public AiTask reading(Reader r) { return new AiTask(feature, tag, schoolId, system, user, attachments, attempts, retry, r, keepRejects, billing, guard); }
    public AiTask retrying(Retry r) { return new AiTask(feature, tag, schoolId, system, user, attachments, attempts, r, reader, keepRejects, billing, guard); }
    public AiTask attempts(int n) { return new AiTask(feature, tag, schoolId, system, user, attachments, n, retry, reader, keepRejects, billing, guard); }
    public AiTask attaching(List<LlmClient.Attachment> a) { return new AiTask(feature, tag, schoolId, system, user, List.copyOf(a), attempts, retry, reader, keepRejects, billing, guard); }
    /** Rejected answers are written under `data/llm-failures/<tag>-…` so the prompt can be tuned against them. */
    public AiTask keepingRejects() { return new AiTask(feature, tag, schoolId, system, user, attachments, attempts, retry, reader, true, billing, guard); }
    public AiTask billing(Billing b) { return new AiTask(feature, tag, schoolId, system, user, attachments, attempts, retry, reader, keepRejects, b, guard); }
    /** Masks e-mail addresses, phone numbers and the given names in the user turn before it is sent. */
    public AiTask guardedBy(AiInputGuard g) { return new AiTask(feature, tag, schoolId, system, user, attachments, attempts, retry, reader, keepRejects, billing, g); }
}
