package quest.server.teacher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * §6 screen 14 end to end: a teacher writes stops, sends them to her classes, the children of exactly those classes
 * see the island on their map, answer it, and she reads the results.
 */
class TeacherQuestionTest extends TeacherTestSupport {
    private static final String A = "tq-school-a", B = "tq-school-b";
    private static final String TEACHER_A = "tq-teacher-a", TEACHER_A2 = "tq-teacher-a2", TEACHER_B = "tq-teacher-b";
    private static final String CLASS_A1 = "tq-school-a:british:1:math", CLASS_A2 = "tq-school-a:british:2:math";
    private static final String CLASS_A_OTHER = "tq-school-a:british:3:english";

    @Override String prefix() { return "tq-"; }

    private String adminToken, teacherToken, otherTeacherToken;
    private String childInClass, childInAnotherGrade;

    @BeforeEach void seed() throws Exception {
        school(A, "Question Academy", "TQSCHA");
        school(B, "Question Beta", "TQSCHB");
        teacher(TEACHER_A, A, "a@tq.test", "Ms Sara", "[\"math\"]", "british", "[1,2]");
        teacher(TEACHER_A2, A, "a2@tq.test", "Ms Dana", "[\"english\"]", "british", "[3]");
        teacher(TEACHER_B, B, "b@tq.test", "Ms Lina", "[\"math\"]", "british", "[1]");
        klass(CLASS_A1, A, "british", 1, "math", TEACHER_A);
        klass(CLASS_A2, A, "british", 2, "math", TEACHER_A);
        klass(CLASS_A_OTHER, A, "british", 3, "english", TEACHER_A2);

        adminToken = adminToken();
        enableTeacherFeatures(adminToken, A);
        enableTeacherFeatures(adminToken, B);
        teacherToken = token(TEACHER_A, "TEACHER", A);
        otherTeacherToken = token(TEACHER_A2, "TEACHER", A);

        childInClass = child("Maya", "TQSCHA", "british", 1);
        childInAnotherGrade = child("Omar", "TQSCHA", "british", 3);
    }

    @AfterEach void clean() { removeSeed(); }

    // ---------------------------------------------------------------- the teacher's side

    @Test void a_question_is_written_edited_and_sent_once() throws Exception {
        var draft = create("Counting check", stopsJson(choice("tq-s1", "Pick the bigger number")), CLASS_A1);
        assertThat(draft.get("sentAt").isNull()).as("a new question is a draft").isTrue();
        assertThat(draft.get("teacherId").asText()).isEqualTo(TEACHER_A);
        assertThat(draft.get("teacherName").asText()).isEqualTo("Ms Sara");
        assertThat(draft.get("totalChildren").asInt()).as("the children of British/1 in her school").isEqualTo(1);

        String id = draft.get("id").asText();
        var edited = json(mvc.perform(as(put("/teacher/questions/" + id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Counting check v2\"}"), teacherToken)).andExpect(status().isOk()).andReturn());
        assertThat(edited.get("title").asText()).isEqualTo("Counting check v2");

        var sent = json(mvc.perform(as(post("/teacher/questions/" + id + "/send"), teacherToken))
                .andExpect(status().isOk()).andReturn());
        assertThat(sent.get("sentAt").isNull()).isFalse();

        // …and only once: a second send, and any edit after it, is a 409
        assertThat(json(mvc.perform(as(post("/teacher/questions/" + id + "/send"), teacherToken))
                .andExpect(status().isConflict()).andReturn()).get("code").asText()).isEqualTo("conflict");
        mvc.perform(as(put("/teacher/questions/" + id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"too late\"}"), teacherToken)).andExpect(status().isConflict());
    }

    @Test void the_stops_are_validated_against_the_shared_schema() throws Exception {
        // `correctOptionId` that is not one of the options — a rule the JSON schema cannot express and
        // `SchemaValidator` does, in the same lenient mode a hand-written manual play is held to.
        String broken = stopsJson(choice("tq-bad", "Broken")).replace("\"correctOptionId\":\"a\"", "\"correctOptionId\":\"zz\"");
        var refused = json(mvc.perform(as(post("/teacher/questions").contentType(MediaType.APPLICATION_JSON)
                .content(body("Broken", broken, CLASS_A1)), teacherToken)).andExpect(status().isBadRequest()).andReturn());
        assertThat(refused.get("message").asText()).contains("correctOptionId");

        // a field the app could never read is refused by the codec before any rule runs
        mvc.perform(as(post("/teacher/questions").contentType(MediaType.APPLICATION_JSON)
                .content(body("Unknown field", "[{\"type\":\"choice\",\"id\":\"x\",\"nope\":1}]", CLASS_A1)), teacherToken))
                .andExpect(status().isBadRequest());

        // an exit ticket is optional (lenient), so a single choice stop is a valid question
        mvc.perform(as(post("/teacher/questions").contentType(MediaType.APPLICATION_JSON)
                .content(body("Fine", stopsJson(choice("tq-ok", "Fine")), CLASS_A1)), teacherToken))
                .andExpect(status().isCreated());
    }

