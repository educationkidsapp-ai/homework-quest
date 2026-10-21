package quest.server.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import quest.server.ClassFixtures;
import quest.server.flags.FlagKeys;
import quest.server.push.PushProbe;
import quest.server.push.PushSender;
import quest.server.tenancy.TenantContext;

/**
 * Feature 32, second half: the class channel. One channel per section, its teachers post, every parent of the class
 * reads — and everybody else, a teacher of another class and a parent of another class alike, gets 404.
 *
 * <p>The cast is {@link ChatTestSupport}'s: 1A is taught by Sara (math, and its homeroom teacher) and Noor (english),
 * 1B by Noor alone. Maya is the test parent's child on 1A, Omar hers on 1B, and Rania another parent's on 1A. Rula
 * teaches 1B only, so 1A is no class of hers.
 */
class ClassChannelApiTest extends ChatTestSupport {
    private static final String RULA = "ch-teacher-rula";
    private static final String PHONE_SARA = "0500000111";

    @Autowired PushSender pushes;
    @Autowired quest.server.push.ParentDeviceRepository parentDevices;

    private String maya, omar, rania, rula, phone;

    @BeforeEach void seed() throws Exception {
        seedSchools();
        maya = child("Maya", "CHSCHA", section1a);
        omar = child("Omar", "CHSCHA", section1b);
        rania = someoneElsesChild("Rania");
        teacher(RULA, A, "Ms Rula");
        ClassFixtures.assign(assignments, section1b, "art", RULA);
        rula = token(RULA, "TEACHER", A);
        users.findById(SARA).ifPresent(u -> { u.setPhone(PHONE_SARA); users.save(u); });
        phone = PushProbe.token("f32-parent-phone");
        PushProbe.register(mvc, PARENT, phone, "en");
    }

    @AfterEach void clean() {
        parentDevices.deleteByTokenValue(phone);
        removeSeed();
    }

    // ---------------------------------------------------------------- the post, its file, and the parents

    @Test void a_teacher_posts_with_a_file_and_every_parent_of_the_class_reads_it_and_is_told_once() throws Exception {
        String file = upload(sara, CLASS_1A);
        var post = json(mvc.perform(bearer(post(staff(CLASS_1A) + "/posts"), sara).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"Bring a ruler tomorrow.\",\"attachmentIds\":[\"" + file + "\"],\"clientId\":\"c-1\"}"))
                .andExpect(status().isCreated()).andReturn());
        assertThat(post.get("authorId").asText()).isEqualTo(SARA);
        assertThat(post.get("authorName").asText()).isEqualTo("Ms Sara");
        assertThat(post.get("authorRole").asText()).isEqualTo("TEACHER");
        assertThat(post.get("subject").asText()).isEqualTo("math");
        assertThat(post.get("attachments")).hasSize(1);

        // The parent of the class reads it, with her own unread count, and may read its file.
        var page = parentGet("/children/" + maya + "/channel");
        assertThat(bodies(page.get("posts"))).containsExactly("Bring a ruler tomorrow.");
        assertThat(page.get("unread").asInt()).isEqualTo(1);
        assertThat(page.get("className").asText()).isEqualTo("1A");
        mvc.perform(get("/media/attachments/" + file).header("Authorization", PARENT)).andExpect(status().isOk());

        // One bell row and one push, linking to her child's channel.
        var bell = rows(parentGet("/me/notifications"), "channel.post");
        assertThat(bell).hasSize(1);
        assertThat(bell.getFirst().get("link").asText()).isEqualTo("/children/" + maya + "/channel");
        assertThat(bell.getFirst().get("title").asText()).isEqualTo("Class channel: Ms Sara");
        assertThat(bell.getFirst().get("lessonId").asText()).isEqualTo(post.get("id").asText());
        assertThat(PushProbe.await(pushes, phone, 1)).hasSize(1);

