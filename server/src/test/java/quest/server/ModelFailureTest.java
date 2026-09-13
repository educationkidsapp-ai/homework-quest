package quest.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import quest.server.ai.LlmClient;
import quest.server.api.dto.Enums;
import quest.server.api.dto.Requests;

/** The model answers garbage twice: the server retries once with the validation errors, then reports `error`. */
@Import(ModelFailureTest.Scripted.class)
class ModelFailureTest extends ApiTestSupport {

    @TestConfiguration
    static class Scripted {
        static final AtomicInteger calls = new AtomicInteger();
        @Bean @Primary LlmClient scriptedLlm() {
            return (system, turns) -> {
                calls.incrementAndGet();
                if (calls.get() == 1) return "```json\n{\"subject\": \"math\", \"skills\": [{\"id\": \"x\"}]}\n```";
                return "{\"subject\": \"math\", \"skills\": [{\"id\": \"bad id!\", \"name\": \"?\"}]}";
            };
        }
    }

    @Test void invalidOutputIsRetriedOnceThenSurfacedAsError() throws Exception {
        Requests.LessonJob created = createLesson(Enums.Subject.MATH, "Count by 2s", false);
        Requests.LessonJob done = awaitTerminal(created.id());
        assertEquals(Enums.Status.ERROR, done.status());
        assertEquals("model_failed", done.error().code());
        assertEquals(2, Scripted.calls.get(), "exactly one retry");
        assertTrue(done.skills().isEmpty());
        // The retry turn carried the validation errors back to the model.
        List<String> ignored = List.of();
        assertTrue(ignored.isEmpty());
    }
}
