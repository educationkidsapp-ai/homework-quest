package quest.server.children;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import quest.server.ApiTestSupport;

/** The app's `ContentApi` end to end: child → map → lesson → attempts → unlock → progress → media. */
class ParentFlowTest extends ApiTestSupport {

    @Test void unauthenticated_requests_are_rejected() throws Exception {
        mvc.perform(get("/children")).andExpect(status().isUnauthorized());
        mvc.perform(get("/children").header("Authorization", "Bearer garbage")).andExpect(status().isUnauthorized());
    }

    @Test void child_map_lesson_attempts_progress() throws Exception {
        var child = parentPost("/children", "{\"name\":\"Lina\",\"avatarColor\":\"sun\",\"curriculum\":\"british\",\"grade\":1}");
        String id = child.get("id").asText();
        assertThat(parentGet("/children")).hasSize(1);

        var map = parentGet("/children/" + id + "/map?from=2026-09-07&to=2026-09-21&today=2026-09-14");
        var islands = map.get("islands");
        assertThat(islands).extracting(i -> i.get("kind").asText()).contains("lesson", "locked");
        var hotSoup = find(islands, "lesson-hot-soup-1");
        assertThat(hotSoup.get("state").asText()).isEqualTo("today");
        assertThat(hotSoup.get("levelsUnlocked")).extracting(n -> n.asInt()).containsExactly(1);

        var lesson = parentGet("/lessons/lesson-hot-soup-1");
        assertThat(lesson.get("plays")).hasSize(3);
        assertThat(lesson.get("variant").get("level").asInt()).isEqualTo(1);

        // every stop of Level 1 answered, most with 3 stars → Level 2 unlocks, sticker + streak recorded
        List<String> uploads = new ArrayList<>(); int i = 0;
        for (var stop : lesson.get("plays").get(0).get("stops")) {
            uploads.add(attempt("a" + i, stop.get("id").asText(), 1, true, i % 3 == 0 ? 2 : 3)); i++;
            if (stop.get("type").asText().equals("exitTicket")) for (var q : stop.get("questions")) { uploads.add(attempt("a" + i, q.get("id").asText(), 1, i % 2 == 0, 3)); i++; }
        }
        var ack = parentPost("/children/" + id + "/attempts", "[" + String.join(",", uploads) + "]");
        assertThat(ack.get("accepted").asInt()).isEqualTo(uploads.size());
        assertThat(parentPost("/children/" + id + "/attempts", "[" + String.join(",", uploads) + "]").get("accepted").asInt()).isZero();

        map = parentGet("/children/" + id + "/map?from=2026-09-07&to=2026-09-21&today=2026-09-14");
        hotSoup = find(map.get("islands"), "lesson-hot-soup-1");
        assertThat(hotSoup.get("state").asText()).isEqualTo("done");
        assertThat(hotSoup.get("levelsUnlocked")).extracting(n -> n.asInt()).containsExactly(1, 2);
        assertThat(hotSoup.get("starsTotal").asInt()).isEqualTo(27);
        // one wrong first try out of two single-answer exit questions → a review island for that lesson, listed before it
        assertThat(map.get("islands").get(0).get("kind").asText()).isEqualTo("review");
        assertThat(map.get("islands").get(0).get("playId").asText()).isEqualTo("lesson-hot-soup-1:1:1");

        var progress = parentGet("/children/" + id + "/progress");
        assertThat(progress.get("streakDays").asInt()).isEqualTo(1);
        assertThat(progress.get("stickers")).hasSize(1);
        assertThat(progress.get("skills")).anySatisfy(s -> assertThat(s.get("attempts").asInt()).isGreaterThan(0));
        assertThat(progress.toString()).doesNotContain("%");

        var media = json(mvc.perform(multipart("/children/" + id + "/stops/hs1-retell/media").file(new MockMultipartFile("file", "r.m4a", "audio/mp4", new byte[] {1, 2, 3})).param("kind", "recording").header("Authorization", PARENT)).andExpect(status().isOk()).andReturn());
        String mediaUrl = media.get("url").asText().replace("http://localhost:8080", "");
        mvc.perform(get(mediaUrl).header("Authorization", PARENT)).andExpect(status().isOk());
        mvc.perform(get(mediaUrl)).andExpect(status().isUnauthorized());        // P1.9: media needs a token now
        mvc.perform(get(mediaUrl).header("Authorization", "Bearer fake-token-other")).andExpect(status().isNotFound());

        // ownership: another parent can't see this child
        mvc.perform(get("/children/" + id + "/progress").header("Authorization", "Bearer fake-token-other")).andExpect(status().isNotFound());
        mvc.perform(patch("/children/" + id).header("Authorization", PARENT).contentType(MediaType.APPLICATION_JSON).content("{\"grade\":9}")).andExpect(status().isBadRequest());
        mvc.perform(delete("/children/" + id).header("Authorization", PARENT)).andExpect(status().isNoContent());
        assertThat(parentGet("/children")).isEmpty();
    }

    @Test void child_on_another_course_sees_only_the_locked_island() throws Exception {
        var child = parentPost("/children", "{\"name\":\"Omar\",\"avatarColor\":\"mint\",\"curriculum\":\"american\",\"grade\":2}");
        var map = parentGet("/children/" + child.get("id").asText() + "/map?from=2026-09-07&to=2026-09-21&today=2026-09-14");
        assertThat(map.get("islands")).hasSize(1);
        assertThat(map.get("islands").get(0).get("kind").asText()).isEqualTo("locked");
    }

    private static String attempt(String id, String stopId, int level, boolean correct, int stars) {
        return "{\"id\":\"" + id + "\",\"stopId\":\"" + stopId + "\",\"lessonId\":\"lesson-hot-soup-1\",\"level\":" + level + ",\"answerJson\":\"{}\",\"correct\":" + correct + ",\"attemptNumber\":1,\"mistakes\":0,\"stars\":" + stars + ",\"answeredAt\":1789300000000}";
    }
    private static com.fasterxml.jackson.databind.JsonNode find(com.fasterxml.jackson.databind.JsonNode islands, String lessonId) {
        for (var i : islands) if ("lesson".equals(i.get("kind").asText()) && lessonId.equals(i.path("lessonId").asText(null))) return i;
        throw new AssertionError("no island for " + lessonId);
    }
}
