package quest.server.flags;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import quest.server.ApiTestSupport;
import quest.server.tenancy.TenantContext;

/**
 * §4's server half: a route carrying {@link FeatureFlag} answers 404 while the flag is off for the caller's school
 * and 200 when it is on — the same route, no rebuild, no restart.
 *
 * <p>The two probe routes exist only in this test's context (a nested `@TestConfiguration` is not registered with the
 * application), so they neither appear in `server/openapi.json` nor need a row in `permissions.json`.
 */
class FeatureFlagInterceptorTest extends ApiTestSupport {

    @TestConfiguration
    static class Probes {
        /** Dashboard side: the caller's school comes from her token, or from an ADMIN's `X-School-Id`. */
        @RestController
        static class DashboardProbe {
            @FeatureFlag(FlagKeys.CERTIFICATES)
            @GetMapping(value = "/admin/flag-probe", produces = MediaType.APPLICATION_JSON_VALUE)
            public String probe() { return "{\"ok\":true}"; }
        }

        /** Parent side: her child's school on `/children/{id}/**`, and nothing to go on anywhere else. */
        @RestController
        @FeatureFlag(FlagKeys.COMPLAINTS)
        static class ParentProbe {
            @GetMapping(value = "/children/{id}/flag-probe", produces = MediaType.APPLICATION_JSON_VALUE)
            public String forChild(@PathVariable String id) { return "{\"child\":\"" + id + "\"}"; }

            @GetMapping(value = "/children/flag-probe", produces = MediaType.APPLICATION_JSON_VALUE)
            public String forParent() { return "{\"ok\":true}"; }

            /**
             * A handler naming its own key on a class that already names one: the method's key <em>replaces</em>
             * the class's rather than adding to it, so this route is gated by `certificates` alone.
             */
            @FeatureFlag(FlagKeys.CERTIFICATES)
            @GetMapping(value = "/children/{id}/flag-probe-method", produces = MediaType.APPLICATION_JSON_VALUE)
            public String forChildWithItsOwnFlag(@PathVariable String id) { return "{\"child\":\"" + id + "\"}"; }
        }
        // The two nested `@RestController` classes are registered because they are member classes of this
        // configuration; declaring `@Bean` methods for them as well would map each route twice.
    }

    @Test void a_flag_off_for_the_school_turns_its_endpoint_into_a_404() throws Exception {
        var token = adminToken();
        String school = createSchool(token);

        mvc.perform(admin(get("/admin/flag-probe").header(TenantContext.HEADER, school), token)).andExpect(status().isOk());

        setFlag(token, school, FlagKeys.CERTIFICATES, false);
        var refused = mvc.perform(admin(get("/admin/flag-probe").header(TenantContext.HEADER, school), token))
                .andExpect(status().isNotFound()).andReturn();
        assertThat(json(refused).get("code").asText()).isEqualTo("not_found");
        // …and it looks exactly like a route that was never built, so the flag set is not discoverable
        var unknown = mvc.perform(admin(get("/admin/no-such-route"), token)).andExpect(status().isNotFound()).andReturn();
        assertThat(json(refused).get("message").asText()).isEqualTo(json(unknown).get("message").asText());

        // the platform ADMIN with no school picked reads the defaults, where the flag is still on (D6)
        mvc.perform(admin(get("/admin/flag-probe"), token)).andExpect(status().isOk());

        setFlag(token, school, FlagKeys.CERTIFICATES, true);
        mvc.perform(admin(get("/admin/flag-probe").header(TenantContext.HEADER, school), token)).andExpect(status().isOk());
    }

    @Test void a_parents_flags_are_her_childs_schools_flags() throws Exception {
        var token = adminToken();
        String school = createSchool(token);
        String code = json(mvc.perform(admin(get("/admin/schools/" + school), token)).andReturn()).get("code").asText();

        var child = parentPost("/children", "{\"name\":\"Maya\",\"avatarColor\":\"sun\",\"curriculum\":\"british\",\"grade\":1,\"schoolCode\":\"" + code + "\"}");
        String childId = child.get("id").asText();
        assertThat(child.get("schoolId").asText()).isEqualTo(school);

        // `complaints` is seeded off, so the feature is not there for this parent yet
        mvc.perform(get("/children/" + childId + "/flag-probe").header("Authorization", PARENT)).andExpect(status().isNotFound());

        setFlag(token, school, FlagKeys.COMPLAINTS, true);
        mvc.perform(get("/children/" + childId + "/flag-probe").header("Authorization", PARENT)).andExpect(status().isOk());
    }

