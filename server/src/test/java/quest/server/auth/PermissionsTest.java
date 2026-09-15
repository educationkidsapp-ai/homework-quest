package quest.server.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import quest.server.ApiTestSupport;

/** Every endpoint the server serves must be in `permissions.json`; a new route without an entry fails here. */
class PermissionsTest extends ApiTestSupport {
    private static final List<String> ROLES = List.of("ADMIN", "TEACHER", "MANAGERIAL", Permissions.PARENT, Permissions.PUBLIC);

    @Autowired Permissions permissions;

    @Test void every_served_endpoint_has_a_permission() throws Exception {
        var doc = json(mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn());
        var paths = doc.get("paths");
        List<String> missing = new ArrayList<>();
        paths.fieldNames().forEachRemaining(path -> paths.get(path).fieldNames().forEachRemaining(method -> {
            if (permissions.find(method, path).isEmpty()) missing.add(method.toUpperCase() + " " + path);
        }));
        assertThat(missing).as("add these to server/src/main/resources/permissions.json").isEmpty();
    }

    @Test void every_permission_grants_known_roles() {
        List<String> unknown = permissions.matrix().entrySet().stream()
                .flatMap(e -> e.getValue().stream().filter(r -> !ROLES.contains(r)).map(r -> e.getKey() + " -> " + r)).toList();
        assertThat(unknown).isEmpty();
        assertThat(permissions.matrix()).isNotEmpty();
        assertThat(permissions.forRole("ADMIN")).contains("lesson.read", "school.write", "me.permissions");
        assertThat(permissions.forRole("TEACHER")).doesNotContain("school.write", "user.impersonate");
    }

    @Test void the_planned_phase_one_endpoints_are_already_declared() {
        List<String> planned = List.of("auth.signIn", "auth.refresh", "auth.forgotPassword", "auth.resetPassword", "auth.changePassword",
                "user.invite", "user.impersonate", "school.read", "school.write", "me.read", "me.permissions");
        assertThat(permissions.matrix().keySet()).containsAll(planned);
    }
}
