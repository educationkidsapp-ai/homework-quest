package quest.server.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import quest.server.ApiTestSupport;
import quest.server.ai.Entities.AiCallEntity;
import quest.server.analysis.LlmClient;

/**
 * Feature 24: the loop the four lesson call sites used to copy, tested once.
 *
 * <p>What the migrated call sites still do is proven by <em>their own</em> tests, which this feature left untouched
 * on purpose ({@code AnalysisUnitTest}, {@code TokenLedgerTest}, {@code StopFromTextTest}, {@code VoiceDraftApiTest}
 * and the pipeline tests). What is asserted here is only what moved: the retry turn, the rejects kept on disk, the
 * three failure kinds, the ledger — and that the ledger is not the caller's to lose.
 */
class AiServiceTest extends ApiTestSupport {
    private static final String SYSTEM = "You answer with JSON.";
    private static final String USER = "Write me something.";

    @MockitoBean LlmClient llm;
    @Autowired AiService ai;
    @Autowired AiCallRepository calls;
    @Autowired PlatformTransactionManager transactions;

    private String tag;

    @BeforeEach void nameThisRun() {
        tag = "f24-" + UUID.randomUUID().toString().substring(0, 8);
        when(llm.name()).thenReturn("test-provider");
    }

    /** A task that rejects every answer, saying what it wants, so the retry turn has something to carry. */
    private AiTask fussy() {
        return AiTask.of("test", tag, "school-a", SYSTEM, USER)
                .reading((raw, attempt) -> AiVerdict.rejected(List.of("\"title\" is required", "attempt was " + attempt)));
    }

    // ---------------------------------------------------------------- the loop

    @Test void the_second_attempt_is_told_what_was_wrong_with_the_first() {
        doReturn(new LlmClient.Result("{}", 10, 5)).when(llm).complete(any(), any(), any());

        assertThatThrownBy(() -> ai.run(fussy())).isInstanceOf(AiFailure.class);

        var turns = ArgumentCaptor.forClass(String.class);
        verify(llm, times(2)).complete(any(), turns.capture(), any());
        assertThat(turns.getAllValues().get(0)).isEqualTo(USER);
        assertThat(turns.getAllValues().get(1)).startsWith(USER)
                .contains("Your previous answer was rejected by the validator:")
                .contains("- \"title\" is required").contains("- attempt was 0")
                .endsWith("Answer again with corrected JSON only.");
    }

    @Test void the_retry_wording_travels_with_the_task() {
        doReturn(new LlmClient.Result("{}", 10, 5)).when(llm).complete(any(), any(), any());

        assertThatThrownBy(() -> ai.run(fussy().retrying(AiTask.Retry.saying("Your previous stop was rejected:", "Answer again with the corrected stop JSON only."))))
                .isInstanceOf(AiFailure.class);

        var turns = ArgumentCaptor.forClass(String.class);
        verify(llm, times(2)).complete(any(), turns.capture(), any());
        assertThat(turns.getAllValues().get(1)).contains("Your previous stop was rejected:")
                .endsWith("Answer again with the corrected stop JSON only.")
                .doesNotContain("by the validator");
    }

    @Test void an_accepted_answer_carries_the_tokens_of_every_attempt_it_took() {
        var answers = List.of(new LlmClient.Result("{\"no\":true}", 10, 5), new LlmClient.Result("{\"yes\":true}", 20, 7));
        var next = new java.util.concurrent.atomic.AtomicInteger();
        when(llm.complete(any(), any(), any())).thenAnswer(i -> answers.get(next.getAndIncrement()));
        long[] billed = {0};

        var outcome = ai.run(AiTask.of("test", tag, "school-a", SYSTEM, USER)
                .reading((raw, attempt) -> raw.contains("yes") ? AiVerdict.ok(raw) : AiVerdict.rejected("say yes"))
                .billing((in, out) -> billed[0] += in + out));

        assertThat(outcome.attempts()).isEqualTo(2);
        assertThat(outcome.text()).isEqualTo("{\"yes\":true}");
        assertThat(outcome.inputTokens()).isEqualTo(30);
        assertThat(outcome.outputTokens()).isEqualTo(12);
        assertThat(billed[0]).as("the caller was told each answered attempt as it came").isEqualTo(42);
    }

    // ---------------------------------------------------------------- the three kinds of failure

    @Test void a_busy_provider_is_transient_and_a_broken_one_is_the_providers_fault() {
        doThrow(new LlmClient.LlmException("the provider is busy", null, true)).when(llm).complete(any(), any(), any());
        assertThatThrownBy(() -> ai.run(fussy()))
                .isInstanceOf(AiFailure.class)
                .matches(e -> ((AiFailure) e).kind() == AiFailure.Kind.TRANSIENT, "is transient")
                .hasMessage("the provider is busy")
                .matches(e -> e.getCause() instanceof LlmClient.LlmException, "keeps the provider's own exception");

        doThrow(new LlmClient.LlmException("401 from the provider")).when(llm).complete(any(), any(), any());
        assertThatThrownBy(() -> ai.run(fussy()))
                .isInstanceOf(AiFailure.class)
                .matches(e -> ((AiFailure) e).kind() == AiFailure.Kind.PROVIDER, "is the provider's fault");
    }

