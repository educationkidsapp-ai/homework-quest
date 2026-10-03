package quest.server.exams;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import quest.server.children.Entities.AttemptEntity;
import quest.server.flags.FlagKeys;

/**
 * B3, from the app end-to-end report (`docs/reports/app-parent-flows.md`): an exam is graded by the server and stays
 * sealed until the teacher releases it (D1, D2), and the parent hears about the release (D5).
 */
class ExamIntegrityTest extends ExamTestSupport {
    private static final String A = "ei-school-a", SARA = "ei-teacher-sara", CLASS_1A = "ei-1a", EXAM = "ei-exam-1", TICKET = "ei-ticket";

    @Autowired ExamReleaseSweep sweep;

    @Override public String prefix() { return "ei-"; }

    private String sara, maya, omar;
    private quest.server.tenancy.Entities.ClassEntity section1a;

    @BeforeEach void seed() throws Exception {
        school(A, "Integrity Academy", "EISCHA");
        teacher(SARA, A, "Ms Sara");
        section1a = klass(CLASS_1A, A, SARA, "1A");
        String admin = adminToken();
        enableGrading(admin, A);
        setFlag(admin, A, FlagKeys.EXAMS, true);
        sara = token(SARA, "TEACHER", A);
        maya = child("Maya", "EISCHA", section1a);
        omar = child("Omar", "EISCHA", section1a);
    }

    @AfterEach void clean() { removeSeed(); }

    // ---------------------------------------------------------------- D2: the server grades

    @Test void a_tampered_exam_answer_is_graded_by_the_key_and_not_by_what_the_app_claims() throws Exception {
        publishedExam(-30, 30, ExamLevels.MANUAL);
        parentPost("/children/" + maya + "/attempts", batch(
                answer("ei-t1", EXAM, stop(EXAM, 1), "b", true, 3),           // wrong, claimed right with three stars
                answer("ei-t2", EXAM, stop(EXAM, 2), "a", false, 0),          // right, claimed wrong
                answer("ei-t3", EXAM, stop(EXAM, 3), "retold", true, 3)));    // a retell: nothing to check

        assertThat(row("ei-t1").isCorrect()).isFalse(); assertThat(row("ei-t1").getStars()).isZero();
        assertThat(row("ei-t2").isCorrect()).isTrue(); assertThat(row("ei-t2").getStars()).isEqualTo(3);
        assertThat(row("ei-t3").getStars()).as("an open stop is never client-scored").isZero();

        var mine = child(results(), maya);
        assertThat(mine.get("percent").asInt()).as("(0 + 100) / 2 — the retell waits for its mark").isEqualTo(50);
        assertThat(mine.get("needsMarking").asInt()).as("the retell is pending marking").isEqualTo(1);

        mvc.perform(post("/children/" + maya + "/attempts").header("Authorization", PARENT).contentType(MediaType.APPLICATION_JSON)
                        .content(batch(answer("ei-t4", EXAM, stop(EXAM, 1), "a", true, 3))))
                .andExpect(status().isConflict());
    }

    @Test void the_first_answer_is_the_answer_and_a_stop_off_the_paper_is_not_one() throws Exception {
        publishedExam(-30, 30, ExamLevels.MANUAL);
        parentPost("/children/" + maya + "/attempts", batch(answer("ei-f1", EXAM, stop(EXAM, 1), "b", false, 0)));
        var second = parentPost("/children/" + maya + "/attempts", batch(
                answer("ei-f2", EXAM, stop(EXAM, 1), "a", true, 3),
                answer("ei-f3", EXAM, "ei-not-on-the-paper", "a", true, 3)));
        assertThat(second.get("accepted").asInt()).isZero();
        assertThat(attempts.findById("ei-f2")).isEmpty();
        assertThat(attempts.findById("ei-f3")).isEmpty();
        assertThat(row("ei-f1").isCorrect()).isFalse();
    }

    // ---------------------------------------------------------------- review: exit tickets, races, the key

