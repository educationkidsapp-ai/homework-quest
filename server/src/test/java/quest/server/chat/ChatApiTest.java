package quest.server.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import quest.api.dto.NotificationKind;
import quest.server.flags.FlagKeys;
import quest.server.push.ParentDeviceRepository;
import quest.server.push.PushProbe;
import quest.server.push.PushSender;
import quest.server.tenancy.TenantContext;

/**
 * C1's REST half and its three promises: a parent reaches only her own child's teachers, a teacher only the
 * children of her sections, and a school with the flag off has no chat at all. Then the mechanics — paging both
 * ways, unread counts and read receipts, the 30-a-minute limit, the plain-text body rule — and the Admin's
 * read-only window.
 */
class ChatApiTest extends ChatTestSupport {
    private String maya, omar;
    @Autowired PushSender pushes;
    @Autowired ParentDeviceRepository devices;

    @BeforeEach void seed() throws Exception {
        seedSchools();
        maya = child("Maya", "CHSCHA", section1a);
        omar = child("Omar", "CHSCHA", section1b);
    }

    @AfterEach void clean() { removeSeed(); }

    // ---------------------------------------------------------------- who reaches whom

    @Test void a_parent_lists_the_teachers_of_her_childs_section_and_nobody_else() throws Exception {
        var rows = parentGet("/children/" + maya + "/chat/threads");
        assertThat(rows).extracting(r -> r.get("teacherId").asText()).containsExactlyInAnyOrder(SARA, NOOR);
        var sara = row(rows, SARA);
        assertThat(sara.hasNonNull("id")).as("no thread until somebody writes").isFalse();
        assertThat(sara.get("teacherName").asText()).isEqualTo("Ms Sara");
        assertThat(sara.get("className").asText()).isEqualTo("1A");
        assertThat(sara.get("subject").asText()).isEqualTo("math");
        assertThat(sara.get("unread").asInt()).isZero();
        assertThat(sara.get("childName").asText()).isEqualTo("Maya");
        // Omar is on 1B, which Sara does not teach
        assertThat(parentGet("/children/" + omar + "/chat/threads")).extracting(r -> r.get("teacherId").asText()).containsExactly(NOOR);
    }

