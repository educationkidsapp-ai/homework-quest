package quest.server.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import quest.server.ApiTestSupport;

/**
 * The QA bug this package is named for: a lesson stuck at `generate_L3`/`generating` for three quarters of an hour,
 * with `Retry` answering "Wait for the current job to finish" and `Delete` answering 409, so the teacher had no way
 * out of it at all.
 *
 * <p>Two halves, because there are two ways a job stops existing. A step that hangs in <em>this</em> process is ended
 * by its deadline ({@code the_deadline}). A job whose process is gone — a recycled Cloud Run instance — leaves a
 * `running` row nothing in this process ever held, and only the sweep can end that ({@code the_sweep}).
 */
@TestPropertySource(properties = {
        "quest.pipeline.hang-at=generate_L3",
        "quest.pipeline.deadline.generate-seconds=6", "quest.pipeline.deadline.analyze-seconds=60", "quest.pipeline.deadline.convert-seconds=60",
        "quest.pipeline.watchdog.grace-seconds=1",
        // the suite shares one H2 database: this context's short deadlines must not sweep another test's lesson
        "quest.pipeline.watchdog.enabled=false" })
class StuckLessonTest extends ApiTestSupport {
    @Autowired quest.server.analysis.PipelineWatchdog watchdog;
    @Autowired quest.server.content.LessonStepRepository stepRows;
    @Autowired quest.server.content.LessonRepository lessons;

    /** A step that never returns is marked `error`/`timeout`, and everything the teacher was refused works again. */
    @Test void a_step_that_hangs_is_timed_out_and_the_lesson_becomes_editable_again() throws Exception {
        String token = adminToken();
        String id = create(token, "2026-11-01");
        upload(token, id, AdminPipelineTest.pdf("Hot Soup", "Mummy is in bed.", "Alan makes soup."));
        mvc.perform(admin(post("/admin/lessons/" + id + "/analyze"), token)).andExpect(status().isOk());
        confirmSkills(token, awaitStatus(token, id, "needs_review"));

        var l = awaitTerminal(token, id);
        assertThat(l.get("status").asText()).isEqualTo("error");
        assertThat(step(l, "generate_L2")).as("the steps before the hang finished").isEqualTo("done");
        assertThat(step(l, "generate_L3")).isEqualTo("error");
        assertThat(l.get("error").get("code").asText()).isEqualTo("timeout");
        assertThat(l.get("error").get("message").asText()).isEqualTo("This step took too long. Retry it.");
        assertThat(l.path("currentStep").isMissingNode() || l.path("currentStep").isNull()).as("no step left running").isTrue();

        // the three things that were refused while it sat there: editing, retrying and deleting
        var stop = l.get("plays").get(0).get("play").get("stops").get(0).deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) stop).put("title", "Edited after the timeout");
        mvc.perform(admin(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/admin/stops/" + stop.get("id").asText()), token)
                .contentType(MediaType.APPLICATION_JSON).content(stop.toString())).andExpect(status().isOk());
        mvc.perform(admin(post("/admin/lessons/" + id + "/steps/generate_L3/retry"), token)).andExpect(status().isOk());
        assertThat(awaitTerminal(token, id).get("error").get("code").asText()).as("it hangs again, and times out again").isEqualTo("timeout");
        mvc.perform(admin(delete("/admin/lessons/" + id), token)).andExpect(status().isNoContent());
    }

    /**
     * The recycled instance. Nothing in this process is running the job, so the rows are simply old: a `running` step
     * past its deadline, and a lesson left `generating` between two steps with nothing running at all.
     */
    @Test void the_sweep_recovers_a_step_and_a_lesson_a_dead_instance_left_behind() throws Exception {
        String token = adminToken();
        String running = create(token, "2026-11-02");
        String between = create(token, "2026-11-03");
        var old = Instant.now().minus(30, ChronoUnit.MINUTES);

        lessonSteps.ensure(running);
        lessonSteps.mark(running, quest.api.PipelineStep.GENERATE_L3, "running", null, null);
        age(running, old); ageLesson(running, "generating", old);
        ageLesson(between, "analyzing", old);                                   // no step row is running: the job died between two

        assertThat(watchdog.sweep()).isGreaterThanOrEqualTo(2);

        var recovered = json(mvc.perform(admin(get("/admin/lessons/" + running), token)).andReturn());
        assertThat(recovered.get("status").asText()).isEqualTo("error");
        assertThat(step(recovered, "generate_L3")).isEqualTo("error");
        assertThat(recovered.get("error").get("code").asText()).isEqualTo("timeout");
        assertThat(json(mvc.perform(admin(get("/admin/lessons/" + between), token)).andReturn()).get("status").asText()).isEqualTo("error");

        // and a lesson that only just started is not swept out from under its own job
        String fresh = create(token, "2026-11-04");
        lessonSteps.ensure(fresh);
        lessonSteps.mark(fresh, quest.api.PipelineStep.GENERATE_L1, "running", null, null);
        ageLesson(fresh, "generating", Instant.now());
        watchdog.sweep();
        assertThat(stepRows.findById(fresh + ":generate_L1").orElseThrow().getStatus()).isEqualTo("running");
    }

    // ---------------------------------------------------------------- helpers

    @Autowired quest.server.analysis.LessonSteps lessonSteps;

    private void age(String lessonId, Instant at) {
        var row = stepRows.findById(lessonId + ":generate_L3").orElseThrow();
        row.setUpdatedAt(at); stepRows.save(row);
    }

    private void ageLesson(String lessonId, String status, Instant at) {
        var lesson = lessons.findById(lessonId).orElseThrow();
        lesson.setStatus(status); lesson.setUpdatedAt(at); lessons.save(lesson);
    }

    private static String step(JsonNode lesson, String name) { for (var s : lesson.get("steps")) if (name.equals(s.get("step").asText())) return s.get("status").asText(); return "missing"; }

    private JsonNode awaitTerminal(String token, String id) throws Exception {
        for (int i = 0; i < 600; i++) {
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
        mvc.perform(admin(multipart("/admin/lessons/" + id + "/files").file(new MockMultipartFile("files", "slides.pdf", MediaType.APPLICATION_PDF_VALUE, pdf)), token)).andExpect(status().isOk());
    }

    private void confirmSkills(String token, JsonNode l) throws Exception {
        var skills = mapper.createArrayNode();
        for (var s : l.get("skills")) skills.addObject().put("id", s.get("id").asText()).put("name", s.get("name").asText()).put("subject", s.get("subject").asText()).put("method", s.get("method").asText());
        mvc.perform(admin(post("/admin/lessons/" + l.get("id").asText() + "/skills"), token).contentType(MediaType.APPLICATION_JSON).content(skills.toString())).andExpect(status().isOk());
    }
}
