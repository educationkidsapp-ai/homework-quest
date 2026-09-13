package quest.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "quest")
public record QuestProperties(Llm llm, Anthropic anthropic, DeepSeek deepseek, Storage storage, boolean fakeLlm) {
    /** provider: anthropic | deepseek | fake */
    public record Llm(String provider) {}
    public record Anthropic(String apiKey, String model, long maxTokens) {}
    /** `model` handles text-only turns (Prompt B); `visionModel` handles turns that carry images (Prompt A). */
    public record DeepSeek(String apiKey, String baseUrl, String model, String visionModel, long maxTokens) {}
    public record Storage(String kind, String localDir, String gcsBucket) {}
}
