package quest.server.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import quest.server.ApiTestSupport;
import quest.server.analysis.LlmClient;
import quest.server.analysis.Prompts;
import quest.server.analysis.SampleLlmClient;

/**
 * D25, the shape of the generate phase: Levels 1–3 are written at the same time, and Again and the parent panel
 * wait for them and are then written at the same time as each other.
 *
 * <p>The claim is about concurrency, so it is tested with a model client that can prove it. Each Prompt B call for a
 * level counts down a latch of three and then waits on it: if the three calls really are in flight together they all
 * pass it, and if the walk is sequential the first one sits there until the timeout and the peak never reaches
 * three. The spans it records answer the other half — Again cannot have started before Level 1 was stored, and the
 * panel not before all three were.
 */
@TestPropertySource(properties = { "quest.pipeline.retry-delay-ms=1" })
class PipelineParallelTest extends ApiTestSupport {

    @TestConfiguration
    static class LatchingLlmConfig {
        @Bean @Primary LatchingLlm latchingLlm() { return new LatchingLlm(); }
    }

    /** The sample client, with a turnstile on the three level calls and a stopwatch on every one. */
    static final class LatchingLlm implements LlmClient {
        private final LlmClient delegate = new SampleLlmClient();
        /** Each level call counts down and then waits: all three pass only if all three are in flight. */
        final CountDownLatch levels = new CountDownLatch(3);
        final AtomicInteger inFlight = new AtomicInteger(), peak = new AtomicInteger();
        /** kind → {first start, last end}, in millis since the test started. */
        final Map<String, long[]> spans = new ConcurrentHashMap<>();
        private final long origin = System.nanoTime();

        @Override public String name() { return "latching"; }

        @Override public Result complete(String system, String user, List<Attachment> attachments) {
            String kind = kind(system, user);
            long start = millis();
            peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            try {
                if (kind.startsWith("L")) { levels.countDown(); levels.await(10, TimeUnit.SECONDS); }
                return delegate.complete(system, user, attachments);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new LlmException("interrupted", e); }
            finally { inFlight.decrementAndGet(); spans.merge(kind, new long[] { start, millis() }, (a, b) -> new long[] { Math.min(a[0], b[0]), Math.max(a[1], b[1]) }); }
        }

        private long millis() { return Duration.ofNanos(System.nanoTime() - origin).toMillis(); }

        private static String kind(String system, String user) {
            if (system.equals(Prompts.SYSTEM_A)) return "analyze";
            if (system.equals(Prompts.SYSTEM_C)) return "panel";
            if (user.contains("AGAIN variant")) return "again";
            return user.contains("LEVEL 2") ? "L2" : user.contains("LEVEL 3") ? "L3" : "L1";
        }

        long startOf(String kind) { return spans.get(kind)[0]; }
        long endOf(String kind) { return spans.get(kind)[1]; }
    }

    @Autowired LatchingLlm llm;

    @Test void the_three_levels_are_written_together_and_again_and_the_panel_wait_for_them() throws Exception {
        String token = adminToken();
        String id = json(mvc.perform(admin(post("/admin/lessons"), token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"curriculum\":\"british\",\"grade\":1,\"subject\":\"english\",\"date\":\"2026-10-21\"}")).andReturn()).get("id").asText();
        // its own text, so the play cache of the shared H2 database cannot answer these calls without the model
        mvc.perform(admin(multipart("/admin/lessons/" + id + "/files")
                .file(new MockMultipartFile("files", "slides.pdf", MediaType.APPLICATION_PDF_VALUE,
                        AdminPipelineTest.pdf("Parallel soup", "Mummy stirs the soup while Alan lays the table.", "They carry it up together."))), token))
                .andExpect(status().isOk());
        mvc.perform(admin(post("/admin/lessons/" + id + "/analyze"), token)).andExpect(status().isOk());
        confirmSkills(token, awaitStatus(token, id, "needs_review"));

        var lesson = awaitStatus(token, id, "review");
        assertThat(lesson.get("plays")).hasSize(4);
        assertThat(lesson.get("parentPanel")).isNotNull();

        assertThat(llm.levels.getCount()).as("all three Prompt B calls reached the turnstile").isZero();
        assertThat(llm.peak.get()).as("three model calls in flight at once").isGreaterThanOrEqualTo(3);
        long lastLevelEnded = Math.max(llm.endOf("L1"), Math.max(llm.endOf("L2"), llm.endOf("L3")));
        assertThat(llm.startOf("again")).as("Again excludes Level 1's ids, so it starts after Level 1 is stored").isGreaterThanOrEqualTo(llm.endOf("L1"));
        assertThat(llm.startOf("again")).as("and after the whole first batch, which is what stores them").isGreaterThanOrEqualTo(lastLevelEnded);
        assertThat(llm.startOf("panel")).as("Prompt C reads all three stored levels").isGreaterThanOrEqualTo(lastLevelEnded);
        long firstLevelStarted = Math.min(llm.startOf("L1"), Math.min(llm.startOf("L2"), llm.startOf("L3")));
        System.out.printf("D25: three levels spanned %d ms together; Again+panel started at %d ms%n",
                lastLevelEnded - firstLevelStarted, Math.min(llm.startOf("again"), llm.startOf("panel")));
    }

    private void confirmSkills(String token, JsonNode l) throws Exception {
        var skills = mapper.createArrayNode();
        for (var s : l.get("skills")) skills.addObject().put("id", s.get("id").asText()).put("name", s.get("name").asText())
                .put("subject", s.get("subject").asText()).put("method", s.get("method").asText());
        mvc.perform(admin(post("/admin/lessons/" + l.get("id").asText() + "/skills"), token)
                .contentType(MediaType.APPLICATION_JSON).content(skills.toString())).andExpect(status().isOk());
    }
}
