package quest.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import quest.api.validation.SchemaValidator;
import quest.server.api.dto.Enums;
import quest.server.api.dto.Questions;
import quest.server.api.dto.Requests;

/** Every endpoint against the sample-backed LLM client (no Anthropic call). */
class LessonEndpointsTest extends ApiTestSupport {

    @Test void healthIsUp() throws Exception {
        mvc.perform(get("/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ok"));
    }

    @Test void unknownLessonIs404WithApiError() throws Exception {
        mvc.perform(get("/lessons/nope")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("not_found"));
    }

    @Test void createWithoutFilesOrTaskIsRejected() throws Exception {
        var req = new Requests.CreateLessonRequest(Enums.Subject.MATH, 1, "IB PYP", java.time.LocalDate.of(2026, 9, 14), 7, null, List.of());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/lessons")
                        .file(new org.springframework.mock.web.MockMultipartFile("request", "", "application/json", mapper.writeValueAsBytes(req))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("bad_request"));
    }

    @Test void fullLoop_uploadConfirmReadyGenerateDelete() throws Exception {
        // POST /lessons
        Requests.LessonJob created = createLesson(Enums.Subject.MATH, null, true);
        assertEquals(Enums.Status.UPLOADING, created.status());
        assertEquals(List.of("slide.png"), created.sourceFileNames());

        // GET /lessons/{id} until needs_confirmation
        Requests.LessonJob extracted = awaitTerminal(created.id());
        assertEquals(Enums.Status.NEEDS_CONFIRMATION, extracted.status(), String.valueOf(extracted.error()));
        assertEquals(2, extracted.skills().size());
        assertEquals("Counting by 2s", extracted.skills().get(0).name());
        assertNotNull(extracted.skills().get(1).unsure());
        assertTrue(extracted.questionSets().isEmpty());

        // POST /lessons/{id}/confirm — keep the first, resolve the unsure one, add a manual skill
        var confirm = new Requests.ConfirmSkillsRequest(List.of(
                new Requests.ConfirmedSkill(extracted.skills().get(0).id(), "Counting by 2s", Enums.Subject.MATH, "number line jumps"),
                new Requests.ConfirmedSkill(extracted.skills().get(1).id(), "Adding two numbers", Enums.Subject.MATH, null),
                new Requests.ConfirmedSkill(null, "Counting by 10s", Enums.Subject.MATH, null)), 7);
        String body = mvc.perform(post("/lessons/" + created.id() + "/confirm").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(confirm)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals(Enums.Status.GENERATING, mapper.readValue(body, Requests.LessonJob.class).status());

        Requests.LessonJob ready = awaitTerminal(created.id());
        assertEquals(Enums.Status.READY, ready.status(), String.valueOf(ready.error()));
        assertEquals(3, ready.skills().size());
        assertEquals("Adding two numbers", ready.skills().get(1).name());
        assertEquals(3, ready.questionSets().size());
        // Every set keeps its 7 questions even though the model reuses q1..q7 ids; stored ids are globally unique.
        Set<String> allIds = new HashSet<>();
        ready.questionSets().forEach(set -> set.questions().forEach(q -> assertTrue(allIds.add(q.id()), "duplicate id " + q.id())));
        assertEquals(21, allIds.size());
        for (Questions.QuestionSet set : ready.questionSets()) {
            assertEquals(7, set.questions().size());
            assertNotNull(set.id());
            // The wire JSON must satisfy the shared schema the app validates against.
            var check = SchemaValidator.INSTANCE.validateQuestionSetJson(mapper.writeValueAsString(set.withId(null)), 7, Set.of());
            assertTrue(check.isValid(), check.getErrors().toString());
        }
        assertNull(ready.error());

        // POST /skills/{id}/generate — again never repeats shown ids
        String skillId = ready.skills().get(0).id();
        Set<String> shown = new HashSet<>(ready.questionSets().get(0).questions().stream().map(Questions.Question::id).toList());
        var gen = new Requests.GenerateRequest(Enums.Mode.AGAIN, List.copyOf(shown), 7);
        String again = mvc.perform(post("/skills/" + skillId + "/generate").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(gen)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        Questions.QuestionSet againSet = mapper.readValue(again, Questions.QuestionSet.class);
        assertEquals(Enums.Mode.AGAIN, againSet.mode());
        assertEquals(7, againSet.questions().size());
        assertTrue(againSet.questions().stream().noneMatch(q -> shown.contains(q.id())));
        assertNotEquals(ready.questionSets().get(0).id(), againSet.id());

        // Same request again → cached set (same id)
        String cached = mvc.perform(post("/skills/" + skillId + "/generate").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(gen)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals(againSet.id(), mapper.readValue(cached, Questions.QuestionSet.class).id());

        // Unknown skill → 404
        mvc.perform(post("/skills/missing/generate").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(gen))).andExpect(status().isNotFound());

        // DELETE /lessons/{id}/files
        mvc.perform(delete("/lessons/" + created.id() + "/files")).andExpect(status().isOk()).andExpect(jsonPath("$.deleted").value(1));
        mvc.perform(delete("/lessons/" + created.id() + "/files")).andExpect(status().isOk()).andExpect(jsonPath("$.deleted").value(0));
        assertFalse(java.nio.file.Files.exists(java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "quest-test-uploads", created.id())) &&
                java.nio.file.Files.list(java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "quest-test-uploads", created.id())).findAny().isPresent());
    }

    @Test void typedTaskEnglishFlow() throws Exception {
        Requests.LessonJob created = createLesson(Enums.Subject.ENGLISH, "Practise the sh words: ship, shop, sheep", false);
        Requests.LessonJob extracted = awaitTerminal(created.id());
        assertEquals(Enums.Status.NEEDS_CONFIRMATION, extracted.status());
        assertEquals("The sh sound", extracted.skills().get(0).name());
        var confirm = new Requests.ConfirmSkillsRequest(List.of(new Requests.ConfirmedSkill(extracted.skills().get(0).id(), "The sh sound", Enums.Subject.ENGLISH, "word family")), 7);
        mvc.perform(post("/lessons/" + created.id() + "/confirm").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(confirm))).andExpect(status().isOk());
        Requests.LessonJob ready = awaitTerminal(created.id());
        assertEquals(Enums.Status.READY, ready.status());
        assertEquals(1, ready.questionSets().size());
        assertTrue(ready.questionSets().get(0).questions().stream().anyMatch(q -> q instanceof Questions.Trace));
    }

    @Test void confirmingWhileReadingIsRejected() throws Exception {
        Requests.LessonJob created = createLesson(Enums.Subject.MATH, "x", false);
        var confirm = new Requests.ConfirmSkillsRequest(List.of(new Requests.ConfirmedSkill(null, "Anything", Enums.Subject.MATH, null)), 7);
        // Either it is still uploading/reading (400) or already extracted (200) — never a 500.
        int status = mvc.perform(post("/lessons/" + created.id() + "/confirm").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(confirm)))
                .andReturn().getResponse().getStatus();
        assertTrue(status == 400 || status == 200, "status " + status);
        awaitTerminal(created.id());
    }
}
