package quest.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import quest.server.ApiTestSupport;

/**
 * The other half of {@link DashboardStaticTest}: with DASHBOARD_DIR unset — every local run and every deploy until
 * the image ships the bundle — `/dashboard/` is a plain 404, not a 500 with a stack trace, and it is still public
 * (a 401 here would send the browser to sign in for a page that does not exist). The reason is logged once at
 * startup by {@link DashboardController}, not on every request.
 *
 * <p>Deliberately no property override, so this runs in the default test context rather than spinning up a second one.
 */
class DashboardMissingBundleTest extends ApiTestSupport {
    @Test void an_unconfigured_bundle_is_a_quiet_404() throws Exception {
        for (String path : new String[]{"/dashboard", "/dashboard/", "/dashboard/admin/schools", "/dashboard/main-WQMMA2Q6.js"}) {
            var response = mvc.perform(get(path)).andExpect(status().isNotFound()).andReturn().getResponse();
            assertThat(response.getContentAsString()).as(path + " must not leak a stack trace").doesNotContain("Exception").isEmpty();
        }
    }
}
