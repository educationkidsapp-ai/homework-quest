package quest.server.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import quest.server.config.QuestProperties;

/**
 * A model call that never answers is what left a QA lesson `generating` for three quarters of an hour: the request
 * had a six-minute timeout, the client retried it three times and the step retried <em>that</em> three times, so one
 * step could hold a thread for the better part of an hour and the ledger said `running` the whole time.
 *
 * <p>So the read timeout is a bound now, and the bound is a transient failure — the one kind of failure
 * {@link LessonSteps#run} already knows how to retry and then turn into an error the teacher can act on
 * (`AnalysisService` and `GenerationService` both map {@code isTransient()} to
 * {@link LessonSteps.TransientFailure}).
 */
class LlmTimeoutTest {

    /** A server that accepts the connection and then says nothing at all — the shape of a hung provider. */
    @Test void a_call_that_never_answers_ends_as_a_transient_failure() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> { try { Thread.sleep(60_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } });
        server.start();
        try {
            var client = new DeepSeekClient(deepseek("http://127.0.0.1:" + server.getAddress().getPort()), new QuestProperties.Llm(null, null, 1, 1));
            long started = System.nanoTime();
            assertThatThrownBy(() -> client.complete("system", "user", List.of()))
                    .isInstanceOf(LlmClient.LlmException.class)
                    .matches(e -> ((LlmClient.LlmException) e).isTransient(), "is transient, so the step retries it and then says `model_unavailable`");
            // three attempts of one second with the client's own backoff between them — not three of six minutes
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(30));
        } finally { server.stop(0); }
    }

    /**
     * PR #96's reviewer did the arithmetic the code did not: three attempts of 120 s plus 2 s + 4 s of backoff is
     * 374 s inside a generate step bounded at 360 s. The third call could only ever be interrupted mid-flight — a
     * paid request nobody would read the answer to — and the step ended as `timeout` ("retry this") when what had
     * happened was a provider busy three times over, which is `model_unavailable` ("wait a minute").
     *
     * <p>So the client counts the step's remaining budget as well as its attempts. Here one call is 1 s + 1 s and
     * the step has 4 s: the first attempt takes a second and backs off two, and the second is never started.
     */
    @Test void it_stops_retrying_when_the_step_has_no_time_left_for_another_call() throws Exception {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            calls.incrementAndGet();
            try { Thread.sleep(60_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        server.start();
        try {
            var client = new DeepSeekClient(deepseek("http://127.0.0.1:" + server.getAddress().getPort()), new QuestProperties.Llm(null, null, 1, 1));
            var thrown = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            StepBudget.within(Duration.ofSeconds(4), () -> {
                try { client.complete("system", "user", List.of()); } catch (Throwable t) { thrown.set(t); }
            });
            assertThat(calls.get()).as("the call that could not have finished is never made").isEqualTo(1);
            assertThat(thrown.get()).isInstanceOf(LlmClient.LlmException.class)
                    .matches(e -> ((LlmClient.LlmException) e).isTransient(), "still transient: the step decides what to do next");
        } finally { server.stop(0); }
    }

    /** Outside a bounded step nothing is claimed about the time left, and the old three attempts stand. */
    @Test void an_unbounded_caller_keeps_all_three_attempts() {
        assertThat(StepBudget.remaining()).isNull();
        assertThat(StepBudget.allows(Duration.ofDays(1))).isTrue();
        StepBudget.within(Duration.ofSeconds(30), () -> {
            assertThat(StepBudget.allows(Duration.ofSeconds(5))).isTrue();
            assertThat(StepBudget.allows(Duration.ofMinutes(5))).isFalse();
        });
        assertThat(StepBudget.remaining()).as("the thread is handed back as it was found").isNull();
    }

    /** The cap is the point: an operator can shorten the bound, not remove it. */
    @Test void the_timeout_is_bounded_whatever_is_configured() {
        assertThat(QuestProperties.Llm.defaults().timeout()).isEqualTo(Duration.ofSeconds(120));
        assertThat(QuestProperties.Llm.defaults().connectTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(new QuestProperties.Llm(null, null, 3600, null).timeout()).isEqualTo(Duration.ofSeconds(120));
        assertThat(new QuestProperties.Llm(null, null, 0, 0).timeout()).isEqualTo(Duration.ofSeconds(120));
        assertThat(new QuestProperties.Llm(null, null, 30, 5).timeout()).isEqualTo(Duration.ofSeconds(30));
    }

    private static QuestProperties.DeepSeek deepseek(String baseUrl) {
        return new QuestProperties.DeepSeek("test-key", baseUrl, "deepseek-v4-pro", "deepseek-flash", 256);
    }
}