    @Test void a_released_apps_exit_ticket_with_answers_is_graded_question_by_question() throws Exception {
        ticketExam();
        parentPost("/children/" + maya + "/attempts", batch(
                answer("ei-x1", EXAM, stop(EXAM, 1), "a", false, 0),
                answer("ei-xw", EXAM, TICKET, "{\\\"ei-q1\\\":\\\"a\\\",\\\"ei-q2\\\":\\\"false\\\"}", true, 3)));
        assertThat(row("ei-xw:ei-q1").isCorrect()).isTrue();
        assertThat(row("ei-xw:ei-q2").isCorrect()).as("the statement is true").isFalse();
        assertThat(examSittings.findOne(maya, EXAM).orElseThrow().getState()).isEqualTo("submitted");
        assertThat(child(results(), maya).get("percent").asInt()).as("(100 + 100 + 0) / 3").isEqualTo(67);
    }

    @Test void a_released_apps_bare_exit_ticket_hands_the_paper_in_with_its_questions_unanswered() throws Exception {
        ticketExam();
        parentPost("/children/" + maya + "/attempts", batch(answer("ei-yw", EXAM, TICKET, "", true, 3)));
        assertThat(examSittings.findOne(maya, EXAM).orElseThrow().getState()).as("one question still to go").isEqualTo("started");
        parentPost("/children/" + maya + "/attempts", batch(answer("ei-y1", EXAM, stop(EXAM, 1), "a", true, 3)));
        assertThat(examSittings.findOne(maya, EXAM).orElseThrow().getState()).isEqualTo("submitted");
        assertThat(child(results(), maya).get("percent").asInt()).as("(100 + 0 + 0) / 3 — nothing was sent for them").isEqualTo(33);
    }

    @Test void an_updated_apps_exit_ticket_is_one_attempt_per_question() throws Exception {
        ticketExam();
        parentPost("/children/" + maya + "/attempts", batch(
                answer("ei-z1", EXAM, stop(EXAM, 1), "a", true, 3),
                answer("ei-zq1", EXAM, "ei-q1", "a", true, 3),
                answer("ei-zq2", EXAM, "ei-q2", "true", true, 3)));
        assertThat(examSittings.findOne(maya, EXAM).orElseThrow().getState()).as("the ticket's questions complete it").isEqualTo("submitted");
        assertThat(child(results(), maya).get("percent").asInt()).isEqualTo(100);
    }

    @Test void two_uploads_racing_on_one_question_store_only_one_answer() throws Exception {
        publishedExam(-30, 30, ExamLevels.MANUAL);
        parentPost("/children/" + maya + "/attempts", batch(answer("ei-r0", EXAM, stop(EXAM, 1), "a", true, 3)));
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var go = new java.util.concurrent.CountDownLatch(1);
        var statuses = new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
        for (String answer : List.of("a", "b"))
            statuses.add(pool.submit(() -> {
                go.await();
                return mvc.perform(post("/children/" + maya + "/attempts").header("Authorization", PARENT).contentType(MediaType.APPLICATION_JSON)
                        .content(batch(answer("ei-r-" + answer, EXAM, stop(EXAM, 2), answer, true, 3)))).andReturn().getResponse().getStatus();
            }));
        go.countDown();
        for (var f : statuses) assertThat(f.get(30, java.util.concurrent.TimeUnit.SECONDS)).isLessThan(500);
        pool.shutdown();
        assertThat(attempts.findByChildIdAndLessonIdIn(maya, List.of(EXAM)).stream().filter(a -> a.getStopId().equals(stop(EXAM, 2))))
                .as("the first answer is the answer, even under a race").hasSize(1);

        var twin = row("ei-r0");
        twin.setId("ei-r0-twin");
        assertThat(attempts.insertExamAnswer(twin)).as("the database refuses a second answer by itself").isZero();
    }

