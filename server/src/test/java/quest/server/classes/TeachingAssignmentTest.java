package quest.server.classes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * `docs/teacher-flow.md` §1–§2: an Admin creates a teacher with a password read out once, gives her classes, and
 * cannot give one teacher's (class, subject) to another.
 */
class TeachingAssignmentTest extends ClassesTestSupport {
    private static final String A = "ta-school-a";
    private String admin;
    private String oneA, oneB;

    @Override String prefix() { return "ta-"; }

    @BeforeEach void seed() throws Exception {
        school(A, "Tamarind School", "TAAAAA");
        admin = adminToken();
        oneA = section("british", 1, "1A");
        oneB = section("british", 1, "1B");
    }

    @AfterEach void cleanUp() { removeSeed(); }

    @Test void a_teacher_is_created_with_a_password_that_is_answered_once() throws Exception {
        var created = json(mvc.perform(scoped(post("/admin/teachers").contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\":\"Sara Al Harbi\",\"email\":\"Sara@School.Test\",\"subjects\":[\"math\"],\"curriculum\":\"british\"}"), admin, A))
                .andExpect(status().isCreated()).andReturn());
        String password = created.get("temporaryPassword").asText();
        assertThat(password).hasSize(12);
        assertThat(created.get("teacher").get("email").asText()).isEqualTo("sara@school.test");
        assertThat(created.get("teacher").get("fullName").asText()).isEqualTo("Sara Al Harbi");
        String userId = created.get("teacher").get("userId").asText();

