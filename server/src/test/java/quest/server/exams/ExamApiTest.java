package quest.server.exams;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import quest.server.flags.FlagKeys;

/**
 * `docs/teacher-flow.md` step 10 and teacher prompt §8, end to end: the window, the one resumable sitting, the
 * re-opening, the automatic release, the results and the printable sheet — and the three rules a school is actually
 * promised, which are that another teacher's exam is a 403, another school's is a 404, and a school with the flag
 * off has no exams at all.
 */
class ExamApiTest extends ExamTestSupport {
    private static final String A = "ex-school-a", B = "ex-school-b";
    private static final String SARA = "ex-teacher-sara", NOOR = "ex-teacher-noor", OTHER = "ex-teacher-other";
    private static final String CLASS_1A = "ex-1a", CLASS_1B = "ex-1b", CLASS_OTHER = "ex-other";
    private static final String EXAM = "ex-exam-1";

    @Autowired ExamReleaseSweep sweep;

    @Override public String prefix() { return "ex-"; }

    private String adminToken, sara, noor, other, maya, omar, layla;
    private quest.server.tenancy.Entities.ClassEntity section1a;

    @BeforeEach void seed() throws Exception {
        school(A, "Exam Academy", "EXSCHA");
        school(B, "Other Academy", "EXSCHB");
        teacher(SARA, A, "Ms Sara"); teacher(NOOR, A, "Ms Noor"); teacher(OTHER, B, "Ms Other");
        section1a = klass(CLASS_1A, A, SARA, "1A");
        klass(CLASS_1B, A, NOOR, "1B");
        klass(CLASS_OTHER, B, OTHER, "1A");

        adminToken = adminToken();
        enableGrading(adminToken, A); enableGrading(adminToken, B);
        setFlag(adminToken, A, FlagKeys.EXAMS, true);
        setFlag(adminToken, B, FlagKeys.EXAMS, true);
        sara = token(SARA, "TEACHER", A); noor = token(NOOR, "TEACHER", A); other = token(OTHER, "TEACHER", B);

        maya = child("Maya", "EXSCHA", section1a);
        omar = child("Omar", "EXSCHA", section1a);
        layla = child("Layla", "EXSCHA", section1a);
    }

    @AfterEach void clean() { removeSeed(); }

    // ---------------------------------------------------------------- the window (§8)

    @Test void the_island_is_on_the_map_only_between_open_and_close() throws Exception {
        publishedExam(-30, 30, ExamLevels.AUTO_ON_CLOSE);
        var island = island(maya, EXAM);
        assertThat(island).as("open now: the island is there").isNotNull();
        assertThat(island.get("examWindow").get("level").asText()).isEqualTo(ExamLevels.ONE);
        assertThat(island.get("examWindow").get("hintsOff").asBoolean()).isTrue();
        assertThat(island.get("examWindow").get("numbersOff").asBoolean()).isTrue();

        exam(EXAM, A, ExamLevels.ONE, 60, 120, ExamLevels.AUTO_ON_CLOSE);
        assertThat(island(maya, EXAM)).as("not yet open").isNull();

        exam(EXAM, A, ExamLevels.ONE, -120, -60, ExamLevels.AUTO_ON_CLOSE);
        assertThat(island(maya, EXAM)).as("already closed").isNull();
    }

    @Test void a_homework_island_carries_no_window() throws Exception {
        var homework = readyToPublish("ex-homework-1", A, section1a, LocalDate.now(), "homework");
        publish(homework.getId());
        assertThat(island(maya, "ex-homework-1").has("examWindow")).isFalse();
    }

    @Test void the_play_the_child_downloads_has_the_two_switches_and_one_paper() throws Exception {
        publishedExam(-30, 30, ExamLevels.AUTO_ON_CLOSE);
        var lesson = parentGet("/lessons/" + EXAM);
        assertThat(lesson.get("type").asText()).isEqualTo("exam");
        assertThat(lesson.get("hintsOff").asBoolean()).isTrue();
        assertThat(lesson.get("numbersOff").asBoolean()).isTrue();
        assertThat(lesson.get("examPlay").get("stops")).as("level 1's three stops, and no other level's").hasSize(3);
    }

