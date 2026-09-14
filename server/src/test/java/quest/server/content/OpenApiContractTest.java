package quest.server.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import quest.server.ApiTestSupport;

/**
 * The server's OpenAPI document must expose exactly the routes the shared `ContentApi` / `AdminApi` call.
 * A renamed endpoint fails here before it fails in the app.
 */
class OpenApiContractTest extends ApiTestSupport {
    static final List<String> CONTENT_API = List.of(
            "/children", "/children/{id}", "/children/{id}/map", "/children/{id}/attempts", "/children/{id}/stops/{stopId}/media", "/children/{id}/progress", "/lessons/{id}");
    static final List<String> ADMIN_API = List.of(
            "/admin/auth/sign-in", "/admin/lessons", "/admin/lessons/{id}", "/admin/lessons/{id}/files", "/admin/lessons/{id}/analyze", "/admin/lessons/{id}/skills",
            "/admin/stops/{stopId}", "/admin/stops/{stopId}/regenerate", "/admin/plays/{playId}/regenerate", "/admin/lessons/{id}/parent-panel",
            "/admin/lessons/{id}/publish", "/admin/lessons/{id}/unpublish", "/admin/cache", "/admin/usage", "/admin/calendar");

    @Test void every_shared_api_route_is_served() throws Exception {
        var doc = json(mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn());
        Set<String> paths = new java.util.HashSet<>(); doc.get("paths").fieldNames().forEachRemaining(paths::add);
        assertThat(paths).containsAll(CONTENT_API);
        assertThat(paths).containsAll(ADMIN_API);
        assertThat(paths).contains("/media/pages/{id}", "/media/child/{id}");
    }
}
