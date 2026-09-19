package quest.server.teacher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import quest.server.analysis.LlmClient;
import quest.server.analysis.SampleLlmClient;
import quest.server.content.StopText;

/**
 * CR5: the teacher saves a stop in her own words and never meets the JSON.
 *
 * <p>The {@link LlmClient} is stubbed rather than taken from the context because both halves of the contract need
 * it: the happy path delegates to the real {@link SampleLlmClient} (deterministic, which is what makes the e2e
 * possible), and the "please rephrase" path needs a model that answers wrongly twice — the one thing the sample
 * client, by design, never does.
 */
class StopFromTextTest extends TeacherTestSupport {
    private static final String SCHOOL = "sft-school", TEACHER = "sft-teacher", OTHER = "sft-other";
    private static final String HERS = "sft-class-1a", HERS_NOT = "sft-class-1c";
    private static final String LESSON = "sft-lesson";

    @Override String prefix() { return "sft-"; }

    @MockitoBean LlmClient llm;

    private final SampleLlmClient sample = new SampleLlmClient();
    private String teacherToken, otherToken;
    private String stopId;

    @BeforeEach void seed() throws Exception {
        school(SCHOOL, "Prose Primary", "SFTSCH");
        teacher(TEACHER, SCHOOL, "sara@sft.test", "Ms Sara", "[\"math\"]", "british", "[1]");
        teacher(OTHER, SCHOOL, "noor@sft.test", "Ms Noor", "[\"math\"]", "british", "[1]");
        klass(HERS, SCHOOL, "british", 1, "math", TEACHER);
        klass(HERS_NOT, SCHOOL, "british", 1, "math", OTHER);
        var l = readyLesson(LESSON, SCHOOL, HERS, "british", 1, "math", LocalDate.now());
        // hand-written, so the fixture's one-stop levels are judged by the lenient rule — the same rule the raw-JSON
        // `PUT` this endpoint replaces is judged by, which is the point of comparing the two here
        l.setSource("manual"); lessons.save(l);
        stopId = prefixed(LESSON, 1, 0);
        teacherToken = token(TEACHER, "TEACHER", SCHOOL);
        otherToken = token(OTHER, "TEACHER", SCHOOL);
        when(llm.complete(any(), any(), any())).thenAnswer(i -> sample.complete(i.getArgument(0), i.getArgument(1), i.getArgument(2)));
    }

    @AfterEach void clean() {
        removeSeed();
        assignments.deleteAll(assignments.findAll().stream().filter(a -> a.getClassId().startsWith(prefix())).toList());
        classes.deleteAll(classes.findAll().stream().filter(k -> k.getId().startsWith(prefix())).toList());
    }

    private static final String TEXT = """
            Count the apples
            Pip says: How many apples can you count?
            Options:
            - three
            - four (correct)
            Hint: Point at each one as you say the number.""";

    // ---------------------------------------------------------------- reading

    /** Before she has written a word, the editor still shows English: the JSON that is stored, described. */
    @Test void a_stop_nobody_has_put_into_words_is_described_from_its_json() throws Exception {
        var stored = store.play(plays.findById(playId()).orElseThrow()).getStops().get(0);
        assertThat(levelOneStop(teacherToken).get("teacherText").asText())
                .startsWith("Pick one\nPip says: Which one is it?").isEqualTo(StopText.describe(stored));
        assertThat(stops.findById(stopId).orElseThrow().getText()).as("describing is a reading, not a write").isNull();
    }

    // ---------------------------------------------------------------- saving

    @Test void the_text_she_writes_becomes_the_json_and_is_stored_beside_it() throws Exception {
        long before = lessons.findById(LESSON).orElseThrow().getTokenUsage();

        JsonNode saved = fromText(teacherToken, stopId, TEXT, status().isOk());
        assertThat(saved.get("id").asText()).isEqualTo(stopId);
        assertThat(saved.get("type").asText()).isEqualTo("choice");
        assertThat(saved.get("title").asText()).isEqualTo("Count the apples");
        assertThat(saved.get("speak").asText()).isEqualTo("How many apples can you count?");
        assertThat(saved.get("teacherText").asText()).as("she gets her own words back, so re-opening never re-converts").isEqualTo(TEXT);

        var row = stops.findById(stopId).orElseThrow();
        assertThat(row.getText()).isEqualTo(TEXT);
        assertThat(row.getTextUpdatedAt()).isNotNull();
        assertThat(row.getContentJson()).as("`teacherText` is a reading of the stop, never part of it").doesNotContain("teacherText");
        assertThat(row.getTitle()).isEqualTo("Count the apples");
        assertThat(plays.findById(playId()).orElseThrow().getPlayJson()).doesNotContain("teacherText");

        assertThat(lessons.findById(LESSON).orElseThrow().getTokenUsage()).as("the save is charged to the lesson").isEqualTo(before + 1200);
        assertThat(levelOneStop(teacherToken).get("teacherText").asText()).isEqualTo(TEXT);
    }

