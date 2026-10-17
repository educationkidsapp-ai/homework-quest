package quest.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** Full context on in-memory H2 with FAKE_AUTH, the sample LLM, the seeded lessons and a pinnable {@link TestClock}. */
@SpringBootTest
@org.springframework.context.annotation.Import(TestTimeConfig.class)
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class ApiTestSupport {
    @Autowired protected MockMvc mvc;
    /** The suite's clock; pin it with {@link TestClock#pinTo} and release it in an `@AfterEach`. */
    @Autowired protected TestClock clock;
    protected final ObjectMapper mapper = new ObjectMapper();
    protected final String PARENT = "Bearer fake-token-parent-" + java.util.UUID.randomUUID().toString().substring(0, 8);   // a fresh parent per test

    protected JsonNode json(MvcResult r) throws Exception { var body = r.getResponse().getContentAsString(); return body.isEmpty() ? mapper.nullNode() : mapper.readTree(body); }

    protected JsonNode parentGet(String path) throws Exception { return json(mvc.perform(MockMvcRequestBuilders.get(path).header("Authorization", PARENT)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().is2xxSuccessful()).andReturn()); }
    protected JsonNode parentPost(String path, String body) throws Exception { return json(mvc.perform(MockMvcRequestBuilders.post(path).header("Authorization", PARENT).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().is2xxSuccessful()).andReturn()); }

    protected String adminToken() throws Exception {
        return json(mvc.perform(MockMvcRequestBuilders.post("/admin/auth/sign-in").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"admin@test.local\",\"password\":\"admin1234\"}")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn()).get("token").asText();
    }
    protected MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder b, String token) { return b.header("Authorization", "Bearer " + token); }

    @Autowired protected quest.server.flags.SchoolFlagRepository schoolFlagRows;
    @Autowired protected quest.server.flags.FeatureFlags featureFlagValues;

    /**
     * Switches one flag for one school: the override row plus the cache drop, which is what `FlagService.set` does
     * besides recording the flip. Deliberately not the Admin route — `flag_audit` is global to the suite's one H2
     * database and `FlagAdminTest` counts its own flips there, so a fixture must not write to it.
     *
     * <p>Since D30 (`V54__flags_on_by_default.sql`) every switch is on by default, so this is mostly used to turn a
     * feature <em>off</em> for a test that is about the behaviour without it.
     */
    protected void setSchoolFlag(String schoolId, String key, boolean enabled) {
        var row = schoolFlagRows.findOne(schoolId, key).orElseGet(() -> {
            var fresh = new quest.server.flags.Entities.SchoolFeatureFlagEntity();
            fresh.setSchoolId(schoolId); fresh.setFlagKey(key);
            return fresh;
        });
        row.setEnabled(enabled); row.setUpdatedBy("test"); row.setUpdatedAt(java.time.Instant.now());
        schoolFlagRows.save(row);
        featureFlagValues.invalidate(schoolId);
    }

    /** Polls the lesson until it leaves the given transient status (async jobs run on the task executor). */
    protected JsonNode awaitStatus(String token, String lessonId, String... terminal) throws Exception {
        for (int i = 0; i < 100; i++) {
            var l = json(mvc.perform(admin(MockMvcRequestBuilders.get("/admin/lessons/" + lessonId), token)).andReturn());
            for (String t : terminal) if (t.equals(l.get("status").asText())) return l;
            if ("error".equals(l.get("status").asText())) throw new AssertionError("lesson failed: " + l.get("error"));
            Thread.sleep(100);
        }
        throw new AssertionError("timed out waiting for " + String.join("/", terminal));
    }
}
