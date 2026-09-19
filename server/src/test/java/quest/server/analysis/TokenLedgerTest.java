package quest.server.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import quest.server.ApiTestSupport;
import quest.server.admin.AdminPipelineTest;

/**
 * What an abandoned step costs is still what it costs.
 *
 * <p>Prompt A, Prompt B, Prompt C and the stop rewriter all call the model up to twice and add the answer's tokens
 * to a local before writing the total to the lesson at the end — so a second call that never came back (a provider
 * that went busy, a step interrupted at its deadline) took the first call's tokens with it. DeepSeek had already
 * answered it and would already bill for it; only `lessons.token_usage` disagreed, which is the number the owner's
 * billing screen adds up per school.
 *
 * <p>The client here answers Prompt A once with JSON the validator rejects and then refuses, which is the shape of
 * exactly that: one answer received, one call that never lands, and a step that ends as `model_unavailable`.
 */
@Import(TokenLedgerTest.OneAnswerThenBusy.class)
@TestPropertySource(properties = "quest.pipeline.retry-delay-ms=1")
class TokenLedgerTest extends ApiTestSupport {
    /** The one answer the fake gives before it goes busy: 1200 in, 800 out. */
    static final long BILLED = 2000;

    @TestConfiguration
    static class OneAnswerThenBusy {
        // A second bean under its own name rather than an override of `llmConfig`'s: overriding is off, and
        // `@Primary` is what every `LlmClient` injection point resolves to anyway.
        @Bean @Primary LlmClient oneAnswerThenBusyLlmClient() {
            var calls = new AtomicInteger();
            var sample = new SampleLlmClient();
            return new LlmClient() {
                @Override public String name() { return "one-answer-then-busy"; }
                @Override public Result complete(String system, String user, List<Attachment> attachments) {
                    if (!Prompts.SYSTEM_A.equals(system)) return sample.complete(system, user, attachments);
                    if (calls.incrementAndGet() == 1) return new Result("{\"not\":\"an analysis at all\"}", 1200, 800);
                    throw new LlmException("the provider is busy", null, true);
                }
            };
        }
    }

    @Autowired quest.server.content.LessonRepository lessons;

    @Test void the_tokens_of_an_answered_call_survive_the_call_that_never_came_back() throws Exception {
        String token = adminToken();
        String id = json(mvc.perform(admin(post("/admin/lessons"), token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"curriculum\":\"british\",\"grade\":1,\"subject\":\"english\",\"date\":\"2026-12-01\"}")).andReturn()).get("id").asText();
        mvc.perform(admin(multipart("/admin/lessons/" + id + "/files")
                .file(new MockMultipartFile("files", "slides.pdf", MediaType.APPLICATION_PDF_VALUE,
                        AdminPipelineTest.pdf("Hot Soup", "Mummy is in bed.", "Alan makes soup."))), token)).andExpect(status().isOk());
        mvc.perform(admin(post("/admin/lessons/" + id + "/analyze"), token)).andExpect(status().isOk());

        var failed = awaitError(token, id);
        assertThat(failed.get("error").get("code").asText()).isEqualTo("model_unavailable");
        assertThat(lessons.findById(id).orElseThrow().getTokenUsage())
                .as("the answer we were given, and were billed for, is on the lesson").isEqualTo(BILLED);
    }

    private com.fasterxml.jackson.databind.JsonNode awaitError(String token, String id) throws Exception {
        for (int i = 0; i < 200; i++) {
            var l = json(mvc.perform(admin(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/admin/lessons/" + id), token)).andReturn());
            if ("error".equals(l.get("status").asText())) return l;
            Thread.sleep(50);
        }
        throw new AssertionError("the analyse step never failed");
    }
}
