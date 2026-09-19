package quest.server.grading;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import quest.server.flags.FlagKeys;

/**
 * `docs/teacher-flow.md` step 9 end to end: the Results page, marking, release, the gradebook and the child page,
 * and the two rules a teacher's school is actually promised — another teacher's class is a 403, and a parent sees a
 * score only after the release.
 */
class GradingApiTest extends GradingTestSupport {
    private static final String A = "gr-school-a", B = "gr-school-b";
    private static final String SARA = "gr-teacher-sara", NOOR = "gr-teacher-noor", OTHER = "gr-teacher-other";
    private static final String CLASS_1A = "gr-1a", CLASS_1B = "gr-1b", CLASS_OTHER = "gr-other";
    private static final String LESSON = "gr-lesson-1";

    @Override String prefix() { return "gr-"; }

    private String adminToken, sara, noor, other, maya, omar;
    private quest.server.tenancy.Entities.ClassEntity section1a;

    @BeforeEach void seed() throws Exception {
        school(A, "Grading Academy", "GRSCHA");
        school(B, "Other Academy", "GRSCHB");
        teacher(SARA, A, "Ms Sara"); teacher(NOOR, A, "Ms Noor"); teacher(OTHER, B, "Ms Other");
        section1a = klass(CLASS_1A, A, SARA, "1A");
        klass(CLASS_1B, A, NOOR, "1B");
        klass(CLASS_OTHER, B, OTHER, "1A");
        lesson(LESSON, A, section1a, LocalDate.now().minusDays(2));

        adminToken = adminToken();
        enableGrading(adminToken, A);
        enableGrading(adminToken, B);
        sara = token(SARA, "TEACHER", A); noor = token(NOOR, "TEACHER", A); other = token(OTHER, "TEACHER", B);

        maya = child("Maya", "GRSCHA", section1a);
        omar = child("Omar", "GRSCHA", section1a);
        playTheSample(maya, LESSON);
    }

    @AfterEach void clean() { removeSeed(); }

    // ---------------------------------------------------------------- results (§7)

    @Test void the_results_page_carries_the_score_the_scorer_computed() throws Exception {
        var results = json(mvc.perform(as(get("/teacher/lessons/" + LESSON + "/results"), sara)).andExpect(status().isOk()).andReturn());

        assertThat(results.get("played").asInt()).isEqualTo(1);
        assertThat(results.get("released").asBoolean()).isFalse();
        assertThat(results.get("needsMarking").asInt()).as("the retell is waiting for her").isEqualTo(1);
        assertThat(results.get("stops")).hasSize(3);

        var mine = child(results, maya);
        assertThat(mine.get("attempted").asBoolean()).isTrue();
        assertThat(mine.get("autoScore").asInt()).as("s1 right first try, s2 wrong first try: (100 + 0) / 2").isEqualTo(50);
        assertThat(mine.get("band").asText()).isEqualTo(Bands.DEVELOPING);
        assertThat(mine.get("completion").asInt()).isEqualTo(100);
        assertThat(mine.get("needsMarking").asInt()).isEqualTo(1);

        var notPlayed = child(results, omar);
        assertThat(notPlayed.get("attempted").asBoolean()).isFalse();
        assertThat(notPlayed.get("score").isNull()).as("a child who did not play has no score, not a zero").isTrue();
    }

    @Test void another_teachers_lesson_is_a_403_and_another_schools_is_a_404() throws Exception {
        mvc.perform(as(get("/teacher/lessons/" + LESSON + "/results"), noor)).andExpect(status().isForbidden());
        mvc.perform(as(get("/teacher/lessons/" + LESSON + "/results"), other)).andExpect(status().isNotFound());
        mvc.perform(as(get("/teacher/classes/" + CLASS_1A + "/gradebook"), other)).andExpect(status().isNotFound());
        mvc.perform(as(get("/teacher/children/" + maya), noor)).andExpect(status().isForbidden());
    }

    @Test void the_routes_are_404_while_the_gradebook_flag_is_off() throws Exception {
        setFlag(adminToken, A, FlagKeys.GRADEBOOK, false);
        try {
            mvc.perform(as(get("/teacher/lessons/" + LESSON + "/results"), sara)).andExpect(status().isNotFound());
            mvc.perform(as(get("/teacher/classes/" + CLASS_1A + "/gradebook"), sara)).andExpect(status().isNotFound());
        } finally { setFlag(adminToken, A, FlagKeys.GRADEBOOK, true); }
    }

    // ---------------------------------------------------------------- marking (§7)

    @Test void marking_the_retell_moves_the_score_and_clears_the_badge() throws Exception {
        mark(sara, maya, 3, 2, "Lovely retelling.").andExpect(status().isOk());

        var results = json(mvc.perform(as(get("/teacher/lessons/" + LESSON + "/results"), sara)).andReturn());
        assertThat(results.get("needsMarking").asInt()).isZero();
        assertThat(child(results, maya).get("autoScore").asInt()).as("the marked retell joins in: (100 + 0 + 70) / 3").isEqualTo(57);
        assertThat(child(results, maya).get("band").asText()).isEqualTo(Bands.DEVELOPING);
    }

