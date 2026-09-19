package quest.server.analysis;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Base64ImageSource;
import com.anthropic.models.messages.Base64PdfSource;
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

/** Optional provider: Claude via the official Java SDK. PDFs go in as document blocks, images as image blocks. */
public class AnthropicClient implements LlmClient {
    private static final Logger log = LoggerFactory.getLogger(AnthropicClient.class);
    private final com.anthropic.client.AnthropicClient client; private final String model; private final long maxTokens;

    public AnthropicClient(QuestProperties.Anthropic cfg, QuestProperties.Llm llm) {
        if (cfg == null || cfg.apiKey() == null || cfg.apiKey().isBlank()) throw new IllegalStateException("ANTHROPIC_API_KEY is not set");
        var limits = llm == null ? QuestProperties.Llm.defaults() : llm;
        // The SDK's `timeout` is the whole request; a call past it throws, which `complete` already turns into a
        // transient failure. Without it the socket could hold open for as long as the provider cared to.
        this.client = AnthropicOkHttpClient.builder().apiKey(cfg.apiKey()).timeout(limits.timeout()).build();
        log.info("Anthropic timeout: {}s", limits.timeout().toSeconds());
        this.model = cfg.model() == null || cfg.model().isBlank() ? "claude-opus-5" : cfg.model();
        this.maxTokens = cfg.maxTokens() > 0 ? cfg.maxTokens() : 16000;
    }

    @Override public String name() { return "anthropic"; }

    @Override public Result complete(String system, String user, List<Attachment> attachments) {
        List<ContentBlockParam> blocks = new ArrayList<>();
        if (attachments != null) for (var a : attachments) {
            String b64 = Base64.getEncoder().encodeToString(a.bytes());
            if (a.mimeType().equals("application/pdf")) blocks.add(ContentBlockParam.ofDocument(DocumentBlockParam.builder().source(Base64PdfSource.builder().data(b64).build()).build()));
            else blocks.add(ContentBlockParam.ofImage(ImageBlockParam.builder().source(Base64ImageSource.builder().mediaType(Base64ImageSource.MediaType.of(a.mimeType())).data(b64).build()).build()));
        }
        blocks.add(ContentBlockParam.ofText(TextBlockParam.builder().text(user).build()));
        var params = MessageCreateParams.builder().model(model).maxTokens(maxTokens).system(system).thinking(ThinkingConfigAdaptive.builder().build()).addUserMessageOfBlockParams(blocks).build();
        Message message;
        try { message = client.messages().create(params); } catch (RuntimeException e) { throw new LlmException("Anthropic call failed: " + e.getMessage(), e, true); }
        var text = new StringBuilder();
        for (var block : message.content()) block.text().ifPresent(t -> text.append(t.text()));
        if (text.isEmpty()) throw new LlmException("Anthropic returned no text");
        log.info("Anthropic {} ok: in={} out={}", model, message.usage().inputTokens(), message.usage().outputTokens());
        return new Result(DeepSeekClient.stripFences(text.toString()), message.usage().inputTokens(), message.usage().outputTokens());
    }
}
