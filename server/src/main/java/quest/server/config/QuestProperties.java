package quest.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "quest")
public record QuestProperties(Anthropic anthropic, Storage storage, boolean fakeLlm) {
    public record Anthropic(String apiKey, String model, long maxTokens) {}
    public record Storage(String kind, String localDir, String gcsBucket) {}
}