    @Test void every_attempt_rejected_is_invalid_and_carries_the_errors() {
        doReturn(new LlmClient.Result("{}", 10, 5)).when(llm).complete(any(), any(), any());

        assertThatThrownBy(() -> ai.run(fussy())).isInstanceOfSatisfying(AiFailure.class, f -> {
            assertThat(f.kind()).isEqualTo(AiFailure.Kind.INVALID);
            assertThat(f.errors()).containsExactly("\"title\" is required", "attempt was 1");
            assertThat(f.errorsJoined(5)).isEqualTo("\"title\" is required; attempt was 1");
            assertThat(f.totalTokens()).as("two answers were billed before we gave up").isEqualTo(30);
        });
    }

    @Test void what_the_readers_own_check_throws_passes_through_untouched() {
        doReturn(new LlmClient.Result("{\"error\":\"no_teaching_content\"}", 10, 5)).when(llm).complete(any(), any(), any());

        assertThatThrownBy(() -> ai.run(AiTask.of("test", tag, "school-a", SYSTEM, USER)
                .reading((raw, attempt) -> { throw new IllegalStateException("nothing to practise"); })))
                .isInstanceOf(IllegalStateException.class).hasMessage("nothing to practise");

        verify(llm, times(1)).complete(any(), any(), any());
        assertThat(outcomes()).containsExactly(AiCallLedger.ABORTED);
    }

    // ---------------------------------------------------------------- the rejects kept on disk

    /**
     * {@code LlmFailures} is how a prompt is tuned against what the model really wrote, and it is opt-in here because
     * one call site never used it: the stop rewriter is one press of a button on one stop, and the prompt behind it is
     * Prompt B's own.
     */
    @Test void a_rejected_answer_is_kept_only_when_the_task_asks_for_it() throws IOException {
        doReturn(new LlmClient.Result("{}", 10, 5)).when(llm).complete(any(), any(), any());
        String quiet = tag + "-quiet";
        try {
            assertThatThrownBy(() -> ai.run(fussy().keepingRejects())).isInstanceOf(AiFailure.class);
            assertThatThrownBy(() -> ai.run(AiTask.of("test", quiet, "school-a", SYSTEM, USER)
                    .reading((raw, attempt) -> AiVerdict.rejected("no")))).isInstanceOf(AiFailure.class);

            assertThat(kept(tag)).as("one file per rejected attempt, with the errors and the answer").hasSize(2);
            assertThat(Files.readString(kept(tag).get(0))).contains("\"title\" is required").contains("{}");
            assertThat(kept(quiet)).as("a task that keeps nothing keeps nothing").isEmpty();
        } finally {
            for (Path p : kept(tag)) Files.deleteIfExists(p);
            for (Path p : kept(quiet)) Files.deleteIfExists(p);
        }
    }

