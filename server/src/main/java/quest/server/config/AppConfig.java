package quest.server.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import quest.server.ai.AnthropicLlmClient;
import quest.server.ai.DeepSeekLlmClient;
import quest.server.ai.LlmClient;
import quest.server.ai.SampleLlmClient;
import quest.server.files.FileStore;
import quest.server.files.GcsFileStore;
import quest.server.files.LocalFileStore;

@Configuration
public class AppConfig {
    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);

    @Bean
    public LlmClient llmClient(QuestProperties props, ObjectMapper mapper) {
        String provider = props.llm() == null || props.llm().provider() == null ? "anthropic" : props.llm().provider().toLowerCase();
        if (props.fakeLlm() || provider.equals("fake")) {
            log.warn("FAKE_LLM active: answering from sample outputs");
            return new SampleLlmClient();
        }
        switch (provider) {
            case "deepseek" -> {
                if (blank(props.deepseek().apiKey())) { log.warn("DEEPSEEK_API_KEY missing: answering from sample outputs"); return new SampleLlmClient(); }
                log.info("LLM provider: DeepSeek model={}", props.deepseek().model());
                return new DeepSeekLlmClient(props.deepseek(), mapper);
            }
            case "anthropic" -> {
                if (blank(props.anthropic().apiKey())) { log.warn("ANTHROPIC_API_KEY missing: answering from sample outputs"); return new SampleLlmClient(); }
                log.info("LLM provider: Anthropic model={}", props.anthropic().model());
                return new AnthropicLlmClient(props.anthropic());
            }
            default -> throw new IllegalStateException("Unknown LLM_PROVIDER " + provider + " (anthropic | deepseek | fake)");
        }
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }

    @Bean
    public FileStore fileStore(QuestProperties props) {
        if ("gcs".equalsIgnoreCase(props.storage().kind())) return new GcsFileStore(props.storage().gcsBucket());
        return new LocalFileStore(props.storage().localDir());
    }

    @Bean
    public Json json(ObjectMapper mapper) { return new Json(mapper); }
}
