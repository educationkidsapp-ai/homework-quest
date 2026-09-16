package quest.server.platform;

import java.util.Locale;
import quest.server.config.ApiException;

/**
 * The two free-text fields §3 and §A let an Admin set — a logo URL and a display name — reach places that make them
 * dangerous if they are taken on trust:
 *
 * <ul>
 *   <li>`logoUrl` is served by the <em>public</em> `GET /schools/{id}/theme`, `GET /platform-settings` and
 *       `JoinSchoolInfo`, and both front-ends put it in an `img src`. A `javascript:` or `data:` value there is
 *       stored XSS handed to the dashboard and the app, so only `https://` is accepted — not even `http://`, which
 *       would be a mixed-content block in the browser and a cleartext fetch in the app.</li>
 *   <li>`appName` becomes `GET /me.platformName`, a page title and a mail subject. Control characters in a subject
 *       are header injection; an unbounded one is a payload on a `max-age=300` public route.</li>
 * </ul>
 *
 * Both are size-capped here as well as with `@Size`, because `@Valid` only reaches the top-level body: a theme
 * arriving as `UpdatePlatformSettingsRequest.defaultTheme` is validated by {@link ThemeService#validated} alone.
 *
 * <p>Public since P4.0: a teacher's `photoUrl` reaches the same places a school's logo does — an `img src` in the
 * dashboard and in the app's teacher island — so it is checked by exactly this rule rather than by a second one
 * that could drift from it.
 */
public final class SafeText {
    /** Long enough for a signed storage URL, short enough that the public theme body stays small. */
    public static final int MAX_URL = 2000;
    /** A school's display name, a page title's worth. */
    public static final int MAX_NAME = 60;
    /** The longest address RFC 5321 allows (64 local + @ + 255 domain, minus the pair of path brackets). */
    static final int MAX_EMAIL = 254;

    /**
     * Characters that end an HTML attribute or start a tag. A logo URL is written into `img src="…"` by both
     * front-ends, so a value carrying `"`, `<` or `>` can close the attribute and open an element even though the
     * scheme is `https://`; whitespace inside a URL is never legal and is how such a payload is usually smuggled
     * past a naive prefix check. See {@link #httpsUrl}.
     */
    private static final String URL_FORBIDDEN = "\"<>";

    private SafeText() {}

    /**
     * A blank value means "not set" and answers null; anything else must be an `https://` URL within
     * {@link #MAX_URL}, free of control characters, of whitespace, and of the {@link #URL_FORBIDDEN} characters that
     * would let it break out of the `img src` attribute it is rendered into.
     */
    public static String httpsUrl(String value, String field) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim();
        if (trimmed.length() > MAX_URL) throw ApiException.badRequest(field + " must be at most " + MAX_URL + " characters, not " + trimmed.length());
        if (hasControlCharacter(trimmed)) throw ApiException.badRequest(field + " must not contain control characters");
        if (!trimmed.toLowerCase(Locale.ROOT).startsWith("https://"))
            throw ApiException.badRequest(field + " must be an https:// URL");
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (Character.isWhitespace(c)) throw ApiException.badRequest(field + " must not contain spaces");
            if (URL_FORBIDDEN.indexOf(c) >= 0) throw ApiException.badRequest(field + " must not contain " + c);
        }
        return trimmed;
    }

    /** A blank value means "not set" and answers null; anything else is trimmed, capped and free of control characters. */
    public static String plainText(String value, String field, int max) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim();
        if (trimmed.length() > max) throw ApiException.badRequest(field + " must be at most " + max + " characters, not " + trimmed.length());
        if (hasControlCharacter(trimmed)) throw ApiException.badRequest(field + " must not contain control characters");
        return trimmed;
    }

    private static boolean hasControlCharacter(String value) {
        return value.chars().anyMatch(Character::isISOControl);
    }
}
