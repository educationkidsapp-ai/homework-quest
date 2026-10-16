package quest.server.children;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import quest.server.ApiTestSupport;
import quest.server.analysis.LlmClient;
import quest.server.content.Entities.LessonEntity;
import quest.server.content.LessonRepository;
import quest.server.flags.FlagKeys;

/**
 * Feature 30, the owner's rule at run time: <strong>the provider is a mock, every child-facing route is called, and
 * the mock is never touched.</strong>
 *
 * <p>{@link quest.server.ai.ChildEndpointsHaveNoAiTest} asserts the same thing over the call graph. This one is the
 * other half of the pair: it exercises the routes a child's and her parent's phone really call — her map, her lesson,
 * her answers (a homework's <em>and</em> an exam's), the recording she uploads, the media she reads back and the
 * teacher's question island — with `smartHints` on for her school, so every code path the feature added is live. A
 * single interaction with {@link LlmClient} fails it, whichever route caused it.
 *
 * <p>With the real provider replaced, nothing in this context can build a lesson — which is the point: everything
 * here reads content the seed already published, because that is all a child-facing route is ever allowed to do.
 */
class NoAiFromChildEndpointsTest extends ApiTestSupport {
    private static final String HOMEWORK = "lesson-hot-soup-1";
    private static final String EXAM = "no-ai-exam-paper";

    /** The provider, replaced. Not a spy and not a partial mock: any call at all is the failure. */
    @MockitoBean LlmClient llm;

    @Autowired LessonRepository lessons;
    @Autowired quest.server.exams.ExamSettingsRepository examSettings;
    @Autowired quest.server.content.LessonStore store;
    @Autowired quest.server.hints.StopHintsRepository hints;

    @AfterEach void clean() {
        examSettings.findById(EXAM).ifPresent(examSettings::delete);
        lessons.findById(EXAM).ifPresent(lessons::delete);
    }

    @Test void no_child_facing_route_touches_the_model() throws Exception {
        String token = adminToken();
        var seed = lessons.findById(HOMEWORK).orElseThrow();
        setFlag(token, seed.getSchoolId(), FlagKeys.SMART_HINTS, true);
        setFlag(token, seed.getSchoolId(), FlagKeys.TEACHER_QUESTIONS, true);
        examPaper(seed);

        // her children, and a new one
        parentGet("/children");
        String child = parentPost("/children", "{\"name\":\"Noor\",\"avatarColor\":\"sky\",\"curriculum\":\"british\",\"grade\":1}").get("id").asText();

        // the map (review islands, practice lookup and the teacher islands all run here)
        parentGet("/children/" + child + "/map?from=2026-09-07&to=2026-09-21&today=2026-09-14");

        // the lesson itself — the body that carries the approved hints — and then the exam paper
        var lesson = parentGet("/lessons/" + HOMEWORK);
        mvc.perform(get("/lessons/" + EXAM + "?childId=" + child).header("Authorization", PARENT)).andReturn();

        // her answers: a homework's, then an exam's
        var uploads = new ArrayList<String>();
        int i = 0;
        for (JsonNode stop : lesson.get("plays").get(0).get("stops")) uploads.add(attempt("noai-" + i++, stop.get("id").asText(), HOMEWORK));
        parentPost("/children/" + child + "/attempts", "[" + String.join(",", uploads) + "]");
        var examUploads = new ArrayList<String>();
        for (JsonNode stop : lesson.get("plays").get(0).get("stops")) examUploads.add(attempt("noai-exam-" + i++, stop.get("id").asText(), EXAM));
        mvc.perform(post("/children/" + child + "/attempts").header("Authorization", PARENT)
                .contentType(MediaType.APPLICATION_JSON).content("[" + String.join(",", examUploads) + "]")).andReturn();

        // the recording she uploads and reads back, and a page crop
        var media = json(mvc.perform(media("/children/" + child + "/stops/hs1-retell/media")).andExpect(status().isOk()).andReturn());
        mvc.perform(get(media.get("url").asText().replace("http://localhost:8080", "")).header("Authorization", PARENT)).andExpect(status().isOk());
        mvc.perform(get("/media/pages/no-such-page").header("Authorization", PARENT)).andReturn();

        // her progress, her teacher's question island, her announcements and her broadcasts
        parentGet("/children/" + child + "/progress");
        mvc.perform(get("/children/" + child + "/teacher-questions/no-such-question").header("Authorization", PARENT)).andReturn();
        mvc.perform(post("/children/" + child + "/teacher-questions/no-such-question/answers").header("Authorization", PARENT)
                .contentType(MediaType.APPLICATION_JSON).content("[]")).andReturn();
        mvc.perform(get("/children/" + child + "/announcements").header("Authorization", PARENT)).andReturn();

        verifyNoInteractions(llm);
    }

    /** A published exam over the seeded lesson's own plays, open right now — so the exam attempt path really runs. */
    private void examPaper(LessonEntity seed) {
        var paper = new LessonEntity();
        paper.setId(EXAM); paper.setCourseId(seed.getCourseId()); paper.setSubject(seed.getSubject()); paper.setDate(seed.getDate());
        paper.setStatus("published"); paper.setVersion(1); paper.setTitle("No-AI exam"); paper.setSourceHash("seed-" + EXAM);
        paper.setType("exam"); paper.setClassId(seed.getClassId()); paper.setSchoolId(seed.getSchoolId());
        paper.setCreatedBy("test"); paper.setCreatedAt(Instant.now()); paper.setUpdatedAt(Instant.now()); paper.setPublishedAt(Instant.now());
        lessons.save(paper);
        for (var play : store.plays(seed.getId())) store.savePlay(EXAM, store.play(play), quest.api.CacheKeys.PROMPT_B_VERSION, 0);
        var settings = new quest.server.exams.Entities.ExamSettingsEntity();
        settings.setLessonId(EXAM); settings.setSchoolId(seed.getSchoolId());
        settings.setOpensAt(Instant.now().minus(1, ChronoUnit.HOURS)); settings.setClosesAt(Instant.now().plus(1, ChronoUnit.HOURS));
        settings.setCreatedAt(Instant.now()); settings.setUpdatedAt(Instant.now());
        examSettings.save(settings);
    }

    private MockHttpServletRequestBuilder media(String path) {
        return multipart(path).file(new MockMultipartFile("file", "r.m4a", "audio/mp4", new byte[] {1, 2, 3}))
                .param("kind", "recording").header("Authorization", PARENT);
    }

    private void setFlag(String token, String schoolId, String key, boolean on) throws Exception {
        mvc.perform(as(put("/admin/schools/" + schoolId + "/flags/" + key), token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":" + on + "}")).andExpect(status().isOk());
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder builder, String token) {
        return builder.header("Authorization", "Bearer " + token);
    }

    private static String attempt(String id, String stopId, String lessonId) {
        return "{\"id\":\"" + id + "\",\"stopId\":\"" + stopId + "\",\"lessonId\":\"" + lessonId + "\",\"level\":1,\"answerJson\":\"{}\","
                + "\"correct\":true,\"attemptNumber\":1,\"mistakes\":0,\"stars\":3,\"answeredAt\":1789300000000}";
    }
}
