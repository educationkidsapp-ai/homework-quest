package quest.server.ai;

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
 * DeepSeek through its OpenAI-compatible Chat Completions API (`/chat/completions`).
 * Images go as base64 data URLs; JSON mode is requested with `response_format: json_object`.
 * DeepSeek takes no PDF documents, so {@link #supportsPdf()} is false and the pipeline renders pages to PNG first.
 */
public class DeepSeekLlmClient implements LlmClient {
    private static final Logger log = LoggerFactory.getLogger(DeepSeekLlmClient.class);
    private final QuestProperties.DeepSeek props;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();

    public DeepSeekLlmClient(QuestProperties.DeepSeek props, ObjectMapper mapper) { this.props = props; this.mapper = mapper; }

    @Override public boolean supportsPdf() { return false; }

    @Override
    public String complete(String system, List<Turn> turns) throws LlmException {
        boolean hasImages = turns.stream().flatMap(t -> t.blocks().stream()).anyMatch(b -> b instanceof Block.Image);
        String model = hasImages && props.visionModel() != null && !props.visionModel().isBlank() ? props.visionModel() : props.model();
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", props.maxTokens());
        body.put("temperature", 0.4);
        body.putObject("response_format").put("type", "json_object");
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", system);
        for (Turn turn : turns) {
            ObjectNode m = messages.addObject().put("role", turn.role());
            boolean textOnly = turn.blocks().stream().allMatch(b -> b instanceof Block.Text);
            if (textOnly) {
                m.put("content", turn.blocks().stream().map(b -> ((Block.Text) b).text()).reduce("", (a, b) -> a.isEmpty() ? b : a + "\n" + b));
            } else {
                ArrayNode parts = m.putArray("content");
                for (Block b : turn.blocks()) {
                    if (b instanceof Block.Text t) parts.addObject().put("type", "text").put("text", t.text());
                    else if (b instanceof Block.Image i) {
                        parts.addObject().put("type", "image_url").putObject("image_url")
                                .put("url", "data:" + i.mediaType() + ";base64," + Base64.getEncoder().encodeToString(i.bytes()))
                                .put("detail", "high");
                    } else if (b instanceof Block.Pdf) {
                        throw new LlmException("DeepSeek cannot read PDF documents directly; render pages to images first.");
                    }
                }
            }
        }
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(props.baseUrl().replaceAll("/$", "") + "/chat/completions"))
                    .timeout(Duration.ofSeconds(180))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + props.apiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
        } catch (IOException e) { throw new LlmException("request encoding failed", e); }

        HttpResponse<String> response;
        try { response = http.send(request, HttpResponse.BodyHandlers.ofString()); }
        catch (IOException e) { throw new LlmException("DeepSeek call failed: " + e.getClass().getSimpleName(), e); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new LlmException("interrupted", e); }

        if (response.statusCode() / 100 != 2) {
            log.warn("deepseek call failed status={}", response.statusCode());
            throw new LlmException("DeepSeek API error " + response.statusCode());
        }
        try {
            JsonNode root = mapper.readTree(response.body());
            JsonNode choice = root.path("choices").path(0);
            String finish = choice.path("finish_reason").asText("");
            if ("length".equals(finish)) throw new LlmException("The model answer was cut off.");
            if ("content_filter".equals(finish)) throw new LlmException("The model declined to read this content.");
            String content = choice.path("message").path("content").asText("");
            log.info("deepseek call ok model={} in={} out={}", model, root.path("usage").path("prompt_tokens").asInt(), root.path("usage").path("completion_tokens").asInt());
            return content;
        } catch (IOException e) { throw new LlmException("DeepSeek response was not JSON", e); }
    }
}
