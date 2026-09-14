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

/** Full context on in-memory H2 with FAKE_AUTH, the sample LLM and the seeded lessons. */
@SpringBootTest
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class ApiTestSupport {
    @Autowired protected MockMvc mvc;
    protected final ObjectMapper mapper = new ObjectMapper();
    protected final String PARENT = "Bearer fake-token-parent-" + java.util.UUID.randomUUID().toString().substring(0, 8);   // a fresh parent per test

    protected JsonNode json(MvcResult r) throws Exception { var body = r.getResponse().getContentAsString(); return body.isEmpty() ? mapper.nullNode() : mapper.readTree(body); }

    protected JsonNode parentGet(String path) throws Exception { return json(mvc.perform(MockMvcRequestBuilders.get(path).header("Authorization", PARENT)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().is2xxSuccessful()).andReturn()); }
    protected JsonNode parentPost(String path, String body) throws Exception { return json(mvc.perform(MockMvcRequestBuilders.post(path).header("Authorization", PARENT).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().is2xxSuccessful()).andReturn()); }

    protected String adminToken() throws Exception {
        return json(mvc.perform(MockMvcRequestBuilders.post("/admin/auth/sign-in").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"admin@test.local\",\"password\":\"admin1234\"}")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn()).get("token").asText();
    }
    protected MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder b, String token) { return b.header("Authorization", "Bearer " + token); }

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
