package quest.server.analysis;

import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import quest.server.config.QuestProperties;

@Configuration
public class LlmConfig {
    @Bean
    public LlmClient llmClient(QuestProperties props) {
        String provider = props.llm() == null || props.llm().provider() == null ? "deepseek" : props.llm().provider().toLowerCase();
        LlmClient client = switch (provider) {
            case "fake", "sample" -> new SampleLlmClient();
            case "anthropic" -> new AnthropicClient(props.anthropic(), props.llm());
            default -> new DeepSeekClient(props.deepseek(), props.llm());
        };
        LoggerFactory.getLogger(LlmConfig.class).info("LLM provider: {}", client.name());
        return client;
    }
}