    @Test void a_teacher_may_only_send_to_her_own_classes() throws Exception {
        var refused = json(mvc.perform(as(post("/teacher/questions").contentType(MediaType.APPLICATION_JSON)
                .content(body("Not mine", stopsJson(choice("tq-s2", "Pick")), CLASS_A_OTHER)), teacherToken))
                .andExpect(status().isForbidden()).andReturn());
        assertThat(refused.get("code").asText()).isEqualTo("forbidden");

        // and she never sees a colleague's question, even in her own school
        String hers = create("Hers", stopsJson(choice("tq-s3", "Pick")), CLASS_A1).get("id").asText();
        mvc.perform(as(get("/teacher/questions/" + hers + "/results"), otherTeacherToken)).andExpect(status().isNotFound());
        var list = json(mvc.perform(as(get("/teacher/questions"), otherTeacherToken)).andExpect(status().isOk()).andReturn());
        assertThat(list.toString()).doesNotContain(hers);
    }

    // ---------------------------------------------------------------- the child's side

    @Test void the_island_reaches_the_chosen_classes_and_nobody_else() throws Exception {
        String id = sendQuestion("Counting check", CLASS_A1, LocalDate.now().minusDays(1), LocalDate.now().plusDays(3));

        var island = islandsOf(childInClass);
        assertThat(island).hasSize(1);
        assertThat(island.get(0).get("questionId").asText()).isEqualTo(id);
        assertThat(island.get(0).get("teacherName").asText()).isEqualTo("Ms Sara");
        assertThat(island.get(0).get("teacherPhotoUrl").asText()).startsWith("https://");
        assertThat(island.get(0).get("stopsCount").asInt()).isEqualTo(1);
        assertThat(island.get(0).get("answered").asInt()).isZero();

        // a child of the same school in another grade sits in no class of the question
        assertThat(islandsOf(childInAnotherGrade)).isEmpty();
    }

    @Test void a_question_outside_its_window_is_not_on_the_map_and_not_playable() throws Exception {
        String past = sendQuestion("Last week", CLASS_A1, LocalDate.now().minusDays(10), LocalDate.now().minusDays(3));
        assertThat(islandsOf(childInClass)).isEmpty();
        mvc.perform(get("/children/" + childInClass + "/teacher-questions/" + past).header("Authorization", PARENT))
                .andExpect(status().isNotFound());

        String future = sendQuestion("Next week", CLASS_A1, LocalDate.now().plusDays(3), LocalDate.now().plusDays(10));
        assertThat(islandsOf(childInClass)).isEmpty();
        mvc.perform(get("/children/" + childInClass + "/teacher-questions/" + future).header("Authorization", PARENT))
                .andExpect(status().isNotFound());
    }

    @Test void a_draft_is_never_visible_to_a_child() throws Exception {
        String draft = create("Draft", stopsJson(choice("tq-s4", "Pick")), CLASS_A1).get("id").asText();
        assertThat(islandsOf(childInClass)).isEmpty();
        mvc.perform(get("/children/" + childInClass + "/teacher-questions/" + draft).header("Authorization", PARENT))
                .andExpect(status().isNotFound());
    }

    @Test void the_child_plays_the_question_and_the_teacher_reads_the_results() throws Exception {
        String id = sendQuestion("Counting check", CLASS_A1, LocalDate.now().minusDays(1), LocalDate.now().plusDays(3));

        var play = parentGet("/children/" + childInClass + "/teacher-questions/" + id);
        assertThat(play.get("level").asInt()).isEqualTo(1);
        assertThat(play.get("variant").asInt()).isZero();
        assertThat(play.get("kind").asText()).isEqualTo("teacher");
        assertThat(play.get("teacherName").asText()).isEqualTo("Ms Sara");
        assertThat(play.get("stops")).hasSize(1);
        assertThat(play.get("answeredStopIds")).isEmpty();
        String stopId = play.get("stops").get(0).get("id").asText();

        String answers = "[{\"stopId\":\"" + stopId + "\",\"answerJson\":\"{}\",\"correct\":true,\"stars\":3,\"answeredAt\":"
                + System.currentTimeMillis() + "}]";
        assertThat(parentPost("/children/" + childInClass + "/teacher-questions/" + id + "/answers", answers)
                .get("accepted").asInt()).isEqualTo(1);
        // the batch is idempotent on (question, child, stop), exactly as an attempt upload is on its id
        assertThat(parentPost("/children/" + childInClass + "/teacher-questions/" + id + "/answers", answers)
                .get("accepted").asInt()).isZero();

        assertThat(islandsOf(childInClass).get(0).get("answered").asInt()).isEqualTo(1);
        assertThat(parentGet("/children/" + childInClass + "/teacher-questions/" + id).get("answeredStopIds")).hasSize(1);

        var results = json(mvc.perform(as(get("/teacher/questions/" + id + "/results"), teacherToken))
                .andExpect(status().isOk()).andReturn());
        assertThat(results.get("stopIds")).hasSize(1);
        assertThat(results.get("firstTryAccuracy").asDouble()).isEqualTo(1.0);
        assertThat(results.get("children")).hasSize(1);
        var row = results.get("children").get(0);
        assertThat(row.get("childId").asText()).isEqualTo(childInClass);
        assertThat(row.get("answered").asInt()).isEqualTo(1);
        assertThat(row.get("stars").asInt()).isEqualTo(3);
        assertThat(row.get("stops").get(0).get("correct").asBoolean()).isTrue();

        var summary = json(mvc.perform(as(get("/teacher/questions"), teacherToken)).andExpect(status().isOk()).andReturn());
        var mine = summary.get(0);
        assertThat(mine.get("answeredChildren").asInt()).isEqualTo(1);
        assertThat(mine.get("totalChildren").asInt()).isEqualTo(1);
    }

