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

        /** Parent side: her child's school on `/children/{id}/**`, her first child's school elsewhere. */
        @RestController
        @FeatureFlag(FlagKeys.COMPLAINTS)
        static class ParentProbe {
            @GetMapping(value = "/children/{id}/flag-probe", produces = MediaType.APPLICATION_JSON_VALUE)
            public String forChild(@PathVariable String id) { return "{\"child\":\"" + id + "\"}"; }

            @GetMapping(value = "/children/flag-probe", produces = MediaType.APPLICATION_JSON_VALUE)
            public String forParent() { return "{\"ok\":true}"; }
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

        // `complaints` is seeded off, so the feature is not there for this parent yet — by child and by parent
        mvc.perform(get("/children/" + childId + "/flag-probe").header("Authorization", PARENT)).andExpect(status().isNotFound());
        mvc.perform(get("/children/flag-probe").header("Authorization", PARENT)).andExpect(status().isNotFound());

        setFlag(token, school, FlagKeys.COMPLAINTS, true);
        mvc.perform(get("/children/" + childId + "/flag-probe").header("Authorization", PARENT)).andExpect(status().isOk());
        // no child in the path: her first child's school answers for her
        mvc.perform(get("/children/flag-probe").header("Authorization", PARENT)).andExpect(status().isOk());
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