    @Test void another_parents_child_is_a_404_and_an_unplaced_child_a_409() throws Exception {
        String theirs = someoneElsesChild("Theirs");
        mvc.perform(parent(get("/children/" + theirs + "/chat/threads"))).andExpect(status().isNotFound());
        mvc.perform(parent(post("/children/" + theirs + "/chat/threads/" + SARA + "/messages")).contentType(MediaType.APPLICATION_JSON).content(send("hi")))
                .andExpect(status().isNotFound());

        String unplaced = unplacedChild("Nobody");
        mvc.perform(parent(get("/children/" + unplaced + "/chat/threads"))).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("child_not_placed"));
        mvc.perform(parent(post("/children/" + unplaced + "/chat/threads/" + SARA + "/messages")).contentType(MediaType.APPLICATION_JSON).content(send("hi")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("child_not_placed"));
        // and the teacher's side of the same rule
        mvc.perform(as(post("/teacher/chat/threads/" + unplaced + "/messages"), sara).contentType(MediaType.APPLICATION_JSON).content(send("hi")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("child_not_placed"));
    }

    @Test void a_parent_cannot_write_to_a_teacher_who_is_not_on_the_section() throws Exception {
        // Noor teaches 1B, so Omar's parent may write to her; Sara does not, and is not even listed as existing
        mvc.perform(parent(post("/children/" + omar + "/chat/threads/" + SARA + "/messages")).contentType(MediaType.APPLICATION_JSON).content(send("hi")))
                .andExpect(status().isNotFound());
        mvc.perform(parent(get("/children/" + omar + "/chat/threads/" + OTHER + "/messages"))).andExpect(status().isNotFound());
    }

    @Test void a_teacher_reaches_only_the_children_of_her_own_sections() throws Exception {
        // Sara teaches 1A: Maya yes, Omar (1B) no
        mvc.perform(as(post("/teacher/chat/threads/" + maya + "/messages"), sara).contentType(MediaType.APPLICATION_JSON).content(send("Welcome!")))
                .andExpect(status().isCreated());
        mvc.perform(as(post("/teacher/chat/threads/" + omar + "/messages"), sara).contentType(MediaType.APPLICATION_JSON).content(send("Welcome!")))
                .andExpect(status().isForbidden());
        mvc.perform(as(get("/teacher/chat/threads/" + omar + "/messages"), sara)).andExpect(status().isForbidden());
        // another school's teacher: the filter hides the child entirely
        mvc.perform(as(get("/teacher/chat/threads/" + maya + "/messages"), other)).andExpect(status().isNotFound());
        // her list is only her own threads
        mvc.perform(as(get("/teacher/chat/threads"), noor)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        var mine = json(mvc.perform(as(get("/teacher/chat/threads"), sara)).andExpect(status().isOk()).andReturn());
        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).get("childId").asText()).isEqualTo(maya);
        assertThat(mine.get(0).get("teacherName").asText()).isEqualTo("Ms Sara");
        assertThat(mine.get(0).get("lastMessage").get("body").asText()).isEqualTo("Welcome!");
        // N1 `peerRole`: the teacher is talking to a parent, and the parent's row of the same thread to a teacher.
        assertThat(mine.get(0).get("peerRole").asText()).isEqualTo("PARENT");
        for (var row : json(mvc.perform(parent(get("/children/" + maya + "/chat/threads"))).andExpect(status().isOk()).andReturn()))
            assertThat(row.get("peerRole").asText()).as("the parent's row with %s", row.get("teacherName")).isEqualTo("TEACHER");
    }