    @Test void a_mixed_paper_takes_one_stop_from_each_level_in_turn() throws Exception {
        publishedExam(-30, 30, ExamLevels.AUTO_ON_CLOSE);
        exam(EXAM, A, ExamLevels.MIXED, -30, 30, ExamLevels.AUTO_ON_CLOSE);
        var stops = parentGet("/lessons/" + EXAM).get("examPlay").get("stops");
        // level 1 has three stops and levels 2 and 3 one each: 1a, 2a, 3a, 1b, 1c — round-robin, then what is left
        assertThat(stops).extracting(s -> s.get("id").asText())
                .containsExactly(stop(EXAM, 1), EXAM + ":L2v0:s1", EXAM + ":L3v0:s1", stop(EXAM, 2), stop(EXAM, 3));
    }

    // ---------------------------------------------------------------- one resumable sitting (§8)

    @Test void a_sitting_resumes_and_a_second_one_is_refused() throws Exception {
        publishedExam(-30, 30, ExamLevels.AUTO_ON_CLOSE);

        // she answers two of the three stops, then the tablet dies
        assertThat(parentPost("/children/" + maya + "/attempts",
                batch(upload("ex-a1", EXAM, stop(EXAM, 1), true, 3))).get("accepted").asInt()).isEqualTo(1);
        var started = examSittings.findOne(maya, EXAM).orElseThrow();
        assertThat(started.getState()).isEqualTo("started");
        assertThat(started.getSubmittedAt()).isNull();

        // she comes back and finishes it on the same sitting
        parentPost("/children/" + maya + "/attempts", batch(
                upload("ex-a2", EXAM, stop(EXAM, 2), true, 3), upload("ex-a3", EXAM, stop(EXAM, 3), true, 3)));
        var submitted = examSittings.findOne(maya, EXAM).orElseThrow();
        assertThat(submitted.getId()).as("the same sitting, not a second one").isEqualTo(started.getId());
        assertThat(submitted.getState()).isEqualTo("submitted");
        assertThat(submitted.getSecondsTaken()).isNotNull();

        // and a second sitting is refused
        mvc.perform(post("/children/" + maya + "/attempts").header("Authorization", PARENT)
                        .contentType(MediaType.APPLICATION_JSON).content(batch(upload("ex-a4", EXAM, stop(EXAM, 1), true, 3))))
                .andExpect(status().isConflict())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.code").value("exam_already_taken"));
    }

    @Test void an_answer_outside_the_window_is_refused() throws Exception {
        publishedExam(-120, -60, ExamLevels.MANUAL);
        mvc.perform(post("/children/" + maya + "/attempts").header("Authorization", PARENT)
                        .contentType(MediaType.APPLICATION_JSON).content(batch(upload("ex-late", EXAM, stop(EXAM, 1), true, 3))))
                .andExpect(status().isConflict())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.code").value("exam_closed"));
        assertThat(examSittings.findOne(maya, EXAM)).as("a refused batch leaves nothing behind").isEmpty();
    }

    // ---------------------------------------------------------------- the one re-opening (§8)

    @Test void an_absent_child_is_re_opened_once_and_can_then_sit_it_after_the_close() throws Exception {
        publishedExam(-120, -60, ExamLevels.MANUAL);

        var reopen = json(mvc.perform(as(post("/teacher/exams/" + EXAM + "/reopen/" + layla), sara))
                .andExpect(status().isOk()).andReturn());
        assertThat(reopen.get("closesAt").asLong()).isGreaterThan(System.currentTimeMillis());

        assertThat(parentPost("/children/" + layla + "/attempts",
                batch(upload("ex-l1", EXAM, stop(EXAM, 1), true, 3))).get("accepted").asInt()).isEqualTo(1);

        mvc.perform(as(post("/teacher/exams/" + EXAM + "/reopen/" + layla), sara))
                .andExpect(status().isConflict())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.code").value("exam_already_reopened"));
    }

