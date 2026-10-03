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
import quest.server.push.ParentDeviceRepository;
import quest.server.push.PushProbe;
import quest.server.push.PushSender;

/**
 * B3, from the app end-to-end report (`docs/reports/app-parent-flows.md`): an exam is graded by the server and stays
 * sealed until the teacher releases it (D1, D2), and the parent hears about the release (D5).
 */
class ExamIntegrityTest extends ExamTestSupport {
    private static final String A = "ei-school-a", SARA = "ei-teacher-sara", CLASS_1A = "ei-1a", EXAM = "ei-exam-1", TICKET = "ei-ticket";

    @Autowired ExamReleaseSweep sweep;
    @Autowired PushSender pushes;
    @Autowired ParentDeviceRepository devices;

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

    @Test void todays_apps_bare_exit_ticket_leaves_its_questions_waiting_for_the_teacher_and_never_zero() throws Exception {
        ticketExam();
        parentPost("/children/" + maya + "/attempts", batch(answer("ei-yw", EXAM, TICKET, "", true, 3)));
        assertThat(examSittings.findOne(maya, EXAM).orElseThrow().getState()).as("one question still to go").isEqualTo("started");
        parentPost("/children/" + maya + "/attempts", batch(answer("ei-y1", EXAM, stop(EXAM, 1), "a", true, 3)));
        assertThat(examSittings.findOne(maya, EXAM).orElseThrow().getState()).isEqualTo("submitted");
        var mine = child(results(), maya);
        assertThat(mine.get("percent").asInt()).as("only the question she was graded on — the two unsent ones wait").isEqualTo(100);
        assertThat(mine.get("needsMarking").asInt()).isEqualTo(2);
        assertThat(row("ei-yw:ei-q1").getAnswerJson()).isEqualTo(quest.server.grading.AnswerKey.PENDING);

        var columns = json(mvc.perform(as(get("/teacher/lessons/" + EXAM + "/results"), sara)).andExpect(status().isOk()).andReturn()).get("stops");
        assertThat(columns).filteredOn(c -> c.get("stopId").asText().startsWith("ei-q")).as("the page offers the mark on both")
                .hasSize(2).allMatch(c -> c.get("open").asBoolean());
        assertThat(columns).filteredOn(c -> c.get("stopId").asText().equals(stop(EXAM, 1))).allMatch(c -> !c.get("open").asBoolean());

        markStop("ei-q1", 3); markStop("ei-q2", 1);
        mine = child(results(), maya);
        assertThat(mine.get("needsMarking").asInt()).as("the teacher's marks settle them").isZero();
        int percent = mine.get("percent").asInt();
        assertThat(percent).isLessThan(100);

        release(true);
        var result = parentGet("/children/" + maya + "/progress").get("results").get(0);
        assertThat(result.get("score").asInt()).as("the released score counts the teacher's marks").isEqualTo(percent);
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
        assertThat(sealed.get("examPlay").get("sealed").asBoolean()).isTrue();
        var stops = sealed.get("examPlay").get("stops");
        var options = stops.get(0).get("options");
        assertThat(options).extracting(o -> o.get("id").asText()).allMatch(id -> id.matches("x[0-9a-f]{16}")).doesNotContain("a", "b");
        assertThat(stops.get(0).get("correctOptionId").asText()).as("a placeholder: the first option sent").isEqualTo(options.get(0).get("id").asText());
        assertThat(stops.get(0).get("hint").asText()).isEqualTo("…");
        var q2 = stops.get(1).get("questions").get(1);
        assertThat(q2.get("type").asText()).isEqualTo("trueFalse");
        assertThat(q2.get("answer").asBoolean()).as("present, and meaningless").isFalse();
        assertThat(sealed.get("plays").get(0).get("sealed").asBoolean()).as("the level plays too").isTrue();

        release(true);
        var open = parentGet("/lessons/" + EXAM).get("examPlay");
        assertThat(open.has("sealed")).isFalse();
        assertThat(open.get("stops").get(0).get("correctOptionId").asText()).isEqualTo("a");
        assertThat(open.get("stops").get(1).get("questions").get(1).get("answer").asBoolean()).isTrue();
    }

