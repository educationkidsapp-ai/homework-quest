package quest.server.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import quest.server.ApiTestSupport;

/**
 * Errors never dead-end a lesson: the pipeline is a ledger of steps, a failure is retried where it happened,
 * and failed lessons can be deleted without losing the AI caches.
 */
@TestPropertySource(properties = { "quest.pipeline.fail-once-at=generate_L2", "quest.pipeline.retry-delay-ms=1" })
class LessonRecoveryTest extends ApiTestSupport {
    @Value("${quest.storage.local-dir}") String storageDir;

    @Test void level2_fails_once_then_retry_and_continue_finishes_without_paying_for_level1_again() throws Exception {
        String token = adminToken();
        String id = create(token, "2026-12-01");
        upload(token, id, AdminPipelineTest.pdf("Hot Soup for Mummy", "Mummy is in bed. She has a cold.", "Alan and Daddy make hot soup."));
        mvc.perform(admin(post("/admin/lessons/" + id + "/analyze"), token)).andExpect(status().isOk());
        var l = awaitStatus(token, id, "needs_review");
        assertThat(step(l, "upload")).isEqualTo("done"); assertThat(step(l, "analyze")).isEqualTo("done"); assertThat(step(l, "skills")).isEqualTo("pending");
        confirmSkills(token, l);
        l = awaitTerminal(token, id);   // the injected failure at generate_L2
        assertThat(l.get("status").asText()).isEqualTo("error");
        assertThat(step(l, "generate_L1")).isEqualTo("done");
        assertThat(step(l, "generate_L2")).isEqualTo("error");
        // D25: the three levels are one batch, so the sibling of the failing step finished rather than never starting
        assertThat(step(l, "generate_L3")).isEqualTo("done");
        assertThat(step(l, "generate_again")).as("the second batch never starts when the first one failed").isEqualTo("pending");
        assertThat(l.path("currentStep").isMissingNode() || l.path("currentStep").isNull()).as("no step running after the failure").isTrue();
        assertThat(l.get("error").get("code").asText()).isEqualTo("model_failed");
        assertThat(l.get("error").get("message").asText()).contains("Retry");
        assertThat(l.get("plays")).hasSize(2);   // Levels 1 and 3 stay reviewable
        long tokensBefore = l.get("tokenUsage").asLong();
        // partial results stay editable while in error
        var stop = l.get("plays").get(0).get("play").get("stops").get(0).deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) stop).put("title", "Edited while L2 failed");
        mvc.perform(admin(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/admin/stops/" + stop.get("id").asText()), token).contentType(MediaType.APPLICATION_JSON).content(stop.toString())).andExpect(status().isOk());

        mvc.perform(admin(post("/admin/lessons/" + id + "/retry"), token)).andExpect(status().isOk());
        l = awaitStatus(token, id, "review");
        for (String s : new String[] {"upload", "analyze", "skills", "generate_L1", "generate_L2", "generate_L3", "generate_again", "panel"}) assertThat(step(l, s)).as(s).isEqualTo("done");
        assertThat(l.get("plays")).hasSize(4);
        assertThat(l.get("parentPanel")).isNotNull();
        assertThat(l.get("steps").findValues("attempt").stream().mapToInt(JsonNode::asInt).sum()).isGreaterThan(0);
        // Level 1 was not regenerated: its edit survived and the sample model charges per call, so usage grew only by the new steps
        assertThat(l.get("plays").get(0).get("play").get("stops").get(0).get("title").asText()).isEqualTo("Edited while L2 failed");
        assertThat(l.get("tokenUsage").asLong()).isGreaterThan(tokensBefore);
    }

    @Test void empty_pdf_fails_at_analyze_with_an_actionable_message_then_replace_file_completes() throws Exception {
        String token = adminToken();
        String id = create(token, "2026-12-02");
        upload(token, id, new byte[0]);
        var l = awaitTerminal(token, id);
        // a 0-byte file is a failed upload step, never a dead end: the message says what to do and Replace file is offered
        assertThat(l.get("status").asText()).isEqualTo("error");
        assertThat(step(l, "upload")).isEqualTo("error");
        assertThat(l.get("error").get("code").asText()).isEqualTo("unreadable_file");
        assertThat(l.get("error").get("message").asText()).contains("can't read");
        // Replace file: same lesson id, course and date; the ledger restarts at analyze
        mvc.perform(admin(delete("/admin/lessons/" + id + "/files"), token)).andExpect(status().isNoContent());
        upload(token, id, AdminPipelineTest.pdf("Hot Soup for Mummy", "Mummy is in bed. She has a cold.", "Alan and Daddy make hot soup."));
        mvc.perform(admin(post("/admin/lessons/" + id + "/analyze"), token)).andExpect(status().isOk());
        l = awaitStatus(token, id, "needs_review");
        assertThat(l.get("course").get("grade").asInt()).isEqualTo(1);
        assertThat(l.get("date").asText()).isEqualTo("2026-12-02");
        confirmSkills(token, l);
        l = awaitTerminal(token, id);
        if ("error".equals(l.get("status").asText())) { mvc.perform(admin(post("/admin/lessons/" + id + "/retry"), token)).andExpect(status().isOk()); l = awaitStatus(token, id, "review"); }
        assertThat(l.get("plays")).hasSize(4);
    }

    @Test void retry_one_step_only_pauses_before_the_rest() throws Exception {
        String token = adminToken();
        String id = create(token, "2026-12-03");
        upload(token, id, AdminPipelineTest.pdf("Counting", "Count by twos: 2, 4, 6, 8.", "Pairs of shoes."));
        mvc.perform(admin(post("/admin/lessons/" + id + "/analyze"), token)).andExpect(status().isOk());
        confirmSkills(token, awaitStatus(token, id, "needs_review"));
        var l = awaitTerminal(token, id);
        assertThat(step(l, "generate_L2")).isEqualTo("error");
        mvc.perform(admin(post("/admin/lessons/" + id + "/steps/generate_L2/retry"), token)).andExpect(status().isOk());
        l = awaitStatus(token, id, "paused", "review");
        assertThat(l.get("status").asText()).isEqualTo("paused");
        assertThat(step(l, "generate_L2")).isEqualTo("done");
        // one step and no batch: Again and the panel are still waiting for "Retry and continue"
        assertThat(step(l, "generate_again")).isEqualTo("pending");
        assertThat(step(l, "panel")).isEqualTo("pending");
        mvc.perform(admin(post("/admin/lessons/" + id + "/steps/generate_L2/retry"), token)).andExpect(status().isBadRequest());   // already done
        mvc.perform(admin(post("/admin/lessons/" + id + "/retry"), token)).andExpect(status().isOk());   // continues from the first pending step
        assertThat(awaitStatus(token, id, "review").get("plays")).hasSize(4);
    }

    @Test void delete_removes_the_lesson_and_its_files_but_keeps_the_cache() throws Exception {
        String token = adminToken();
        byte[] pdf = AdminPipelineTest.pdf("The sh sound", "Ship, shop, shell.", "Fish and dish.");
        String id = create(token, "2026-12-04");
        upload(token, id, pdf);
        mvc.perform(admin(post("/admin/lessons/" + id + "/analyze"), token)).andExpect(status().isOk());
        var l = awaitStatus(token, id, "needs_review");
        Path stored = Path.of(storageDir).resolve("uploads").resolve(id);
        assertThat(Files.exists(stored)).isTrue();
        // published → 409; unpublish first
        confirmSkills(token, l);
        if ("error".equals(awaitTerminal(token, id).get("status").asText())) mvc.perform(admin(post("/admin/lessons/" + id + "/retry"), token)).andExpect(status().isOk());   // the injected L2 failure
        awaitStatus(token, id, "review");
        mvc.perform(admin(post("/admin/lessons/" + id + "/publish"), token)).andExpect(status().isOk());
        mvc.perform(admin(delete("/admin/lessons/" + id), token)).andExpect(status().isConflict());
        mvc.perform(admin(post("/admin/lessons/" + id + "/unpublish"), token)).andExpect(status().isOk());
        mvc.perform(admin(delete("/admin/lessons/" + id), token)).andExpect(status().isNoContent());
        mvc.perform(admin(get("/admin/lessons/" + id), token)).andExpect(status().isNotFound());
        assertThat(!Files.exists(stored) || isEmptyDir(stored)).as("uploaded object gone").isTrue();
        // the same file again: analysed before, zero tokens
        String again = create(token, "2026-12-05");
        upload(token, again, pdf);
        var l2 = json(mvc.perform(admin(get("/admin/lessons/" + again), token)).andReturn());
        assertThat(l2.get("files").get(0).get("cacheHit").asBoolean()).isTrue();
        mvc.perform(admin(post("/admin/lessons/" + again + "/analyze"), token)).andExpect(status().isOk());
        assertThat(awaitStatus(token, again, "needs_review").get("tokenUsage").asLong()).isZero();
    }

    @Test void delete_all_failed_removes_only_lessons_in_error() throws Exception {
        String token = adminToken();
        String failing = create(token, "2026-12-06");
        upload(token, failing, AdminPipelineTest.pdf("Hot Soup for Mummy", "Mummy is in bed.", "Soup."));
        mvc.perform(admin(post("/admin/lessons/" + failing + "/analyze"), token)).andExpect(status().isOk());
        confirmSkills(token, awaitStatus(token, failing, "needs_review"));
        assertThat(awaitTerminal(token, failing).get("status").asText()).isEqualTo("error");
        String healthy = create(token, "2026-12-07");
        var before = json(mvc.perform(admin(get("/admin/lessons"), token)).andReturn()).size();
        var result = json(mvc.perform(admin(delete("/admin/lessons/failed"), token)).andExpect(status().isOk()).andReturn());
        assertThat(result.get("deleted").asInt()).isGreaterThanOrEqualTo(1);
        var after = json(mvc.perform(admin(get("/admin/lessons"), token)).andReturn());
        assertThat(after.findValues("id").stream().map(JsonNode::asText)).contains(healthy).doesNotContain(failing);
        assertThat(after.size()).isLessThan(before);
    }

    @org.springframework.beans.factory.annotation.Autowired quest.server.analysis.LessonSteps lessonSteps;

    /** Acceptance 3: a network drop mid-generation is a transient failure — retried with backoff, then it succeeds. */
    @Test void transient_failures_are_retried_three_times_with_backoff_then_marked_error() throws Exception {
        String token = adminToken();
        String id = create(token, "2026-12-08");
        lessonSteps.ensure(id);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        boolean ok = lessonSteps.run(id, quest.api.PipelineStep.GENERATE_L3, () -> { if (calls.incrementAndGet() < 3) throw new quest.server.analysis.LessonSteps.TransientFailure("connection reset", null); });
        assertThat(ok).isTrue();
        assertThat(calls.get()).isEqualTo(3);
        var row = lessonSteps.get(id, quest.api.PipelineStep.GENERATE_L3).orElseThrow();
        assertThat(row.getStatus()).isEqualTo("done"); assertThat(row.getAttempt()).isEqualTo(3);
        // never recovers: error after the third attempt, with the message that says what to do
        var never = new java.util.concurrent.atomic.AtomicInteger();
        assertThat(lessonSteps.run(id, quest.api.PipelineStep.PANEL, () -> { never.incrementAndGet(); throw new quest.server.analysis.LessonSteps.TransientFailure("DeepSeek unreachable", null); })).isFalse();
        assertThat(never.get()).isEqualTo(3);
        var failed = lessonSteps.get(id, quest.api.PipelineStep.PANEL).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo("error"); assertThat(failed.getErrorCode()).isEqualTo("model_unavailable"); assertThat(failed.getErrorMessage()).contains("Retry and continue");
        // a permanent failure is not retried at all
        var once = new java.util.concurrent.atomic.AtomicInteger();
        assertThat(lessonSteps.run(id, quest.api.PipelineStep.GENERATE_AGAIN, () -> { once.incrementAndGet(); throw quest.server.config.ApiException.badRequest("Upload the slides first."); })).isFalse();
        assertThat(once.get()).isEqualTo(1);
        // a done step is a no-op
        var again = new java.util.concurrent.atomic.AtomicInteger();
        assertThat(lessonSteps.run(id, quest.api.PipelineStep.GENERATE_L3, again::incrementAndGet)).isTrue();
        assertThat(again.get()).isZero();
    }

    /** Lessons from before the ledger: the strip is derived from what exists and a retry resumes at the real failure. */
    @Test void a_lesson_without_step_rows_is_backfilled_from_its_state() throws Exception {
        String token = adminToken();
        String id = create(token, "2026-12-09");
        upload(token, id, AdminPipelineTest.pdf("Hot Soup for Mummy", "Mummy is in bed.", "Soup."));
        mvc.perform(admin(post("/admin/lessons/" + id + "/analyze"), token)).andExpect(status().isOk());
        confirmSkills(token, awaitStatus(token, id, "needs_review"));
        assertThat(awaitTerminal(token, id).get("status").asText()).isEqualTo("error");   // injected at generate_L2
        stepRows.deleteAll(stepRows.findByLessonIdOrderByPosition(id));                    // pretend the ledger never existed
        var l = json(mvc.perform(admin(get("/admin/lessons/" + id), token)).andReturn());
        assertThat(step(l, "upload")).isEqualTo("done"); assertThat(step(l, "analyze")).isEqualTo("done"); assertThat(step(l, "skills")).isEqualTo("done");
        assertThat(step(l, "generate_L1")).isEqualTo("done"); assertThat(step(l, "generate_L2")).isEqualTo("error"); assertThat(step(l, "generate_L3")).isEqualTo("done");
        mvc.perform(admin(post("/admin/lessons/" + id + "/retry"), token)).andExpect(status().isOk());
        assertThat(awaitStatus(token, id, "review").get("plays")).hasSize(4);
    }
    @org.springframework.beans.factory.annotation.Autowired quest.server.content.LessonStepRepository stepRows;

    // ---------------------------------------------------------------- helpers
    private static boolean isEmptyDir(Path p) throws Exception { try (var s = Files.walk(p)) { return s.noneMatch(Files::isRegularFile); } }
    private static String step(JsonNode lesson, String name) { for (var s : lesson.get("steps")) if (name.equals(s.get("step").asText())) return s.get("status").asText(); return "missing"; }
    private JsonNode awaitTerminal(String token, String id) throws Exception {
        for (int i = 0; i < 200; i++) {
            var l = json(mvc.perform(admin(get("/admin/lessons/" + id), token)).andReturn());
            String s = l.get("status").asText();
            if (!"analyzing".equals(s) && !"generating".equals(s) && !"uploading".equals(s)) return l;
            Thread.sleep(100);
        }
        throw new AssertionError("job never finished");
    }
    private String create(String token, String date) throws Exception {
        return json(mvc.perform(admin(post("/admin/lessons"), token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"curriculum\":\"british\",\"grade\":1,\"subject\":\"english\",\"date\":\"" + date + "\"}")).andReturn()).get("id").asText();
    }
    private void upload(String token, String id, byte[] pdf) throws Exception {
        mvc.perform(admin(multipart("/admin/lessons/" + id + "/files").file(new MockMultipartFile("files", "slides.pdf", "application/pdf", pdf)), token));
    }
    private void confirmSkills(String token, JsonNode l) throws Exception {
        var skills = mapper.createArrayNode();
        for (var s : l.get("skills")) skills.addObject().put("id", s.get("id").asText()).put("name", s.get("name").asText()).put("subject", s.get("subject").asText()).put("method", s.get("method").asText());
        mvc.perform(admin(post("/admin/lessons/" + l.get("id").asText() + "/skills"), token).contentType(MediaType.APPLICATION_JSON).content(skills.toString())).andExpect(status().isOk());
    }
}
