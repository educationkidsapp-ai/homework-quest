package quest.server.staff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import quest.server.auth.Entities.UserEntity;
import quest.server.chat.ChatTestSupport;
import quest.server.flags.FlagKeys;

/**
 * Feature 13: the `/staff` facade. What it promises is that it adds no rules of its own — the teacher sees through it
 * exactly the threads her own dashboard routes give her, and sends and reads land on the same rows — and that it is
 * shut to everybody it should be shut to: a parent's token, a school with `staffMobileApp` off, a thread of another
 * school, and a worker, who has no conversations yet and no dashboard either.
 */
class StaffApiTest extends ChatTestSupport {
    private static final String WORKER = "ch-worker-hassan";
    private String maya, worker, threadId;

    @BeforeEach void seed() throws Exception {
        seedSchools();
        setFlag(adminToken, A, FlagKeys.STAFF_MOBILE_APP, true);
        maya = child("Maya", "CHSCHA", section1a);
        var row = users.findById(WORKER).orElseGet(UserEntity::new);
        row.setId(WORKER); row.setSchoolId(A); row.setEmail("w.hassan.0001"); row.setPasswordHash("x");
        row.setRole("WORKER"); row.setStatus("active"); row.setDisplayName("Hassan Ali");
        if (row.getCreatedAt() == null) row.setCreatedAt(Instant.now());
        row.setUpdatedAt(Instant.now());
        users.save(row);
        worker = token(WORKER, "WORKER", A);
        // A parent writes to Sara, so there is a thread to find through both doors.
        parentPost("/children/" + maya + "/chat/threads/" + SARA + "/messages", send("Is Maya all right today?"));
        threadId = json(mvc.perform(as(get("/teacher/chat/threads"), sara)).andExpect(status().isOk()).andReturn())
                .get(0).get("id").asText();
    }

    /** The flag goes back off in both schools: a test class that runs after this one starts from the default. */
    @AfterEach void clean() throws Exception {
        setFlag(adminToken, A, FlagKeys.STAFF_MOBILE_APP, false);
        setFlag(adminToken, B, FlagKeys.STAFF_MOBILE_APP, false);
        removeSeed();
    }

    @Test void a_parent_token_is_refused_on_every_staff_route() throws Exception {
        for (var route : new String[][] {{"GET", "/staff/me"}, {"GET", "/staff/chat/threads"},
                {"GET", "/staff/chat/threads/" + threadId + "/messages"}, {"POST", "/staff/chat/threads/" + threadId + "/messages"},
                {"POST", "/staff/chat/threads/" + threadId + "/read"}}) {
            var request = route[0].equals("GET") ? get(route[1])
                    : post(route[1]).contentType(MediaType.APPLICATION_JSON).content(send("Hello"));
            mvc.perform(request.header("Authorization", PARENT)).andExpect(status().isForbidden());
        }
    }

    @Test void the_facade_is_not_there_while_the_school_has_the_flag_off() throws Exception {
        setFlag(adminToken, A, FlagKeys.STAFF_MOBILE_APP, false);
        mvc.perform(as(get("/staff/me"), sara)).andExpect(status().isNotFound());
        mvc.perform(as(get("/staff/chat/threads"), sara)).andExpect(status().isNotFound());
    }

    @Test void staff_me_names_her_school_and_what_it_has_switched_on() throws Exception {
        var me = json(mvc.perform(as(get("/staff/me"), sara)).andExpect(status().isOk()).andReturn());
        assertThat(me.get("userId").asText()).isEqualTo(SARA);
        assertThat(me.get("role").asText()).isEqualTo("TEACHER");
        assertThat(me.get("displayName").asText()).isEqualTo("Ms Sara");
        assertThat(me.get("schoolId").asText()).isEqualTo(A);
        assertThat(me.get("schoolName").asText()).isEqualTo("Chat Academy");
        assertThat(me.get("flags").get(FlagKeys.STAFF_MOBILE_APP).asBoolean()).isTrue();
        assertThat(me.get("flags").get(FlagKeys.CHAT).asBoolean()).isTrue();
    }