    /** The same default {@code LlmFailures} reads, so this finds the files wherever it put them. */
    private static List<Path> kept(String tag) throws IOException {
        Path dir = Path.of(System.getProperty("quest.failures.dir", "data/llm-failures"));
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().startsWith(tag + "-")).sorted().toList();
        }
    }

    // ---------------------------------------------------------------- the ledger

    @Test void one_row_per_attempt_carries_the_tokens_the_outcome_and_the_provider() {
        var answers = List.of(new LlmClient.Result("{\"no\":true}", 10, 5), new LlmClient.Result("{\"yes\":true}", 20, 7));
        var next = new java.util.concurrent.atomic.AtomicInteger();
        when(llm.complete(any(), any(), any())).thenAnswer(i -> answers.get(next.getAndIncrement()));

        ai.run(AiTask.of("test", tag, "school-a", SYSTEM, USER)
                .reading((raw, attempt) -> raw.contains("yes") ? AiVerdict.ok(raw) : AiVerdict.rejected("say yes")));

        var rows = calls.findByTagOrderByCreatedAtAsc(tag);
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(AiCallEntity::getOutcome).containsExactlyInAnyOrder(AiCallLedger.REJECTED, AiCallLedger.OK);
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.getFeature()).isEqualTo("test");
            assertThat(r.getSchoolId()).isEqualTo("school-a");
            assertThat(r.getProvider()).isEqualTo("test-provider");
            assertThat(r.getDurationMs()).isGreaterThanOrEqualTo(0);
        });
        assertThat(rows.stream().mapToLong(AiCallEntity::getInputTokens).sum()).isEqualTo(30);
        assertThat(rows.stream().mapToLong(AiCallEntity::getOutputTokens).sum()).isEqualTo(12);
    }

    @Test void a_provider_that_never_answered_is_an_attempt_too() {
        doThrow(new LlmClient.LlmException("the provider is busy", null, true)).when(llm).complete(any(), any(), any());
        assertThatThrownBy(() -> ai.run(fussy())).isInstanceOf(AiFailure.class);
        assertThat(outcomes()).containsExactly(AiCallLedger.TRANSIENT);

        doThrow(new LlmClient.LlmException("401 from the provider")).when(llm).complete(any(), any(), any());
        assertThatThrownBy(() -> ai.run(fussy())).isInstanceOf(AiFailure.class);
        assertThat(outcomes()).containsExactlyInAnyOrder(AiCallLedger.TRANSIENT, AiCallLedger.PROVIDER);
    }

    /**
     * The point of the {@code REQUIRES_NEW} write: a teacher's save that cannot store the answer rolls its own
     * transaction back, and the provider invoices for the turn regardless.
     */
    @Test void the_ledger_survives_the_callers_rollback() {
        doReturn(new LlmClient.Result("{\"yes\":true}", 10, 5)).when(llm).complete(any(), any(), any());
        var tx = new TransactionTemplate(transactions);

        tx.executeWithoutResult(status -> {
            ai.run(AiTask.of("test", tag, "school-a", SYSTEM, USER).reading((raw, attempt) -> AiVerdict.ok(raw)));
            status.setRollbackOnly();
        });

        assertThat(calls.findByTagOrderByCreatedAtAsc(tag)).hasSize(1);
        assertThat(outcomes()).containsExactly(AiCallLedger.OK);
    }

    /** The one thing the ledger must never hold: what was asked and what came back. */
    @Test void the_ledger_holds_numbers_and_never_a_prompt_or_an_answer() {
        doReturn(new LlmClient.Result("{\"secret\":\"Lamya Alqahtani\"}", 10, 5)).when(llm).complete(any(), any(), any());

        ai.run(AiTask.of("test", tag, "school-a", SYSTEM, USER).reading((raw, attempt) -> AiVerdict.ok(raw)));

        assertThat(calls.findByTagOrderByCreatedAtAsc(tag)).singleElement().satisfies(r ->
                assertThat(r.getFeature() + "|" + r.getTag() + "|" + r.getProvider() + "|" + r.getOutcome())
                        .doesNotContain("Lamya").doesNotContain(USER).doesNotContain(SYSTEM));
    }

    private List<String> outcomes() { return calls.findByTagOrderByCreatedAtAsc(tag).stream().map(AiCallEntity::getOutcome).toList(); }

    // ---------------------------------------------------------------- the guard

    /**
     * Opt-in per task, and no lesson call site uses it — features 26 and 27 do. Nine digits is the phone threshold
     * precisely so that the date every feature tells the model survives it.
     */
    @Test void the_guard_masks_an_email_a_phone_number_and_the_names_it_is_given() {
        var guard = AiInputGuard.masking("Nada Alqahtani", "ليان");
        String masked = guard.mask("Nada Alqahtani (nada@school.test, +966 50 123 4567) asked about ليان on 2026-10-15.");

        assertThat(masked).doesNotContain("Nada").doesNotContain("Alqahtani").doesNotContain("nada@school.test")
                .doesNotContain("50 123 4567").doesNotContain("ليان");
        assertThat(masked).contains(AiInputGuard.NAME_MASK).contains(AiInputGuard.EMAIL_MASK).contains(AiInputGuard.PHONE_MASK);
        assertThat(masked).as("a date is not a telephone number").contains("2026-10-15");
        assertThat(AiInputGuard.numbersOnly().mask("Grade 3 meets at 7:30 on 2026-10-15")).isEqualTo("Grade 3 meets at 7:30 on 2026-10-15");
    }

    @Test void a_guarded_task_sends_the_masked_turn_and_an_unguarded_one_sends_her_words() {
        doReturn(new LlmClient.Result("{\"yes\":true}", 10, 5)).when(llm).complete(any(), any(), any());
        String said = "Call Nada on 0501234567.";
        var accepting = AiTask.of("test", tag, "school-a", SYSTEM, said).reading((raw, attempt) -> AiVerdict.ok(raw));

        ai.run(accepting.guardedBy(AiInputGuard.masking("Nada")));
        ai.run(accepting);

        var turns = ArgumentCaptor.forClass(String.class);
        verify(llm, times(2)).complete(any(), turns.capture(), any());
        assertThat(turns.getAllValues().get(0)).isEqualTo("Call [name] on [phone].");
        assertThat(turns.getAllValues().get(1)).isEqualTo(said);
    }
}
