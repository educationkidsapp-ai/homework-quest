package quest.server.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.socket.WebSocketSession;
import quest.server.ApiTestSupport;
import quest.server.auth.AdminJwtService;
import quest.server.auth.Entities.UserEntity;
import quest.server.auth.TeacherRepository;
import quest.server.auth.UserRepository;
import quest.server.classes.SchoolSeed;
import quest.server.flags.FlagKeys;
import quest.server.notifications.NotificationRepository;
import quest.server.tenancy.ClassRepository;
import quest.server.tenancy.Entities.SchoolEntity;
import quest.server.tenancy.SchoolRepository;
import quest.server.tenancy.StaffScopeRepository;
import quest.server.tenancy.TeachingAssignmentRepository;

/**
 * T1 (the owner's list, 2026-10-01) on the acceptance fixture `BroadcastApiTest` uses: Maya teaches math in 1A and 1B
 * British, Rami english in 1A American, Lina coordinates math/British, Omar english/American, Nour manages British and
 * Sami American.
 *
 * <p>What it proves, in the brief's order: Maya's Coordinator page names Lina "Coordinator · Grade 1 · Math · British"
 * and her Manager page names Nour the British department manager, both with the address and the phone the seed carries;
 * Rami — one track over — reads Omar and Sami and neither of Maya's; Lina reads Nour and not Sami. Then the bell: a
 * message to the manager writes her one `chat.message` row, a second message updates that row instead of adding one,
 * reading the thread marks it read, and the next message rings again. Then presence: a socket of Nour's makes her
 * `online` on the directory and `peerOnline` on Maya's thread row, and closing it takes both back.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StaffDirectoryApiTest extends ApiTestSupport {
    private static final String SCHOOL = "sd-school", DIR = "seed/acceptance/", STAFF_PASSWORD = "sd-pass";
    private static final String LINA = "coord.math@test.com", OMAR = "coord.english@test.com";
    private static final String NOUR = "manager@test.com", SAMI = "manager2@test.com";
    private static final String MAYA = "maya@test.com", RAMI = "rami@test.com";

    @Autowired SchoolSeed seed;
    @Autowired SchoolRepository schools;
    @Autowired ClassRepository classes;
    @Autowired TeachingAssignmentRepository assignments;
    @Autowired StaffScopeRepository staffScopes;
    @Autowired UserRepository users;
    @Autowired TeacherRepository teacherProfiles;
    @Autowired ChatThreadRepository threadRows;
    @Autowired ChatMessageRepository messageRows;
    @Autowired NotificationRepository notificationRows;
    @Autowired quest.server.flags.SchoolFlagRepository schoolFlags;
    @Autowired quest.server.flags.FeatureFlags featureFlags;
    @Autowired ChatSessions sessions;
    @Autowired AdminJwtService jwt;

    private String lina, omar, nour, sami, maya, rami, staffThread;

    @BeforeAll void loadTheSchool() throws Exception {
        school();
        seed.load(SCHOOL, STAFF_PASSWORD, false, DIR);
        lina = idOf(LINA); omar = idOf(OMAR); nour = idOf(NOUR); sami = idOf(SAMI); maya = idOf(MAYA); rami = idOf(RAMI);
        flag(FlagKeys.CHAT, true);
    }

    @AfterAll void takeItBackOut() {
        notificationRows.deleteAll(notificationRows.findAll().stream().filter(n -> SCHOOL.equals(n.getSchoolId())).toList());
        messageRows.deleteAll(messageRows.findAll().stream().filter(m -> SCHOOL.equals(m.getSchoolId())).toList());
        threadRows.deleteAll(threadRows.findAll().stream().filter(t -> SCHOOL.equals(t.getSchoolId())).toList());
        assignments.deleteAll(assignments.findAll().stream().filter(a -> SCHOOL.equals(a.getSchoolId())).toList());
        staffScopes.deleteAll(staffScopes.findAll().stream().filter(r -> SCHOOL.equals(r.getSchoolId())).toList());
        classes.deleteAll(classes.findAll().stream().filter(k -> SCHOOL.equals(k.getSchoolId())).toList());
        var staff = new ArrayList<UserEntity>(users.findBySchoolId(SCHOOL));
        teacherProfiles.deleteAll(teacherProfiles.findAllById(staff.stream().map(UserEntity::getId).toList()));
        users.deleteAll(staff);
    }

    // ---------------------------------------------------------------- the directory

    @Test @Order(1) void maya_reads_lina_as_her_grade_one_math_coordinator_and_nour_as_her_manager() throws Exception {
        var coordinators = staffGet(maya, "TEACHER", "/teacher/coordinators");
        assertThat(names(coordinators, "displayName")).containsExactly("Lina");
        var hers = coordinators.get(0);
        assertThat(hers.get("userId").asText()).isEqualTo(lina);
        assertThat(hers.get("job").asText()).isEqualTo("Coordinator · Grade 1 · Math · British");
        assertThat(hers.get("email").asText()).isEqualTo(LINA);
        assertThat(hers.get("phone").asText()).isEqualTo("+971501000301");
        assertThat(hers.get("role").asText()).isEqualTo("COORDINATOR");
        assertThat(hers.get("subjects").asText()).isEqualTo("math");
        var parts = hers.get("jobParts");
        assertThat(parts.get("kind").asText()).isEqualTo("coordinator");
        assertThat(parts.get("subject").asText()).isEqualTo("math");
        assertThat(parts.get("curriculum").asText()).isEqualTo("british");
        assertThat(ints(parts.get("grades"))).as("both her sections are grade 1, named once").containsExactly(1);

        var managers = staffGet(maya, "TEACHER", "/teacher/managers");
        assertThat(names(managers, "displayName")).containsExactly("Nour");
        assertThat(managers.get(0).get("job").asText()).isEqualTo("British department manager");
        assertThat(managers.get(0).get("phone").asText()).isEqualTo("+971501000201");
        assertThat(managers.get(0).get("jobParts").get("kind").asText()).isEqualTo("manager");
        assertThat(managers.get(0).get("jobParts").get("curriculum").asText()).isEqualTo("british");
        assertThat(managers.get(0).get("jobParts").get("grades")).as("a department is not a grade").isEmpty();
    }

    @Test @Order(2) void rami_reads_the_american_pair_and_lina_reads_her_own_manager() throws Exception {
        assertThat(names(staffGet(rami, "TEACHER", "/teacher/coordinators"), "userId")).containsExactly(omar);
        assertThat(staffGet(rami, "TEACHER", "/teacher/coordinators").get(0).get("job").asText())
                .isEqualTo("Coordinator · Grade 1 · English · American");
        assertThat(names(staffGet(rami, "TEACHER", "/teacher/managers"), "userId")).containsExactly(sami);

        var hers = staffGet(lina, "COORDINATOR", "/coordinator/managers");
        assertThat(names(hers, "userId")).containsExactly(nour);
        assertThat(hers.get(0).get("email").asText()).isEqualTo(NOUR);
        assertThat(hers.get(0).get("job").asText()).isEqualTo("British department manager");
        assertThat(names(staffGet(omar, "COORDINATOR", "/coordinator/managers"), "userId")).containsExactly(sami);
    }

    // ---------------------------------------------------------------- the bell

    @Test @Order(3) void a_message_to_the_manager_rings_her_bell_once_per_thread() throws Exception {
        staffThread = created(maya, "TEACHER", "/teacher/chat/staff-threads", "{\"managerUserId\":\"" + nour + "\"}").get("id").asText();
        send(maya, "Could we move the maths test?");
        var first = chatRows(nour);
        assertThat(first).hasSize(1);
        assertThat(first.get(0).get("title").asText()).isEqualTo("Message from Maya");
        assertThat(first.get(0).get("body").asText()).isEqualTo("Could we move the maths test?");
        assertThat(first.get(0).get("link").asText()).isEqualTo("/management/messages?thread=" + staffThread);
        assertThat(first.get(0).get("lessonId").asText()).as("the row is about the thread").isEqualTo(staffThread);
        assertThat(chatRows(maya)).as("the sender's own bell says nothing").isEmpty();

        // the throttle: a second unread message updates the row she already has
        send(maya, "Thursday would suit us better.");
        var again = chatRows(nour);
        assertThat(again).hasSize(1);
        assertThat(again.get(0).get("id").asText()).isEqualTo(first.get(0).get("id").asText());
        assertThat(again.get(0).get("body").asText()).isEqualTo("Thursday would suit us better.");
    }

    @Test @Order(4) void reading_the_thread_clears_the_row_and_the_next_message_rings_again() throws Exception {
        mvc.perform(as(post("/management/chat/threads/" + staffThread + "/read"), token(nour, "MANAGERIAL"))).andExpect(status().isOk());
        assertThat(chatRows(nour)).isEmpty();
        send(maya, "Thank you.");
        assertThat(chatRows(nour)).hasSize(1);
        assertThat(chatRows(nour).get(0).get("body").asText()).isEqualTo("Thank you.");
    }

    // ---------------------------------------------------------------- T1b: the direct message to a coordinator

    /**
     * The owner's "direct message" on the Coordinator page. One row, opened from the teacher's side: Maya writes to
     * Lina, Lina finds it in her own inbox and answers it there, and each of them gets the other's message in her bell
     * linked to her own screen. The reach is the directory's, so Omar — the American english coordinator — is 404 to
     * her, and naming both a manager and a coordinator is 400.
     */
    @Test @Order(6) void maya_opens_a_thread_with_lina_and_lina_answers_it_from_her_own_inbox() throws Exception {
        var opened = created(maya, "TEACHER", "/teacher/chat/staff-threads", "{\"coordinatorUserId\":\"" + lina + "\"}");
        String thread = opened.get("id").asText();
        assertThat(opened.get("staffRole").asText()).as("staff_role names the peer").isEqualTo("COORDINATOR");
        assertThat(opened.get("teacherId").asText()).as("the row names the other person").isEqualTo(lina);
        // the same row whichever side asks, and it is in both lists
        assertThat(created(maya, "TEACHER", "/teacher/chat/staff-threads", "{\"coordinatorUserId\":\"" + lina + "\"}")
                .get("id").asText()).isEqualTo(thread);
        assertThat(names(staffGet(maya, "TEACHER", "/teacher/chat/staff-threads"), "id")).contains(thread);

        created(maya, "TEACHER", "/teacher/chat/staff-threads/" + thread + "/messages", "{\"body\":\"Is the counting unit still on?\"}");
        var hers = rowOf(staffGet(lina, "COORDINATOR", "/coordinator/chat/threads"), thread);
        assertThat(hers.get("teacherName").asText()).isEqualTo("Maya");
        assertThat(hers.get("unread").asInt()).as("the coordinator's badge is parent_unread on a staff row").isEqualTo(1);
        var bell = chatRows(lina);
        assertThat(bell).hasSize(1);
        assertThat(bell.get(0).get("title").asText()).isEqualTo("Message from Maya");
        assertThat(bell.get(0).get("link").asText()).isEqualTo("/coordinator/messages?thread=" + thread);

        // she reads it — which clears her bell — and answers in the same thread
        mvc.perform(as(post("/coordinator/chat/threads/" + thread + "/read"), token(lina, "COORDINATOR"))).andExpect(status().isOk());
        assertThat(chatRows(lina)).isEmpty();
        created(lina, "COORDINATOR", "/coordinator/chat/threads/" + thread + "/messages", "{\"body\":\"It is, until Thursday.\"}");
        assertThat(names(staffGet(maya, "TEACHER", "/teacher/chat/staff-threads/" + thread + "/messages"), "body"))
                .containsExactly("Is the counting unit still on?", "It is, until Thursday.");
        var mayasBell = chatRows(maya);
        assertThat(mayasBell).hasSize(1);
        assertThat(mayasBell.get(0).get("title").asText()).isEqualTo("Message from Lina");
        assertThat(mayasBell.get(0).get("link").asText()).as("the teacher's inbox is /teacher/chat").isEqualTo("/teacher/chat?thread=" + thread);
    }

    @Test @Order(7) void a_coordinator_of_another_subject_is_not_hers_and_naming_two_peers_is_a_bad_request() throws Exception {
        // Omar coordinates english in the American track; Maya teaches British maths, so he is not on her page at all
        mvc.perform(as(post("/teacher/chat/staff-threads").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"coordinatorUserId\":\"" + omar + "\"}"), token(maya, "TEACHER")))
                .andExpect(status().isNotFound());
        mvc.perform(as(post("/teacher/chat/staff-threads").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"managerUserId\":\"" + nour + "\",\"coordinatorUserId\":\"" + lina + "\"}"), token(maya, "TEACHER")))
                .andExpect(status().isBadRequest());
        mvc.perform(as(post("/teacher/chat/staff-threads").contentType(MediaType.APPLICATION_JSON).content("{}"), token(maya, "TEACHER")))
                .andExpect(status().isBadRequest());
        // and a thread of Maya's is not Omar's to read, whichever id he guesses
        String thread = rowOf(staffGet(maya, "TEACHER", "/teacher/chat/staff-threads"), names(staffGet(maya, "TEACHER", "/teacher/chat/staff-threads"), "id").get(0)).get("id").asText();
        mvc.perform(as(get("/coordinator/chat/threads/" + thread + "/messages"), token(omar, "COORDINATOR")))
                .andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- presence

    @Test @Order(5) void a_socket_of_the_managers_makes_her_online_on_the_directory_and_on_the_thread_row() throws Exception {
        assertThat(staffGet(maya, "TEACHER", "/teacher/managers").get(0).get("online").asBoolean())
                .as("nobody is connected in a MockMvc test until we say so").isFalse();
        assertThat(rowOf(staffGet(maya, "TEACHER", "/teacher/chat/staff-threads"), staffThread).get("peerOnline").asBoolean()).isFalse();

        var socket = mock(WebSocketSession.class);
        when(socket.getId()).thenReturn("sd-nour-1");
        sessions.register(socket, new ChatSessions.Peer("user:" + nour, "MANAGERIAL", nour, SCHOOL, null, true));
        try {
            assertThat(staffGet(maya, "TEACHER", "/teacher/managers").get(0).get("online").asBoolean()).isTrue();
            assertThat(rowOf(staffGet(maya, "TEACHER", "/teacher/chat/staff-threads"), staffThread).get("peerOnline").asBoolean()).isTrue();
            // and her own list shows Maya offline, because presence is the peer's and not the row's
            assertThat(rowOf(staffGet(nour, "MANAGERIAL", "/management/chat/threads"), staffThread).get("peerOnline").asBoolean()).isFalse();
        } finally {
            sessions.remove(socket);
        }
        assertThat(staffGet(maya, "TEACHER", "/teacher/managers").get(0).get("online").asBoolean()).isFalse();
        assertThat(rowOf(staffGet(maya, "TEACHER", "/teacher/chat/staff-threads"), staffThread).get("peerOnline").asBoolean()).isFalse();
    }

    // ---------------------------------------------------------------- plumbing

    private void send(String userId, String body) throws Exception {
        created(userId, "TEACHER", "/teacher/chat/staff-threads/" + staffThread + "/messages", "{\"body\":\"" + body + "\"}");
    }

    /** Her unread `chat.message` rows, which is what the throttle and the read-clear are about. */
    private List<JsonNode> chatRows(String userId) throws Exception {
        var out = new ArrayList<JsonNode>();
        staffGet(userId, roleOf(userId), "/me/notifications?unread=true").forEach(row -> {
            if ("chat.message".equals(row.get("kind").asText())) out.add(row);
        });
        return out;
    }

    private String roleOf(String userId) { return users.findById(userId).orElseThrow().getRole(); }

    private static JsonNode rowOf(JsonNode rows, String threadId) {
        for (var row : rows) if (row.get("id") != null && threadId.equals(row.get("id").asText())) return row;
        throw new AssertionError("no thread " + threadId + " in " + rows);
    }

    private String token(String userId, String role) { return jwt.issue(userId, userId + "@seed.test", role, SCHOOL).token(); }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder b, String token) { return b.header("Authorization", "Bearer " + token); }

    private JsonNode staffGet(String userId, String role, String path) throws Exception {
        return json(mvc.perform(as(get(path), token(userId, role))).andExpect(status().isOk()).andReturn());
    }

    private JsonNode created(String userId, String role, String path, String body) throws Exception {
        return json(mvc.perform(as(post(path).contentType(MediaType.APPLICATION_JSON).content(body), token(userId, role)))
                .andExpect(status().isCreated()).andReturn());
    }

    private static List<String> names(JsonNode rows, String field) {
        var out = new ArrayList<String>();
        rows.forEach(row -> out.add(row.get(field) == null || row.get(field).isNull() ? null : row.get(field).asText()));
        return out;
    }

    private static List<Integer> ints(JsonNode rows) {
        var out = new ArrayList<Integer>();
        rows.forEach(row -> out.add(row.asInt()));
        return out;
    }

    private void school() {
        schools.findById(SCHOOL).orElseGet(() -> {
            var s = new SchoolEntity();
            s.setId(SCHOOL); s.setName("Directory School"); s.setCode("SDIR01");
            s.setCurriculumOptionsJson("[\"british\",\"american\"]"); s.setGradeOptionsJson("[1,2,3]");
            s.setStatus("active"); s.setCreatedAt(Instant.now());
            return schools.save(s);
        });
    }

    private void flag(String key, boolean enabled) {
        var row = schoolFlags.findOne(SCHOOL, key).orElseGet(() -> {
            var fresh = new quest.server.flags.Entities.SchoolFeatureFlagEntity();
            fresh.setSchoolId(SCHOOL); fresh.setFlagKey(key);
            return fresh;
        });
        row.setEnabled(enabled); row.setUpdatedBy("test"); row.setUpdatedAt(Instant.now());
        schoolFlags.save(row);
        featureFlags.invalidate(SCHOOL);
    }

    private String idOf(String email) {
        return users.findBySchoolId(SCHOOL).stream().filter(u -> email.equalsIgnoreCase(u.getEmail()))
                .findFirst().orElseThrow(() -> new AssertionError("the seed has no " + email)).getId();
    }
}
