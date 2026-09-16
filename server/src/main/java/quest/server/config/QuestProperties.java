package quest.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * §A: there is no `platform-name` property any more. The product's name lives in the `platform_settings` row seeded
 * by `V5__flags_themes.sql`, is edited by Admin under Platform settings, and is read through
 * {@link quest.server.platform.PlatformSettingsService} — an environment variable that could silently win over the
 * Admin's own setting would be a second source of truth.
 */
@ConfigurationProperties(prefix = "quest")
public record QuestProperties(Auth auth, Llm llm, Anthropic anthropic, DeepSeek deepseek, Storage storage, Admin admin, Mail mail,
                              String version, String publicUrl, String dashboardUrl) {
    /** fake=true accepts `Bearer fake-token-<uid>` (development without Firebase). */
    public record Auth(boolean fake, String firebaseCredentials, String jwtSecret, long jwtHours,
                       long accessMinutes, long refreshDays, long resetMinutes, long inviteDays, long impersonateMinutes,
                       int signInAttempts, long signInWindowMinutes) {}
    /** provider: anthropic | deepseek | fake */
    public record Llm(String provider) {}
    public record Anthropic(String apiKey, String model, long maxTokens) {}
    public record DeepSeek(String apiKey, String baseUrl, String model, String visionModel, long maxTokens) {}
    public record Storage(String kind, String localDir, String gcsBucket) {}
    public record Admin(String seedEmail, String seedPassword) {}
    /** provider: log (default) | resend. `api-key` is RESEND_API_KEY, `from` the verified sender. */
    public record Mail(String provider, String apiKey, String from) {}
}
