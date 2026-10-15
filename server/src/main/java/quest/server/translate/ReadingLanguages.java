package quest.server.translate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import quest.server.config.ApiException;

/**
 * Feature 29: the eight languages the bridge speaks, and the one place that decides whether a code is one of them.
 *
 * <p><strong>An allow-list, not a free text field.</strong> The pilot school's families read Arabic, English, Urdu,
 * Hindi, Bengali, Filipino, French and Indonesian; a language nobody at the school can check the wording of is worse
 * for a parent than the English she was already given, so anything else is a 400 rather than a translation nobody
 * can read back. The order is the order a picker shows them in: the school's own two first.
 *
 * <p>The name beside each code is what {@link quest.server.analysis.Prompts#userTranslate} names the target language
 * by — "Urdu (ur)" rather than "ur", because a two-letter code is the one thing a model is most likely to guess
 * wrongly, and `fil` and `id` are exactly the two it would guess wrongly.
 */
public final class ReadingLanguages {
    private ReadingLanguages() {}

    private static final Map<String, String> NAMES = names();

    private static Map<String, String> names() {
        var out = new LinkedHashMap<String, String>();
        out.put("ar", "Arabic"); out.put("en", "English"); out.put("ur", "Urdu"); out.put("hi", "Hindi");
        out.put("bn", "Bengali"); out.put("fil", "Filipino"); out.put("fr", "French"); out.put("id", "Indonesian");
        return Map.copyOf(out);
    }

    /** The eight codes, in picker order. */
    public static final List<String> ALL = List.of("ar", "en", "ur", "hi", "bn", "fil", "fr", "id");

    /** The code as it is stored and serialised, or `400` for anything that is not one of the eight. */
    public static String require(String lang) {
        String code = lang == null ? "" : lang.strip().toLowerCase(java.util.Locale.ROOT);
        if (!NAMES.containsKey(code))
            throw ApiException.badRequest("Choose one of " + String.join(", ", ALL) + ".");
        return code;
    }

    /** `Urdu (ur)` — what the prompt names the target language by. */
    public static String label(String code) { return NAMES.get(code) + " (" + code + ")"; }
}