    @Test void an_answer_naming_a_stop_the_question_does_not_have_is_refused() throws Exception {
        String id = sendQuestion("Counting check", CLASS_A1, LocalDate.now().minusDays(1), LocalDate.now().plusDays(3));
        var refused = json(mvc.perform(post("/children/" + childInClass + "/teacher-questions/" + id + "/answers")
                .header("Authorization", PARENT).contentType(MediaType.APPLICATION_JSON)
                .content("[{\"stopId\":\"not-a-stop\",\"answerJson\":\"{}\",\"correct\":true,\"stars\":3,\"answeredAt\":1}]"))
                .andExpect(status().isBadRequest()).andReturn());
        assertThat(refused.get("message").asText()).contains("not-a-stop");
    }

    /** The map keeps its §7 shape: the teacher island is attached beside the islands, never mixed into them. */
    @Test void the_teacher_island_does_not_disturb_the_map_rule() throws Exception {
        sendQuestion("Counting check", CLASS_A1, LocalDate.now().minusDays(1), LocalDate.now().plusDays(3));
        var map = map(childInClass);
        assertThat(map.get("islands")).isNotNull();
        assertThat(map.get("teacherIslands")).hasSize(1);
        var kinds = new java.util.ArrayList<String>();
        map.get("islands").forEach(i -> kinds.add(i.get("kind").asText()));
        assertThat(kinds).doesNotContain("teacher");
        assertThat(kinds.stream().filter("locked"::equals).count()).as("still exactly one locked island").isEqualTo(1);
    }

    /** With nothing sent, the field is absent altogether — which is what keeps the body schema-valid. */
    @Test void a_map_with_no_questions_carries_no_teacher_islands_field() throws Exception {
        assertThat(map(childInClass).has("teacherIslands")).isFalse();
    }

    // ---------------------------------------------------------------- helpers

    private JsonNode create(String title, String stops, String classId) throws Exception {
        return json(mvc.perform(as(post("/teacher/questions").contentType(MediaType.APPLICATION_JSON)
                .content(body(title, stops, classId)), teacherToken)).andExpect(status().isCreated()).andReturn());
    }

    private String sendQuestion(String title, String classId, LocalDate from, LocalDate to) throws Exception {
        String stops = stopsJson(choice(prefix() + java.util.UUID.randomUUID().toString().substring(0, 8), title));
        var created = json(mvc.perform(as(post("/teacher/questions").contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"" + title + "\",\"stops\":" + stops + ",\"classIds\":[\"" + classId + "\"],"
                        + "\"from\":\"" + from + "\",\"to\":\"" + to + "\"}"), teacherToken))
                .andExpect(status().isCreated()).andReturn());
        String id = created.get("id").asText();
        mvc.perform(as(post("/teacher/questions/" + id + "/send"), teacherToken)).andExpect(status().isOk());
        return id;
    }

    private static String body(String title, String stops, String classId) {
        return "{\"title\":\"" + title + "\",\"stops\":" + stops + ",\"classIds\":[\"" + classId + "\"],"
                + "\"from\":\"" + LocalDate.now().minusDays(1) + "\",\"to\":\"" + LocalDate.now().plusDays(7) + "\"}";
    }

    private JsonNode map(String childId) throws Exception {
        return parentGet("/children/" + childId + "/map?from=" + LocalDate.now().minusDays(7)
                + "&to=" + LocalDate.now().plusDays(7) + "&today=" + LocalDate.now());
    }

    private JsonNode islandsOf(String childId) throws Exception {
        var map = map(childId);
        return map.has("teacherIslands") ? map.get("teacherIslands") : mapper.createArrayNode();
    }
}