    /** The owner's rule: two tries, then say so. The second turn is the one that carries the validator's errors. */
    @Test void a_text_the_schema_cannot_hold_is_retried_once_and_then_asks_her_to_rephrase() throws Exception {
        org.mockito.Mockito.doReturn(new LlmClient.Result("{\"nonsense\":true}", 20, 10)).when(llm).complete(any(), any(), any());
        long before = lessons.findById(LESSON).orElseThrow().getTokenUsage();
        String stored = stops.findById(stopId).orElseThrow().getContentJson();

        JsonNode error = fromText(teacherToken, stopId, TEXT, status().isUnprocessableEntity());
        assertThat(error.get("code").asText()).isEqualTo("rephrase");
        assertThat(error.get("message").asText()).as("the debug panel needs the validator's own lines").startsWith("Couldn't save, please rephrase.").hasSizeGreaterThan(40);

        verify(llm, times(2)).complete(any(), any(), any());
        assertThat(stops.findById(stopId).orElseThrow().getContentJson()).as("a refused save changes nothing").isEqualTo(stored);
        assertThat(stops.findById(stopId).orElseThrow().getText()).isNull();
        assertThat(lessons.findById(LESSON).orElseThrow().getTokenUsage()).as("the two turns were paid for either way").isEqualTo(before + 60);
    }

    @Test void another_teachers_stop_is_refused_and_a_stop_that_does_not_exist_is_silence() throws Exception {
        fromText(otherToken, stopId, TEXT, status().isForbidden());
        fromText(teacherToken, "sft-no-such-stop", TEXT, status().isNotFound());
        assertThat(stops.findById(stopId).orElseThrow().getText()).isNull();
    }

    /**
     * The raw-JSON save is still there for the admin panel, and it clears the prose: a sentence about the JSON that
     * was there would be a lie about the JSON that is there now, so the next read describes the new content instead.
     */
    @Test void a_raw_json_save_drops_the_prose_and_the_next_read_describes_what_is_stored() throws Exception {
        fromText(teacherToken, stopId, TEXT, status().isOk());
        assertThat(stops.findById(stopId).orElseThrow().getText()).isNotNull();

        JsonNode stop = levelOneStop(teacherToken);
        var raw = (com.fasterxml.jackson.databind.node.ObjectNode) stop.deepCopy();
        raw.remove("teacherText"); raw.put("title", "Hand-edited JSON");
        mvc.perform(as(put("/teacher/stops/" + stopId).contentType(MediaType.APPLICATION_JSON).content(raw.toString()), teacherToken))
                .andExpect(status().isOk());

        assertThat(stops.findById(stopId).orElseThrow().getText()).as("the prose no longer describes the stop").isNull();
        assertThat(levelOneStop(teacherToken).get("teacherText").asText()).startsWith("Hand-edited JSON\n");
    }

    // ---------------------------------------------------------------- helpers

    private JsonNode fromText(String token, String stop, String text, org.springframework.test.web.servlet.ResultMatcher expected) throws Exception {
        String body = mapper.createObjectNode().put("text", text).toString();
        return json(mvc.perform(as(post("/teacher/stops/" + stop + "/from-text").contentType(MediaType.APPLICATION_JSON).content(body), token))
                .andExpect(expected).andReturn());
    }

    /** The one stop of Level 1 as the editor receives it — through the real read path, `teacherText` and all. */
    private JsonNode levelOneStop(String token) throws Exception {
        var lesson = json(mvc.perform(as(get("/teacher/lessons/" + LESSON), token)).andExpect(status().isOk()).andReturn());
        for (JsonNode play : lesson.get("plays"))
            if (play.get("level").asInt() == 1 && play.get("variant").asInt() == 0) return play.get("play").get("stops").get(0);
        throw new AssertionError("Level 1 is missing from the lesson");
    }

    private String playId() {
        return plays.findByLessonIdOrderByLevelAscVariantAsc(LESSON).stream()
                .filter(p -> p.getLevel() == 1 && p.getVariant() == 0).findFirst().orElseThrow().getId();
    }
}
