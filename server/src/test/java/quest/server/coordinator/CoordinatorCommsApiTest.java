package quest.server.coordinator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import quest.server.ApiTestSupport;
import quest.server.auth.AdminJwtService;
import quest.server.auth.Entities.UserEntity;
import quest.server.auth.TeacherRepository;
import quest.server.auth.UserRepository;
import quest.server.chat.ChatBus;
import quest.server.chat.ChatEvent;
import quest.server.chat.ChatMessageRepository;
import quest.server.chat.ChatSessions;
import quest.server.chat.ChatThreadRepository;
import quest.server.children.ChildRepository;
import quest.server.classes.SchoolSeed;
import quest.server.flags.FlagKeys;
import quest.server.tenancy.ClassRepository;
import quest.server.tenancy.Entities.SchoolEntity;
import quest.server.tenancy.Entities.StaffScopeEntity;
import quest.server.tenancy.SchoolRepository;
import quest.server.tenancy.StaffScopeRepository;
import quest.server.tenancy.TeachingAssignmentRepository;

/**
 * R4 (DR3, DR4) on the owner's own fixture, the one `CoordinatorApiTest` uses: Maya teaches math in 1A and 1B
 * British, Rami english in 1A American, Lina coordinates math/British, Nour manages British and Sami American.
 *
 * <p>What it proves, in the order the brief puts it: a British parent is offered Lina and nobody else, opens a
 * `complaint` with her and is answered; Lina sees it in her inbox, replies and resolves it, and the parent's own
 * socket hears both; an American parent cannot open a thread with Lina at all; Lina and Nour get one thread however
 * many times either asks for it and Sami is refused; her announcement lands on 1A and 1B British and reaches a
 * British parent's app and not an American one's; and school B hears nothing of any of it, on REST or on the bus.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CoordinatorCommsApiTest extends ApiTestSupport {
    private static final String SCHOOL = "comms-school", OTHER_SCHOOL = "comms-school-b";
    private static final String DIR = "seed/acceptance/";
    private static final String STAFF_PASSWORD = "comms-pass";
    private static final String LINA = "coord.math@test.com", OMAR = "coord.english@test.com";
    private static final String NOUR = "manager@test.com", SAMI = "manager2@test.com";
    /** The uid behind `Bearer fake-token-<uid>`; the `parents` row it creates carries its own id (the socket key). */
    private static final String BRITISH_A_PARENT = "comms-parent-1a", BRITISH_B_PARENT = "comms-parent-1b", AMERICAN_PARENT = "comms-parent-us";

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
    @Autowired ChatSessions sessions;
    @Autowired ChatBus bus;
    @Autowired AdminJwtService jwt;
    @Autowired quest.server.teacher.AnnouncementRepository announcementRows;
    @Autowired quest.server.flags.SchoolFlagRepository schoolFlags;
    @Autowired quest.server.flags.FeatureFlags featureFlags;

    private String lina, omar, nour, sami, britishA, britishB, americanA;
    private String childBritishA, childBritishB, childAmerican;
    private final List<ChatEvent> heard = new CopyOnWriteArrayList<>();

    @BeforeAll void loadTheSchool() throws Exception {
        school(SCHOOL, "Comms School", "COMMS1");
        school(OTHER_SCHOOL, "Quiet School", "COMMS2");
        seed.load(SCHOOL, STAFF_PASSWORD, false, DIR);
        lina = idOf(LINA); omar = idOf(OMAR); nour = idOf(NOUR); sami = idOf(SAMI);
        britishA = sectionId("1A British"); britishB = sectionId("1B British"); americanA = sectionId("1A American");
        childBritishA = child("Lila", britishA, "british", BRITISH_A_PARENT);
        childBritishB = child("Bilal", britishB, "british", BRITISH_B_PARENT);
        childAmerican = child("Hana", americanA, "american", AMERICAN_PARENT);
        for (String key : List.of(FlagKeys.CHAT, FlagKeys.ANNOUNCEMENTS)) { flag(SCHOOL, key, true); flag(OTHER_SCHOOL, key, true); }
        bus.subscribe(heard::add);
    }

    @AfterAll void takeItBackOut() {
        messageRows.deleteAll(messageRows.findAll().stream().filter(m -> m.getSchoolId().startsWith("comms-")).toList());
        threadRows.deleteAll(threadRows.findAll().stream().filter(t -> t.getSchoolId().startsWith("comms-")).toList());
        announcementRows.deleteAll(announcementRows.findAll().stream().filter(a -> a.getSchoolId().startsWith("comms-")).toList());
        childRows.deleteAll(childRows.findAll().stream().filter(c -> c.getSchoolId().startsWith("comms-")).toList());
        assignments.deleteAll(assignments.findAll().stream().filter(a -> SCHOOL.equals(a.getSchoolId())).toList());
        staffScopes.deleteAll(staffScopes.findAll().stream().filter(r -> r.getSchoolId().startsWith("comms-")).toList());
        classes.deleteAll(classes.findAll().stream().filter(k -> k.getSchoolId().startsWith("comms-")).toList());
        var staff = new ArrayList<UserEntity>(users.findBySchoolId(SCHOOL));
        staff.addAll(users.findBySchoolId(OTHER_SCHOOL));
        teacherProfiles.deleteAll(teacherProfiles.findAllById(staff.stream().map(UserEntity::getId).toList()));
        users.deleteAll(staff);
    }

    // ---------------------------------------------------------------- the seed itself

    @Test @Order(1) void the_seed_carries_a_manager_per_department() {
        assertThat(departments(nour)).containsExactly("british");
        assertThat(departments(sami)).containsExactly("american");
    }

    // ---------------------------------------------------------------- parent ↔ coordinator

    @Test @Order(2) void a_british_parent_is_offered_the_math_coordinator_and_nobody_else() throws Exception {
        var rows = parentJson(BRITISH_A_PARENT, "/children/" + childBritishA + "/coordinators");
        assertThat(names(rows, "teacherId")).containsExactly(lina);
        var row = rows.get(0);
        assertThat(row.has("id")).as("no thread until she writes; the codec omits nulls").isFalse();
        assertThat(row.get("staffRole").asText()).isEqualTo("COORDINATOR");
        assertThat(row.get("subject").asText()).isEqualTo("math");
        assertThat(row.get("className").asText()).isEqualTo("1A British");
        assertThat(row.get("topic").asText()).isEqualTo("question");
        assertThat(row.get("status").asText()).isEqualTo("open");

        // Omar coordinates english/American: not this section's subject, not this track, so never on her list.
        assertThat(names(rows, "teacherId")).doesNotContain(omar);
    }

    @Test @Order(3) void the_complaint_round_trip() throws Exception {
        // The socket key is the `parents` row id, not the Firebase uid: `Principals.Parent.parentId()` is the row's.
        var parentSocket = listen("parent:" + childRows.findById(childBritishA).orElseThrow().getParentId(), null);
        var opened = parentPostJson(BRITISH_A_PARENT, "/children/" + childBritishA + "/chat/threads/" + lina + "/messages",
                "{\"body\":\"The homework is too long every night.\",\"topic\":\"complaint\"}");
        assertThat(opened.get("sender").asText()).isEqualTo("parent");
        String threadId = opened.get("threadId").asText();

        // The parent's own thread list now carries the coordinator's thread beside the teachers'.
        var hers = parentJson(BRITISH_A_PARENT, "/children/" + childBritishA + "/chat/threads");
        assertThat(rowWith(hers, "teacherId", lina).get("topic").asText()).isEqualTo("complaint");

        // Lina's inbox: the complaint, open, with the child's name on it and her own unread badge.
        var inbox = staffJson(lina, "/coordinator/complaints?status=open");
        var complaint = rowWith(inbox, "id", threadId);
        assertThat(complaint.get("childName").asText()).isEqualTo("Lila");
        assertThat(complaint.get("status").asText()).isEqualTo("open");
        assertThat(complaint.get("unread").asInt()).isEqualTo(1);
        assertThat(complaint.get("staffRole").asText()).isEqualTo("COORDINATOR");

        // She reads it, answers it, and resolves it.
        staffPost(lina, "/coordinator/chat/threads/" + threadId + "/read", null);
        var reply = staffPostJson(lina, "/coordinator/chat/threads/" + threadId + "/messages", "{\"body\":\"I have asked Maya to halve it.\"}");
        assertThat(reply.get("sender").asText()).isEqualTo("teacher");
        var resolved = json(mvc.perform(as(patch("/coordinator/chat/threads/" + threadId + "/status")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"resolved\"}"), token(lina, "COORDINATOR", SCHOOL)))
                .andExpect(status().isOk()).andReturn());
        assertThat(resolved.get("status").asText()).isEqualTo("resolved");
        assertThat(resolved.get("resolvedAt").asLong()).isPositive();

        // The parent sees the answer and the resolution: two frames on her socket, and the reply in her own page.
        var messages = parentJson(BRITISH_A_PARENT, "/children/" + childBritishA + "/chat/threads/" + lina + "/messages");
        assertThat(names(messages, "body")).containsExactly("The homework is too long every night.", "I have asked Maya to halve it.");
        assertThat(framesUntil(parentSocket, "\"type\":\"status\""))
                .anyMatch(f -> f.contains("\"type\":\"message\"") && f.contains("halve it"))
                .anyMatch(f -> f.contains("\"type\":\"status\"") && f.contains("\"status\":\"resolved\""));

        // `?status=open` no longer lists it; the inbox without a filter still does.
        assertThat(names(staffJson(lina, "/coordinator/complaints?status=open"), "id")).doesNotContain(threadId);
        assertThat(names(staffJson(lina, "/coordinator/complaints"), "id")).contains(threadId);
        // And a `question` thread is not a complaint: the inbox is the topic, not the whole list.
        assertThat(names(staffJson(lina, "/coordinator/chat/threads"), "id")).contains(threadId);

        // RM1 addendum: the inbox names the parent who wrote, not only the child. A parent has no display name — she
        // signs in through Firebase — so the row carries her registered address.
        JsonNode named = null;
        for (var row : staffJson(lina, "/coordinator/complaints")) if (threadId.equals(row.get("id").asText())) named = row;
        assertThat(java.util.Objects.requireNonNull(named).get("parentName").asText()).contains("@");
    }

    @Test @Order(4) void an_american_parent_cannot_reach_the_british_math_coordinator() throws Exception {
        assertThat(parentJson(AMERICAN_PARENT, "/children/" + childAmerican + "/coordinators")).noneSatisfy(
                row -> assertThat(row.get("teacherId").asText()).isEqualTo(lina));
        mvc.perform(post("/children/" + childAmerican + "/chat/threads/" + lina + "/messages")
                        .header("Authorization", bearer(AMERICAN_PARENT)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"Hello?\",\"topic\":\"complaint\"}"))
                .andExpect(status().isNotFound());
        assertThat(threadRows.findAll()).noneSatisfy(t -> assertThat(t.getChildId()).isEqualTo(childAmerican));
    }

    /**
     * A complaint on a teacher's thread would be a label nobody's inbox lists, so it is refused at the edge rather
     * than stored: `/coordinator/complaints` only lists the threads the coordinator is the staff peer of.
     */
    @Test @Order(5) void a_complaint_aimed_at_a_teacher_is_refused_and_writes_nothing() throws Exception {
        String maya = idOf("maya@test.com");
        var refused = json(mvc.perform(post("/children/" + childBritishA + "/chat/threads/" + maya + "/messages")
                        .header("Authorization", bearer(BRITISH_A_PARENT)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"This is a complaint.\",\"topic\":\"complaint\"}"))
                .andExpect(status().isBadRequest()).andReturn());
        assertThat(refused.get("code").asText()).isEqualTo("complaint_needs_coordinator");
        assertThat(threadRows.findByChildIdAndTeacherId(childBritishA, maya)).as("nothing was written").isEmpty();

        // The same thread as a question is fine, and stays a question.
        var asked = parentPostJson(BRITISH_A_PARENT, "/children/" + childBritishA + "/chat/threads/" + maya + "/messages",
                "{\"body\":\"Could you explain the homework?\"}");
        assertThat(rowWith(parentJson(BRITISH_A_PARENT, "/children/" + childBritishA + "/chat/threads"), "id",
                asked.get("threadId").asText()).get("topic").asText()).isEqualTo("question");
    }

    // ---------------------------------------------------------------- coordinator ↔ manager

    /** RM1 addendum: the chooser the `managerUserId` below has to come from, and the department beside each name. */
    @Test @Order(6) void her_manager_chooser_is_her_own_department() throws Exception {
        var options = staffJson(lina, "/coordinator/managers");
        assertThat(names(options, "userId")).as("Lina coordinates British; Sami runs the other department").containsExactly(nour);
        assertThat(options.get(0).get("curriculum").asText()).isEqualTo("british");
        assertThat(options.get(0).get("displayName").asText()).isNotBlank();
        assertThat(names(staffJson(omar, "/coordinator/managers"), "userId")).containsExactly(sami);
    }

    @Test @Order(6) void lina_and_the_british_manager_share_one_thread_and_the_american_one_is_refused() throws Exception {
        var managerSocket = listen("user:" + nour, SCHOOL);
        var otherSchoolSocket = listen("user:" + otherSchoolCoordinator(), OTHER_SCHOOL);

        var thread = staffPostJson(lina, "/coordinator/chat/threads", "{\"managerUserId\":\"" + nour + "\"}");
        assertThat(thread.get("staffRole").asText()).isEqualTo("MANAGERIAL");
        assertThat(thread.get("teacherId").asText()).as("the row names the other person").isEqualTo(nour);
        assertThat(thread.get("childId").asText()).as("a department thread is about no child").isEmpty();
        String threadId = thread.get("id").asText();
        assertThat(staffPostJson(lina, "/coordinator/chat/threads", "{\"managerUserId\":\"" + nour + "\"}").get("id").asText())
                .as("asked for twice, it is the same thread").isEqualTo(threadId);

        staffPostJson(lina, "/coordinator/chat/threads/" + threadId + "/messages", "{\"body\":\"Maya needs a second math period.\"}");
        assertThat(framesUntil(managerSocket, "second math period")).as("the manager's own socket carries it (RM2 adds her REST list)")
                .anyMatch(f -> f.contains("second math period"));
        assertThat(framesSoFar(otherSchoolSocket)).as("school B hears nothing of school A's thread")
                .noneMatch(f -> f.contains("second math period"));

        // Sami manages the other department, so he is not a manager Lina is told about at all.
        mvc.perform(as(post("/coordinator/chat/threads").contentType(MediaType.APPLICATION_JSON)
                .content("{\"managerUserId\":\"" + sami + "\"}"), token(lina, "COORDINATOR", SCHOOL))).andExpect(status().isNotFound());

        // The bus event names the two staff members and this school only — what a NOTIFY payload carries.
        var event = heard.stream().filter(e -> threadId.equals(e.threadId()) && ChatEvent.MESSAGE.equals(e.kind())).findFirst().orElseThrow();
        assertThat(event.schoolId()).isEqualTo(SCHOOL);
        assertThat(event.teacherId()).isEqualTo(lina);
        assertThat(event.peerUserId()).isEqualTo(nour);
        assertThat(event.parentId()).isNull();
    }

    @Test @Order(7) void another_schools_coordinator_reaches_none_of_it() throws Exception {
        String hers = otherSchoolCoordinator();
        assertThat(staffJson(hers, OTHER_SCHOOL, "/coordinator/complaints")).isEmpty();
        var mine = threadRows.findAll().stream().filter(t -> SCHOOL.equals(t.getSchoolId())).findFirst().orElseThrow();
        mvc.perform(as(get("/coordinator/chat/threads/" + mine.getId() + "/messages"), token(hers, "COORDINATOR", OTHER_SCHOOL)))
                .andExpect(status().isNotFound());
        mvc.perform(as(patch("/coordinator/chat/threads/" + mine.getId() + "/status").contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"resolved\"}"), token(hers, "COORDINATOR", OTHER_SCHOOL))).andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- announcements

    @Test @Order(8) void her_announcement_reaches_the_british_parents_only() throws Exception {
        var written = staffPostJson(lina, "/coordinator/announcements", "{\"bodyEn\":\"Times tables week starts Sunday.\"}");
        assertThat(names(written, "classId")).containsExactlyInAnyOrder(britishA, britishB);

        for (var parent : List.of(BRITISH_A_PARENT, BRITISH_B_PARENT)) {
            String child = BRITISH_A_PARENT.equals(parent) ? childBritishA : childBritishB;
            assertThat(names(parentJson(parent, "/children/" + child + "/announcements"), "bodyEn"))
                    .contains("Times tables week starts Sunday.");
        }
        assertThat(names(parentJson(AMERICAN_PARENT, "/children/" + childAmerican + "/announcements"), "bodyEn"))
                .doesNotContain("Times tables week starts Sunday.");

        assertThat(names(staffJson(lina, "/coordinator/announcements"), "bodyEn")).contains("Times tables week starts Sunday.");

        // A section of the other track is 403, and an empty body is 400: her scope is the audience, always.
        mvc.perform(as(post("/coordinator/announcements").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"classIds\":[\"" + americanA + "\"],\"bodyEn\":\"Hello\"}"), token(lina, "COORDINATOR", SCHOOL)))
                .andExpect(status().isForbidden());
        mvc.perform(as(post("/coordinator/announcements").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bodyEn\":\"  \"}"), token(lina, "COORDINATOR", SCHOOL)))
                .andExpect(status().isBadRequest());

        // `expiresAt` is a date a note stops being shown, so the past and the far future are both refused.
        long now = System.currentTimeMillis();
        for (long at : new long[] {now - 60_000, now + java.time.Duration.ofDays(401).toMillis()})
            mvc.perform(as(post("/coordinator/announcements").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"bodyEn\":\"Sports day\",\"expiresAt\":" + at + "}"), token(lina, "COORDINATOR", SCHOOL)))
                    .andExpect(status().isBadRequest());
        assertThat(names(staffJson(lina, "/coordinator/announcements"), "bodyEn")).doesNotContain("Sports day");
    }

    @Test @Order(9) void a_teacher_holds_none_of_the_coordinators_communication_keys() throws Exception {
        String teacher = jwt.issue("comms-who-teacher", "who@x.test", "TEACHER", SCHOOL).token();
        for (String path : List.of("/coordinator/chat/threads", "/coordinator/complaints", "/coordinator/announcements",
                "/coordinator/managers"))
            mvc.perform(as(get(path), teacher)).andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------- fixture helpers

    private String token(String userId, String role, String schoolId) { return jwt.issue(userId, userId + "@seed.test", role, schoolId).token(); }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder builder, String token) {
        return builder.header("Authorization", "Bearer " + token);
    }

    private static String bearer(String parentUid) { return "Bearer fake-token-" + parentUid; }

    private JsonNode parentJson(String parentUid, String path) throws Exception {
        return json(mvc.perform(get(path).header("Authorization", bearer(parentUid))).andExpect(status().isOk()).andReturn());
    }

    private JsonNode parentPostJson(String parentUid, String path, String body) throws Exception {
        return json(mvc.perform(post(path).header("Authorization", bearer(parentUid))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated()).andReturn());
    }

    private JsonNode staffJson(String userId, String path) throws Exception { return staffJson(userId, SCHOOL, path); }

    private JsonNode staffJson(String userId, String schoolId, String path) throws Exception {
        return json(mvc.perform(as(get(path), token(userId, "COORDINATOR", schoolId))).andExpect(status().isOk()).andReturn());
    }

    private JsonNode staffPostJson(String userId, String path, String body) throws Exception {
        return json(mvc.perform(as(post(path).contentType(MediaType.APPLICATION_JSON).content(body), token(userId, "COORDINATOR", SCHOOL)))
                .andExpect(status().isCreated()).andReturn());
    }

    private void staffPost(String userId, String path, String body) throws Exception {
        var builder = post(path);
        if (body != null) builder = builder.contentType(MediaType.APPLICATION_JSON).content(body);
        mvc.perform(as(builder, token(userId, "COORDINATOR", SCHOOL))).andExpect(status().isOk());
    }

    /** A socket of this person, registered the way the handshake does, so the hub's fan-out can be watched. */
    private WebSocketSession listen(String key, String schoolId) {
        var session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(UUID.randomUUID().toString());
        when(session.isOpen()).thenReturn(true);
        sessions.register(session, new ChatSessions.Peer(key, key.startsWith("parent:") ? "parent" : "coordinator",
                key.substring(key.indexOf(':') + 1), schoolId, null, true));
        return session;
    }

    /** The frames of a watched socket, once one of them says `needle`; the hub writes on a virtual thread of its own. */
    private static List<String> framesUntil(WebSocketSession session, String needle) throws Exception {
        for (int i = 0; i < 100; i++) {
            var frames = framesSoFar(session);
            if (frames.stream().anyMatch(f -> f.contains(needle))) return frames;
            Thread.sleep(50);
        }
        return framesSoFar(session);
    }

    /** What a socket has been written so far, with no wait and no minimum — the "and nobody else heard it" case. */
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

    private List<String> departments(String userId) {
        return staffScopes.findBySchoolIdAndUserIdOrderBySubjectAscCurriculumAsc(SCHOOL, userId).stream()
                .filter(r -> r.getSubject() == null).map(StaffScopeEntity::getCurriculum).toList();
    }

    /** A COORDINATOR of the other school, with a math scope of her own, so tenancy is asserted on a real caller. */
    private String otherSchoolCoordinator() {
        String id = "comms-other-coordinator";
        users.findById(id).orElseGet(() -> {
            var u = new UserEntity();
            u.setId(id); u.setSchoolId(OTHER_SCHOOL); u.setEmail("other.coord@test.com"); u.setDisplayName("Other");
            u.setRole("COORDINATOR"); u.setStatus("active"); u.setPasswordHash("x");
            u.setCreatedAt(Instant.now()); u.setUpdatedAt(Instant.now());
            return users.save(u);
        });
        staffScopes.findById("comms-other-scope").orElseGet(() -> {
            var row = new StaffScopeEntity();
            row.setId("comms-other-scope"); row.setSchoolId(OTHER_SCHOOL); row.setUserId(id);
            row.setSubject("math"); row.setCurriculum("british"); row.setCreatedAt(Instant.now());
            return staffScopes.save(row);
        });
        return id;
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

    /**
     * A child of a real parent: created through the parent API so the `parents` row behind `children.parent_id`
     * exists, then placed on a section by hand — the roster write is the teacher's route and not what is under test.
     */
    private String child(String name, String classId, String curriculum, String parentUid) throws Exception {
        String id = json(mvc.perform(post("/children").header("Authorization", bearer(parentUid)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"avatarColor\":\"sun\",\"curriculum\":\"" + curriculum
                                + "\",\"grade\":1,\"schoolCode\":\"COMMS1\"}"))
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