    @Test void the_paper_carries_no_answer_key_until_the_results_are_released() throws Exception {
        ticketExam();
        var sealed = parentGet("/lessons/" + EXAM);
        var stops = sealed.get("examPlay").get("stops");
        assertThat(stops.get(0).get("correctOptionId").asText()).isEmpty();
        assertThat(stops.get(0).get("hint").asText()).isEmpty();
        assertThat(stops.get(0).get("parentTip").get("en").asText()).isEmpty();
        var q2 = stops.get(1).get("questions").get(1);
        assertThat(q2.get("type").asText()).isEqualTo("trueFalse");
        assertThat(q2.has("answer")).isFalse();
        assertThat(sealed.get("plays").get(0).get("stops").get(0).get("correctOptionId").asText()).as("the level plays too").isEmpty();

        release(true);
        var open = parentGet("/lessons/" + EXAM).get("examPlay").get("stops");
        assertThat(open.get(0).get("correctOptionId").asText()).isEqualTo("a");
        assertThat(open.get(1).get("questions").get(1).get("answer").asBoolean()).isTrue();
    }

    // ---------------------------------------------------------------- D1: sealed until released

    @Test void an_unreleased_exam_shows_no_score_to_the_parent_and_a_released_one_does() throws Exception {
        publishedExam(-30, 30, ExamLevels.MANUAL);
        sit(maya);

        var island = island(maya);
        assertThat(island.get("state").asText()).as("handed in: the app's Submitted card").isEqualTo("done");
        assertThat(island.has("starsEarned")).isFalse();
        assertThat(island.has("starsTotal")).isFalse();
        var skill = skill(parentGet("/children/" + maya + "/progress"));
        assertThat(skill.get("attempts").asInt()).as("no skill contribution before release").isZero();
        assertThat(skill.has("band")).isFalse();
        assertThat(parentGet("/children/" + maya + "/progress").path("results")).isEmpty();

        release(true);
        island = island(maya);
        assertThat(island.get("starsEarned").asInt()).as("3 + 0 + 0").isEqualTo(3);
        assertThat(island.get("starsTotal").asInt()).isEqualTo(9);
        skill = skill(parentGet("/children/" + maya + "/progress"));
        assertThat(skill.get("attempts").asInt()).isEqualTo(2);
        assertThat(skill.has("band")).isTrue();
        assertThat(parentGet("/children/" + maya + "/progress").path("results")).hasSize(1);
    }

    // ---------------------------------------------------------------- D5: the parent is told

    @Test void releasing_an_exam_tells_each_childs_parent_once() throws Exception {
        publishedExam(-30, 30, ExamLevels.MANUAL);
        sit(maya);
        release(true);

        var rows = kinds("exam.released");
        assertThat(rows).as("one per child of the section — both are this parent's").hasSize(2);
        assertThat(rows).extracting(r -> r.get("childId").asText()).containsExactlyInAnyOrder(maya, omar);
        assertThat(rows.get(0).get("lessonId").asText()).isEqualTo(EXAM);
        assertThat(rows.get(0).has("readAt")).isFalse();
        assertThat(parentGet("/me/notifications/unread-count").get("count").asInt()).isEqualTo(2);

        release(false); release(true);
        assertThat(kinds("exam.released")).as("a release given again is not news").hasSize(2);
        assertThat(parentPost("/me/notifications/read-all", "").get("count").asInt()).isZero();
        assertThat(parentGet("/me/notifications/unread-count").get("count").asInt()).isZero();
    }

    @Test void the_close_of_window_sweep_tells_the_parents_too() throws Exception {
        publishedExam(-30, 30, ExamLevels.AUTO_ON_CLOSE);
        exam(EXAM, A, ExamLevels.ONE, -120, -60, ExamLevels.AUTO_ON_CLOSE);
        sweep.sweep();
        assertThat(kinds("exam.released")).hasSize(2);
    }

