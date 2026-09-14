package quest.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "quest")
public record QuestProperties(Auth auth, Llm llm, Anthropic anthropic, DeepSeek deepseek, Storage storage, Admin admin, String version, String publicUrl) {
    /** fake=true accepts `Bearer fake-token-<uid>` (development without Firebase). */
    public record Auth(boolean fake, String firebaseCredentials, String jwtSecret, long jwtHours) {}
    /** provider: anthropic | deepseek | fake */
    public record Llm(String provider) {}
    public record Anthropic(String apiKey, String model, long maxTokens) {}
    public record DeepSeek(String apiKey, String baseUrl, String model, String visionModel, long maxTokens) {}
    public record Storage(String kind, String localDir, String gcsBucket) {}
    public record Admin(String seedEmail, String seedPassword) {}
}
