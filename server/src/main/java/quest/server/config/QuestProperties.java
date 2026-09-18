package quest.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * §A: there is no `platform-name` property any more. The product's name lives in the `platform_settings` row seeded
 * by `V5__flags_themes.sql`, is edited by Admin under Platform settings, and is read through
 * {@link quest.server.platform.PlatformSettingsService} — an environment variable that could silently win over the
 * Admin's own setting would be a second source of truth.
 *
 * <p>`dashboard-dir` (DASHBOARD_DIR) is where the built Angular bundle lives on disk; {@link DashboardController}
 * serves it at `/dashboard/` (D10) and answers 404 while it is blank. `dashboard-url` is a different thing: the
 * origin the invite and reset links in {@link quest.server.mail.DashboardMails} point at.
 */
@ConfigurationProperties(prefix = "quest")
public record QuestProperties(Auth auth, Llm llm, Anthropic anthropic, DeepSeek deepseek, Storage storage, Admin admin, Mail mail,
                              Seed seed, String version, String publicUrl, String dashboardUrl, String dashboardDir) {
    /** fake=true accepts `Bearer fake-token-<uid>` (development without Firebase). */
    public record Auth(boolean fake, String firebaseCredentials, String jwtSecret, long jwtHours,
                       long accessMinutes, long refreshDays, long resetMinutes, long inviteDays, long impersonateMinutes,
                       int signInAttempts, long signInWindowMinutes) {}
    /**
     * provider: anthropic | deepseek | fake.
     *
     * <p>`price-per-1k-tokens` is what the Billing tab (§6 screen 5) and Platform usage & cost (§6 screen 10) turn
     * `lessons.token_usage` into money with. <strong>It is an assumption, not an invoice.</strong> The pipeline
     * records one combined input+output token count per lesson, while every provider prices the two separately, so
     * no single rate can be exact; the default is a blended figure for the configured DeepSeek chat model and is
     * deliberately configurable (`LLM_PRICE_PER_1K_TOKENS`) so the Admin can set the rate their own bill actually
     * uses. Both endpoints return the rate they applied alongside the totals, so the screen shows the assumption
     * rather than hiding it.
     */
    public record Llm(String provider, Double pricePer1kTokens) {
        /** USD per 1 000 tokens when nothing is configured; see the note above. */
        public static final double DEFAULT_PRICE_PER_1K_TOKENS = 0.0004;

        public double price() { return pricePer1kTokens == null || pricePer1kTokens < 0 ? DEFAULT_PRICE_PER_1K_TOKENS : pricePer1kTokens; }
    }
    public record Anthropic(String apiKey, String model, long maxTokens) {}
    public record DeepSeek(String apiKey, String baseUrl, String model, String visionModel, long maxTokens) {}
    public record Storage(String kind, String localDir, String gcsBucket) {}
    public record Admin(String seedEmail, String seedPassword) {}
    /** provider: log (default) | resend. `api-key` is RESEND_API_KEY, `from` the verified sender. */
    public record Mail(String provider, String apiKey, String from) {}
    /**
     * `school` (SEED_SCHOOL) is what lets {@link quest.server.classes.SchoolSeed} load `resources/seed/*.csv` into the
     * default school; it is off everywhere but `qa` and `h2`, and `prod` cannot switch it on because the seed is not a
     * bean there. `staff-password` (SEED_STAFF_PASSWORD) is the one password the seeded teachers share so an e2e run
     * can sign in as any of them — blank leaves each of them with her own one-time password, which nothing prints.
     */
    public record Seed(boolean school, String staffPassword) {}
}
