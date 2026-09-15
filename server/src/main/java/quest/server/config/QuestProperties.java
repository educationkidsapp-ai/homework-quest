package quest.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "quest")
public record QuestProperties(Auth auth, Llm llm, Anthropic anthropic, DeepSeek deepseek, Storage storage, Admin admin, Mail mail,
                              String version, String publicUrl, String dashboardUrl, String platformName) {
    /**
     * The platform's name as it is first seeded (§A). It lives here, not in the mail code: P2.1 moves it into a
     * `platform_settings` row and `quest.server.mail.PlatformName` is the only class that has to follow.
     */
    public static final String SEEDED_PLATFORM_NAME = "Schools Dashboard";

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
