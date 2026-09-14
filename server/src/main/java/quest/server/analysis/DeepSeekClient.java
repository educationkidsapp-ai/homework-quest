package quest.server.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quest.server.config.QuestProperties;

/**
 * DeepSeek V4 over its OpenAI-compatible `/chat/completions`: JSON mode, images as `image_url` data URLs.
 * The vision model reads slides (Prompt A with page images); the text model writes plays and panels.
 * DeepSeek has no PDF input, so {@link SlideProcessor} renders pages to PNG.
 */
public class DeepSeekClient implements LlmClient {
    private static final Logger log = LoggerFactory.getLogger(DeepSeekClient.class);
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final QuestProperties.DeepSeek cfg;

    public DeepSeekClient(QuestProperties.DeepSeek cfg) {
        if (cfg == null || cfg.apiKey() == null || cfg.apiKey().isBlank()) throw new IllegalStateException("DEEPSEEK_API_KEY is not set");
        this.cfg = cfg;
    }

    @Override public String name() { return "deepseek"; }

    @Override public Result complete(String system, String user, List<Attachment> attachments) {
        boolean vision = attachments != null && !attachments.isEmpty();
        String model = vision && cfg.visionModel() != null && !cfg.visionModel().isBlank() ? cfg.visionModel() : cfg.model();
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model); body.put("max_tokens", cfg.maxTokens() > 0 ? cfg.maxTokens() : 8192); body.put("temperature", 0.4);
        body.putObject("response_format").put("type", "json_object");
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", system);
        ObjectNode userMsg = messages.addObject(); userMsg.put("role", "user");
        if (!vision) userMsg.put("content", user);
        else {
            ArrayNode parts = userMsg.putArray("content");
            for (var a : attachments) {
                if (a.mimeType().equals("application/pdf")) throw new LlmException("DeepSeek cannot read PDFs directly");
                parts.addObject().put("type", "image_url").putObject("image_url").put("url", "data:" + a.mimeType() + ";base64," + Base64.getEncoder().encodeToString(a.bytes()));
            }
            parts.addObject().put("type", "text").put("text", user);
        }
        String base = cfg.baseUrl() == null || cfg.baseUrl().isBlank() ? "https://api.deepseek.com" : cfg.baseUrl().replaceAll("/+$", "");
        var request = HttpRequest.newBuilder(URI.create(base + "/chat/completions"))
                .header("Authorization", "Bearer " + cfg.apiKey()).header("Content-Type", "application/json")
                .timeout(Duration.ofMinutes(6)).POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                HttpResponse<String> res = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() >= 500 || res.statusCode() == 429) { log.warn("DeepSeek {} (attempt {}): {}", res.statusCode(), attempt + 1, abbreviate(res.body())); sleep(2000L * (attempt + 1)); continue; }
                if (res.statusCode() != 200) throw new LlmException("DeepSeek " + res.statusCode() + ": " + abbreviate(res.body()));
                JsonNode json = mapper.readTree(res.body());
                String text = json.path("choices").path(0).path("message").path("content").asText("");
                if (text.isBlank()) throw new LlmException("DeepSeek returned no content");
                JsonNode usage = json.path("usage");
                log.info("DeepSeek {} ok: in={} out={}", model, usage.path("prompt_tokens").asLong(), usage.path("completion_tokens").asLong());
                return new Result(stripFences(text), usage.path("prompt_tokens").asLong(), usage.path("completion_tokens").asLong());
            } catch (IOException e) { last = e; log.warn("DeepSeek I/O error (attempt {}): {}", attempt + 1, e.toString()); sleep(2000L * (attempt + 1)); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new LlmException("interrupted", e); }
        }
        throw new LlmException("DeepSeek unreachable", last);
    }

    /** Removes markdown fences and any explicit nulls (the shared codec treats a missing key as null, the schemas don't allow null). */
    static String stripFences(String s) {
        var t = s.trim();
        if (t.startsWith("```")) { t = t.substring(t.indexOf('\n') + 1); int end = t.lastIndexOf("```"); if (end >= 0) t = t.substring(0, end); }
        t = t.trim();
        try { var node = new ObjectMapper().readTree(t); dropNulls(node); return node.toString(); } catch (IOException e) { return t; }
    }

    static void dropNulls(JsonNode node) {
        if (node instanceof ObjectNode o) {
            var it = o.fields(); var remove = new java.util.ArrayList<String>();
            while (it.hasNext()) { var e = it.next(); if (e.getValue().isNull()) remove.add(e.getKey()); else dropNulls(e.getValue()); }
            remove.forEach(o::remove);
        } else if (node instanceof ArrayNode a) for (JsonNode n : a) dropNulls(n);
    }
    private static String abbreviate(String s) { return s == null ? "" : s.length() > 400 ? s.substring(0, 400) + "…" : s; }
    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
}
