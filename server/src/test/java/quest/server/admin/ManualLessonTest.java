package quest.server.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import quest.server.ApiTestSupport;

/** A lesson written by hand in the panel: no PDF, a readPage and a multiSelect, a picture, then published and played. */
class ManualLessonTest extends ApiTestSupport {

    @Test void manual_lesson_from_two_stops_to_the_childs_map() throws Exception {
        String token = adminToken();
        var created = json(mvc.perform(admin(post("/admin/lessons"), token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"curriculum\":\"british\",\"grade\":1,\"subject\":\"english\",\"date\":\"2026-11-03\",\"source\":\"manual\",\"title\":\"Our class pet\"}")).andExpect(status().is2xxSuccessful()).andReturn());
        String id = created.get("id").asText();
        assertThat(created.get("status").asText()).isEqualTo("review");
        assertThat(created.get("source").asText()).isEqualTo("manual");
        String playId = created.get("plays").get(0).get("id").asText();
        assertThat(created.get("plays").get(0).get("play").get("stops")).isEmpty();

        // a picture for the readPage
        var png = new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB), "png", png);
        var image = json(mvc.perform(admin(multipart("/admin/lessons/" + id + "/images").file(new MockMultipartFile("file", "hamster.png", "image/png", png.toByteArray())), token)).andExpect(status().isOk()).andReturn());
        assertThat(image.get("id").asText()).contains(":img-");
        assertThat(image.get("url").asText()).contains("/media/pages/");
        mvc.perform(get("/media/pages/" + image.get("id").asText())).andExpect(status().isOk());

        String common = "\"title\":\"%s\",\"speak\":\"%s\",\"ingredient\":{\"emoji\":\"🐹\",\"name\":\"hamster\"},\"parentTip\":{\"en\":\"Read it together.\",\"ar\":\"اقرآه معًا.\"}";
        var readPage = json(mvc.perform(admin(post("/admin/plays/" + playId + "/stops"), token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"readPage\",\"id\":\"\"," + common.formatted("Meet Biscuit", "Let's read about Biscuit.") + ",\"pageNumber\":1,\"sentences\":[\"Biscuit is our class hamster.\",\"He likes carrots.\"],\"imageId\":\"" + image.get("id").asText() + "\"}"))
                .andExpect(status().isOk()).andReturn());
        assertThat(readPage.get("id").asText()).startsWith(id.substring(0, 8) + ":1:0:");
        var multi = json(mvc.perform(admin(post("/admin/plays/" + playId + "/stops"), token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"multiSelect\",\"id\":\"\"," + common.formatted("What does he eat?", "Tap two things Biscuit eats.") + ",\"prompt\":\"Tap two foods.\",\"options\":[{\"id\":\"a\",\"label\":\"carrot\"},{\"id\":\"b\",\"label\":\"seeds\"},{\"id\":\"c\",\"label\":\"pizza\"}],\"correctIds\":[\"a\",\"b\"],\"pick\":2}"))
                .andExpect(status().isOk()).andReturn());

        // reorder: the question first, then the page — and back
        String a = readPage.get("id").asText(), b = multi.get("id").asText();
        var reordered = json(mvc.perform(admin(put("/admin/plays/" + playId + "/order"), token).contentType(MediaType.APPLICATION_JSON).content("{\"stopIds\":[\"" + b + "\",\"" + a + "\"]}")).andExpect(status().isOk()).andReturn());
        assertThat(reordered.get("stops").get(0).get("id").asText()).isEqualTo(b);
        mvc.perform(admin(put("/admin/plays/" + playId + "/order"), token).contentType(MediaType.APPLICATION_JSON).content("{\"stopIds\":[\"" + a + "\",\"" + b + "\"]}")).andExpect(status().isOk());
        mvc.perform(admin(put("/admin/plays/" + playId + "/order"), token).contentType(MediaType.APPLICATION_JSON).content("{\"stopIds\":[\"" + a + "\"]}")).andExpect(status().isBadRequest());

        // a third stop, deleted again
        var extra = json(mvc.perform(admin(post("/admin/plays/" + playId + "/stops"), token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"trueFalse\",\"id\":\"\"," + common.formatted("True or false", "Biscuit is a cat.") + ",\"hint\":\"Look at the picture.\",\"statement\":\"Biscuit is a cat.\",\"answer\":false}")).andExpect(status().isOk()).andReturn());
        mvc.perform(admin(delete("/admin/stops/" + extra.get("id").asText()), token)).andExpect(status().isNoContent());

        // publish: levels 2, 3 and the Again variant repeat Level 1, the panel is derived from the stops
        var published = json(mvc.perform(admin(post("/admin/lessons/" + id + "/publish"), token)).andExpect(status().isOk()).andReturn());
        assertThat(published.get("status").asText()).isEqualTo("published");
        assertThat(published.get("plays")).hasSize(4);
        assertThat(published.get("parentPanel").get("stopTips")).hasSize(2);

        // the app: same shape as a generated lesson, the picture is in images, the stop points at it
        JsonNode lesson = parentGet("/lessons/" + id);
        assertThat(lesson.get("plays")).hasSize(3);
        assertThat(lesson.get("plays").get(0).get("stops")).hasSize(2);
        assertThat(lesson.get("plays").get(0).get("stops").get(0).get("imageId").asText()).isEqualTo(image.get("id").asText());
        assertThat(lesson.get("pageImages").findValues("id").stream().map(JsonNode::asText)).contains(image.get("id").asText());
        assertThat(lesson.get("plays").get(1).get("stops").get(0).get("id").asText()).startsWith(id.substring(0, 8) + ":2:0:");

        // the child sees the island on 2026-11-03
        var child = parentPost("/children", "{\"name\":\"Lina\",\"avatarColor\":\"sun\",\"curriculum\":\"british\",\"grade\":1,\"languages\":[\"en\"]}");
        var map = parentGet("/children/" + child.get("id").asText() + "/map?from=2026-11-01&to=2026-11-30");
        assertThat(map.get("islands").findValues("lessonId").stream().map(JsonNode::asText)).contains(id);
    }

    @Test void status_filter_is_gone_and_source_is_reported() throws Exception {
        String token = adminToken();
        mvc.perform(admin(get("/admin/lessons?status=published"), token)).andExpect(status().isOk()); // ignored, not an error
        var list = json(mvc.perform(admin(get("/admin/lessons?curriculum=british&grade=1"), token)).andReturn());
        for (JsonNode l : list) assertThat(l.get("source").asText()).isIn("pdf", "slides", "images", "manual");
    }
}
