package quest.server.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import quest.api.dto.NotificationKind;
import quest.server.ApiTestSupport;
import quest.server.auth.AdminJwtService;
import quest.server.auth.Entities.UserEntity;
import quest.server.auth.TeacherRepository;
import quest.server.auth.UserRepository;
import quest.server.children.ChildRepository;
import quest.server.classes.SchoolSeed;
import quest.server.flags.FlagKeys;
import quest.server.push.PushProbe;
import quest.server.tenancy.ClassRepository;
import quest.server.tenancy.Entities.SchoolEntity;
import quest.server.tenancy.SchoolRepository;
import quest.server.tenancy.StaffScopeRepository;
import quest.server.tenancy.TeachingAssignmentRepository;

/**
 * B6 (owner, 2026-10-03): "Complaints must be separate from messages." On the acceptance fixture `CoordinatorCommsApiTest`
 * uses — Maya teaches math in 1A and 1B British, Rami english in 1A American, Lina coordinates math/British, Omar
 * english/American, Nour manages British and Sami American — a British parent complains to Maya, Lina and Nour.
 *
 * <p>What it proves: a complaint is its own conversation (never the Messages thread with the same person, and never on
 * a Messages list — nor a Messages thread on a Complaints list); the recipient answers, resolves and reopens it, the
 * parent reopens it but cannot resolve it, a supervisor reads and moves it but does not write in it; every change is an
 * event with who and when, a socket `status` frame and a `complaint.status` row for the other side; the new complaint,
 * each reply and each change ring the right bell with the right link; and everyone outside the scope is 404.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ComplaintApiTest extends ApiTestSupport {
    private static final String SCHOOL = "cmp-school", OTHER_SCHOOL = "cmp-school-b";
    private static final String BRITISH_PARENT = "cmp-parent-1a", AMERICAN_PARENT = "cmp-parent-us";

    @Autowired SchoolSeed seed;
    @Autowired SchoolRepository schools;
    @Autowired ClassRepository classes;
    @Autowired TeachingAssignmentRepository assignments;
    @Autowired StaffScopeRepository staffScopes;
    @Autowired UserRepository users;
    @Autowired TeacherRepository teacherProfiles;
    @Autowired ChildRepository childRows;
    @Autowired ChatThreadRepository threadRows;
    @Autowired ChatMessageRepository messageRows;
    @Autowired ComplaintEventRepository eventRows;
    @Autowired ChatSessions sessions;
    @Autowired quest.server.push.PushSender pushes;
    @Autowired quest.server.push.ParentDeviceRepository devices;
    @Autowired AdminJwtService jwt;
    @Autowired quest.server.notifications.NotificationRepository notificationRows;
    @Autowired quest.server.flags.SchoolFlagRepository schoolFlags;
    @Autowired quest.server.flags.FeatureFlags featureFlags;
    @Autowired javax.sql.DataSource dataSource;

    private String maya, rami, lina, nour, sami, britishA, americanA, childBritish, childAmerican, britishParentId;
    private String mayaComplaint, phone;

    @BeforeAll void loadTheSchool() throws Exception {
        school(SCHOOL, "Complaint School", "CMPS1");
        school(OTHER_SCHOOL, "Other Complaint School", "CMPS2");
        seed.load(SCHOOL, "cmp-pass", false, "seed/acceptance/");
        maya = idOf("maya@test.com"); rami = idOf("rami@test.com"); lina = idOf("coord.math@test.com");
        nour = idOf("manager@test.com"); sami = idOf("manager2@test.com");
        britishA = sectionId("1A British"); americanA = sectionId("1A American");
        childBritish = child("Lila", britishA, "british", BRITISH_PARENT);
        childAmerican = child("Hana", americanA, "american", AMERICAN_PARENT);
        britishParentId = childRows.findById(childBritish).orElseThrow().getParentId();
        for (String school : List.of(SCHOOL, OTHER_SCHOOL)) flag(school, FlagKeys.CHAT, true);
        phone = PushProbe.token("cmp-phone");
        PushProbe.register(mvc, bearer(BRITISH_PARENT), phone, null);
    }

    @AfterAll void takeItBackOut() {
        devices.deleteByTokenValue(phone);
        notificationRows.deleteAll(notificationRows.findAll().stream().filter(n -> n.getSchoolId() != null && n.getSchoolId().startsWith("cmp-")).toList());
        eventRows.deleteAll(eventRows.findAll().stream().filter(e -> e.getSchoolId().startsWith("cmp-")).toList());
        messageRows.deleteAll(messageRows.findAll().stream().filter(m -> m.getSchoolId().startsWith("cmp-")).toList());
        threadRows.deleteAll(threadRows.findAll().stream().filter(t -> t.getSchoolId().startsWith("cmp-")).toList());
        childRows.deleteAll(childRows.findAll().stream().filter(c -> c.getSchoolId().startsWith("cmp-")).toList());
        assignments.deleteAll(assignments.findAll().stream().filter(a -> SCHOOL.equals(a.getSchoolId())).toList());
        staffScopes.deleteAll(staffScopes.findAll().stream().filter(r -> r.getSchoolId().startsWith("cmp-")).toList());
        classes.deleteAll(classes.findAll().stream().filter(k -> k.getSchoolId().startsWith("cmp-")).toList());
        var staff = new ArrayList<UserEntity>(users.findBySchoolId(SCHOOL));
        teacherProfiles.deleteAll(teacherProfiles.findAllById(staff.stream().map(UserEntity::getId).toList()));
        users.deleteAll(staff);
    }

    // ---------------------------------------------------------------- separate from Messages

    @Test @Order(1) void the_parent_is_offered_the_sections_teachers_its_coordinator_and_its_manager() throws Exception {
        var rows = parentJson(BRITISH_PARENT, "/children/" + childBritish + "/complaints/recipients");
        assertThat(names(rows, "staffId")).containsExactlyInAnyOrder(maya, lina, nour);
        assertThat(rowWith(rows, "staffId", maya).get("role").asText()).isEqualTo("TEACHER");
        assertThat(rowWith(rows, "staffId", maya).get("subject").asText()).isEqualTo("math");
        assertThat(rowWith(rows, "staffId", lina).get("role").asText()).isEqualTo("COORDINATOR");
        assertThat(rowWith(rows, "staffId", nour).get("role").asText()).isEqualTo("MANAGERIAL");
    }

    @Test @Order(2) void a_complaint_is_its_own_conversation_and_never_on_a_messages_list() throws Exception {
        var mayaSocket = listen("user:" + maya, SCHOOL);
        // She already has a Messages thread with Maya; the complaint is a second conversation beside it.
        String question = parentPostJson(BRITISH_PARENT, "/children/" + childBritish + "/chat/threads/" + maya + "/messages",
                "{\"body\":\"Which page is tonight's homework?\"}").get("threadId").asText();
        var created = parentPostJson(BRITISH_PARENT, "/children/" + childBritish + "/complaints",
                "{\"staffId\":\"" + maya + "\",\"title\":\"Homework is too long\",\"body\":\"Two hours every night.\",\"clientId\":\"c-1\"}");
        mayaComplaint = created.get("complaint").get("id").asText();
        assertThat(mayaComplaint).isNotEqualTo(question);
        var complaint = created.get("complaint");
        assertThat(complaint.get("title").asText()).isEqualTo("Homework is too long");
        assertThat(complaint.get("status").asText()).isEqualTo("open");
        assertThat(complaint.get("recipientRole").asText()).isEqualTo("TEACHER");
        assertThat(complaint.get("subject").asText()).isEqualTo("math");
        assertThat(complaint.get("canReply").asBoolean()).isTrue();
        assertThat(names(created.get("messages"), "body")).containsExactly("Two hours every night.");
        assertThat(framesUntil(mayaSocket, "Two hours")).as("the recipient's socket carries the first message")
                .anyMatch(f -> f.contains("\"type\":\"message\"") && f.contains(mayaComplaint));

        // Messages lists: the question thread only, on both sides and in support's.
        assertThat(names(parentJson(BRITISH_PARENT, "/children/" + childBritish + "/chat/threads"), "id")).contains(question).doesNotContain(mayaComplaint);
        assertThat(names(staffGet(maya, "TEACHER", "/teacher/chat/threads"), "id")).contains(question).doesNotContain(mayaComplaint);
        assertThat(names(adminGet("/admin/chat/threads"), "id")).contains(question).doesNotContain(mayaComplaint);
        // Complaints lists: the complaint only.
        var hers = parentJson(BRITISH_PARENT, "/children/" + childBritish + "/complaints");
        assertThat(names(hers.get("complaints"), "id")).containsExactly(mayaComplaint);
        assertThat(hers.get("open").asInt()).isEqualTo(1);
        assertThat(names(staffGet(maya, "TEACHER", "/teacher/complaints").get("complaints"), "id")).containsExactly(mayaComplaint);
        assertThat(names(adminGet("/admin/complaints").get("complaints"), "id")).containsExactly(mayaComplaint);
        assertThat(rowWith(staffGet(maya, "TEACHER", "/teacher/complaints").get("complaints"), "id", mayaComplaint).get("unread").asInt()).isEqualTo(1);

        // No Messages route opens a complaint, and no Complaints route opens a Messages thread.
        perform(as(get("/coordinator/chat/threads/" + mayaComplaint + "/messages"), token(lina, "COORDINATOR"))).andExpect(status().isNotFound());
        perform(as(get("/admin/chat/threads/" + mayaComplaint + "/messages"), adminToken()).header("X-School-Id", SCHOOL)).andExpect(status().isNotFound());
        perform(as(get("/teacher/complaints/" + question), token(maya, "TEACHER"))).andExpect(status().isNotFound());
        perform(get("/children/" + childBritish + "/complaints/" + question).header("Authorization", bearer(BRITISH_PARENT))).andExpect(status().isNotFound());

        // And marking a Messages send a complaint — what S1 did — is refused.
        perform(post("/children/" + childBritish + "/chat/threads/" + maya + "/messages").header("Authorization", bearer(BRITISH_PARENT))
                .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"This is a complaint.\",\"topic\":\"complaint\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("complaint_moved"));

        // The recipient's bell: `complaint.new`, the title as its body, her Complaints page opened on it.
        var bell = rowWith(staffGet(maya, "TEACHER", "/me/notifications"), "kind", "complaint.new");
        assertThat(bell.get("body").asText()).isEqualTo("Homework is too long");
        assertThat(bell.get("link").asText()).isEqualTo("/teacher/complaints?open=" + mayaComplaint);
        var aboutIt = new ArrayList<String>();
        for (var n : staffGet(maya, "TEACHER", "/me/notifications")) if (mayaComplaint.equals(n.path("lessonId").asText())) aboutIt.add(n.get("kind").asText());
        assertThat(aboutIt).as("one bell for the new complaint, and no chat.message for its first message").containsExactly("complaint.new");
    }

    @Test @Order(3) void the_recipient_answers_and_resolves_and_the_parent_reopens() throws Exception {
        var parentSocket = listen("parent:" + britishParentId, null);
        String path = "/teacher/complaints/" + mayaComplaint;
        String teacher = token(maya, "TEACHER");
        perform(as(post(path + "/read"), teacher)).andExpect(status().isOk());
        assertThat(json(perform(as(post(path + "/messages").contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"I will shorten it from Sunday.\"}"), teacher)).andExpect(status().isCreated()).andReturn())
                .get("sender").asText()).isEqualTo("teacher");

        // Her reply: a `complaint.message` row and push for the parent, opening the complaint, never a `chat.message`.
        var pushed = PushProbe.await(pushes, phone, 1);
        assertThat(pushed.get(0).message().getKind()).isEqualTo(NotificationKind.COMPLAINT_MESSAGE);
        assertThat(pushed.get(0).message().getComplaintId()).isEqualTo(mayaComplaint);
        assertThat(pushed.get(0).message().getCollapseKey()).isEqualTo("complaint:" + mayaComplaint);
        var row = rowWith(parentJson(BRITISH_PARENT, "/me/notifications"), "kind", "complaint.message");
        assertThat(row.get("link").asText()).isEqualTo("/children/" + childBritish + "/complaints/" + mayaComplaint);
        assertThat(row.get("childId").asText()).isEqualTo(childBritish);

        // She resolves it: the row says so and by whom, the event is recorded, both sides hear the `status` frame.
        var resolved = json(perform(as(patch(path + "/status").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"resolved\"}"), teacher))
                .andExpect(status().isOk()).andReturn());
        assertThat(resolved.get("status").asText()).isEqualTo("resolved");
        assertThat(resolved.get("resolvedByName").asText()).isEqualTo("Maya");
        assertThat(framesUntil(parentSocket, "\"type\":\"status\"")).anyMatch(f -> f.contains("\"type\":\"status\"") && f.contains("resolved"));
        assertThat(PushProbe.await(pushes, phone, 2).get(1).message().getKind()).isEqualTo(NotificationKind.COMPLAINT_STATUS);
        var status = rowWith(parentJson(BRITISH_PARENT, "/me/notifications"), "kind", "complaint.status");
        assertThat(status.get("title").asText()).isEqualTo("Complaint resolved");
        assertThat(status.get("link").asText()).isEqualTo("/children/" + childBritish + "/complaints/" + mayaComplaint);
        // Resolving it again changes nothing and tells nobody.
        perform(as(patch(path + "/status").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"resolved\"}"), teacher)).andExpect(status().isOk());
        assertThat(PushProbe.await(pushes, phone, 3)).hasSize(2);

        // The parent cannot resolve; her "thank you" does not reopen it; she reopens it herself.
        String hers = "/children/" + childBritish + "/complaints/" + mayaComplaint;
        perform(patch(hers + "/status").header("Authorization", bearer(BRITISH_PARENT)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"resolved\"}")).andExpect(status().isForbidden());
        parentPostJson(BRITISH_PARENT, hers + "/messages", "{\"body\":\"Thank you.\"}");
        assertThat(parentJson(BRITISH_PARENT, hers).get("complaint").get("status").asText()).isEqualTo("resolved");
        assertThat(rowWith(staffGet(maya, "TEACHER", "/me/notifications"), "kind", "complaint.message").get("link").asText())
                .isEqualTo("/teacher/complaints?open=" + mayaComplaint);
        var reopened = json(perform(patch(hers + "/status").header("Authorization", bearer(BRITISH_PARENT)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"open\"}")).andExpect(status().isOk()).andReturn());
        assertThat(reopened.get("status").asText()).isEqualTo("open");
        assertThat(reopened.has("resolvedAt")).isFalse();
        var told = rowWith(staffGet(maya, "TEACHER", "/me/notifications"), "kind", "complaint.status");
        assertThat(told.get("title").asText()).startsWith("Complaint reopened by ");
        assertThat(told.get("link").asText()).isEqualTo("/teacher/complaints?open=" + mayaComplaint);

        // The conversation: three messages, and the two changes with who and when — the system lines.
        var detail = parentJson(BRITISH_PARENT, hers);
        assertThat(names(detail.get("messages"), "body"))
                .containsExactly("Two hours every night.", "I will shorten it from Sunday.", "Thank you.");
        assertThat(names(detail.get("events"), "status")).containsExactly("resolved", "open");
        assertThat(names(detail.get("events"), "by")).containsExactly("staff", "parent");
        assertThat(detail.get("events").get(0).get("byName").asText()).isEqualTo("Maya");
        assertThat(detail.get("events").get(1).get("byId").asText()).isEqualTo(britishParentId);

        // Her read clears her badge and her bell entry.
        perform(post(hers + "/read").header("Authorization", bearer(BRITISH_PARENT))).andExpect(status().isOk());
        assertThat(parentJson(BRITISH_PARENT, "/children/" + childBritish + "/complaints").get("complaints").get(0).get("unread").asInt()).isZero();
    }

    @Test @Order(4) void a_supervisor_reads_and_moves_it_but_does_not_write_in_it() throws Exception {
        String coordinator = token(lina, "COORDINATOR");
        // Lina coordinates math/British and Maya teaches math on 1A British: the complaint is on Lina's page, read-only.
        var row = rowWith(json(perform(as(get("/coordinator/complaints?status=open"), coordinator)).andExpect(status().isOk()).andReturn())
                .get("complaints"), "id", mayaComplaint);
        assertThat(row.get("canReply").asBoolean()).isFalse();
        assertThat(row.get("unread").asInt()).isZero();
        assertThat(names(json(perform(as(get("/coordinator/chat/threads"), coordinator)).andReturn()), "id")).doesNotContain(mayaComplaint);
        perform(as(post("/coordinator/complaints/" + mayaComplaint + "/messages").contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"Noted.\"}"), coordinator)).andExpect(status().isForbidden());
        perform(as(post("/coordinator/complaints/" + mayaComplaint + "/read"), coordinator)).andExpect(status().isForbidden());

        // She resolves it: the parent and Maya are both told, and the event names Lina.
        int before = PushProbe.sentTo(pushes, phone).size();
        assertThat(json(perform(as(patch("/coordinator/complaints/" + mayaComplaint + "/status").contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"resolved\"}"), coordinator)).andExpect(status().isOk()).andReturn()).get("resolvedByName").asText()).isEqualTo("Lina");
        assertThat(PushProbe.await(pushes, phone, before + 1)).hasSize(before + 1);
        assertThat(staffGet(maya, "TEACHER", "/me/notifications").findValuesAsText("title")).contains("Complaint resolved by Lina");
        // Nour manages British: she supervises it too. Sami does not, and is not told it exists.
        assertThat(names(staffGet(nour, "MANAGERIAL", "/management/complaints").get("complaints"), "id")).contains(mayaComplaint);
        assertThat(names(staffGet(sami, "MANAGERIAL", "/management/complaints").get("complaints"), "id")).doesNotContain(mayaComplaint);
        perform(as(get("/management/complaints/" + mayaComplaint), token(sami, "MANAGERIAL"))).andExpect(status().isNotFound());
        perform(as(patch("/management/complaints/" + mayaComplaint + "/status").contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"open\"}"), token(sami, "MANAGERIAL"))).andExpect(status().isNotFound());
        // Filters and counts.
        var resolvedOnly = staffGet(maya, "TEACHER", "/teacher/complaints?status=resolved");
        assertThat(names(resolvedOnly.get("complaints"), "id")).containsExactly(mayaComplaint);
        assertThat(resolvedOnly.get("resolved").asInt()).isEqualTo(1);
        assertThat(staffGet(maya, "TEACHER", "/teacher/complaints?status=open").get("complaints")).isEmpty();
        perform(as(get("/teacher/complaints?status=pending"), token(maya, "TEACHER"))).andExpect(status().isBadRequest());
    }

    @Test @Order(5) void complaints_to_the_coordinator_and_the_manager_are_theirs_to_answer() throws Exception {
        String toLina = parentPostJson(BRITISH_PARENT, "/children/" + childBritish + "/complaints",
                "{\"staffId\":\"" + lina + "\",\"title\":\"Math teacher\",\"body\":\"Please check the marking.\"}").get("complaint").get("id").asText();
        String toNour = parentPostJson(BRITISH_PARENT, "/children/" + childBritish + "/complaints",
                "{\"staffId\":\"" + nour + "\",\"title\":\"The coordinator\",\"body\":\"Nobody answers.\"}").get("complaint").get("id").asText();
        var linas = staffGet(lina, "COORDINATOR", "/coordinator/complaints").get("complaints");
        assertThat(rowWith(linas, "id", toLina).get("canReply").asBoolean()).isTrue();
        assertThat(names(linas, "id")).doesNotContain(toNour);
        var nours = staffGet(nour, "MANAGERIAL", "/management/complaints").get("complaints");
        assertThat(rowWith(nours, "id", toNour).get("recipientRole").asText()).isEqualTo("MANAGERIAL");
        assertThat(rowWith(nours, "id", toNour).get("canReply").asBoolean()).isTrue();
        assertThat(rowWith(nours, "id", toLina).get("canReply").asBoolean()).as("Lina's, which Nour supervises").isFalse();
        assertThat(rowWith(staffGet(lina, "COORDINATOR", "/me/notifications"), "kind", "complaint.new").get("link").asText())
                .isEqualTo("/coordinator/complaints?open=" + toLina);
        assertThat(rowWith(staffGet(nour, "MANAGERIAL", "/me/notifications"), "kind", "complaint.new").get("link").asText())
                .isEqualTo("/management/complaints?open=" + toNour);

        String manager = token(nour, "MANAGERIAL");
        perform(as(post("/management/complaints/" + toNour + "/messages").contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"I will speak to her today.\"}"), manager)).andExpect(status().isCreated());
        perform(as(post("/management/complaints/" + toNour + "/read"), manager)).andExpect(status().isOk());
        assertThat(json(perform(as(patch("/management/complaints/" + toNour + "/status").contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"resolved\"}"), manager)).andExpect(status().isOk()).andReturn()).get("status").asText()).isEqualTo("resolved");
        assertThat(names(parentJson(BRITISH_PARENT, "/children/" + childBritish + "/complaints/" + toNour).get("messages"), "body"))
                .containsExactly("Nobody answers.", "I will speak to her today.");
        // A teacher holds no coordinator or manager key; Maya does not see Lina's complaint at all.
        perform(as(get("/management/complaints"), token(maya, "TEACHER"))).andExpect(status().isForbidden());
        perform(as(get("/teacher/complaints/" + toLina), token(maya, "TEACHER"))).andExpect(status().isNotFound());
    }

    @Test @Order(6) void nobody_outside_the_scope_reaches_a_complaint() throws Exception {
        // Rami teaches english on the American section: Maya's complaint is not his.
        perform(as(get("/teacher/complaints/" + mayaComplaint), token(rami, "TEACHER"))).andExpect(status().isNotFound());
        perform(as(post("/teacher/complaints/" + mayaComplaint + "/messages").contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"Hi\"}"), token(rami, "TEACHER"))).andExpect(status().isNotFound());
        // Another parent: not her child, and not her child's complaint.
        perform(get("/children/" + childBritish + "/complaints/" + mayaComplaint).header("Authorization", bearer(AMERICAN_PARENT))).andExpect(status().isNotFound());
        perform(get("/children/" + childAmerican + "/complaints/" + mayaComplaint).header("Authorization", bearer(AMERICAN_PARENT))).andExpect(status().isNotFound());
        assertThat(parentJson(AMERICAN_PARENT, "/children/" + childAmerican + "/complaints").get("complaints")).isEmpty();
        // She cannot complain to the British coordinator about her American child.
        perform(post("/children/" + childAmerican + "/complaints").header("Authorization", bearer(AMERICAN_PARENT)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"staffId\":\"" + lina + "\",\"title\":\"Hello\",\"body\":\"Hello?\"}")).andExpect(status().isNotFound());
        // Another school's coordinator, read through her own school: nothing.
        perform(as(get("/coordinator/complaints/" + mayaComplaint), jwt.issue("cmp-other", "o@seed.test", "COORDINATOR", OTHER_SCHOOL).token()))
                .andExpect(status().isNotFound());
        // Validation: a title is required, and support writes nothing.
        perform(post("/children/" + childBritish + "/complaints").header("Authorization", bearer(BRITISH_PARENT)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"staffId\":\"" + maya + "\",\"title\":\" \",\"body\":\"Hello\"}")).andExpect(status().isBadRequest());
        perform(as(patch("/admin/complaints/" + mayaComplaint + "/status").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"open\"}"),
                adminToken()).header("X-School-Id", SCHOOL)).andExpect(status().is4xxClientError());
    }

    /**
     * V33 on a complaint written before B6: a relabelled Messages thread (`thread_key` `''`, no title, already
     * resolved) becomes a complaint as it is — its own key, a title from its first message, its resolution as an
     * event — and leaves the Messages list, so the parent's next message to Rami's colleague opens a fresh thread.
     * Run twice, it changes nothing the second time.
     */
    @Test @Order(7) void v33_turns_an_old_complaint_thread_into_a_complaint_once() throws Exception {
        String id = "cmp-legacy-" + UUID.randomUUID();
        var t = new Entities.ChatThreadEntity();
        t.setId(id); t.setSchoolId(SCHOOL); t.setChildId(childAmerican); t.setTeacherId(sami); t.setStaffRole("MANAGERIAL");
        t.setTopic("complaint"); t.setStatus("resolved"); t.setResolvedAt(Instant.now()); t.setResolvedBy(sami);
        t.setCreatedAt(Instant.now()); t.setLastMessageAt(Instant.now()); t.setThreadKey("");
        threadRows.saveAndFlush(t);
        var m = new Entities.ChatMessageEntity();
        m.setId(id + "-m"); m.setSchoolId(SCHOOL); m.setThreadId(id); m.setSenderRole("parent"); m.setSenderId("p"); m.setBody("The bus is late every day.");
        m.setCreatedAt(Instant.now());
        messageRows.saveAndFlush(m);
        for (int run = 0; run < 2; run++)
            try (var c = dataSource.getConnection()) { ScriptUtils.executeSqlScript(c, new ClassPathResource("db/migration/V33__complaints_separate.sql")); }

        var row = threadRows.findById(id).orElseThrow();
        assertThat(row.getThreadKey()).isEqualTo(id);
        assertThat(row.getTitle()).isEqualTo("The bus is late every day.");
        assertThat(eventRows.findByThreadIdOrderByChangedAtAscIdAsc(id)).singleElement().satisfies(e -> {
            assertThat(e.getStatus()).isEqualTo("resolved"); assertThat(e.getActorId()).isEqualTo(sami); });
        var hers = parentJson(AMERICAN_PARENT, "/children/" + childAmerican + "/complaints");
        assertThat(rowWith(hers.get("complaints"), "id", id).get("title").asText()).isEqualTo("The bus is late every day.");
        assertThat(rowWith(parentJson(AMERICAN_PARENT, "/children/" + childAmerican + "/managers"), "teacherId", sami).has("id"))
                .as("the Messages row with Sami has no thread until she writes again").isFalse();
        String fresh = parentPostJson(AMERICAN_PARENT, "/children/" + childAmerican + "/chat/threads/" + sami + "/messages",
                "{\"body\":\"Thank you for fixing the bus.\"}").get("threadId").asText();
        assertThat(fresh).isNotEqualTo(id);
    }

    // ---------------------------------------------------------------- plumbing

    private ResultActions perform(MockHttpServletRequestBuilder b) throws Exception { return mvc.perform(b); }

    private String token(String userId, String role) { return jwt.issue(userId, userId + "@seed.test", role, SCHOOL).token(); }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder b, String token) { return b.header("Authorization", "Bearer " + token); }

    private static String bearer(String parentUid) { return "Bearer fake-token-" + parentUid; }

    private JsonNode staffGet(String userId, String role, String path) throws Exception {
        return json(mvc.perform(as(get(path), token(userId, role))).andExpect(status().isOk()).andReturn());
    }

    private JsonNode adminGet(String path) throws Exception {
        return json(mvc.perform(as(get(path), adminToken()).header("X-School-Id", SCHOOL)).andExpect(status().isOk()).andReturn());
    }

    private JsonNode parentJson(String parentUid, String path) throws Exception {
        return json(mvc.perform(get(path).header("Authorization", bearer(parentUid))).andExpect(status().isOk()).andReturn());
    }

    private JsonNode parentPostJson(String parentUid, String path, String body) throws Exception {
        return json(mvc.perform(post(path).header("Authorization", bearer(parentUid)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn());
    }

    private WebSocketSession listen(String key, String schoolId) {
        var session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(UUID.randomUUID().toString());
        when(session.isOpen()).thenReturn(true);
        sessions.register(session, new ChatSessions.Peer(key, key.startsWith("parent:") ? "parent" : "teacher",
                key.substring(key.indexOf(':') + 1), schoolId, null, true));
        return session;
    }

    private static List<String> framesUntil(WebSocketSession session, String needle) throws Exception {
        for (int i = 0; i < 100; i++) {
            var frames = framesSoFar(session);
            if (frames.stream().anyMatch(f -> f.contains(needle))) return frames;
            Thread.sleep(50);
        }
        return framesSoFar(session);
    }

    private static List<String> framesSoFar(WebSocketSession session) throws Exception {
        var captor = org.mockito.ArgumentCaptor.forClass(TextMessage.class);
        verify(session, org.mockito.Mockito.atLeast(0)).sendMessage(captor.capture());
        return captor.getAllValues().stream().map(TextMessage::getPayload).toList();
    }

    private static List<String> names(JsonNode rows, String field) {
        var out = new ArrayList<String>();
        rows.forEach(row -> out.add(row.get(field) == null || row.get(field).isNull() ? null : row.get(field).asText()));
        return out;
    }

    private static JsonNode rowWith(JsonNode rows, String field, String value) {
        for (var row : rows) if (row.get(field) != null && value.equals(row.get(field).asText())) return row;
        throw new AssertionError("no row with " + field + " = " + value + " in " + rows);
    }

    private void school(String id, String name, String code) {
        schools.findById(id).orElseGet(() -> {
            var s = new SchoolEntity();
            s.setId(id); s.setName(name); s.setCode(code);
            s.setCurriculumOptionsJson("[\"british\",\"american\"]"); s.setGradeOptionsJson("[1,2,3]");
            s.setStatus("active"); s.setCreatedAt(Instant.now());
            return schools.save(s);
        });
    }

    private void flag(String schoolId, String key, boolean enabled) {
        var row = schoolFlags.findOne(schoolId, key).orElseGet(() -> {
            var fresh = new quest.server.flags.Entities.SchoolFeatureFlagEntity();
            fresh.setSchoolId(schoolId); fresh.setFlagKey(key);
            return fresh;
        });
        row.setEnabled(enabled); row.setUpdatedBy("test"); row.setUpdatedAt(Instant.now());
        schoolFlags.save(row);
        featureFlags.invalidate(schoolId);
    }

    private String child(String name, String classId, String curriculum, String parentUid) throws Exception {
        String id = json(mvc.perform(post("/children").header("Authorization", bearer(parentUid)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"avatarColor\":\"sun\",\"curriculum\":\"" + curriculum + "\",\"grade\":1,\"schoolCode\":\"CMPS1\"}"))
                .andExpect(status().is2xxSuccessful()).andReturn()).get("id").asText();
        childRows.findById(id).ifPresent(c -> { c.setClassId(classId); childRows.save(c); });
        return id;
    }

    private String idOf(String email) {
        return users.findBySchoolId(SCHOOL).stream().filter(u -> email.equalsIgnoreCase(u.getEmail())).findFirst().orElseThrow().getId();
    }

    private String sectionId(String name) {
        return classes.findAll().stream().filter(k -> SCHOOL.equals(k.getSchoolId())
                        && name.toLowerCase(Locale.ROOT).equals(k.getName() == null ? null : k.getName().toLowerCase(Locale.ROOT)))
                .findFirst().orElseThrow().getId();
    }
}
