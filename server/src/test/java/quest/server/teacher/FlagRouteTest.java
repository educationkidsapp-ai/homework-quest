package quest.server.teacher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import quest.server.flags.FlagKeys;

/**
 * §4 on P4.0's own routes: with `teacherQuestions` or `announcements` off for a school, every route of that feature
 * is a 404 — for the teacher and for the parent — and with it on they answer. The same route, no rebuild, no
 * restart.
 *
 * <p>Both flags are seeded <strong>off</strong> (`V5__flags_themes.sql`), so "off" here is the state a school that
 * has never been touched is actually in.
 */
class FlagRouteTest extends TeacherTestSupport {
    private static final String A = "fr-school-a";
    private static final String TEACHER_A = "fr-teacher-a";
    private static final String CLASS_A = "fr-school-a:british:1:math";

    @Override String prefix() { return "fr-"; }

    private String adminToken, teacherToken, childId;

    @BeforeEach void seed() throws Exception {
        school(A, "Flag Academy", "FRSCHA");
        teacher(TEACHER_A, A, "a@fr.test", "Ms Sara", "[\"math\"]", "british", "[1]");
        klass(CLASS_A, A, "british", 1, "math", TEACHER_A);
        adminToken = adminToken();
        teacherToken = token(TEACHER_A, "TEACHER", A);
        childId = child("Maya", "FRSCHA", "british", 1);
    }

    @AfterEach void clean() throws Exception {
        // Both flags back off whatever the test did, and before `removeSeed`: a failed assertion must not leave a
        // flag on for the next parameterised row, which would make one failure look like several.
        setFlag(adminToken, A, FlagKeys.TEACHER_QUESTIONS, false);
        setFlag(adminToken, A, FlagKeys.ANNOUNCEMENTS, false);
        removeSeed();
    }

    /** `{0}` is the flag, `{1} {2}` the route: off → the gate's 404, on → reached (whatever it then answers). */
    static Stream<Arguments> flaggedRoutes() {
        return Stream.of(
                Arguments.of(FlagKeys.TEACHER_QUESTIONS, "TEACHER_GET", "/teacher/questions"),
                Arguments.of(FlagKeys.TEACHER_QUESTIONS, "TEACHER_POST_QUESTION", "/teacher/questions"),
                Arguments.of(FlagKeys.ANNOUNCEMENTS, "TEACHER_GET", "/teacher/announcements"),
                Arguments.of(FlagKeys.ANNOUNCEMENTS, "TEACHER_POST_ANNOUNCEMENT", "/teacher/announcements"),
                Arguments.of(FlagKeys.ANNOUNCEMENTS, "PARENT_GET", "/children/{child}/announcements"),
                Arguments.of(FlagKeys.TEACHER_QUESTIONS, "PARENT_GET", "/children/{child}/teacher-questions/any-id"),
                Arguments.of(FlagKeys.TEACHER_QUESTIONS, "PARENT_POST_ANSWERS", "/children/{child}/teacher-questions/any-id/answers"));
    }

    @ParameterizedTest(name = "{1} {2} is 404 while {0} is off and served once it is on")
    @MethodSource("flaggedRoutes")
    void a_flagged_route_is_a_404_until_the_school_switches_it_on(String flag, String method, String path) throws Exception {
        String route = path.replace("{child}", childId);

        var refused = mvc.perform(request(method, route)).andExpect(status().isNotFound()).andReturn();
        assertThat(json(refused).get("code").asText()).isEqualTo("not_found");
        // …and it looks exactly like a route that was never built, so the flag set is not discoverable
        var unknown = mvc.perform(as(get("/teacher/no-such-route"), teacherToken)).andExpect(status().isNotFound()).andReturn();
        assertThat(json(refused).get("message").asText()).isEqualTo(GATE);

        setFlag(adminToken, A, flag, true);
        // "Served" is not "not a 404": `/children/{id}/teacher-questions/any-id` is reached and then answers 404 for
        // a question that does not exist. What the flag changes is the body — the gate's is the unknown-route one.
        assertThat(gateBody(mvc.perform(request(method, route)).andReturn()))
                .as("%s %s is still behind the %s gate after it was switched on", method, route, flag).isNotEqualTo(GATE);
        assertThat(json(unknown).get("message").asText()).as("the gate's body is the unknown-route body").isEqualTo(GATE);
    }

    /** The message the flag gate answers with — deliberately the one an unknown path gets. */
    private static final String GATE = "No such endpoint.";

    /** The `message` of a 404 body, or "" for anything the gate cannot have produced. */
    private String gateBody(org.springframework.test.web.servlet.MvcResult result) throws Exception {
        if (result.getResponse().getStatus() != 404) return "";
        var body = json(result);
        return body.has("message") ? body.get("message").asText() : "";
    }

    /** The unflagged half of the teacher's dashboard keeps working whatever the two feature flags say. */
    @Test void the_core_teacher_routes_are_never_flagged_off() throws Exception {
        setFlag(adminToken, A, FlagKeys.TEACHER_QUESTIONS, false);
        setFlag(adminToken, A, FlagKeys.ANNOUNCEMENTS, false);
        mvc.perform(as(get("/teacher/profile"), teacherToken)).andExpect(status().isOk());
        mvc.perform(as(get("/teacher/options"), teacherToken)).andExpect(status().isOk());
        mvc.perform(as(get("/teacher/classes/" + CLASS_A + "/students"), teacherToken)).andExpect(status().isOk());
        mvc.perform(as(get("/teacher/classes/" + CLASS_A + "/calendar"), teacherToken)).andExpect(status().isOk());
    }

    /** The map is not flagged, but the islands on it are: with the flag off the field is absent, not empty. */
    @Test void the_map_carries_no_teacher_islands_while_the_flag_is_off() throws Exception {
        setFlag(adminToken, A, FlagKeys.TEACHER_QUESTIONS, false);
        var map = parentGet("/children/" + childId + "/map?from=" + LocalDate.now().minusDays(7)
                + "&to=" + LocalDate.now().plusDays(7) + "&today=" + LocalDate.now());
        assertThat(map.has("teacherIslands")).isFalse();
    }

    private MockHttpServletRequestBuilder request(String method, String route) {
        return switch (method) {
            case "TEACHER_GET" -> as(get(route), teacherToken);
            case "TEACHER_POST_QUESTION" -> as(post(route).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"title\":\"Q\",\"stops\":[],\"classIds\":[\"" + CLASS_A + "\"],\"from\":\""
                            + LocalDate.now() + "\",\"to\":\"" + LocalDate.now().plusDays(1) + "\"}"), teacherToken);
            case "TEACHER_POST_ANNOUNCEMENT" -> as(post(route).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"classId\":\"" + CLASS_A + "\",\"bodyEn\":\"Tomorrow we start subtraction\"}"), teacherToken);
            case "PARENT_GET" -> get(route).header("Authorization", PARENT);
            default -> post(route).header("Authorization", PARENT).contentType(MediaType.APPLICATION_JSON).content("[]");
        };
    }
}