    @Test void the_opaque_ids_are_hers_stable_on_resume_and_graded_back_to_the_key() throws Exception {
        publishedExam(-30, 30, ExamLevels.MANUAL);
        var first = parentGet("/lessons/" + EXAM).get("examPlay").get("stops").get(0);
        assertThat(parentGet("/lessons/" + EXAM).get("examPlay").get("stops").get(0)).as("a resumed sitting sees the same ids").isEqualTo(first);
        var otherParent = json(mvc.perform(get("/lessons/" + EXAM).header("Authorization", "Bearer fake-token-parent-ei-other")).andExpect(status().isOk()).andReturn());
        assertThat(otherParent.get("examPlay").get("stops").get(0).get("options").get(0).get("id").asText())
                .as("another parent's copy has ids of its own").isNotIn(idsOf(first.get("options")));

        String right = null, wrong = null;
        for (var o : first.get("options")) if ("A".equals(o.get("label").asText())) right = o.get("id").asText(); else wrong = o.get("id").asText();
        parentPost("/children/" + maya + "/attempts", batch(answer("ei-o1", EXAM, stop(EXAM, 1), right, false, 0)));
        parentPost("/children/" + omar + "/attempts", batch(answer("ei-o2", EXAM, stop(EXAM, 1), wrong, true, 3)));
        assertThat(row("ei-o1").isCorrect()).isTrue();
        assertThat(row("ei-o2").isCorrect()).isFalse();
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
        String phone = PushProbe.token("ei-phone");
        PushProbe.register(mvc, PARENT, phone, "ar");
        publishedExam(-30, 30, ExamLevels.MANUAL);
        sit(maya);
        release(true);

        var rows = kinds("exam.released");
        assertThat(rows).as("one per child of the section — both are this parent's").hasSize(2);
        assertThat(rows).extracting(r -> r.get("childId").asText()).containsExactlyInAnyOrder(maya, omar);
        assertThat(rows.get(0).get("lessonId").asText()).isEqualTo(EXAM);
        assertThat(rows.get(0).has("readAt")).isFalse();
        assertThat(parentGet("/me/notifications/unread-count").get("count").asInt()).isEqualTo(2);

        // B4: one push per row, after the release committed — English, since the server has no Arabic for these.
        var pushed = PushProbe.await(pushes, phone, 2);
        assertThat(pushed).hasSize(2);
        assertThat(pushed).extracting(p -> p.message().getChildId()).containsExactlyInAnyOrder(maya, omar);
        assertThat(pushed).allSatisfy(p -> {
            assertThat(p.message().getKind()).isEqualTo(quest.api.dto.NotificationKind.EXAM_RELEASED);
            assertThat(p.message().getCollapseKey()).isEqualTo("lesson:" + EXAM);
            assertThat(p.message().getLink()).isEqualTo("/children/" + p.message().getChildId() + "/progress");
            assertThat(p.message().getTitle()).startsWith("Results ready");
        });
        assertThat(pushed).extracting(p -> p.message().getNotificationId())
                .containsExactlyInAnyOrderElementsOf(rows.stream().map(r -> r.get("id").asText()).toList());

        release(false); release(true);
        assertThat(kinds("exam.released")).as("a release given again is not news").hasSize(2);
        assertThat(PushProbe.await(pushes, phone, 3)).as("and pushes nothing").hasSize(2);
        devices.deleteByTokenValue(phone);
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
        String phone = PushProbe.token("ei-phone");
        PushProbe.register(mvc, PARENT, phone, null);
        publishedExam(-30, 30, ExamLevels.MANUAL);
        assertThat(kinds("homework.published")).as("publishing an exam releases nothing").isEmpty();
        readyToPublish("ei-homework-1", A, section1a, LocalDate.now(), "homework");
        publish("ei-homework-1");
        var rows = kinds("homework.published");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("lessonId").asText()).isEqualTo("ei-homework-1");
        assertThat(PushProbe.await(pushes, phone, 2)).as("B4: one push per row, none for the exam's publish")
                .hasSize(2).allSatisfy(p -> assertThat(p.message().getKind()).isEqualTo(quest.api.dto.NotificationKind.HOMEWORK_PUBLISHED));
        devices.deleteByTokenValue(phone);
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

    private void markStop(String stopId, int stars) throws Exception {
        mvc.perform(as(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/teacher/marks"), sara).contentType(MediaType.APPLICATION_JSON)
                .content("{\"marks\":[{\"childId\":\"" + maya + "\",\"lessonId\":\"" + EXAM + "\",\"stopId\":\"" + stopId + "\",\"stars\":" + stars + "}]}"))
                .andExpect(status().isOk());
    }

    private static List<String> idsOf(JsonNode options) { var out = new ArrayList<String>(); options.forEach(o -> out.add(o.get("id").asText())); return out; }

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
