package quest.server.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import quest.server.ai.AnthropicLlmClient;
import quest.server.ai.LlmClient;
import quest.server.ai.SampleLlmClient;
import quest.server.files.FileStore;
import quest.server.files.GcsFileStore;
import quest.server.files.LocalFileStore;

@Configuration
public class AppConfig {
    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);

    @Bean
    public LlmClient llmClient(QuestProperties props) {
        if (props.fakeLlm() || props.anthropic().apiKey() == null || props.anthropic().apiKey().isBlank()) {
            log.warn("FAKE_LLM active or ANTHROPIC_API_KEY missing: answering from sample outputs");
            return new SampleLlmClient();
        }
        return new AnthropicLlmClient(props.anthropic());
    }

    @Bean
    public FileStore fileStore(QuestProperties props) {
        if ("gcs".equalsIgnoreCase(props.storage().kind())) return new GcsFileStore(props.storage().gcsBucket());
        return new LocalFileStore(props.storage().localDir());
    }

    @Bean
    public Json json(ObjectMapper mapper) { return new Json(mapper); }
}