    @Test void a_child_of_another_class_cannot_be_re_opened_through_this_exam() throws Exception {
        publishedExam(-30, 30, ExamLevels.MANUAL);
        var elsewhere = child("Zain", "EXSCHB", klass(CLASS_OTHER, B, OTHER, "1A"));
        mvc.perform(as(post("/teacher/exams/" + EXAM + "/reopen/" + elsewhere), sara)).andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- release (§8)

    @Test void the_sweep_releases_a_closed_exam_only_when_the_teacher_asked_for_that() throws Exception {
        publishedExam(-120, -60, ExamLevels.MANUAL);
        sweep.sweep();
        assertThat(lessons.findById(EXAM).orElseThrow().getReleasedAt()).as("manual: nothing happens").isNull();

        exam(EXAM, A, ExamLevels.ONE, -120, -60, ExamLevels.AUTO_ON_CLOSE);
        assertThat(sweep.sweep()).isPositive();
        assertThat(lessons.findById(EXAM).orElseThrow().getReleasedAt()).isNotNull();
    }

    @Test void publishing_an_exam_does_not_release_it_and_a_withdrawal_survives_the_sweep() throws Exception {
        publishedExam(-120, -60, ExamLevels.AUTO_ON_CLOSE);
        assertThat(lessons.findById(EXAM).orElseThrow().getReleasedAt()).as("§7's default is for homework only").isNull();

        mvc.perform(as(post("/teacher/exams/" + EXAM + "/release"), sara)
                .contentType(MediaType.APPLICATION_JSON).content("{\"released\":false}")).andExpect(status().isOk());
        sweep.sweep();
        assertThat(lessons.findById(EXAM).orElseThrow().getReleasedAt())
                .as("a default is not an argument against an instruction").isNull();
    }

    // ---------------------------------------------------------------- the results page (§8)

    @Test void the_results_carry_the_distribution_the_difficulty_and_the_absent_list() throws Exception {
        publishedExam(-120, 120, ExamLevels.MANUAL);
        sit(maya, true, false);            // 3 stars, then wrong-then-right, then the retell → 50
        sit(omar, true, true);             // both right first try → 100
        // Layla never sits it

        var results = json(mvc.perform(as(get("/teacher/exams/" + EXAM + "/results"), sara)).andExpect(status().isOk()).andReturn());
        assertThat(results.get("roster").asInt()).isEqualTo(3);
        assertThat(results.get("sat").asInt()).isEqualTo(2);
        assertThat(results.get("submitted").asInt()).isEqualTo(2);
        assertThat(results.get("absent").asInt()).isEqualTo(1);
        assertThat(results.get("classAverage").asInt()).as("(50 + 100) / 2").isEqualTo(75);
        assertThat(results.get("needsMarking").asInt()).as("two unmarked retells").isEqualTo(2);
        assertThat(results.get("absentees")).hasSize(1);
        assertThat(results.get("absentees").get(0).get("state").asText()).isEqualTo("absent");

        assertThat(bucket(results, "developing")).isEqualTo(1);
        assertThat(bucket(results, "exceeding")).isEqualTo(1);
        assertThat(bucket(results, "emerging")).isZero();
        assertThat(bucket(results, "secure")).isZero();

        var questions = results.get("questions");
        assertThat(questions).hasSize(3);
        assertThat(questions.get(0).get("answered").asInt()).isEqualTo(2);
        assertThat(questions.get(0).get("missedPercent").asInt()).as("both right first try").isZero();
        assertThat(questions.get(1).get("missedPercent").asInt()).as("Maya missed it first try, Omar did not").isEqualTo(50);
        assertThat(questions.get(2).get("open").asBoolean()).isTrue();
        assertThat(questions.get(2).path("missedPercent").isNull()).as("an open stop has no right answer").isTrue();

        var mine = child(results, maya);
        assertThat(mine.get("percent").asInt()).isEqualTo(50);
        assertThat(mine.get("band").asText()).isEqualTo("developing");
        assertThat(mine.get("score").asInt()).as("3 + 1 + 3 stars").isEqualTo(7);
        assertThat(mine.get("maxScore").asInt()).isEqualTo(9);
        assertThat(mine.get("secondsTaken").isNull()).isFalse();
    }

    @Test void the_exports_and_the_printable_sheet_are_served() throws Exception {
        publishedExam(-120, 120, ExamLevels.MANUAL);
        sit(maya, true, false);

        var csv = mvc.perform(as(get("/teacher/exams/" + EXAM + "/results.csv"), sara)).andExpect(status().isOk()).andReturn();
        assertThat(csv.getResponse().getContentAsString()).contains("Maya").contains("Class average").contains("Missed %");

        var xlsx = mvc.perform(as(get("/teacher/exams/" + EXAM + "/results.xlsx"), sara)).andExpect(status().isOk()).andReturn();
        assertThat(xlsx.getResponse().getContentAsByteArray()).hasSizeGreaterThan(1000);

        var pdf = mvc.perform(as(get("/teacher/exams/" + EXAM + "/results/" + maya + ".pdf"), sara))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(pdf, 0, 4, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("%PDF");
        assertThat(pdf).hasSizeGreaterThan(800);

        mvc.perform(as(get("/teacher/exams/" + EXAM + "/results/" + layla + "-nobody.pdf"), sara)).andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- settings, scope and the flag (§8, §4)

    @Test void an_exam_is_created_from_the_class_page_and_frozen_once_it_opens() throws Exception {
        var monday = LocalDate.now().with(java.time.temporal.TemporalAdjusters.next(java.time.DayOfWeek.MONDAY));
        long opensAt = monday.atTime(9, 0).toInstant(java.time.ZoneOffset.UTC).toEpochMilli();
        var created = json(mvc.perform(as(post("/teacher/classes/" + CLASS_1A + "/exams"), sara)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Autumn test\",\"opensAt\":" + opensAt + ",\"closesAt\":" + (opensAt + 1_800_000)
                                + ",\"level\":\"2\",\"source\":\"manual\",\"durationMinutes\":30,\"releaseMode\":\"manual\"}"))
                .andExpect(status().isOk()).andReturn());
        String id = created.get("examId").asText();
        assertThat(created.get("level").asText()).isEqualTo("2");
        assertThat(created.get("singleAttempt").asBoolean()).isTrue();
        assertThat(created.get("open").asBoolean()).isFalse();
        assertThat(lessons.findById(id).orElseThrow().getType()).isEqualTo("exam");

        assertThat(json(mvc.perform(as(patch("/teacher/exams/" + id), sara).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"level\":\"mixed\"}")).andExpect(status().isOk()).andReturn())
                .get("level").asText()).isEqualTo("mixed");

        exam(id, A, ExamLevels.MIXED, -10, 50, ExamLevels.MANUAL);
        mvc.perform(as(patch("/teacher/exams/" + id), sara).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"level\":\"1\"}"))
                .andExpect(status().isConflict())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("exam_open"));
    }

    @Test void a_homework_is_not_an_exam_and_answers_404_on_these_routes() throws Exception {
        var homework = readyToPublish("ex-homework-2", A, section1a, LocalDate.now(), "homework");
        publish(homework.getId());
        mvc.perform(as(get("/teacher/exams/" + homework.getId() + "/results"), sara)).andExpect(status().isNotFound());
    }

    @Test void another_teachers_exam_is_a_403_and_another_schools_is_a_404() throws Exception {
        publishedExam(-30, 30, ExamLevels.MANUAL);
        mvc.perform(as(get("/teacher/exams/" + EXAM + "/results"), noor)).andExpect(status().isForbidden());
        mvc.perform(as(get("/teacher/exams/" + EXAM + "/results"), other)).andExpect(status().isNotFound());
        mvc.perform(as(post("/teacher/exams/" + EXAM + "/reopen/" + maya), other)).andExpect(status().isNotFound());
    }

    @Test void every_route_is_404_while_the_flag_is_off() throws Exception {
        publishedExam(-30, 30, ExamLevels.MANUAL);
        setFlag(adminToken, A, FlagKeys.EXAMS, false);
        for (var path : java.util.List.of("/teacher/exams/" + EXAM + "/results", "/teacher/exams/" + EXAM + "/results.csv",
                "/teacher/exams/" + EXAM + "/results.xlsx", "/teacher/classes/" + CLASS_1A + "/exams",
                "/teacher/exams/" + EXAM + "/results/" + maya + ".pdf"))
            mvc.perform(as(get(path), sara)).andExpect(status().isNotFound());
        mvc.perform(as(post("/teacher/exams/" + EXAM + "/publish"), sara)).andExpect(status().isNotFound());
        setFlag(adminToken, A, FlagKeys.EXAMS, true);
    }

    @Test void the_class_page_lists_its_exams() throws Exception {
        publishedExam(-30, 30, ExamLevels.MANUAL);
        var list = json(mvc.perform(as(get("/teacher/classes/" + CLASS_1A + "/exams"), sara)).andExpect(status().isOk()).andReturn());
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("examId").asText()).isEqualTo(EXAM);
        assertThat(list.get(0).get("open").asBoolean()).isTrue();
    }

    // ---------------------------------------------------------------- the gradebook and the child's level (§7, §8)

    @Test void an_exam_is_a_gradebook_column_marked_exam_and_counts_double_in_her_level() throws Exception {
        publishedExam(-120, 120, ExamLevels.MANUAL);
        sit(maya, true, true);                                                  // the exam: 100
        var homework = readyToPublish("ex-homework-3", A, section1a, LocalDate.now().minusDays(1), "homework");
        publish(homework.getId());
        playTheSample(maya, homework.getId());                                  // a homework: 50

        var book = json(mvc.perform(as(get("/teacher/classes/" + CLASS_1A + "/gradebook"), sara)).andExpect(status().isOk()).andReturn());
        assertThat(book.get("lessons")).anySatisfy(l -> {
            if (EXAM.equals(l.get("lessonId").asText())) assertThat(l.get("type").asText()).isEqualTo("exam");
        });

        var report = json(mvc.perform(as(get("/teacher/children/" + maya), sara)).andExpect(status().isOk()).andReturn());
        // newest first: the exam (100, ×2) then the homework (50, ×1); recency weights 2.0 and 1.0
        // → (100 × 2 × 2 + 50 × 1 × 1) / (2 × 2 + 1) = 450 / 5 = 90
        assertThat(report.get("levels").get(0).get("levelScore").asInt()).isEqualTo(90);
        assertThat(report.get("levels").get(0).get("band").asText()).isEqualTo("exceeding");
    }

    // ---------------------------------------------------------------- fixture

    /** The fixture exam: a published `type = exam` lesson over Level 1, with the window given in minutes from now. */
    private void publishedExam(long opensIn, long closesIn, String releaseMode) throws Exception {
        readyToPublish(EXAM, A, section1a, LocalDate.now(), "exam");
        exam(EXAM, A, ExamLevels.ONE, opensIn, closesIn, releaseMode);
        publish(EXAM);
    }

    private void publish(String lessonId) throws Exception {
        mvc.perform(as(post("/teacher/lessons/" + lessonId + "/publish"), sara)
                .contentType(MediaType.APPLICATION_JSON).content("{\"classIds\":[\"" + CLASS_1A + "\"]}"))
                .andExpect(status().isOk());
    }

    /** One child sitting the whole paper: the first stop right, the second right or wrong, then the retell. */
    private void sit(String childId, boolean first, boolean second) throws Exception {
        parentPost("/children/" + childId + "/attempts", batch(
                upload(childId + "-1", EXAM, stop(EXAM, 1), first, first ? 3 : 1),
                upload(childId + "-2", EXAM, stop(EXAM, 2), second, second ? 3 : 1),
                upload(childId + "-3", EXAM, stop(EXAM, 3), true, 3)));
    }

    private com.fasterxml.jackson.databind.JsonNode island(String childId, String lessonId) throws Exception {
        var today = LocalDate.now();
        var map = parentGet("/children/" + childId + "/map?from=" + today.minusDays(7) + "&to=" + today.plusDays(7) + "&today=" + today);
        for (var i : map.get("islands"))
            if ("lesson".equals(i.get("kind").asText()) && lessonId.equals(i.path("lessonId").asText(null))) return i;
        return null;
    }

    private static int bucket(com.fasterxml.jackson.databind.JsonNode results, String band) {
        for (var b : results.get("distribution")) if (band.equals(b.get("band").asText())) return b.get("children").asInt();
        throw new AssertionError("no distribution bucket for " + band);
    }

    private static com.fasterxml.jackson.databind.JsonNode child(com.fasterxml.jackson.databind.JsonNode results, String childId) {
        for (var c : results.get("children")) if (childId.equals(c.get("childId").asText())) return c;
        throw new AssertionError("no row for " + childId);
    }
}