    @Test void a_lesson_score_override_keeps_the_automatic_one_visible() throws Exception {
        mvc.perform(as(put("/teacher/marks").contentType(MediaType.APPLICATION_JSON)
                .content("{\"marks\":[{\"childId\":\"" + maya + "\",\"lessonId\":\"" + LESSON
                        + "\",\"score\":80,\"comment\":\"A real effort.\"}]}"), sara)).andExpect(status().isOk());

        var mine = child(json(mvc.perform(as(get("/teacher/lessons/" + LESSON + "/results"), sara)).andReturn()), maya);
        assertThat(mine.get("autoScore").asInt()).isEqualTo(50);
        assertThat(mine.get("teacherScore").asInt()).isEqualTo(80);
        assertThat(mine.get("score").asInt()).isEqualTo(80);
        assertThat(mine.get("comment").asText()).isEqualTo("A real effort.");
    }

    @Test void a_mark_with_nothing_in_it_takes_the_mark_back() throws Exception {
        mark(sara, maya, 3, 2, "Lovely.").andExpect(status().isOk());
        mark(sara, maya, 3, null, null).andExpect(status().isOk());

        var results = json(mvc.perform(as(get("/teacher/lessons/" + LESSON + "/results"), sara)).andReturn());
        assertThat(results.get("needsMarking").asInt()).as("the star is gone, so it is waiting again").isEqualTo(1);
    }

    @Test void marking_another_teachers_lesson_is_a_403_whatever_the_body_says() throws Exception {
        mark(noor, maya, 3, 2, "Not mine to mark.").andExpect(status().isForbidden());
        assertThat(markRows.findByLessonId(LESSON)).as("nothing is written before every lesson is checked").isEmpty();
    }

    @Test void marking_is_404_while_the_open_stop_marking_flag_is_off() throws Exception {
        setFlag(adminToken, A, FlagKeys.OPEN_STOP_MARKING, false);
        try {
            mark(sara, maya, 3, 2, "Lovely.").andExpect(status().isNotFound());
        } finally { setFlag(adminToken, A, FlagKeys.OPEN_STOP_MARKING, true); }
    }

    // ---------------------------------------------------------------- release (§7)

    @Test void a_released_lesson_refuses_a_new_mark_until_the_release_is_withdrawn() throws Exception {
        release(sara, true).andExpect(status().isOk());

        mark(sara, maya, 3, 2, "Too late.").andExpect(status().isConflict());

        release(sara, false).andExpect(status().isOk());
        mark(sara, maya, 3, 2, "Now it lands.").andExpect(status().isOk());
    }

    @Test void the_release_covers_the_whole_section_at_once() throws Exception {
        var body = json(release(sara, true).andExpect(status().isOk()).andReturn());

        assertThat(body.get("released").asBoolean()).isTrue();
        assertThat(body.get("releasedAt").asLong()).isPositive();
        assertThat(body.get("children").asInt()).as("§7's toggle is per lesson, and a lesson copy is one section's").isEqualTo(2);
    }

    /** §7: the parent sees the score and the comment only after release. The child never sees a number at all. */
    @Test void a_parent_sees_nothing_until_the_teacher_releases() throws Exception {
        mark(sara, maya, 3, 2, "Lovely retelling.").andExpect(status().isOk());
        mvc.perform(as(put("/teacher/marks").contentType(MediaType.APPLICATION_JSON)
                .content("{\"marks\":[{\"childId\":\"" + maya + "\",\"lessonId\":\"" + LESSON
                        + "\",\"comment\":\"She is getting there.\"}]}"), sara)).andExpect(status().isOk());

        assertThat(parentGet("/children/" + maya + "/progress").get("results"))
                .as("nothing reaches the parent before the release").isNullOrEmpty();

        release(sara, true).andExpect(status().isOk());

        var results = parentGet("/children/" + maya + "/progress").get("results");
        assertThat(results).hasSize(1);
        assertThat(results.get(0).get("lessonId").asText()).isEqualTo(LESSON);
        assertThat(results.get(0).get("score").asInt()).isEqualTo(57);
        assertThat(results.get(0).get("band").asText()).isEqualTo(Bands.DEVELOPING);
        assertThat(results.get(0).get("comment").asText()).isEqualTo("She is getting there.");

        release(sara, false).andExpect(status().isOk());
        assertThat(parentGet("/children/" + maya + "/progress").get("results"))
                .as("withdrawing the release takes it back off the parent's report").isNullOrEmpty();
    }

    @Test void an_unpublished_lesson_cannot_be_released() throws Exception {
        var draft = lesson("gr-lesson-draft", A, section1a, LocalDate.now().minusDays(1));
        draft.setStatus("draft"); draft.setPublishedAt(null); lessons.save(draft);

        mvc.perform(as(post("/teacher/lessons/gr-lesson-draft/release").contentType(MediaType.APPLICATION_JSON)
                .content("{\"released\":true}"), sara)).andExpect(status().isConflict());
    }