        // it works, it has to be replaced, and nothing answers it a second time
        var signIn = json(mvc.perform(post("/auth/sign-in").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"sara@school.test\",\"password\":\"" + password + "\"}")).andExpect(status().isOk()).andReturn());
        assertThat(signIn.get("mustChangePassword").asBoolean()).isTrue();
        assertThat(json(mvc.perform(scoped(get("/admin/teachers"), admin, A)).andExpect(status().isOk()).andReturn()).toString())
                .as("a temporary password is never in a list, a log or an audit row").doesNotContain(password);

        // the same address twice is a 409, and a reset answers a new one and kills the old
        mvc.perform(scoped(post("/admin/teachers").contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\":\"Someone Else\",\"email\":\"sara@school.test\"}"), admin, A)).andExpect(status().isConflict());
        String next = json(mvc.perform(scoped(post("/admin/teachers/" + userId + "/reset-password"), admin, A))
                .andExpect(status().isOk()).andReturn()).get("temporaryPassword").asText();
        assertThat(next).hasSize(12).isNotEqualTo(password);
        mvc.perform(post("/auth/sign-in").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"sara@school.test\",\"password\":\"" + password + "\"}")).andExpect(status().isUnauthorized());

        var renamed = json(mvc.perform(scoped(patch("/admin/teachers/" + userId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\":\"Sara Harbi\",\"subjects\":[\"math\",\"english\"]}"), admin, A))
                .andExpect(status().isOk()).andReturn());
        assertThat(renamed.get("fullName").asText()).isEqualTo("Sara Harbi");
        assertThat(renamed.get("subjects").toString()).contains("english");
    }

    /** §2's one-teacher-per-subject-per-class rule, and the refusal that names who holds the slot. */
    @Test void a_taken_class_and_subject_is_refused_by_name() throws Exception {
        String sara = teacher("Sara Al Harbi", "sara2@school.test");
        String maryam = teacher("Maryam Nasser", "maryam@school.test");

        var hers = json(mvc.perform(scoped(put("/admin/teachers/" + sara + "/assignments").contentType(MediaType.APPLICATION_JSON)
                .content(assignments(oneA + ":math", oneB + ":math")), admin, A)).andExpect(status().isOk()).andReturn());
        assertThat(hers).hasSize(2);
        assertThat(hers.get(0).get("className").asText()).isEqualTo("1A");
        assertThat(hers.get(0).get("teacherName").asText()).isEqualTo("Sara Al Harbi");

        var refused = json(mvc.perform(scoped(put("/admin/teachers/" + maryam + "/assignments").contentType(MediaType.APPLICATION_JSON)
                .content(assignments(oneA + ":math")), admin, A)).andExpect(status().isConflict()).andReturn());
        assertThat(refused.get("message").asText()).isEqualTo("1A · math is taught by Sara Al Harbi");
        assertThat(json(mvc.perform(scoped(get("/admin/classes/" + oneA + "/assignments"), admin, A)).andExpect(status().isOk()).andReturn()))
                .as("a refused call changes nothing").hasSize(1);

        // English in the same class is free, and re-sending a set replaces it rather than adding to it
        mvc.perform(scoped(put("/admin/teachers/" + maryam + "/assignments").contentType(MediaType.APPLICATION_JSON)
                .content(assignments(oneA + ":english")), admin, A)).andExpect(status().isOk());
        var shrunk = json(mvc.perform(scoped(put("/admin/teachers/" + sara + "/assignments").contentType(MediaType.APPLICATION_JSON)
                .content(assignments(oneB + ":math")), admin, A)).andExpect(status().isOk()).andReturn());
        assertThat(shrunk).hasSize(1);
        assertThat(shrunk.get(0).get("className").asText()).isEqualTo("1B");
        assertThat(json(mvc.perform(scoped(get("/admin/classes/" + oneA + "/assignments"), admin, A)).andExpect(status().isOk()).andReturn()))
                .as("1A · math was given back when Sara's set stopped naming it").hasSize(1);
    }

    /** `GET /me` carries what she teaches, so the dashboard builds her whole navigation from one request. */
    @Test void me_carries_her_assignments_and_neither_list_grows_a_query_per_row() throws Exception {
        String sara = teacher("Sara Al Harbi", "sara3@school.test");
        mvc.perform(scoped(put("/admin/teachers/" + sara + "/assignments").contentType(MediaType.APPLICATION_JSON)
                .content(assignments(oneA + ":math", oneB + ":math")), admin, A)).andExpect(status().isOk());
        String hers = token(sara, "TEACHER", A);

        var me = json(mvc.perform(as(get("/me"), hers)).andExpect(status().isOk()).andReturn());
        assertThat(me.get("assignments")).hasSize(2);
        assertThat(me.get("assignments").get(0).get("className").asText()).isEqualTo("1A");
        assertThat(me.get("assignments").get(0).get("subject").asText()).isEqualTo("math");
        assertThat(json(mvc.perform(scoped(get("/me"), admin, A)).andExpect(status().isOk()).andReturn()).hasNonNull("assignments"))
                .as("an ADMIN is not assignment-scoped").isFalse();

        // the pin: three more sections and three more assignments must not cost three more statements
        long two = statements(() -> quietly(() -> mvc.perform(scoped(get("/admin/classes"), admin, A)).andExpect(status().isOk())));
        long meTwo = statements(() -> quietly(() -> mvc.perform(as(get("/me"), hers)).andExpect(status().isOk())));
        var more = new java.util.ArrayList<String>(java.util.List.of(oneA + ":math", oneB + ":math"));
        for (int i = 0; i < 3; i++) more.add(section("british", 2, "2" + (char) ('A' + i)) + ":math");
        mvc.perform(scoped(put("/admin/teachers/" + sara + "/assignments").contentType(MediaType.APPLICATION_JSON)
                .content(assignments(more.toArray(String[]::new))), admin, A)).andExpect(status().isOk());

        assertThat(statements(() -> quietly(() -> mvc.perform(scoped(get("/admin/classes"), admin, A)).andExpect(status().isOk()))))
                .as("GET /admin/classes is a fixed number of statements, not one per class").isEqualTo(two);
        assertThat(statements(() -> quietly(() -> mvc.perform(as(get("/me"), hers)).andExpect(status().isOk()))))
                .as("GET /me is a fixed number of statements, not one per assignment").isEqualTo(meTwo);
    }

    // ---------------------------------------------------------------- helpers

    private String teacher(String fullName, String email) throws Exception {
        return json(mvc.perform(scoped(post("/admin/teachers").contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\":\"" + fullName + "\",\"email\":\"" + email + "\",\"subjects\":[\"math\",\"english\"],\"curriculum\":\"british\"}"), admin, A))
                .andExpect(status().isCreated()).andReturn()).get("teacher").get("userId").asText();
    }

    private String section(String curriculum, int grade, String name) throws Exception {
        return json(mvc.perform(scoped(post("/admin/classes").contentType(MediaType.APPLICATION_JSON)
                .content("{\"curriculum\":\"" + curriculum + "\",\"grade\":" + grade + ",\"name\":\"" + name + "\"}"), admin, A))
                .andExpect(status().isCreated()).andReturn()).get("id").asText();
    }

    /** `{"assignments":[{"classId":…,"subject":…}]}` from `classId:subject` pairs. */
    private String assignments(String... pairs) {
        var body = mapper.createObjectNode();
        var array = body.putArray("assignments");
        for (String pair : pairs) {
            int cut = pair.lastIndexOf(':');
            array.addObject().put("classId", pair.substring(0, cut)).put("subject", pair.substring(cut + 1));
        }
        return body.toString();
    }

    /** `statements(…)` takes a `Runnable`; MockMvc throws a checked exception. */
    private static void quietly(Call call) { try { call.run(); } catch (Exception e) { throw new IllegalStateException(e); } }

    private interface Call { void run() throws Exception; }
}
