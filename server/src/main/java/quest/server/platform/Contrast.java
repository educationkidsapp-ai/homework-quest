package quest.server.platform;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * WCAG 2.2 contrast, the rule a school theme is measured against (§3: "reject a theme whose contrast ratio is below
 * 4.5:1 on any text/background pair and show which pair failed").
 *
 * <p>Relative luminance and the (L1 + 0.05) / (L2 + 0.05) ratio are the definitions from WCAG 2.2 §1.4.3, sRGB with
 * the 2.4 gamma. Nothing here is theme-specific: {@link ThemeService} owns which pairs are checked.
 */
public final class Contrast {
    /** §3: the threshold for text on its background. */
    public static final double MINIMUM = 4.5;

    /** The only colour syntax a theme may use — no names, no rgba(), no 3-digit shorthand. */
    public static final Pattern HEX = Pattern.compile("^#[0-9A-Fa-f]{6}$");

    private Contrast() {}

    public static boolean isHex(String colour) { return colour != null && HEX.matcher(colour).matches(); }

    /** 1.0 (identical) to 21.0 (black on white). */
    public static double ratio(String foreground, String background) {
        double a = luminance(foreground), b = luminance(background);
        double lighter = Math.max(a, b), darker = Math.min(a, b);
        return (lighter + 0.05) / (darker + 0.05);
    }

    /** As the §3 message spells it: one decimal, so "2.9:1" is what the Admin reads. */
    public static String format(double ratio) { return String.format(Locale.ROOT, "%.1f", ratio); }

    public static double luminance(String hex) {
        int rgb = Integer.parseInt(hex.substring(1), 16);
        return 0.2126 * channel((rgb >> 16) & 0xFF) + 0.7152 * channel((rgb >> 8) & 0xFF) + 0.0722 * channel(rgb & 0xFF);
    }

    private static double channel(int value) {
        double c = value / 255.0;
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    /**
     * The same move `design/tokens.json` documents for `color.accent-strong` ("the same red darkened until … both
     * clear 4.5:1"), done arithmetically: scale the colour's linear channels down until it clears {@code minimum}
     * against {@code background}, keeping its hue. Used to build the default theme's `mascotColor` from the tokens'
     * mascot blue, which — like any light blue on a near-white ground — is far below the threshold as it stands.
     *
     * <p>Returns the colour unchanged when it already clears the threshold, and never goes past black.
     */
    public static String darkenUntil(String colour, String background, double minimum) {
        if (ratio(colour, background) >= minimum) return colour;
        double target = (luminance(background) + 0.05) / minimum - 0.05;
        double current = luminance(colour);
        if (target <= 0 || current <= 0) return "#000000";
        double factor = target / current;
        for (int attempt = 0; attempt < 40 && factor > 0; attempt++, factor *= 0.96) {
            String candidate = scaled(colour, factor);
            if (ratio(candidate, background) >= minimum) return candidate;
        }
        return "#000000";
    }

    /** Multiplies the linear-light value of every channel by {@code factor} and encodes back to sRGB hex. */
    private static String scaled(String hex, double factor) {
        int rgb = Integer.parseInt(hex.substring(1), 16);
        return String.format(Locale.ROOT, "#%02X%02X%02X",
                encode(channel((rgb >> 16) & 0xFF) * factor), encode(channel((rgb >> 8) & 0xFF) * factor), encode(channel(rgb & 0xFF) * factor));
    }

    private static int encode(double linear) {
        double clamped = Math.clamp(linear, 0.0, 1.0);
        double srgb = clamped <= 0.0031308 ? clamped * 12.92 : 1.055 * Math.pow(clamped, 1 / 2.4) - 0.055;
        return (int) Math.round(Math.clamp(srgb, 0.0, 1.0) * 255);
    }
}
