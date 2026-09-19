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
    public record Llm(String provider, Double pricePer1kTokens, Integer timeoutSeconds, Integer connectTimeoutSeconds) {
        /** USD per 1 000 tokens when nothing is configured; see the note above. */
        public static final double DEFAULT_PRICE_PER_1K_TOKENS = 0.0004;
        /** One model call may hold a connection this long; anything past it is a transient failure, not a stuck job. */
        public static final int DEFAULT_TIMEOUT_SECONDS = 120;
        public static final int MAX_TIMEOUT_SECONDS = 120;
        public static final int DEFAULT_CONNECT_TIMEOUT_SECONDS = 10;

        public double price() { return pricePer1kTokens == null || pricePer1kTokens < 0 ? DEFAULT_PRICE_PER_1K_TOKENS : pricePer1kTokens; }

        /**
         * The read timeout of one HTTP call to the provider. It is <strong>capped</strong> at
         * {@value #MAX_TIMEOUT_SECONDS} s on purpose: a call that hangs is the whole of QA's stuck-lesson bug, and a
         * value an operator could raise to an hour would put the hang back. The step deadline
         * ({@link quest.server.analysis.PipelineDeadlines}) is the outer bound; this is the inner one.
         */
        public java.time.Duration timeout() { return java.time.Duration.ofSeconds(clamp(timeoutSeconds, DEFAULT_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS)); }
        public java.time.Duration connectTimeout() { return java.time.Duration.ofSeconds(clamp(connectTimeoutSeconds, DEFAULT_CONNECT_TIMEOUT_SECONDS, 60)); }

        private static int clamp(Integer value, int fallback, int max) { return value == null || value <= 0 ? fallback : Math.min(value, max); }

        /** The defaults, for a client built outside Spring (tests, {@link quest.server.analysis.LlmConfig}'s fallback when nothing is bound). */
        public static Llm defaults() { return new Llm(null, null, null, null); }
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
     *
     * <p>`profile` (SEED_PROFILE) picks <em>which</em> four files: `full` is the 30-class QA school the automated e2e
     * suite needs, `acceptance` the owner's three sections and two teachers with no children at all. `reset`
     * (SEED_RESET) is the one-shot wipe {@link quest.server.classes.SeedReset} runs before the seed — it is refused
     * outright under `prod`, and the deploy turns it back off once it has run.
     */
    public record Seed(boolean school, String profile, boolean reset, String staffPassword) {
        /** The 30-class QA school of `resources/seed/*.csv`. */
        public static final String FULL = "full";
        /** The owner's acceptance school of `resources/seed/acceptance/*.csv`: 3 sections, 2 teachers, no children. */
        public static final String ACCEPTANCE = "acceptance";

        /**
         * `full` when nothing is configured, else the profile named — and a name that is neither <strong>fails the
         * start</strong>. A typo silently falling back to `full` is the worst of the three outcomes: the deploy
         * reports success and QA quietly fills with the 30-class school the owner asked to be rid of.
         */
        public String profileOrFull() {
            if (profile == null || profile.isBlank()) return FULL;
            String name = profile.strip().toLowerCase(java.util.Locale.ROOT);
            if (FULL.equals(name) || ACCEPTANCE.equals(name)) return name;
            throw new IllegalStateException("SEED_PROFILE=" + profile + " is not a seed profile: use `"
                    + FULL + "` (the 30-class QA school) or `" + ACCEPTANCE + "` (the owner's two teachers).");
        }

        /** Where {@link quest.server.classes.SchoolSeed} reads its four files from, classpath-relative. */
        public String directory() { return ACCEPTANCE.equals(profileOrFull()) ? "seed/acceptance/" : "seed/"; }
    }
}
