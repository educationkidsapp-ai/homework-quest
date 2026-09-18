package quest.server.classes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import quest.server.flags.FlagKeys;

/**
 * `docs/teacher-flow.md` §3: a teacher edits the roster of her own classes, and only while `teacher.rosterEdit` is
 * on. The Admin's roster routes stay the Admin's — that is what stops the flag being a suggestion.
 */
class TeacherRosterTest extends ClassesTestSupport {
    private static final String A = "tr-school-a", SARA = "tr-sara";
    private String admin, hers, oneA, oneB;

    @Override String prefix() { return "tr-"; }

    @BeforeEach void seed() throws Exception {
        school(A, "Tulip School", "TRAAAA");
        admin = adminToken();
        oneA = section("1A"); oneB = section("1B");
        user(SARA, A, "sara@tr.test", "TEACHER");
        mvc.perform(scoped(put("/admin/teachers/" + SARA + "/assignments").contentType(MediaType.APPLICATION_JSON)
                .content("{\"assignments\":[{\"classId\":\"" + oneA + "\",\"subject\":\"math\"}]}"), admin, A)).andExpect(status().isOk());
        hers = token(SARA, "TEACHER", A);
        setFlag(true);
    }

    @AfterEach void cleanUp() throws Exception { setFlag(false); removeSeed(); }

    @Test void she_edits_her_own_class_and_nothing_else() throws Exception {
        var child = json(mvc.perform(as(post("/teacher/classes/" + oneA + "/children").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Rania Fadel\"}"), hers)).andExpect(status().isCreated()).andReturn());
        assertThat(json(mvc.perform(as(get("/teacher/classes/" + oneA + "/children"), hers)).andExpect(status().isOk()).andReturn())).hasSize(1);

        var renamed = json(mvc.perform(as(patch("/teacher/classes/" + oneA + "/children/" + child.get("id").asText())
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Rania F.\"}"), hers)).andExpect(status().isOk()).andReturn());
        assertThat(renamed.get("name").asText()).isEqualTo("Rania F.");

        // 1B is a class of her school she holds no assignment on: 403, read and write
        mvc.perform(as(get("/teacher/classes/" + oneB + "/children"), hers)).andExpect(status().isForbidden());
        mvc.perform(as(post("/teacher/classes/" + oneB + "/children").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Nobody\"}"), hers)).andExpect(status().isForbidden());
        // …and her own child, reached through the wrong class of hers, is a 404 rather than an edit then a refusal
        mvc.perform(as(patch("/teacher/classes/" + oneB + "/children/" + child.get("id").asText())
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Moved\"}"), hers)).andExpect(status().isForbidden());
    }

    /** The flag is the switch, and the Admin routes are not a way round it. */
    @Test void the_routes_are_gone_while_the_flag_is_off_and_the_admin_routes_are_never_hers() throws Exception {
        setFlag(false);
        mvc.perform(as(get("/teacher/classes/" + oneA + "/children"), hers)).andExpect(status().isNotFound());
        mvc.perform(as(post("/teacher/classes/" + oneA + "/children").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Nobody\"}"), hers)).andExpect(status().isNotFound());

        setFlag(true);
        for (var request : java.util.List.of(
                get("/admin/classes"), get("/admin/classes/" + oneA + "/children"), get("/admin/classes/" + oneA + "/join-card.pdf"),
                post("/admin/classes/" + oneA + "/children").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Nobody\"}")))
            mvc.perform(as(request, hers)).andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------- helpers

    private String section(String name) throws Exception {
        return json(mvc.perform(scoped(post("/admin/classes").contentType(MediaType.APPLICATION_JSON)
                .content("{\"curriculum\":\"british\",\"grade\":1,\"name\":\"" + name + "\"}"), admin, A))
                .andExpect(status().isCreated()).andReturn()).get("id").asText();
    }

    private void setFlag(boolean enabled) throws Exception {
        mvc.perform(as(put("/admin/schools/" + A + "/flags/" + FlagKeys.TEACHER_ROSTER_EDIT).contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":" + enabled + "}"), admin)).andExpect(status().isOk());
    }
}
