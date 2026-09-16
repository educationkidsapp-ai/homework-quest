package quest.server.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * The default theme, read from the design tokens at startup rather than written out as literals — `design/tokens.json`
 * is the one source both front-ends generate from (D3), and this is the server's third reader of it.
 *
 * <p>The file is loaded from the classpath (`server/src/main/resources/design/tokens.json`) because the server's
 * Docker build context is `server/` alone and does not contain the repository's `design/` directory;
 * `DesignTokensDriftTest` fails the build when that copy and `design/tokens.json` differ.
 *
 * <p>How the §3 theme fields map onto the tokens:
 * <ul>
 *   <li>`ground` ← `color.bg`, `primary` ← `color.surface`, `primaryInk` ← `color.ink`, `softBorder` ← `color.rule`;</li>
 *   <li>`accent` ← `color.accent-strong`, not `color.accent`: the tokens file says accent-strong is the brand red
 *       darkened until it clears 4.5:1, and the brand red itself is 3.8:1 on the ground — it would be rejected by
 *       the very validation this default has to pass;</li>
 *   <li>`mascotColor` ← `mascotColor.body` darkened the same way (a light blue on a near-white ground is 1.6:1);</li>
 *   <li>each world ← `worldPalettes.<subject>` with `color.ink` as the ink drawn on it.</li>
 * </ul>
 */
@Component
public class DesignTokens {
    public static final String RESOURCE = "design/tokens.json";

    private final JsonNode root;
    private final ThemeDto.SchoolTheme defaultTheme;

    public DesignTokens(ObjectMapper mapper) {
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            this.root = mapper.readTree(in);
        } catch (IOException e) { throw new IllegalStateException(RESOURCE + " is missing or malformed", e); }
        String ground = colour("bg");
        var worlds = new LinkedHashMap<String, ThemeDto.WorldPalette>();
        for (String world : ThemeDto.WORLDS) worlds.put(world, world(world));
        this.defaultTheme = new ThemeDto.SchoolTheme(null, null, colour("surface"), colour("ink"), colour("accent-strong"),
                ground, colour("rule"), Contrast.darkenUntil(value("mascotColor", "body"), ground, Contrast.MINIMUM),
                java.util.Collections.unmodifiableMap(worlds), ThemeDto.FontChoice.NUNITO);
    }

    /** The theme a school with no theme of its own is shown (§3 "Default theme = the one in `docs/design.md`"). */
    public ThemeDto.SchoolTheme defaultTheme() { return defaultTheme; }

    /** `color.<name>.value`, e.g. `color.accent-strong` → `#CC2A0F`. */
    public String colour(String name) { return value("color", name); }

    private ThemeDto.WorldPalette world(String subject) {
        var node = require(root.path("worldPalettes").path(subject), "worldPalettes." + subject);
        return new ThemeDto.WorldPalette(text(node, "primary", subject), text(node, "deep", subject), text(node, "soft", subject), colour("ink"));
    }

    private String value(String group, String name) { return text(require(root.path(group), group), name, group); }

    private static String text(JsonNode parent, String name, String where) {
        var value = require(parent.path(name), where + "." + name).path("value");
        if (!value.isTextual()) throw new IllegalStateException(RESOURCE + ": " + where + "." + name + ".value is not a string");
        return value.asText();
    }

    private static JsonNode require(JsonNode node, String path) {
        if (node.isMissingNode() || node.isNull()) throw new IllegalStateException(RESOURCE + " has no " + path);
        return node;
    }
}