    /**
     * P4.0, from the #41 review: a flagged parent route with no child in the path fails <strong>closed</strong>.
     *
     * <p>It used to fall back to her <em>first</em> child's school, which was wrong twice over — a parent with
     * children in two schools had one school's flags decide what she saw about the other, and a parent with no
     * children at all read the platform defaults, so a feature no school had switched on was reachable. There is no
     * fallback now: the flag is resolved by the child in the path, or the request is a 404.
     */
    @Test void a_flagged_parent_route_without_a_child_in_the_path_is_refused() throws Exception {
        var token = adminToken();
        String school = createSchool(token);
        childIn(token, school);

        setFlag(token, school, FlagKeys.COMPLAINTS, true);
        // her only child's school has the feature on, and the route still refuses: there is no child to resolve by
        var refused = mvc.perform(get("/children/flag-probe").header("Authorization", PARENT))
                .andExpect(status().isNotFound()).andReturn();
        assertThat(json(refused).get("code").asText()).isEqualTo("not_found");
    }

    /** Two schools, two answers — never one school's flags deciding what she sees about the other's child. */
    @Test void a_parent_with_children_in_two_schools_gets_each_childs_own_answer() throws Exception {
        var token = adminToken();
        String on = createSchool(token), off = createSchool(token);
        String childOn = childIn(token, on), childOff = childIn(token, off);

        setFlag(token, on, FlagKeys.COMPLAINTS, true);
        setFlag(token, off, FlagKeys.COMPLAINTS, false);
        mvc.perform(get("/children/" + childOn + "/flag-probe").header("Authorization", PARENT)).andExpect(status().isOk());
        mvc.perform(get("/children/" + childOff + "/flag-probe").header("Authorization", PARENT)).andExpect(status().isNotFound());

        // …and the other way round, so neither order of "first child" can be what is answering
        setFlag(token, on, FlagKeys.COMPLAINTS, false);
        setFlag(token, off, FlagKeys.COMPLAINTS, true);
        mvc.perform(get("/children/" + childOn + "/flag-probe").header("Authorization", PARENT)).andExpect(status().isNotFound());
        mvc.perform(get("/children/" + childOff + "/flag-probe").header("Authorization", PARENT)).andExpect(status().isOk());
    }

    /** A child that is not hers resolves to nothing, so the route is refused before the handler can 404 on its own. */
    @Test void a_child_that_is_not_hers_resolves_no_flags_at_all() throws Exception {
        var token = adminToken();
        String school = createSchool(token);
        setFlag(token, school, FlagKeys.COMPLAINTS, true);
        mvc.perform(get("/children/not-her-child/flag-probe").header("Authorization", PARENT)).andExpect(status().isNotFound());
    }

    /** §4's one-flag rule: the handler's key replaces the controller's rather than adding to it. */
    @Test void a_handlers_own_flag_replaces_the_controllers() throws Exception {
        var token = adminToken();
        String school = createSchool(token);
        String childId = childIn(token, school);

        // the class says `complaints` (off) and the method says `certificates` (on): the method's key decides
        setFlag(token, school, FlagKeys.COMPLAINTS, false);
        setFlag(token, school, FlagKeys.CERTIFICATES, true);
        mvc.perform(get("/children/" + childId + "/flag-probe").header("Authorization", PARENT)).andExpect(status().isNotFound());
        mvc.perform(get("/children/" + childId + "/flag-probe-method").header("Authorization", PARENT)).andExpect(status().isOk());

        // and the other way round: the class's key never gates the method's route, on or off
        setFlag(token, school, FlagKeys.COMPLAINTS, true);
        setFlag(token, school, FlagKeys.CERTIFICATES, false);
        mvc.perform(get("/children/" + childId + "/flag-probe").header("Authorization", PARENT)).andExpect(status().isOk());
        mvc.perform(get("/children/" + childId + "/flag-probe-method").header("Authorization", PARENT)).andExpect(status().isNotFound());
    }

    /** A child of the given school, owned by this test's parent. */
    private String childIn(String token, String school) throws Exception {
        String code = json(mvc.perform(admin(get("/admin/schools/" + school), token)).andReturn()).get("code").asText();
        return parentPost("/children", "{\"name\":\"Child\",\"avatarColor\":\"sun\",\"curriculum\":\"british\","
                + "\"grade\":1,\"schoolCode\":\"" + code + "\"}").get("id").asText();
    }

    private String createSchool(String token) throws Exception {
        var body = "{\"name\":\"Flag School " + UUID.randomUUID().toString().substring(0, 6) + "\",\"curriculumOptions\":[\"british\"],\"gradeOptions\":[1,2,3]}";
        return json(mvc.perform(admin(post("/admin/schools").contentType(MediaType.APPLICATION_JSON).content(body), token))
                .andExpect(status().is2xxSuccessful()).andReturn()).get("id").asText();
    }

    private void setFlag(String token, String school, String key, boolean enabled) throws Exception {
        mvc.perform(admin(put("/admin/schools/" + school + "/flags/" + key).contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":" + enabled + "}"), token)).andExpect(status().isOk());
    }
}
