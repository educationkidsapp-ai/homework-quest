package quest.server.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import quest.server.ApiTestSupport;

/**
 * CR4 §4 end to end on the Admin routes: the Convert step sits between Upload and Analyse, the file carries its own
 * `convertStatus`, the teacher can read the extracted text before the AI does, and a conversion she is not happy
 * with has two ways out.
 *
 * <p>Neither Node nor Tesseract is installed in CI, so the `test` profile has
 * `quest.pipeline.convert.allow-builtin-fallback` on and the conversion comes out as method `text` — exactly the way
 * a laptop runs the e2e. What is being tested here is the pipeline and the contract around it, not anydoc itself
 * ({@code ConversionServiceTest} drives every exit code the binary can return).
 */
class ConvertStepTest extends ApiTestSupport {

    @Test void convert_runs_between_upload_and_analyze_and_the_file_says_so() throws Exception {
        String token = adminToken();
        String id = lessonReadyForReview(token, "2026-12-01");

        var lesson = json(mvc.perform(admin(get("/admin/lessons/" + id), token)).andReturn());
        assertThat(stepNames(lesson)).containsSubsequence("upload", "convert", "analyze");
        assertThat(step(lesson, "convert").get("status").asText()).isEqualTo("done");

        var file = lesson.get("files").get(0);
        assertThat(file.get("convertStatus").asText()).isEqualTo("ready");
        assertThat(file.get("convertMethod").asText()).isEqualTo("text");
        assertThat(file.hasNonNull("convertErrorCode")).as("the shared codec leaves a null out of the JSON entirely").isFalse();
        assertThat(file.get("markdownChars").asInt()).isGreaterThan(0);

        // the preview: what the model was given, as Markdown
        var preview = mvc.perform(admin(get("/admin/lessons/" + id + "/files/" + file.get("id").asText() + "/markdown"), token))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(preview.getContentType()).startsWith("text/markdown");
        assertThat(preview.getContentAsString()).contains("## Page 1").contains("Mummy is in bed");
    }

    @Test void the_teacher_can_paste_the_text_herself_when_the_conversion_is_wrong() throws Exception {
        String token = adminToken();
        String id = create(token, "2026-12-02");
        upload(token, id);
        String fileId = json(mvc.perform(admin(get("/admin/lessons/" + id), token)).andReturn()).get("files").get(0).get("id").asText();

        var after = json(mvc.perform(admin(post("/admin/lessons/" + id + "/files/" + fileId + "/retry-conversion?method=text"), token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"markdown\":\"# What the slides say\\n\\nMummy is in bed.\"}"))
                .andExpect(status().isOk()).andReturn());

        var file = after.get("files").get(0);
        assertThat(file.get("convertStatus").asText()).isEqualTo("ready");
        assertThat(file.get("convertMethod").asText()).isEqualTo("text");
        assertThat(file.get("markdownChars").asInt()).isEqualTo("# What the slides say\n\nMummy is in bed.".length());
        assertThat(mvc.perform(admin(get("/admin/lessons/" + id + "/files/" + fileId + "/markdown"), token)).andReturn().getResponse().getContentAsString())
                .isEqualTo("# What the slides say\n\nMummy is in bed.");
        // and the lesson is a draft again, with the steps after Convert waiting
        assertThat(after.get("status").asText()).isEqualTo("draft");
        assertThat(step(after, "analyze").get("status").asText()).isEqualTo("pending");
    }

    @Test void the_fallback_refuses_a_method_it_does_not_know_and_a_file_from_another_lesson() throws Exception {
        String token = adminToken();
        String mine = create(token, "2026-12-03"); upload(token, mine);
        String other = create(token, "2026-12-04"); upload(token, other);
        String otherFile = json(mvc.perform(admin(get("/admin/lessons/" + other), token)).andReturn()).get("files").get(0).get("id").asText();

        mvc.perform(admin(post("/admin/lessons/" + mine + "/files/" + otherFile + "/retry-conversion?method=text"), token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"markdown\":\"# nope\"}")).andExpect(status().isNotFound());
        mvc.perform(admin(get("/admin/lessons/" + mine + "/files/" + otherFile + "/markdown"), token)).andExpect(status().isNotFound());
        String mineFile = json(mvc.perform(admin(get("/admin/lessons/" + mine), token)).andReturn()).get("files").get(0).get("id").asText();
        mvc.perform(admin(post("/admin/lessons/" + mine + "/files/" + mineFile + "/retry-conversion?method=magic"), token)
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------- helpers

    private List<String> stepNames(JsonNode lesson) {
        var out = new ArrayList<String>();
        for (var s : lesson.get("steps")) out.add(s.get("step").asText());
        return out;
    }

    private JsonNode step(JsonNode lesson, String name) {
        for (var s : lesson.get("steps")) if (name.equals(s.get("step").asText())) return s;
        throw new AssertionError("no " + name + " step in " + lesson.get("steps"));
    }

    private String lessonReadyForReview(String token, String date) throws Exception {
        String id = create(token, date);
        upload(token, id);
        mvc.perform(admin(post("/admin/lessons/" + id + "/analyze"), token)).andExpect(status().isOk());
        awaitStatus(token, id, "needs_review");
        return id;
    }

    private String create(String token, String date) throws Exception {
        return json(mvc.perform(admin(post("/admin/lessons"), token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"curriculum\":\"british\",\"grade\":1,\"subject\":\"english\",\"date\":\"" + date + "\",\"notes\":\"Story: Hot Soup\"}"))
                .andExpect(status().isCreated()).andReturn()).get("id").asText();
    }

    private void upload(String token, String id) throws Exception {
        byte[] pdf = AdminPipelineTest.pdf("Mummy is in bed. She has a cold.", "Alan and Daddy make hot soup.");
        mvc.perform(admin(multipart("/admin/lessons/" + id + "/files").file(new MockMultipartFile("files", "slides.pdf", "application/pdf", pdf)), token))
                .andExpect(status().isOk());
    }
}
