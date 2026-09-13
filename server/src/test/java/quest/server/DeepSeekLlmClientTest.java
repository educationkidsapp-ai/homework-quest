package quest.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import quest.server.ai.DeepSeekLlmClient;
import quest.server.ai.LlmClient;
import quest.server.config.QuestProperties;

/** Verifies the OpenAI-compatible request shape against a local stub of api.deepseek.com. */
class DeepSeekLlmClientTest {
    private HttpServer server;
    private final AtomicReference<String> captured = new AtomicReference<>();
    private final ObjectMapper mapper = new ObjectMapper();
    private DeepSeekLlmClient client;

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", ex -> {
            captured.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"subject\\\":\\\"math\\\",\\\"skills\\\":[]}\"}}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        client = new DeepSeekLlmClient(new QuestProperties.DeepSeek("test-key", "http://127.0.0.1:" + server.getAddress().getPort(), "deepseek-v4-pro", "deepseek-flash", 8000), mapper);
    }

    @AfterEach void stop() { server.stop(0); }

    @Test void sendsSystemJsonModeAndImageParts() throws Exception {
        String reply = client.complete("SYSTEM RULES", List.of(new LlmClient.Turn("user", List.of(
                new LlmClient.Block.Image(new byte[] {1, 2, 3}, "image/png"),
                new LlmClient.Block.Text("List the skills taught")))));
        assertEquals("{\"subject\":\"math\",\"skills\":[]}", reply);

        JsonNode req = mapper.readTree(captured.get());
        assertEquals("deepseek-flash", req.get("model").asText());
        assertEquals("json_object", req.get("response_format").get("type").asText());
        assertEquals("system", req.get("messages").get(0).get("role").asText());
        assertEquals("SYSTEM RULES", req.get("messages").get(0).get("content").asText());
        JsonNode parts = req.get("messages").get(1).get("content");
        assertEquals("image_url", parts.get(0).get("type").asText());
        assertTrue(parts.get(0).get("image_url").get("url").asText().startsWith("data:image/png;base64,AQID"));
        assertEquals("List the skills taught", parts.get(1).get("text").asText());
    }

    @Test void textOnlyTurnsUseTheTextModel() throws Exception {
        client.complete("S", List.of(new LlmClient.Turn("user", List.of(new LlmClient.Block.Text("Skill: x")))));
        assertEquals("deepseek-v4-pro", mapper.readTree(captured.get()).get("model").asText());
    }

    @Test void pdfIsNotSupportedDirectly() {
        assertFalse(client.supportsPdf());
    }
}
