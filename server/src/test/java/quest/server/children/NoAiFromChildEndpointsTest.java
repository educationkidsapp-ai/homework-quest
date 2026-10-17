package quest.server.children;

import static org.assertj.core.api.Assertions.assertThat;
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
import quest.server.auth.AdminJwtService;
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
    /** The teacher this test makes: the seeded content's section has none of its own. */
    private static final String TEACHER = "noai-teacher";
    private static final String EXAM = "no-ai-exam-paper";

    /** The provider, replaced. Not a spy and not a partial mock: any call at all is the failure. */
    @MockitoBean LlmClient llm;

    @Autowired LessonRepository lessons;
    @Autowired quest.server.exams.ExamSettingsRepository examSettings;
    @Autowired quest.server.content.LessonStore store;
    @Autowired quest.server.hints.StopHintsRepository hints;
    @Autowired ChildRepository childRows;
    @Autowired quest.server.tenancy.TeachingAssignmentRepository assignments;
    @Autowired quest.server.config.Json codec;
    @Autowired AdminJwtService jwt;
    @Autowired quest.server.teacher.TeacherQuestionRepository questions;
    @Autowired quest.server.auth.UserRepository users;
    @Autowired quest.server.auth.TeacherRepository teachers;
    @Autowired quest.server.teacher.TeacherQuestionAnswerRepository questionAnswers;
    @Autowired quest.server.chat.ChatThreadRepository threadRows;
    @Autowired quest.server.chat.ChatMessageRepository messageRows;
    @Autowired quest.server.chat.ComplaintEventRepository complaintEvents;

    @AfterEach void clean() {
        examSettings.findById(EXAM).ifPresent(examSettings::delete);
        lessons.findById(EXAM).ifPresent(lessons::delete);
        // The question really was sent to the seeded section and the complaint really was created, so both come out
        // again: they are this test's rows in a database every other test reads.
        for (var question : questions.findAll().stream().filter(q -> "No-AI question".equals(q.getTitle())).toList()) {
            questionAnswers.deleteAll(questionAnswers.findByQuestionIdOrderByAnsweredAtAsc(question.getId()));
            questions.delete(question);
        }
        var mine = threadRows.findAll().stream().filter(t -> "The bus is late".equals(t.getTitle())).map(t -> t.getId()).toList();
        if (!mine.isEmpty()) { messageRows.deleteByThreads(mine); complaintEvents.deleteByThreads(mine); threadRows.deleteAllById(mine); }
        assignments.findAll().stream().filter(a -> TEACHER.equals(a.getTeacherId())).forEach(assignments::delete);
        teachers.findById(TEACHER).ifPresent(teachers::delete);
        users.findById(TEACHER).ifPresent(users::delete);
    }

    @Test void no_child_facing_route_touches_the_model() throws Exception {
        String token = adminToken();
        var seed = lessons.findById(HOMEWORK).orElseThrow();
        setFlag(token, seed.getSchoolId(), FlagKeys.SMART_HINTS, true);
        setFlag(token, seed.getSchoolId(), FlagKeys.TEACHER_QUESTIONS, true);
        setFlag(token, seed.getSchoolId(), FlagKeys.CHAT, true);
        setFlag(token, seed.getSchoolId(), FlagKeys.ANNOUNCEMENTS, true);
        // D30 turned every switch on, feature 27's triage with it — and that one *does* call the model, after the
        // request has committed and on a task thread. It is off here so `verifyNoInteractions` below is a fact about
        // the parent's own request rather than a race with that thread; see the complaint note at the end.
        setFlag(token, seed.getSchoolId(), FlagKeys.COMPLAINT_TRIAGE, false);
        examPaper(seed);

        // her children, and a new one — on the seeded lesson's own section, so every read below is a real read and
        // not an early 404: her map holds the lesson, her class has teachers, and her answers are scored.
        parentGet("/children");
        String child = parentPost("/children", "{\"name\":\"Noor\",\"avatarColor\":\"sky\",\"curriculum\":\"british\",\"grade\":1}").get("id").asText();
        var row = childRows.findById(child).orElseThrow();
        row.setClassId(seed.getClassId());
        childRows.save(row);

        // the map (review islands, practice lookup and the teacher islands all run here)
        parentGet("/children/" + child + "/map?from=2026-09-07&to=2026-09-21&today=2026-09-14");

        // the lesson itself — the body that carries the approved hints — and then the exam paper, which is open now
        var lesson = parentGet("/lessons/" + HOMEWORK);
        mvc.perform(get("/lessons/" + EXAM + "?childId=" + child).header("Authorization", PARENT))
                .andExpect(status().isOk());

        // her answers: a homework's, then an exam's. Both asserted, because a refused upload is an exam path that
        // never ran — `ExamAttemptService.paper`/`settle` is exactly what this test is here to watch.
        var uploads = new ArrayList<String>();
        int i = 0;
        for (JsonNode stop : lesson.get("plays").get(0).get("stops")) uploads.add(attempt("noai-" + i++, stop.get("id").asText(), HOMEWORK));
        parentPost("/children/" + child + "/attempts", "[" + String.join(",", uploads) + "]");
        var examUploads = new ArrayList<String>();
        for (JsonNode stop : lesson.get("plays").get(0).get("stops")) examUploads.add(attempt("noai-exam-" + i++, stop.get("id").asText(), EXAM));
        parentPost("/children/" + child + "/attempts", "[" + String.join(",", examUploads) + "]");

        // the recording she uploads and reads back, and a page crop of a page that is not there
        var media = json(mvc.perform(media("/children/" + child + "/stops/hs1-retell/media")).andExpect(status().isOk()).andReturn());
        mvc.perform(get(media.get("url").asText().replace("http://localhost:8080", "")).header("Authorization", PARENT)).andExpect(status().isOk());
        mvc.perform(get("/media/pages/no-such-page").header("Authorization", PARENT)).andExpect(status().isNotFound());

        // her progress, her teacher's question island — a real question, sent to her real class — and her feeds
        parentGet("/children/" + child + "/progress");
        String question = teacherQuestion(seed);
        parentGet("/children/" + child + "/teacher-questions/" + question);
        parentPost("/children/" + child + "/teacher-questions/" + question + "/answers",
                "[{\"stopId\":\"noai-q-s1\",\"answerJson\":\"{}\",\"correct\":true,\"stars\":3,\"answeredAt\":1789300000000}]");
        parentGet("/children/" + child + "/announcements");

        // Feature 27's complaint: the one `/children` route that reaches a model at all, and only *after* the
        // request has committed, on a task thread (`ChildEndpointsHaveNoAiTest.PARENT_ONLY` names it, and
        // `ComplaintTriageApiTest` is where the triage itself is tested). What is asserted here is the half this
        // test is about: the parent's own request — the recipients it offers her and the complaint it creates —
        // holds no model call of its own. The triage switch is off, so the assertion below is a fact and not a
        // race with a task thread (it is switched off at the top of this test, since D30 turned it on by default);
        // the synchronous path is the same either way, since the flag is read by the listener and not by the request.
        teacherOfTheSection(seed);
        var recipients = parentGet("/children/" + child + "/complaints/recipients");
        assertThat(recipients.size()).as("her class has staff to complain to, so the POST below is a real one").isPositive();
        parentPost("/children/" + child + "/complaints",
                "{\"staffId\":\"" + recipients.get(0).get("staffId").asText() + "\",\"title\":\"The bus is late\","
                        + "\"body\":\"She waits alone every morning.\"}");

        verifyNoInteractions(llm);
    }

    /**
     * A teacher of the seeded lesson's own section: the author of the question below and the member of staff the
     * complaint is addressed to. The sample content seed publishes into a section nobody teaches, so she is made
     * here rather than looked up — and taken out again in {@link #clean}.
     */
    private String teacherOfTheSection(LessonEntity seed) {
        var user = users.findById(TEACHER).orElseGet(() -> {
            var fresh = new quest.server.auth.Entities.UserEntity();
            fresh.setId(TEACHER); fresh.setSchoolId(seed.getSchoolId()); fresh.setEmail("noai-teacher@seed.test");
            fresh.setPasswordHash("x"); fresh.setRole("TEACHER"); fresh.setStatus("active"); fresh.setDisplayName("Ms No-AI");
            fresh.setCreatedAt(Instant.now()); fresh.setUpdatedAt(Instant.now());
            return users.save(fresh);
        });
        if (teachers.findById(TEACHER).isEmpty()) {
            var profile = new quest.server.auth.Entities.TeacherEntity();
            profile.setUserId(TEACHER); profile.setSubjectsJson("[\"english\"]"); profile.setCurriculum("british");
            profile.setGradesJson("[1]"); profile.setUpdatedAt(Instant.now());
            teachers.save(profile);
        }
        if (assignments.findByClassIdAndSubject(seed.getClassId(), seed.getSubject()).isEmpty()) {
            var a = new quest.server.tenancy.Entities.TeachingAssignmentEntity();
            a.setId("noai-ta-" + seed.getClassId()); a.setSchoolId(seed.getSchoolId()); a.setTeacherId(TEACHER);
            a.setClassId(seed.getClassId()); a.setSubject(seed.getSubject()); a.setCreatedAt(Instant.now());
            assignments.save(a);
        }
        return user.getId();
    }

    /** One question of the child's own class, written and sent by the teacher who teaches it. */
    private String teacherQuestion(LessonEntity seed) throws Exception {
        String teacher = jwt.issue(teacherOfTheSection(seed), "noai-teacher@seed.test", "TEACHER", seed.getSchoolId()).token();
        String stops = codec.encodeShared(java.util.List.<quest.api.dto.Stop>of(new quest.api.dto.Stop.Choice(
                        "noai-q-s1", "noai-q-s1", "Which one?", new quest.api.dto.Ingredient("C", "carrot"),
                        new quest.api.dto.Bilingual("Count together.", "Count together."), "Count first.", "Which is bigger?",
                        java.util.List.of(new quest.api.dto.Tile("a", "A", null, null), new quest.api.dto.Tile("b", "B", null, null)),
                        "a", null, null)),
                kotlinx.serialization.builtins.BuiltinSerializersKt.ListSerializer(quest.api.dto.Stop.Companion.serializer()));
        var created = json(mvc.perform(post("/teacher/questions").header("Authorization", "Bearer " + teacher)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"No-AI question\",\"stops\":" + stops + ",\"classIds\":[\"" + seed.getClassId() + "\"],"
                                + "\"from\":\"" + java.time.LocalDate.now().minusDays(1) + "\",\"to\":\"" + java.time.LocalDate.now().plusDays(7) + "\"}"))
                .andExpect(status().isCreated()).andReturn());
        String id = created.get("id").asText();
        mvc.perform(post("/teacher/questions/" + id + "/send").header("Authorization", "Bearer " + teacher))
                .andExpect(status().isOk());
        return id;
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
        // …and the panel, without which `LessonStore.assemble` has nothing to assemble and the paper is a 404 — which
        // is how the exam half of this test used to pass without ever reaching `ExamAttemptService`.
        store.savePanel(EXAM, java.util.Objects.requireNonNull(store.assemble(seed)).getParentPanel());
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
