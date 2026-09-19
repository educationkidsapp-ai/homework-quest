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
