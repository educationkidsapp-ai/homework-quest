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
    /** `quest.api.dashboard.DashboardApi` (P1.3, P2.1): the Angular client is generated from exactly these. */
    static final List<String> DASHBOARD_API = List.of(
            "/auth/sign-in", "/auth/refresh", "/auth/sign-out", "/auth/forgot-password", "/auth/reset-password", "/auth/change-password",
            "/me", "/me/permissions",
            "/admin/schools", "/admin/schools/{id}", "/admin/schools/{id}/invites", "/admin/schools/{id}/users",
            "/admin/users", "/admin/users/{id}", "/admin/users/{id}/reset-password", "/admin/users/{id}/impersonate",
            "/invites/{token}", "/invites/{token}/accept", "/schools/by-code/{code}",
            "/admin/flags", "/admin/flags/audit", "/admin/flags/{key}/all", "/admin/schools/{id}/flags/{key}",
            "/admin/schools/{id}/theme", "/admin/platform-settings");
    /** P3.0: what the Angular Homes, School page, usage screens and New school wizard call (§6 screens 2, 4–6, 10, 19–20). */
    static final List<String> DASHBOARD_DATA_API = List.of(
            "/me/home",
            "/admin/schools/wizard",
            "/admin/schools/{id}/classes", "/admin/schools/{id}/classes/{classId}",
            "/admin/schools/{id}/usage", "/admin/schools/{id}/billing",
            "/admin/usage/platform", "/school/usage", "/school/teachers");
    /** Public and unauthenticated (§3, §4, §6 screen 1, §A): read before anyone has a token. */
    static final List<String> PUBLIC_API = List.of("/schools/{id}/flags", "/schools/{id}/theme", "/platform-settings", "/schools/logo");

    @Test void every_shared_api_route_is_served() throws Exception {
        var doc = json(mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn());
        Set<String> paths = new java.util.HashSet<>(); doc.get("paths").fieldNames().forEachRemaining(paths::add);
        assertThat(paths).containsAll(CONTENT_API);
        assertThat(paths).containsAll(ADMIN_API);
        assertThat(paths).containsAll(DASHBOARD_API);
        assertThat(paths).containsAll(DASHBOARD_DATA_API);
        assertThat(paths).containsAll(PUBLIC_API);
        assertThat(paths).contains("/media/pages/{id}", "/media/child/{id}");
    }
}