        // And the teacher reads her own channel on the class page.
        assertThat(bodies(json(mvc.perform(bearer(get(staff(CLASS_1A)), sara)).andExpect(status().isOk()).andReturn()).get("posts")))
                .containsExactly("Bring a ruler tomorrow.");
    }

    @Test void the_same_clientId_posts_once_and_tells_nobody_twice() throws Exception {
        String body = "{\"body\":\"Swimming on Thursday.\",\"clientId\":\"c-same\"}";
        String first = json(mvc.perform(bearer(post(staff(CLASS_1A) + "/posts"), sara).contentType(MediaType.APPLICATION_JSON)
                .content(body)).andExpect(status().isCreated()).andReturn()).get("id").asText();
        String again = json(mvc.perform(bearer(post(staff(CLASS_1A) + "/posts"), sara).contentType(MediaType.APPLICATION_JSON)
                .content(body)).andExpect(status().isCreated()).andReturn()).get("id").asText();
        assertThat(again).isEqualTo(first);
        assertThat(parentGet("/children/" + maya + "/channel").get("posts")).hasSize(1);
        assertThat(rows(parentGet("/me/notifications"), "channel.post")).hasSize(1);
        assertThat(PushProbe.await(pushes, phone, 1)).hasSize(1);
    }

    @Test void the_unread_count_and_the_read_mark_are_each_parents_own() throws Exception {
        postTo(CLASS_1A, sara, "One");
        postTo(CLASS_1A, sara, "Two");
        assertThat(parentGet("/children/" + maya + "/channel").get("unread").asInt()).isEqualTo(2);

        assertThat(parentPost("/children/" + maya + "/channel/read", "").get("unread").asInt()).isZero();
        assertThat(parentGet("/children/" + maya + "/channel").get("unread").asInt()).isZero();

        postTo(CLASS_1A, sara, "Three");
        assertThat(parentGet("/children/" + maya + "/channel").get("unread").asInt())
                .as("a post written after her mark").isEqualTo(1);
        // The other parent of 1A has read nothing, so her count is still every post.
        assertThat(json(mvc.perform(get("/children/" + rania + "/channel").header("Authorization", OTHER_PARENT))
                .andExpect(status().isOk()).andReturn()).get("unread").asInt()).isEqualTo(3);
    }

    // ---------------------------------------------------------------- everybody else gets 404

    @Test void a_teacher_who_does_not_teach_the_class_is_not_found() throws Exception {
        postTo(CLASS_1A, sara, "Bring a ruler");
        mvc.perform(bearer(get(staff(CLASS_1A)), rula)).andExpect(status().isNotFound());
        mvc.perform(bearer(post(staff(CLASS_1A) + "/posts"), rula).contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"hello\"}")).andExpect(status().isNotFound());
        // Another school's teacher does not even see that the class exists.
        mvc.perform(bearer(get(staff(CLASS_1A)), other)).andExpect(status().isNotFound());
        // Her own 1B channel is hers, and the homeroom teacher of a section may post in it too.
        mvc.perform(bearer(post(staff(CLASS_1B) + "/posts"), rula).contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"Art tomorrow.\"}")).andExpect(status().isCreated());
    }

    @Test void a_parent_of_another_class_is_not_found_for_the_post_and_for_its_file() throws Exception {
        String file = upload(noor, CLASS_1B);
        mvc.perform(bearer(post(staff(CLASS_1B) + "/posts"), noor).contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"1B stays inside today.\",\"attachmentIds\":[\"" + file + "\"]}")).andExpect(status().isCreated());

        // Rania's parent has a child in 1A only: 1B's post is in no page of hers, and its file is 404.
        assertThat(json(mvc.perform(get("/children/" + rania + "/channel").header("Authorization", OTHER_PARENT))
                .andExpect(status().isOk()).andReturn()).get("posts")).isEmpty();
        mvc.perform(get("/media/attachments/" + file).header("Authorization", OTHER_PARENT)).andExpect(status().isNotFound());
        // The test's own parent has Omar in 1B, so for her the very same post and file are hers to read.
        assertThat(bodies(parentGet("/children/" + omar + "/channel").get("posts"))).containsExactly("1B stays inside today.");
        mvc.perform(get("/media/attachments/" + file).header("Authorization", PARENT)).andExpect(status().isOk());
    }

    /**
     * A parent holds no channel write key at all: `channel.post` is TEACHER's, and the staff half of the feature is
     * behind `/teacher/**`, which refuses her before any handler of it runs. Her own half of the channel is a read
     * and a read mark, and nothing else — there is no parent write route to find.
     */
    @Test void a_parent_cannot_post_in_the_channel() throws Exception {
        mvc.perform(post(staff(CLASS_1A) + "/posts").header("Authorization", PARENT).contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"Can we have a trip?\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/children/" + maya + "/channel").header("Authorization", PARENT).contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"Can we have a trip?\"}")).andExpect(status().isMethodNotAllowed());
    }

    @Test void another_parents_child_has_no_channel_for_her() throws Exception {
        mvc.perform(get("/children/" + rania + "/channel").header("Authorization", PARENT)).andExpect(status().isNotFound());
        mvc.perform(post("/children/" + rania + "/channel/read").header("Authorization", PARENT)).andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- taking a post down

    @Test void the_author_and_the_admin_delete_a_post_and_another_teacher_may_not() throws Exception {
        String file = upload(sara, CLASS_1A);
        String mine = json(mvc.perform(bearer(post(staff(CLASS_1A) + "/posts"), sara).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"Wrong day, sorry.\",\"attachmentIds\":[\"" + file + "\"]}"))
                .andExpect(status().isCreated()).andReturn()).get("id").asText();

        // Noor teaches 1A too and reads the post, but it is not hers to take down.
        mvc.perform(bearer(delete(staff(CLASS_1A) + "/posts/" + mine), noor)).andExpect(status().isForbidden());
        mvc.perform(bearer(delete(staff(CLASS_1A) + "/posts/" + mine), sara)).andExpect(status().isNoContent());

        var page = parentGet("/children/" + maya + "/channel");
        assertThat(page.get("posts")).hasSize(1);
        assertThat(page.get("posts").get(0).get("deleted").asBoolean()).isTrue();
        assertThat(page.get("posts").get(0).get("body").asText()).isEmpty();
        assertThat(page.get("posts").get(0).has("attachments")).isFalse();
        assertThat(page.get("unread").asInt()).as("a tombstone is nothing to read").isZero();
        mvc.perform(get("/media/attachments/" + file).header("Authorization", PARENT)).andExpect(status().isNotFound());

        // And the Admin moderates on the same route, with `X-School-Id`.
        String hers = json(mvc.perform(bearer(post(staff(CLASS_1A) + "/posts"), noor).contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"Please ignore that.\"}")).andExpect(status().isCreated()).andReturn()).get("id").asText();
        mvc.perform(scoped(get(staff(CLASS_1A)), adminToken, A)).andExpect(status().isOk());
        mvc.perform(scoped(delete(staff(CLASS_1A) + "/posts/" + hers), adminToken, A)).andExpect(status().isNoContent());
        assertThat(parentGet("/children/" + maya + "/channel").get("posts")).allSatisfy(
                p -> assertThat(p.get("deleted").asBoolean()).isTrue());
    }

    // ---------------------------------------------------------------- the switches, and no staff phone

    @Test void each_switch_off_answers_404() throws Exception {
        postTo(CLASS_1A, sara, "Bring a ruler");
        setFlag(adminToken, A, FlagKeys.CLASS_CHANNEL, false);
        mvc.perform(get("/children/" + maya + "/channel").header("Authorization", PARENT)).andExpect(status().isNotFound());
        mvc.perform(post("/children/" + maya + "/channel/read").header("Authorization", PARENT)).andExpect(status().isNotFound());
        mvc.perform(bearer(get(staff(CLASS_1A)), sara)).andExpect(status().isNotFound());
        mvc.perform(bearer(post(staff(CLASS_1A) + "/posts"), sara).contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"hello\"}")).andExpect(status().isNotFound());

        setFlag(adminToken, A, FlagKeys.CLASS_CHANNEL, true);
        mvc.perform(bearer(get(staff(CLASS_1A)), sara)).andExpect(status().isOk());
        // `chat` is the channel's other switch: school B has it off, so its teacher has no channel either.
        mvc.perform(bearer(get("/teacher/classes/" + CLASS_OTHER + "/channel"), other)).andExpect(status().isNotFound());
        setFlag(adminToken, A, FlagKeys.CHAT, false);
        mvc.perform(bearer(get(staff(CLASS_1A)), sara)).andExpect(status().isNotFound());
        mvc.perform(get("/children/" + maya + "/channel").header("Authorization", PARENT)).andExpect(status().isNotFound());
        setFlag(adminToken, A, FlagKeys.CHAT, true);
    }

    @Test void the_channel_carries_no_staff_phone_number() throws Exception {
        postTo(CLASS_1A, sara, "Bring a ruler tomorrow.");
        for (String body : List.of(raw(get("/children/" + maya + "/channel"), PARENT), staffRaw(CLASS_1A, sara))) {
            assertThat(keys(mapper.readTree(body))).doesNotContain("phone");
            assertThat(body).doesNotContain(PHONE_SARA);
        }
    }

    // ---------------------------------------------------------------- helpers

    private static String staff(String classId) { return "/teacher/classes/" + classId + "/channel"; }

    private void postTo(String classId, String token, String body) throws Exception {
        mvc.perform(bearer(post(staff(classId) + "/posts"), token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":" + quote(body) + "}")).andExpect(status().isCreated());
    }

    private String upload(String token, String classId) throws Exception {
        var file = new MockMultipartFile("file", "board.png", "image/png", png());
        return json(mvc.perform(multipart(staff(classId) + "/attachments").file(file).header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated()).andReturn()).get("id").asText();
    }

    private String raw(MockHttpServletRequestBuilder builder, String bearer) throws Exception {
        return mvc.perform(builder.header("Authorization", bearer)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private String staffRaw(String classId, String token) throws Exception {
        return raw(get(staff(classId)), "Bearer " + token);
    }

    private static MockHttpServletRequestBuilder bearer(MockHttpServletRequestBuilder builder, String token) {
        return builder.header("Authorization", "Bearer " + token);
    }

    private static MockHttpServletRequestBuilder scoped(MockHttpServletRequestBuilder builder, String token, String schoolId) {
        return bearer(builder, token).header(TenantContext.HEADER, schoolId);
    }

    private static byte[] png() throws Exception {
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    private static List<String> bodies(JsonNode posts) {
        var out = new ArrayList<String>();
        posts.forEach(p -> out.add(p.get("body").asText()));
        return out;
    }

    private static List<JsonNode> rows(JsonNode notifications, String kind) {
        var out = new ArrayList<JsonNode>();
        notifications.forEach(r -> { if (kind.equals(r.path("kind").asText())) out.add(r); });
        return out;
    }

    /** Every field name in the answer, however deep — what "no post carries a phone number" is asserted against. */
    private static List<String> keys(JsonNode node) {
        var out = new ArrayList<String>();
        node.fieldNames().forEachRemaining(out::add);
        node.forEach(child -> out.addAll(keys(child)));
        return out;
    }
}
