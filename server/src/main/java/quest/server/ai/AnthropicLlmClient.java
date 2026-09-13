package quest.server.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.Base64ImageSource;
import com.anthropic.models.messages.Base64PdfSource;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.DocumentBlockParam;
import com.anthropic.models.messages.ImageBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quest.server.config.QuestProperties;

/**
 * Anthropic Messages API through the official Java SDK. PDFs go as document blocks, slide images as image
 * blocks, with adaptive thinking (the Opus 5 default). Nothing about the slides is logged.
 */
public class AnthropicLlmClient implements LlmClient {
    private static final Logger log = LoggerFactory.getLogger(AnthropicLlmClient.class);
    private final AnthropicClient client;
    private final QuestProperties.Anthropic props;

    public AnthropicLlmClient(QuestProperties.Anthropic props) {
        this.props = props;
        this.client = AnthropicOkHttpClient.builder().apiKey(props.apiKey()).build();
    }

    @Override
    public String complete(String system, List<Turn> turns) throws LlmException {
        MessageCreateParams.Builder builder = MessageCreateParams.builder()
                .model(props.model())
                .maxTokens(props.maxTokens())
                .thinking(ThinkingConfigAdaptive.builder().build())
                // The long, stable system prompt (rules + schema) is cached across calls.
                .systemOfTextBlockParams(List.of(TextBlockParam.builder().text(system)
                        .cacheControl(CacheControlEphemeral.builder().build()).build()));
        for (Turn turn : turns) {
            List<ContentBlockParam> blocks = new ArrayList<>();
            for (Block b : turn.blocks()) blocks.add(toParam(b));
            if (turn.role().equals("assistant")) builder.addAssistantMessageOfBlockParams(blocks);
            else builder.addUserMessageOfBlockParams(blocks);
        }
        try {
            Message response = client.messages().create(builder.build());
            String stop = response.stopReason().map(Object::toString).orElse("");
            if (stop.contains("refusal")) throw new LlmException("The model declined to read this content.");
            if (stop.contains("max_tokens")) throw new LlmException("The model answer was cut off.");
            StringBuilder text = new StringBuilder();
            response.content().forEach(block -> block.text().ifPresent(t -> text.append(t.text())));
            log.info("anthropic call ok model={} in={} out={}", props.model(), response.usage().inputTokens(), response.usage().outputTokens());
            return text.toString();
        } catch (AnthropicServiceException e) {
            log.warn("anthropic call failed status={} type={}", e.statusCode(), e.errorType().map(Object::toString).orElse("?"));
            throw new LlmException("Anthropic API error " + e.statusCode(), e);
        } catch (RuntimeException e) {
            throw new LlmException("Anthropic call failed: " + e.getClass().getSimpleName(), e);
        }
    }

    private static ContentBlockParam toParam(Block b) {
        if (b instanceof Block.Text t) return ContentBlockParam.ofText(TextBlockParam.builder().text(t.text()).build());
        if (b instanceof Block.Pdf p) {
            return ContentBlockParam.ofDocument(DocumentBlockParam.builder()
                    .source(Base64PdfSource.builder().data(Base64.getEncoder().encodeToString(p.bytes())).build())
                    .title(p.title()).build());
        }
        Block.Image i = (Block.Image) b;
        Base64ImageSource.MediaType mt = switch (i.mediaType()) {
            case "image/png" -> Base64ImageSource.MediaType.IMAGE_PNG;
            case "image/webp" -> Base64ImageSource.MediaType.IMAGE_WEBP;
            case "image/gif" -> Base64ImageSource.MediaType.IMAGE_GIF;
            default -> Base64ImageSource.MediaType.IMAGE_JPEG;
        };
        return ContentBlockParam.ofImage(ImageBlockParam.builder()
                .source(Base64ImageSource.builder().mediaType(mt).data(Base64.getEncoder().encodeToString(i.bytes())).build())
                .build());
    }
}