    @Test void the_flag_off_is_a_404_on_every_route() throws Exception {
        mvc.perform(as(get("/teacher/chat/threads"), other)).andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("No such endpoint."));
        mvc.perform(as(get("/admin/chat/threads"), adminToken).header(TenantContext.HEADER, B)).andExpect(status().isNotFound());
        setFlag(adminToken, A, FlagKeys.CHAT, false);
        try { mvc.perform(parent(get("/children/" + maya + "/chat/threads"))).andExpect(status().isNotFound()); }
        finally { setFlag(adminToken, A, FlagKeys.CHAT, true); }
    }

    // ---------------------------------------------------------------- the conversation

    @Test void messages_land_with_unread_counts_and_read_receipts_both_ways() throws Exception {
        var sent = parentPost("/children/" + maya + "/chat/threads/" + SARA + "/messages", send("Hello Ms Sara, <b>not bold</b>", "c-1"));
        assertThat(sent.get("sender").asText()).isEqualTo("parent");
        assertThat(sent.get("body").asText()).as("plain text, stored as typed").isEqualTo("Hello Ms Sara, <b>not bold</b>");
        assertThat(sent.hasNonNull("readAt")).isFalse();
        String threadId = sent.get("threadId").asText();

        var saraRows = json(mvc.perform(as(get("/teacher/chat/threads"), sara)).andReturn());
        assertThat(saraRows.get(0).get("id").asText()).isEqualTo(threadId);
        assertThat(saraRows.get(0).get("unread").asInt()).isEqualTo(1);
        assertThat(row(parentGet("/children/" + maya + "/chat/threads"), SARA).get("unread").asInt()).as("her own message is not unread for her").isZero();

        var receipt = json(mvc.perform(as(post("/teacher/chat/threads/" + maya + "/read"), sara)).andExpect(status().isOk()).andReturn());
        assertThat(receipt.get("readBy").asText()).isEqualTo("teacher");
        assertThat(receipt.get("threadId").asText()).isEqualTo(threadId);
        assertThat(json(mvc.perform(as(get("/teacher/chat/threads"), sara)).andReturn()).get(0).get("unread").asInt()).isZero();
        var page = parentGet("/children/" + maya + "/chat/threads/" + SARA + "/messages");
        assertThat(page.get(0).hasNonNull("readAt")).as("the parent sees her message was read").isTrue();

        mvc.perform(as(post("/teacher/chat/threads/" + maya + "/messages"), sara).contentType(MediaType.APPLICATION_JSON).content(send("Hello!"))).andExpect(status().isCreated());
        assertThat(row(parentGet("/children/" + maya + "/chat/threads"), SARA).get("unread").asInt()).isEqualTo(1);
        parentPost("/children/" + maya + "/chat/threads/" + SARA + "/read", "");
        assertThat(row(parentGet("/children/" + maya + "/chat/threads"), SARA).get("unread").asInt()).isZero();
        // a read before any message exists names no thread
        mvc.perform(parent(post("/children/" + maya + "/chat/threads/" + NOOR + "/read"))).andExpect(status().isNotFound());
    }

    @Test void pages_walk_backwards_with_before_and_forwards_with_since() throws Exception {
        for (int i = 1; i <= 7; i++) parentPost("/children/" + maya + "/chat/threads/" + SARA + "/messages", send("m" + i));
        var newest = json(mvc.perform(as(get("/teacher/chat/threads/" + maya + "/messages?limit=3"), sara)).andExpect(status().isOk()).andReturn());
        assertThat(bodies(newest)).as("oldest first within the page, newest page by default").containsExactly("m5", "m6", "m7");
        var older = json(mvc.perform(as(get("/teacher/chat/threads/" + maya + "/messages?limit=3&before=" + newest.get(0).get("id").asText()), sara)).andReturn());
        assertThat(bodies(older)).containsExactly("m2", "m3", "m4");
        var oldest = json(mvc.perform(as(get("/teacher/chat/threads/" + maya + "/messages?limit=3&before=" + older.get(0).get("id").asText()), sara)).andReturn());
        assertThat(bodies(oldest)).containsExactly("m1");
        // the reconnect refetch: everything after the last message the client holds
        var gap = json(mvc.perform(as(get("/teacher/chat/threads/" + maya + "/messages?since=" + older.get(2).get("id").asText()), sara)).andReturn());
        assertThat(bodies(gap)).containsExactly("m5", "m6", "m7");
        // a cursor that is not a message of this thread is refused, and the page size is clamped
        mvc.perform(as(get("/teacher/chat/threads/" + maya + "/messages?before=not-a-message"), sara)).andExpect(status().isBadRequest());
        assertThat(parentGet("/children/" + maya + "/chat/threads/" + SARA + "/messages?limit=9999")).hasSize(7);
        assertThat(parentGet("/children/" + maya + "/chat/threads/" + NOOR + "/messages")).as("no thread yet: an empty page, not a 404").isEmpty();
    }

    @Test void the_thirty_first_message_in_a_minute_is_a_429() throws Exception {
        for (int i = 0; i < 30; i++) parentPost("/children/" + maya + "/chat/threads/" + SARA + "/messages", send("m" + i));
        mvc.perform(parent(post("/children/" + maya + "/chat/threads/" + SARA + "/messages")).contentType(MediaType.APPLICATION_JSON).content(send("one more")))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("rate_limited"));
        // the limit is per sender: the teacher still writes
        mvc.perform(as(post("/teacher/chat/threads/" + maya + "/messages"), sara).contentType(MediaType.APPLICATION_JSON).content(send("fine"))).andExpect(status().isCreated());
    }

    @Test void the_body_is_plain_text_between_one_and_two_thousand_characters() throws Exception {
        var refuse = post("/children/" + maya + "/chat/threads/" + SARA + "/messages");
        mvc.perform(parent(refuse).contentType(MediaType.APPLICATION_JSON).content(send("   \n "))).andExpect(status().isBadRequest());
        mvc.perform(parent(refuse).contentType(MediaType.APPLICATION_JSON).content(send("x".repeat(2001)))).andExpect(status().isBadRequest());
        mvc.perform(parent(refuse).contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"wrong field\"}")).andExpect(status().isBadRequest());
        var ok = parentPost("/children/" + maya + "/chat/threads/" + SARA + "/messages", send("  " + "y".repeat(2000) + "  "));
        assertThat(ok.get("body").asText()).hasSize(2000);
        var controls = parentPost("/children/" + maya + "/chat/threads/" + SARA + "/messages", send("line one\nline two" + (char) 7));
        assertThat(controls.get("body").asText()).as("line breaks stay, other control characters go").isEqualTo("line one\nline two");
    }

    // ---------------------------------------------------------------- support

    @Test void the_admin_reads_a_schools_threads_with_the_header_and_nothing_without_it() throws Exception {
        var sent = parentPost("/children/" + maya + "/chat/threads/" + SARA + "/messages", send("Question about homework"));
        var rows = json(mvc.perform(as(get("/admin/chat/threads"), adminToken).header(TenantContext.HEADER, A)).andExpect(status().isOk()).andReturn());
        assertThat(rows).extracting(r -> r.get("id").asText()).contains(sent.get("threadId").asText());
        var page = json(mvc.perform(as(get("/admin/chat/threads/" + sent.get("threadId").asText() + "/messages"), adminToken).header(TenantContext.HEADER, A)).andExpect(status().isOk()).andReturn());
        assertThat(bodies(page)).containsExactly("Question about homework");
        // without the header an Admin reads the flag defaults, and `chat` is off by default: the gate answers first
        mvc.perform(as(get("/admin/chat/threads"), adminToken)).andExpect(status().isNotFound());
        mvc.perform(as(get("/admin/chat/threads"), sara).header(TenantContext.HEADER, A)).andExpect(status().isForbidden());
    }

    @Test void deleting_the_child_takes_her_threads_with_her() throws Exception {
        var sent = parentPost("/children/" + maya + "/chat/threads/" + SARA + "/messages", send("bye"));
        mvc.perform(as(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/admin/children/" + maya), adminToken).header(TenantContext.HEADER, A))
                .andExpect(status().is2xxSuccessful());
        assertThat(threadRows.findById(sent.get("threadId").asText())).isEmpty();
        assertThat(messageRows.findById(sent.get("id").asText())).isEmpty();
    }

    /** B3 (D5): a staff member's message is a notification for the parent too — one unread per thread, read with it. */
    @Test void a_teachers_messages_reach_the_parents_notifications_once_per_thread_until_she_reads_it() throws Exception {
        mvc.perform(as(post("/teacher/chat/threads/" + maya + "/messages"), sara).contentType(MediaType.APPLICATION_JSON).content(send("Hello!"))).andExpect(status().isCreated());
        mvc.perform(as(post("/teacher/chat/threads/" + maya + "/messages"), sara).contentType(MediaType.APPLICATION_JSON).content(send("Bring a ruler."))).andExpect(status().isCreated());

        var rows = parentGet("/me/notifications");
        assertThat(rows).as("one row for the thread, not one per message").hasSize(1);
        assertThat(rows.get(0).get("kind").asText()).isEqualTo("chat.message");
        assertThat(rows.get(0).get("childId").asText()).isEqualTo(maya);
        assertThat(rows.get(0).get("body").asText()).isEqualTo("Bring a ruler.");
        assertThat(rows.get(0).get("link").asText()).isEqualTo("/children/" + maya + "/chat/" + SARA);
        assertThat(parentGet("/me/notifications/unread-count").get("count").asInt()).isEqualTo(1);
        assertThat(json(mvc.perform(as(get("/me/notifications"), sara)).andExpect(status().isOk()).andReturn()))
                .as("the teacher's own bell never carries the parent's row").noneMatch(r -> maya.equals(r.path("childId").asText(null)));

        parentPost("/children/" + maya + "/chat/threads/" + SARA + "/read", "");
        assertThat(parentGet("/me/notifications/unread-count").get("count").asInt()).as("reading the thread reads its row").isZero();
        parentPost("/children/" + maya + "/chat/threads/" + SARA + "/messages", send("Will do", "c-9"));
        assertThat(parentGet("/me/notifications/unread-count").get("count").asInt()).as("her own message is not news to her").isZero();
    }

    /**
     * B4: the bell's throttle is the push's — one push per new unread row, carrying that row, and none to another parent.
     * Reading the thread re-arms it, and a push that fails fails nothing: the message is sent and the row written.
     */
    @Test void a_teachers_messages_push_the_parent_once_per_unread_row() throws Exception {
        String phone = PushProbe.token("ch-phone"), others = PushProbe.token("ch-other-phone");
        PushProbe.register(mvc, PARENT, phone, "en");
        PushProbe.register(mvc, OTHER_PARENT, others, null);
        var sent = json(mvc.perform(as(post("/teacher/chat/threads/" + maya + "/messages"), sara).contentType(MediaType.APPLICATION_JSON).content(send("Hello!")))
                .andExpect(status().isCreated()).andReturn());
        mvc.perform(as(post("/teacher/chat/threads/" + maya + "/messages"), sara).contentType(MediaType.APPLICATION_JSON).content(send("Bring a ruler."))).andExpect(status().isCreated());

        var pushed = PushProbe.await(pushes, phone, 1);
        assertThat(pushed).as("two messages, one unread row, one push").hasSize(1);
        var push = pushed.get(0).message();
        var row = parentGet("/me/notifications").get(0);
        assertThat(push.getKind()).isEqualTo(NotificationKind.CHAT_MESSAGE);
        assertThat(push.getNotificationId()).isEqualTo(row.get("id").asText());
        assertThat(push.getChildId()).isEqualTo(maya);
        assertThat(push.getLink()).isEqualTo("/children/" + maya + "/chat/" + SARA);
        assertThat(push.getTitle()).isEqualTo("Message from Ms Sara");
        assertThat(push.getBody()).as("the row as it was when it was new").isEqualTo("Hello!");
        assertThat(push.getCollapseKey()).isEqualTo("chat:" + sent.get("threadId").asText());
        assertThat(PushProbe.sentTo(pushes, others)).as("another parent hears nothing").isEmpty();

        parentPost("/children/" + maya + "/chat/threads/" + SARA + "/read", "");
        PushProbe.script(pushes, phone, PushSender.Outcome.FAILED);
        mvc.perform(as(post("/teacher/chat/threads/" + maya + "/messages"), sara).contentType(MediaType.APPLICATION_JSON).content(send("See you."))).andExpect(status().isCreated());
        assertThat(PushProbe.await(pushes, phone, 2)).as("FCM failed: nothing delivered").hasSize(1);
        assertThat(parentGet("/me/notifications/unread-count").get("count").asInt()).as("and the row is written all the same").isEqualTo(1);

        parentPost("/children/" + maya + "/chat/threads/" + SARA + "/read", "");
        mvc.perform(as(post("/teacher/chat/threads/" + maya + "/messages"), sara).contentType(MediaType.APPLICATION_JSON).content(send("Bye!"))).andExpect(status().isCreated());
        assertThat(PushProbe.await(pushes, phone, 2)).as("read, then a new message: a new push").hasSize(2);
        devices.deleteByTokenValue(phone); devices.deleteByTokenValue(others);
    }

    // ---------------------------------------------------------------- helpers

    private MockHttpServletRequestBuilder parent(MockHttpServletRequestBuilder b) { return b.header("Authorization", PARENT); }
    private static JsonNode row(JsonNode rows, String teacherId) { for (var r : rows) if (teacherId.equals(r.get("teacherId").asText())) return r; throw new AssertionError("no row for " + teacherId); }
    private static List<String> bodies(JsonNode page) { var out = new ArrayList<String>(); page.forEach(m -> out.add(m.get("body").asText())); return out; }
}
