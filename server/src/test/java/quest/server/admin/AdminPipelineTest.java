package quest.server.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import quest.server.ApiTestSupport;

/** Upload → analyse → confirm → generate → edit → publish, then the same slides again with zero model calls. */
public class AdminPipelineTest extends ApiTestSupport {

    @Test void admin_endpoints_need_the_admin_role() throws Exception {
        mvc.perform(get("/admin/lessons")).andExpect(status().isUnauthorized());
        assertThat(mvc.perform(get("/admin/lessons").header("Authorization", PARENT)).andReturn().getResponse().getStatus()).isIn(401, 403);
        mvc.perform(post("/admin/auth/sign-in").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"admin@test.local\",\"password\":\"wrong\"}")).andExpect(status().isUnauthorized());
        String token = adminToken();
        assertThat(mvc.perform(admin(get("/children"), token)).andReturn().getResponse().getStatus()).isIn(401, 403);
    }

    @Test void full_pipeline_and_permanent_cache() throws Exception {
        String token = adminToken();
        byte[] pdf = pdf("Hot Soup for Mummy", "Mummy is in bed. She has a cold.", "Alan and Daddy make hot soup.");

        String first = runToReview(token, pdf, "2026-10-01");
        var lesson = json(mvc.perform(admin(get("/admin/lessons/" + first), token)).andReturn());
        assertThat(lesson.get("tokenUsage").asLong()).isGreaterThan(0);
        assertThat(lesson.get("tokensSaved").asLong()).isZero();
        assertThat(lesson.get("files").get(0).get("cacheHit").asBoolean()).isFalse();
        assertThat(lesson.get("plays")).hasSize(4);
        assertThat(lesson.get("parentPanel").get("stopTips").get(0).get("stopId").asText()).startsWith(first.substring(0, 8) + ":");
        for (var p : lesson.get("plays")) for (var s : p.get("play").get("stops")) assertThat(s.get("id").asText()).startsWith(first.substring(0, 8) + ":" + p.get("level").asInt() + ":" + p.get("variant").asInt() + ":");

        // edit a stop, publish, check the app can download it and a child on the course sees the island
        var stop = lesson.get("plays").get(0).get("play").get("stops").get(0).deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) stop).put("title", "Edited");
        mvc.perform(admin(put("/admin/stops/" + stop.get("id").asText()), token).contentType(MediaType.APPLICATION_JSON).content(stop.toString())).andExpect(status().isOk());
        mvc.perform(admin(post("/admin/lessons/" + first + "/publish"), token)).andExpect(status().isOk());
        var published = parentGet("/lessons/" + first);
        assertThat(published.get("version").asInt()).isEqualTo(1);
        assertThat(published.get("plays").get(0).get("stops").get(0).get("title").asText()).isEqualTo("Edited");
        var child = parentPost("/children", "{\"name\":\"Zed\",\"avatarColor\":\"sky\",\"curriculum\":\"british\",\"grade\":1}");
        var map = parentGet("/children/" + child.get("id").asText() + "/map?from=2026-09-28&to=2026-10-05&today=2026-10-01");
        assertThat(map.get("islands")).anySatisfy(i -> { assertThat(i.path("lessonId").asText()).isEqualTo(first); assertThat(i.get("state").asText()).isEqualTo("today"); });

        // the same slides for the same course a second time: cache badge, zero tokens used, identical content
        String second = runToReview(token, pdf, "2026-10-02");
        var again = json(mvc.perform(admin(get("/admin/lessons/" + second), token)).andReturn());
        assertThat(again.get("files").get(0).get("cacheHit").asBoolean()).isTrue();
        assertThat(again.get("tokenUsage").asLong()).isZero();
        assertThat(again.get("tokensSaved").asLong()).isEqualTo(lesson.get("tokenUsage").asLong());
        var cache = json(mvc.perform(admin(get("/admin/cache"), token)).andReturn());
        assertThat(cache).anySatisfy(c -> { assertThat(c.get("hits").asInt()).isEqualTo(1); assertThat(c.get("lessonIds")).hasSize(2); });

        // a different course is a different key → a new analysis
        String other = create(token, "american", 2, "2026-10-03");
        upload(token, other, pdf);
        var otherLesson = json(mvc.perform(admin(get("/admin/lessons/" + other), token)).andReturn());
        assertThat(otherLesson.get("files").get(0).get("cacheHit").asBoolean()).isFalse();
    }

    @Test void publish_requires_all_levels_and_panel() throws Exception {
        String token = adminToken();
        String id = create(token, "british", 1, "2026-11-01");
        var r = json(mvc.perform(admin(post("/admin/lessons/" + id + "/publish"), token)).andExpect(status().isBadRequest()).andReturn());
        assertThat(r.get("code").asText()).isEqualTo("bad_request");
        mvc.perform(admin(post("/admin/lessons/" + id + "/analyze"), token)).andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------- helpers
    private String runToReview(String token, byte[] pdf, String date) throws Exception {
        String id = create(token, "british", 1, date);
        upload(token, id, pdf);
        mvc.perform(admin(post("/admin/lessons/" + id + "/analyze"), token)).andExpect(status().isOk());
        var l = awaitStatus(token, id, "needs_review");
        var skills = mapper.createArrayNode();
        for (var s : l.get("skills")) skills.addObject().put("id", s.get("id").asText()).put("name", s.get("name").asText()).put("subject", s.get("subject").asText()).put("method", s.get("method").asText());
        mvc.perform(admin(post("/admin/lessons/" + id + "/skills"), token).contentType(MediaType.APPLICATION_JSON).content(skills.toString())).andExpect(status().isOk());
        awaitStatus(token, id, "review");
        return id;
    }
    private String create(String token, String curriculum, int grade, String date) throws Exception {
        return json(mvc.perform(admin(post("/admin/lessons"), token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"curriculum\":\"" + curriculum + "\",\"grade\":" + grade + ",\"subject\":\"english\",\"date\":\"" + date + "\",\"notes\":\"Story: Hot Soup\"}")).andExpect(status().isCreated()).andReturn()).get("id").asText();
    }
    private void upload(String token, String id, byte[] pdf) throws Exception {
        mvc.perform(admin(multipart("/admin/lessons/" + id + "/files").file(new MockMultipartFile("files", "slides.pdf", "application/pdf", pdf)), token)).andExpect(status().isOk());
    }
    public static byte[] pdf(String... lines) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            for (String line : lines) {
                PDPage page = new PDPage(); doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) { cs.beginText(); cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 20); cs.newLineAtOffset(60, 700); cs.showText(line); cs.endText(); }
            }
            var out = new ByteArrayOutputStream(); doc.save(out); return out.toByteArray();
        }
    }
}
