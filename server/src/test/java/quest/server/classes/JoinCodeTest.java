package quest.server.classes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * The parent's side of a section (`docs/teacher-flow.md` §2): the code on the printed card turns into a class, and
 * `POST /children` puts the child straight into it — no curriculum to pick, no grade, no school code.
 */
class JoinCodeTest extends ClassesTestSupport {
    private static final String A = "jc-school-a";
    private String admin, classId, code;

    @Override String prefix() { return "jc-"; }

    @BeforeEach void seed() throws Exception {
        school(A, "Jacaranda School", "JCAAAA");
        admin = adminToken();
        var section = json(mvc.perform(scoped(post("/admin/classes").contentType(MediaType.APPLICATION_JSON)
                .content("{\"curriculum\":\"american\",\"grade\":2,\"name\":\"2C\"}"), admin, A))
                .andExpect(status().isCreated()).andReturn());
        classId = section.get("id").asText(); code = section.get("joinCode").asText();
    }

    @AfterEach void cleanUp() { removeSeed(); }

    @Test void a_code_answers_the_class_and_nothing_else_about_the_school() throws Exception {
        var lookup = json(mvc.perform(get("/classes/lookup?code=" + code)).andExpect(status().isOk()).andReturn());
        assertThat(lookup.get("classId").asText()).isEqualTo(classId);
        assertThat(lookup.get("name").asText()).isEqualTo("2C");
        assertThat(lookup.get("grade").asInt()).isEqualTo(2);
        assertThat(lookup.get("curriculum").asText()).isEqualTo("american");
        assertThat(lookup.get("schoolName").asText()).isEqualTo("Jacaranda School");
        // exactly these five fields: the card is handed out, so the answer must carry nothing a stranger holding it
        // should not have — no roster, no teacher, no join code, no school status or settings.
        assertThat(lookup.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("classId", "name", "grade", "curriculum", "schoolName");

        // spaces and case are a person typing, not a different code
        assertThat(json(mvc.perform(get("/classes/lookup?code=" + code.toLowerCase().replaceAll("(...)(...)", "$1 $2")))
                .andExpect(status().isOk()).andReturn()).get("classId").asText()).isEqualTo(classId);
    }

    @Test void an_unknown_or_switched_off_code_is_the_same_uniform_404() throws Exception {
        mvc.perform(get("/classes/lookup?code=ZZZZZZ")).andExpect(status().isNotFound());
        mvc.perform(get("/classes/lookup?code=" + code.substring(0, 5))).andExpect(status().isBadRequest());

        mvc.perform(scoped(patch("/admin/classes/" + classId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"joinCodeEnabled\":false}"), admin, A)).andExpect(status().isOk());
        mvc.perform(get("/classes/lookup?code=" + code)).andExpect(status().isNotFound());

        mvc.perform(scoped(patch("/admin/classes/" + classId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"joinCodeEnabled\":true,\"active\":false}"), admin, A)).andExpect(status().isOk());
        mvc.perform(get("/classes/lookup?code=" + code)).andExpect(status().isNotFound());
    }

    /** The code settles school, section, curriculum and grade; the body's own course fields are ignored. */
    @Test void a_child_created_with_a_join_code_lands_in_that_section() throws Exception {
        var child = parentPost("/children", "{\"name\":\"Yara\",\"avatarColor\":\"mint\",\"curriculum\":\"british\",\"grade\":1,"
                + "\"schoolCode\":\"HQ0001\",\"joinCode\":\"" + code + "\"}");
        assertThat(child.get("curriculum").asText()).isEqualTo("american");
        assertThat(child.get("grade").asInt()).isEqualTo(2);
        assertThat(child.get("schoolId").asText()).isEqualTo(A);
        var row = childRows.findById(child.get("id").asText()).orElseThrow();
        assertThat(row.getClassId()).isEqualTo(classId);

        // an unknown code is a 404 and creates nothing; the old shape still works untouched
        mvc.perform(post("/children").header("Authorization", PARENT).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Nobody\",\"avatarColor\":\"sky\",\"curriculum\":\"british\",\"grade\":1,\"joinCode\":\"ZZZZZZ\"}"))
                .andExpect(status().isNotFound());
        var legacy = parentPost("/children", "{\"name\":\"Adam\",\"avatarColor\":\"sun\",\"curriculum\":\"british\",\"grade\":1,\"schoolCode\":\"JCAAAA\"}");
        assertThat(childRows.findById(legacy.get("id").asText()).orElseThrow().getClassId()).isNull();
    }
}
