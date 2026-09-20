package quest.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import quest.server.ApiTestSupport;

/**
 * D10: the Angular dashboard is served by the API at `/dashboard/`. The fixture below is a miniature of what
 * `pnpm build --configuration=qa` actually emits — `main-<8 base32 chars>.js`, an unhashed `assets/i18n/en.json`
 * and a bundled `media/*.woff2` — so the cache rules are checked against real Angular 22 output names rather than
 * against names invented to fit the pattern.
 *
 * <p>`quest.panel-dir` is pointed at the same directory: `/panel/` and `/dashboard/` share
 * {@link StaticBundle}, and the legacy panel must keep working exactly as it did until P3.6 retires it.
 */
class DashboardStaticTest extends ApiTestSupport {
    /** Set by {@link #bundle()}; false (the filesystem refused symlinks) skips the symlink test. */
    private static boolean SYMLINKS;

    private static final Path BUNDLE = bundle();

    @DynamicPropertySource static void bundleDir(DynamicPropertyRegistry registry) {
        registry.add("quest.dashboard-dir", BUNDLE::toString);
        registry.add("quest.panel-dir", BUNDLE::toString);
    }

    @Test void the_root_serves_the_shell_and_is_never_cached() throws Exception {
        mvc.perform(get("/dashboard/")).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("<hq-root>")))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-cache")));
        mvc.perform(get("/dashboard")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("<hq-root>")));
    }

    @Test void a_client_route_falls_back_to_the_shell() throws Exception {
        mvc.perform(get("/dashboard/admin/schools")).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("<hq-root>")))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-cache")));
    }

    @Test void content_hashed_files_are_immutable_and_unhashed_ones_are_not() throws Exception {
        for (String hashed : new String[]{"main-0123abcd.js", "main-WQMMA2Q6.js", "chunk-2RKMGULP.js", "styles-E4MV3RDG.css", "media/archivo_variable-CP6HG7EB.woff2"})
            mvc.perform(get("/dashboard/" + hashed)).andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.allOf(
                            org.hamcrest.Matchers.containsString("max-age=31536000"), org.hamcrest.Matchers.containsString("immutable"))));
        // Angular copies src/assets/** and public/** verbatim: those names survive a deploy, so they must not be pinned
        for (String plain : new String[]{"assets/i18n/en.json", "assets/fonts/archivo_variable.woff2", "favicon.ico"})
            mvc.perform(get("/dashboard/" + plain)).andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-cache")));
    }

    @Test void every_bundle_extension_gets_the_mime_type_the_browser_needs() throws Exception {
        mvc.perform(get("/dashboard/main-WQMMA2Q6.js")).andExpect(content().contentTypeCompatibleWith("text/javascript"));
        mvc.perform(get("/dashboard/styles-E4MV3RDG.css")).andExpect(content().contentTypeCompatibleWith("text/css"));
        mvc.perform(get("/dashboard/media/archivo_variable-CP6HG7EB.woff2")).andExpect(content().contentType("font/woff2"));
        mvc.perform(get("/dashboard/assets/i18n/en.json")).andExpect(content().contentTypeCompatibleWith("application/json"));
        mvc.perform(get("/dashboard/manifest.webmanifest")).andExpect(content().contentType("application/manifest+json"));
    }

    /** `media-src` is N4.2's addition: the Results page plays back the retell a child recorded. */
    @Test void the_security_headers_are_on_every_dashboard_response() throws Exception {
        for (String path : new String[]{"/dashboard/", "/dashboard/admin/schools", "/dashboard/main-WQMMA2Q6.js"})
            mvc.perform(get(path)).andExpect(status().isOk())
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                    .andExpect(header().string("Content-Security-Policy",
                            "default-src 'self'; img-src 'self' https: data:; media-src 'self' data:; style-src 'self' 'unsafe-inline'; connect-src 'self'"));
    }

    /**
     * Two layers, and the test asserts what each one really does. A `..` or `//` never reaches the handler in the
     * first place — Spring Security's `StrictHttpFirewall` answers 400 here, and behind Tomcat the connector has
     * already normalised the URI to `/application.yml`, which is a 404 with no handler. Either way the file is not
     * served, so the assertion is 4xx rather than a status that depends on which layer ran.
     */
    @Test void a_path_cannot_climb_out_of_the_bundle() throws Exception {
        assertThat(Files.exists(BUNDLE.getParent().resolve("secret.txt"))).as("the fixture really does have a file to steal one level up").isTrue();
        for (String escape : new String[]{"/dashboard/../application.yml", "/dashboard/../../secret.txt", "/dashboard/media/../../secret.txt", "/panel/../application.yml"}) {
            var response = mvc.perform(get(escape)).andReturn().getResponse();
            assertThat(response.getStatus()).as("%s must not be served", escape).isBetween(400, 499);
            assertThat(response.getContentAsString()).as(escape).doesNotContain("not reachable through").doesNotContain("spring:");
        }
        // A doubled slash is not a traversal. MockMvc leaves `/dashboard//etc/passwd` to the handler, which treats it
        // as an unknown client route and answers the shell; under real Tomcat the connector collapses it to
        // `/etc/passwd` first, which is a 401 from `anyRequest().authenticated()`. Neither serves the file.
        mvc.perform(get("/dashboard//etc/passwd")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("<hq-root>")));
    }

    /**
     * A symlink planted inside the bundle points outside it, and `normalize()` — being purely lexical — cannot see
     * that, so the root and the candidate are both resolved with `toRealPath()` before they are compared. Defence in
     * depth (it takes a link inside the image to exploit), but this is the one rule the two bundles share precisely
     * because it must never be wrong.
     */
    @Test void a_symlink_inside_the_bundle_cannot_escape_it() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(SYMLINKS, "the filesystem does not allow symlinks");
        for (String escape : new String[]{"/dashboard/pw-CCCCDDDD.js", "/dashboard/res/secret.txt", "/panel/pw-CCCCDDDD.js"}) {
            var response = mvc.perform(get(escape)).andExpect(status().isNotFound()).andReturn().getResponse();
            assertThat(response.getContentAsString()).as(escape).doesNotContain("not reachable through");
        }
        var bundle = new StaticBundle(BUNDLE.toString(), "dashboard");
        assertThat(bundle.resolve(requestFor("/dashboard/pw-CCCCDDDD.js"))).as("the link itself").isNull();
        assertThat(bundle.resolve(requestFor("/dashboard/res/secret.txt"))).as("through a linked directory").isNull();
    }

    /**
     * The other half of the SPA fallback: it may not swallow a missing asset. A browser holding a stale `index.html`
     * across a deploy asks for a chunk that is gone, and `200 text/html` turns a 404 a reload handler could act on
     * into an opaque "failed to fetch dynamically imported module".
     */
    @Test void a_missing_asset_is_a_404_and_never_the_shell() throws Exception {
        for (String gone : new String[]{"/dashboard/main-DEADBEEF.js", "/dashboard/chunk-GONE1234.js", "/dashboard/styles-00000000.css",
                                        "/dashboard/assets/i18n/fr.json", "/dashboard/media/missing-ABCDEFGH.woff2", "/dashboard/nope.png"}) {
            var response = mvc.perform(get(gone)).andExpect(status().isNotFound()).andReturn().getResponse();
            assertThat(response.getContentAsString()).as("%s must not be answered with the shell", gone).doesNotContain("<hq-root>");
        }
        // ...while an extension-less path is still a client route and still gets the shell
        for (String route : new String[]{"/dashboard/admin/schools", "/dashboard/accept-invite", "/dashboard/reset-password"})
            mvc.perform(get(route)).andExpect(status().isOk())
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("<hq-root>")));
    }

    /**
     * ...and the handler's own guard, called directly so it is covered on its merits rather than by whatever the
     * firewall in front of it happens to block today. {@link StaticBundle} resolves under the root, normalises and
     * refuses anything that no longer starts with it.
     */
    @Test void the_bundle_itself_refuses_a_path_that_escapes_its_root() {
        var bundle = new StaticBundle(BUNDLE.toString(), "dashboard");
        for (String escape : new String[]{"/dashboard/../secret.txt", "/dashboard/../../../../etc/passwd", "/dashboard//etc/passwd", "/dashboard/media/../../secret.txt"})
            assertThat(bundle.resolve(requestFor(escape))).as(escape).isNull();
        assertThat(bundle.resolve(requestFor("/dashboard/main-WQMMA2Q6.js"))).as("a legitimate asset still resolves").isNotNull();
        assertThat(new StaticBundle("", "dashboard").resolve(requestFor("/dashboard/"))).as("no bundle configured").isNull();
    }

    private static org.springframework.mock.web.MockHttpServletRequest requestFor(String path) {
        var request = new org.springframework.mock.web.MockHttpServletRequest("GET", path);
        request.setAttribute(org.springframework.web.servlet.HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE, path);
        return request;
    }

    @Test void the_legacy_panel_still_serves_the_same_bundle() throws Exception {
        mvc.perform(get("/panel/")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("<hq-root>")))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-cache")));
        mvc.perform(get("/panel/anything/at/all")).andExpect(status().isOk());
        mvc.perform(get("/panel/main-WQMMA2Q6.js")).andExpect(status().isOk())   // not webpack's hash shape: the panel must not pin it
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-cache")));
    }

    /**
     * A miniature Angular 22 `dist/dashboard/browser`, plus a file one level up that traversal must never reach and
     * two symlinks that try to reach it anyway — one pretending to be a hashed chunk, one a linked directory.
     */
    private static Path bundle() {
        try {
            Path root = Files.createTempDirectory("quest-dashboard-test");
            Files.writeString(root.resolve("secret.txt"), "not reachable through /dashboard/");
            Path dir = Files.createDirectory(root.resolve("browser"));
            try {
                Files.createSymbolicLink(dir.resolve("pw-CCCCDDDD.js"), root.resolve("secret.txt"));
                Files.createSymbolicLink(dir.resolve("res"), root);
                SYMLINKS = true;
            } catch (java.io.IOException | UnsupportedOperationException e) { SYMLINKS = false; }
            Files.writeString(dir.resolve("index.html"), "<!doctype html><html><head><base href=\"/dashboard/\"></head><body><hq-root></hq-root></body></html>");
            Files.writeString(dir.resolve("favicon.ico"), "icon");
            Files.writeString(dir.resolve("manifest.webmanifest"), "{\"name\":\"dashboard\"}");
            for (String name : new String[]{"main-0123abcd.js", "main-WQMMA2Q6.js", "chunk-2RKMGULP.js", "styles-E4MV3RDG.css"})
                Files.writeString(dir.resolve(name), "/* " + name + " */");
            Files.createDirectories(dir.resolve("media"));
            Files.writeString(dir.resolve("media/archivo_variable-CP6HG7EB.woff2"), "woff2");
            Files.createDirectories(dir.resolve("assets/i18n"));
            Files.createDirectories(dir.resolve("assets/fonts"));
            Files.writeString(dir.resolve("assets/i18n/en.json"), "{\"hello\":\"hi\"}");
            Files.writeString(dir.resolve("assets/fonts/archivo_variable.woff2"), "woff2");
            return dir;
        } catch (java.io.IOException e) { throw new UncheckedIOException(e); }
    }
}