    // ---------------------------------------------------------------- gradebook and the child page (§7)

    @Test void the_gradebook_is_children_by_lessons_with_a_cell_each() throws Exception {
        var book = json(mvc.perform(as(get("/teacher/classes/" + CLASS_1A + "/gradebook"), sara)).andExpect(status().isOk()).andReturn());

        assertThat(book.get("className").asText()).isEqualTo("1A");
        assertThat(book.get("lessons")).hasSize(1);
        assertThat(book.get("children")).hasSize(2);
        assertThat(book.get("needsMarking").asInt()).isEqualTo(1);

        var row = row(book, maya);
        assertThat(row.get("cells")).as("one cell per lesson column, always").hasSize(book.get("lessons").size());
        assertThat(row.get("cells").get(0).get("score").asInt()).isEqualTo(50);
        assertThat(row.get("cells").get(0).get("needsMarking").asBoolean()).isTrue();
        assertThat(row.get("average").asInt()).isEqualTo(50);
        assertThat(row.get("band").asText()).isEqualTo(Bands.DEVELOPING);
        assertThat(book.get("lessons").get(0).get("classAverage").asInt()).as("over the children who have a score").isEqualTo(50);

        var empty = row(book, omar);
        assertThat(empty.get("cells").get(0).get("attempted").asBoolean()).isFalse();
        assertThat(empty.get("average").isNull()).isTrue();
    }

    @Test void a_window_the_lesson_falls_outside_of_leaves_an_empty_grid() throws Exception {
        var book = json(mvc.perform(as(get("/teacher/classes/" + CLASS_1A + "/gradebook?from="
                + LocalDate.now().plusDays(1) + "&to=" + LocalDate.now().plusDays(7)), sara)).andExpect(status().isOk()).andReturn());

        assertThat(book.get("lessons")).isEmpty();
        assertThat(book.get("children")).hasSize(2);
        assertThat(book.get("children").get(0).get("cells")).isEmpty();
    }

    @Test void the_child_page_carries_the_band_the_trend_the_comments_and_the_saved_work() throws Exception {
        mark(sara, maya, 3, 2, "Lovely retelling.").andExpect(status().isOk());

        var page = json(mvc.perform(as(get("/teacher/children/" + maya), sara)).andExpect(status().isOk()).andReturn());

        assertThat(page.get("name").asText()).isEqualTo("Maya");
        assertThat(page.get("className").asText()).isEqualTo("1A");
        assertThat(page.get("levels")).hasSize(1);
        assertThat(page.get("levels").get(0).get("subject").asText()).isEqualTo("math");
        assertThat(page.get("levels").get(0).get("band").asText()).isEqualTo(Bands.DEVELOPING);
        assertThat(page.get("trend")).hasSize(1);
        assertThat(page.get("trend").get(0).get("score").asInt()).isEqualTo(57);
        assertThat(page.get("comments")).hasSize(1);
        assertThat(page.get("comments").get(0).get("comment").asText()).isEqualTo("Lovely retelling.");
    }

    /** The Admin reaches the same routes with `X-School-Id`; there is no second set of `/admin/**` aliases. */
    @Test void an_admin_reads_the_results_of_the_school_she_picked() throws Exception {
        mvc.perform(get("/teacher/lessons/" + LESSON + "/results")
                        .header("Authorization", "Bearer " + adminToken)
                        .header(quest.server.tenancy.TenantContext.HEADER, A))
                .andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- helpers

    private org.springframework.test.web.servlet.ResultActions mark(String token, String childId, int stop,
                                                                    Integer stars, String comment) throws Exception {
        String body = "{\"marks\":[{\"childId\":\"" + childId + "\",\"lessonId\":\"" + LESSON + "\",\"stopId\":\""
                + stopId(LESSON, stop) + "\"" + (stars == null ? "" : ",\"stars\":" + stars)
                + (comment == null ? "" : ",\"comment\":\"" + comment + "\"") + "}]}";
        return mvc.perform(as(put("/teacher/marks").contentType(MediaType.APPLICATION_JSON).content(body), token));
    }

    private org.springframework.test.web.servlet.ResultActions release(String token, boolean released) throws Exception {
        return mvc.perform(as(post("/teacher/lessons/" + LESSON + "/release").contentType(MediaType.APPLICATION_JSON)
                .content("{\"released\":" + released + "}"), token));
    }

    private static com.fasterxml.jackson.databind.JsonNode child(com.fasterxml.jackson.databind.JsonNode results, String childId) {
        for (var node : results.get("children")) if (childId.equals(node.get("childId").asText())) return node;
        throw new AssertionError(childId + " is not on the results page");
    }

    private static com.fasterxml.jackson.databind.JsonNode row(com.fasterxml.jackson.databind.JsonNode book, String childId) {
        for (var node : book.get("children")) if (childId.equals(node.get("childId").asText())) return node;
        throw new AssertionError(childId + " is not in the gradebook");
    }
}