    @Test void a_published_homework_is_announced_and_an_exam_publish_is_not() throws Exception {
        publishedExam(-30, 30, ExamLevels.MANUAL);
        assertThat(kinds("homework.published")).as("publishing an exam releases nothing").isEmpty();
        readyToPublish("ei-homework-1", A, section1a, LocalDate.now(), "homework");
        publish("ei-homework-1");
        var rows = kinds("homework.published");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("lessonId").asText()).isEqualTo("ei-homework-1");
    }

    // ---------------------------------------------------------------- fixture

    private void publishedExam(long opensIn, long closesIn, String releaseMode) throws Exception {
        readyToPublish(EXAM, A, section1a, LocalDate.now(), "exam");
        exam(EXAM, A, ExamLevels.ONE, opensIn, closesIn, releaseMode);
        publish(EXAM);
    }

    /** The fixture exam with its Level 1 play ending in an exit ticket — what every generated play does. */
    private void ticketExam() throws Exception {
        publishedExam(-30, 30, ExamLevels.MANUAL);
        var tip = new quest.api.dto.Bilingual("Count together.", "Count together.");
        var ing = new quest.api.dto.Ingredient("C", "carrot");
        var tiles = List.of(new quest.api.dto.Tile("a", "A", null, null), new quest.api.dto.Tile("b", "B", null, null));
        var ticket = new quest.api.dto.Stop.ExitTicket(TICKET, "Exit", "Three to finish", ing, tip, List.of(
                new quest.api.dto.Stop.Choice("ei-q1", "Q1", "Which?", ing, tip, "Count first.", "Which?", tiles, "a", null, null),
                new quest.api.dto.Stop.TrueFalse("ei-q2", "Q2", "True?", ing, tip, "Look again.", "Two is more than one.", true, null, null)), null, null);
        var first = new quest.api.dto.Stop.Choice(stop(EXAM, 1), "S1", "Which?", ing, tip, "Count first.", "Which?", tiles, "a", null, null);
        store.savePlay(EXAM, new quest.api.dto.Play(1, 0, quest.api.dto.SourceKind.MATH, new quest.api.dto.Theme("Pot", "Soup", "S", "Served!"),
                List.of(first, ticket), null), "v1", 1);
    }

    private void publish(String lessonId) throws Exception {
        mvc.perform(as(post("/teacher/lessons/" + lessonId + "/publish"), sara)
                .contentType(MediaType.APPLICATION_JSON).content("{\"classIds\":[\"" + CLASS_1A + "\"]}")).andExpect(status().isOk());
    }

    private void release(boolean released) throws Exception {
        mvc.perform(as(post("/teacher/lessons/" + EXAM + "/release"), sara)
                .contentType(MediaType.APPLICATION_JSON).content("{\"released\":" + released + "}")).andExpect(status().isOk());
    }

    /** The whole paper: the first question right, the second wrong, then the retell. */
    private void sit(String childId) throws Exception {
        parentPost("/children/" + childId + "/attempts", batch(
                upload(childId + "-1", EXAM, stop(EXAM, 1), true, 3),
                upload(childId + "-2", EXAM, stop(EXAM, 2), false, 3),
                upload(childId + "-3", EXAM, stop(EXAM, 3), true, 3)));
    }

    private AttemptEntity row(String id) { return attempts.findById(id).orElseThrow(); }

    private JsonNode results() throws Exception {
        return json(mvc.perform(as(get("/teacher/exams/" + EXAM + "/results"), sara)).andExpect(status().isOk()).andReturn());
    }

    private JsonNode island(String childId) throws Exception {
        var today = LocalDate.now();
        var map = parentGet("/children/" + childId + "/map?from=" + today.minusDays(7) + "&to=" + today.plusDays(7) + "&today=" + today);
        for (var i : map.get("islands")) if (EXAM.equals(i.path("lessonId").asText(null)) && "lesson".equals(i.get("kind").asText())) return i;
        throw new AssertionError("no exam island");
    }

    private static JsonNode skill(JsonNode progress) {
        for (var s : progress.get("skills")) if ((EXAM + ":skill-1").equals(s.get("skillId").asText())) return s;
        throw new AssertionError("no skill row for the exam");
    }

    private List<JsonNode> kinds(String kind) throws Exception {
        var out = new ArrayList<JsonNode>();
        for (var r : parentGet("/me/notifications")) if (kind.equals(r.get("kind").asText())) out.add(r);
        return out;
    }

    private static JsonNode child(JsonNode results, String childId) {
        for (var c : results.get("children")) if (childId.equals(c.get("childId").asText())) return c;
        throw new AssertionError("no row for " + childId);
    }
}