    @Test void a_teacher_sees_the_threads_her_own_dashboard_routes_give_her() throws Exception {
        var dashboard = json(mvc.perform(as(get("/teacher/chat/threads"), sara)).andExpect(status().isOk()).andReturn());
        var app = json(mvc.perform(as(get("/staff/chat/threads"), sara)).andExpect(status().isOk()).andReturn());
        assertThat(ids(app)).isEqualTo(ids(dashboard)).containsExactly(threadId);
        assertThat(app.get(0).get("childName").asText()).isEqualTo("Maya");
    }

    @Test void she_sends_and_reads_through_the_facade_onto_the_same_rows() throws Exception {
        var sent = json(mvc.perform(as(post("/staff/chat/threads/" + threadId + "/messages"), sara)
                .contentType(MediaType.APPLICATION_JSON).content(send("She is doing very well."))).andExpect(status().isCreated()).andReturn());
        assertThat(sent.get("threadId").asText()).isEqualTo(threadId);
        assertThat(sent.get("sender").asText()).isEqualTo("teacher");

        var page = json(mvc.perform(as(get("/staff/chat/threads/" + threadId + "/messages"), sara)).andExpect(status().isOk()).andReturn());
        assertThat(page).hasSize(2);
        // The same page the dashboard's own route serves, which is the whole point of a facade.
        var dashboard = json(mvc.perform(as(get("/teacher/chat/threads/" + maya + "/messages"), sara)).andExpect(status().isOk()).andReturn());
        assertThat(page.toString()).isEqualTo(dashboard.toString());

        var receipt = json(mvc.perform(as(post("/staff/chat/threads/" + threadId + "/read"), sara)).andExpect(status().isOk()).andReturn());
        assertThat(receipt.get("threadId").asText()).isEqualTo(threadId);
        assertThat(json(mvc.perform(as(get("/staff/chat/threads"), sara)).andExpect(status().isOk()).andReturn())
                .get(0).get("unread").asInt()).isZero();
    }

    @Test void a_thread_of_another_school_is_not_found() throws Exception {
        setFlag(adminToken, B, FlagKeys.CHAT, true);
        setFlag(adminToken, B, FlagKeys.STAFF_MOBILE_APP, true);
        mvc.perform(as(get("/staff/chat/threads/" + threadId + "/messages"), other)).andExpect(status().isNotFound());
        mvc.perform(as(post("/staff/chat/threads/" + threadId + "/read"), other)).andExpect(status().isNotFound());
        mvc.perform(as(post("/staff/chat/threads/" + threadId + "/messages"), other)
                .contentType(MediaType.APPLICATION_JSON).content(send("Hello"))).andExpect(status().isNotFound());
    }

    @Test void a_worker_has_an_empty_inbox_and_no_dashboard_at_all() throws Exception {
        var me = json(mvc.perform(as(get("/staff/me"), worker)).andExpect(status().isOk()).andReturn());
        assertThat(me.get("role").asText()).isEqualTo("WORKER");
        assertThat(json(mvc.perform(as(get("/staff/chat/threads"), worker)).andExpect(status().isOk()).andReturn())).isEmpty();
        mvc.perform(as(get("/staff/chat/threads/" + threadId + "/messages"), worker)).andExpect(status().isNotFound());

        for (String path : new String[] {"/me", "/me/permissions", "/teacher/chat/threads", "/teacher/profile", "/admin/workers"})
            mvc.perform(as(get(path), worker)).andExpect(status().isForbidden());
        // Her bell is the one `/me` area she keeps: it is what a push takes her to.
        mvc.perform(as(get("/me/notifications"), worker)).andExpect(status().isOk());
    }

    private static java.util.List<String> ids(com.fasterxml.jackson.databind.JsonNode rows) {
        var out = new java.util.ArrayList<String>();
        rows.forEach(r -> out.add(r.hasNonNull("id") ? r.get("id").asText() : null));
        return out;
    }
}
