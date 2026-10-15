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
    /**
     * Feature 13 adds WORKER: a mobile-app-only account, so it holds `staff.app` and little else. Feature 20 adds
     * FLOOR_SUPERVISOR, the sixth role, whose own area is `floor.*`.
     */
    private static final List<String> ROLES = List.of("ADMIN", "TEACHER", "MANAGERIAL", "COORDINATOR",
            "FLOOR_SUPERVISOR", "WORKER", Permissions.PARENT, Permissions.PUBLIC);

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
        // R2 (DR2): the coordinator reads, and holds no write key at all — not the teacher's, not the Admin's, and
        // not `coordinator.manage`, which is how her own scope is changed.
        // Feature 13: a worker reaches the app, her own bell and her own phone, and nothing a dashboard role holds.
        assertThat(permissions.forRole("WORKER")).contains("staff.app", "device.register", "notifications.read", "auth.changePassword")
                .doesNotContain("me.read", "teacher.chat", "worker.read", "worker.access", "chat.socket");
        assertThat(permissions.forRole("COORDINATOR")).contains("coordinator.read", "coordinator.lesson.read")
                .doesNotContain("coordinator.manage", "lesson.write", "lesson.publish", "results.write",
                        "attendance.write", "roster.write", "teacher.manage", "school.write", "user.impersonate");
        // Feature 18: whoever can be a direct manager decides requests (which ones is the service's scope); only the
        // Admin corrects a day directly, and a worker decides nothing.
        assertThat(permissions.forRole("COORDINATOR")).contains("attendance.requests.decide").doesNotContain("attendance.correction.write");
        assertThat(permissions.forRole("WORKER")).contains("attendance.self").doesNotContain("attendance.requests.decide", "attendance.correction.write");
        assertThat(permissions.forRole(Permissions.PARENT)).doesNotContain("attendance.self", "attendance.requests.decide");
        // Feature 20: she reads her floors and writes only messages — never the Admin's Floors page, never a lesson,
        // never a coordinator's or a manager's area, and she may be a direct manager, so she decides requests.
        assertThat(permissions.forRole("FLOOR_SUPERVISOR"))
                .contains("floor.read", "floor.chat", "me.read", "staff.app", "attendance.board",
                        "attendance.self", "attendance.requests.decide", "notifications.read")
                .doesNotContain("floor.manage", "coordinator.read", "management.read", "lesson.write",
                        "attendance.write", "attendance.correction.write", "user.impersonate", "school.write");
    }

    @Test void the_planned_phase_one_endpoints_are_already_declared() {
        List<String> planned = List.of("auth.signIn", "auth.refresh", "auth.forgotPassword", "auth.resetPassword", "auth.changePassword",
                "user.invite", "user.impersonate", "school.read", "school.write", "me.read", "me.permissions");
        assertThat(permissions.matrix().keySet()).containsAll(planned);
    }
}
