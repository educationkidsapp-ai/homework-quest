package quest.server.floors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import quest.server.ApiTestSupport;
import quest.server.auth.AdminJwtService;
import quest.server.auth.Entities.TeacherEntity;
import quest.server.auth.Entities.UserEntity;
import quest.server.auth.TeacherRepository;
import quest.server.auth.UserRepository;
import quest.server.children.ChildRepository;
import quest.server.flags.FeatureFlags;
import quest.server.flags.FlagKeys;
import quest.server.flags.SchoolFlagRepository;
import quest.server.tenancy.ClassRepository;
import quest.server.tenancy.Entities.SchoolEntity;
import quest.server.tenancy.SchoolRepository;
import quest.server.tenancy.TeachingAssignmentRepository;
import quest.server.tenancy.TenantContext;

/**
 * Feature 20's Messages, on a school of this test's own: she writes to a parent of her floor and to a teacher on it,
 * both sides read the thread, and anybody outside her floors is refused.
 *
 * <p>The cast: floor "Second floor" (british) holds 1A; 2A is on no floor. Maya teaches 1A, Rami 2A. Hana (the
 * test's own parent's child) sits in 1A and Omar in 2A. Dalia is the school's manager. `chat` and `floorSupervisor`
 * are both on.
 */
class FloorChatApiTest extends ApiTestSupport {
    @Autowired SchoolRepository schools;
    @Autowired ClassRepository classes;
    @Autowired TeachingAssignmentRepository assignments;
    @Autowired ChildRepository childRows;
    @Autowired UserRepository users;
    @Autowired TeacherRepository teachers;
    @Autowired SchoolFlagRepository schoolFlags;
    @Autowired FeatureFlags featureFlags;
    @Autowired AdminJwtService jwt;

    @Test void she_messages_a_parent_of_her_floor_and_the_parent_answers() throws Exception {
        var s = school("parent");
        String hers = s.token(s.nadia);

        var thread = json(mvc.perform(bearer(post("/floor/chat/threads"), hers).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"childId\":\"" + s.hana + "\"}"))
                .andExpect(status().isCreated()).andReturn());
        assertThat(thread.get("staffRole").asText()).isEqualTo("FLOOR_SUPERVISOR");
        assertThat(thread.get("childId").asText()).isEqualTo(s.hana);
        String id = thread.get("id").asText();

        mvc.perform(bearer(post("/floor/chat/threads/" + id + "/messages"), hers).contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"Hana left her bag on the floor.\"}")).andExpect(status().isCreated());

        // The parent's own list names her by her role, and she may answer the thread she did not start.
        var mine = json(mvc.perform(get("/children/" + s.hana + "/chat/threads").header("Authorization", PARENT))
                .andExpect(status().isOk()).andReturn());
        var row = row(mine, s.nadia);
        assertThat(row.get("staffRole").asText()).isEqualTo("FLOOR_SUPERVISOR");
        assertThat(row.get("peerRole").asText()).isEqualTo("FLOOR_SUPERVISOR");
        assertThat(row.get("unread").asInt()).isEqualTo(1);
        mvc.perform(post("/children/" + s.hana + "/chat/threads/" + s.nadia + "/messages").header("Authorization", PARENT)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"Thank you.\"}"))
                .andExpect(status().isCreated());

        // And back on her side: one thread, two messages, and the read receipt clears her badge.
        var threads = json(mvc.perform(bearer(get("/floor/chat/threads"), hers)).andExpect(status().isOk()).andReturn());
        assertThat(ids(threads)).containsExactly(id);
        assertThat(ids(json(mvc.perform(bearer(get("/floor/chat/threads/" + id + "/messages"), hers)).andExpect(status().isOk()).andReturn())))
                .hasSize(2);
        mvc.perform(bearer(post("/floor/chat/threads/" + id + "/read"), hers)).andExpect(status().isOk());
        assertThat(json(mvc.perform(bearer(get("/floor/chat/threads"), hers)).andExpect(status().isOk()).andReturn())
                .get(0).get("unread").asInt()).isZero();
    }

    @Test void she_messages_a_teacher_on_her_floor_and_the_teacher_reads_it() throws Exception {
        var s = school("teacher");
        String hers = s.token(s.nadia);

        var thread = json(mvc.perform(bearer(post("/floor/chat/threads"), hers).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"teacherUserId\":\"" + s.maya + "\"}"))
                .andExpect(status().isCreated()).andReturn());
        assertThat(thread.get("staffRole").asText()).isEqualTo("FLOOR_SUPERVISOR");
        String id = thread.get("id").asText();
        mvc.perform(bearer(post("/floor/chat/threads/" + id + "/messages"), hers).contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"1A is noisy this morning.\"}")).andExpect(status().isCreated());

        // The teacher's own staff-thread list carries it, because a floor supervisor of her sections is one of hers.
        String maya = jwt.issue(s.maya, s.maya + "@f20c.test", "TEACHER", s.id).token();
        var staffThreads = json(mvc.perform(bearer(get("/teacher/chat/staff-threads"), maya)).andExpect(status().isOk()).andReturn());
        assertThat(ids(staffThreads)).contains(id);
        mvc.perform(bearer(post("/teacher/chat/staff-threads/" + id + "/messages"), maya).contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"I will sit them down.\"}")).andExpect(status().isCreated());
        assertThat(ids(json(mvc.perform(bearer(get("/floor/chat/threads/" + id + "/messages"), hers)).andExpect(status().isOk()).andReturn())))
                .hasSize(2);
    }

    @Test void she_messages_a_manager_and_the_admin_from_the_chooser_the_route_offers() throws Exception {
        var s = school("manager");
        String hers = s.token(s.nadia);
        var peers = json(mvc.perform(bearer(get("/floor/chat/peers"), hers)).andExpect(status().isOk()).andReturn());
        assertThat(names(peers.get("managers"))).containsExactly(s.dalia);
        assertThat(names(peers.get("admins"))).isNotEmpty();
        String admin = peers.get("admins").get(0).get("userId").asText();

        var withManager = json(mvc.perform(bearer(post("/floor/chat/threads"), hers).contentType(MediaType.APPLICATION_JSON)
                .content("{\"managerUserId\":\"" + s.dalia + "\"}")).andExpect(status().isCreated()).andReturn());
        assertThat(withManager.get("staffRole").asText()).isEqualTo("MANAGERIAL");
        var withAdmin = json(mvc.perform(bearer(post("/floor/chat/threads"), hers).contentType(MediaType.APPLICATION_JSON)
                .content("{\"adminUserId\":\"" + admin + "\"}")).andExpect(status().isCreated()).andReturn());
        assertThat(withAdmin.get("withAdmin").asBoolean()).isTrue();
        mvc.perform(bearer(post("/floor/chat/threads/" + withManager.get("id").asText() + "/messages"), hers)
                .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"The lift is broken.\"}")).andExpect(status().isCreated());
        assertThat(ids(json(mvc.perform(bearer(get("/floor/chat/threads"), hers)).andExpect(status().isOk()).andReturn())))
                .contains(withManager.get("id").asText());
    }

    @Test void anybody_outside_her_floors_is_refused() throws Exception {
        var s = school("refused");
        String hers = s.token(s.nadia);

        // A child of the off-floor section: 403, the answer `FloorScope.requireChild` gives everywhere.
        mvc.perform(bearer(post("/floor/chat/threads"), hers).contentType(MediaType.APPLICATION_JSON)
                .content("{\"childId\":\"" + s.omar + "\"}")).andExpect(status().isForbidden());
        // A teacher who teaches only there: 404 — she is not told which staff exist beyond her own floors.
        mvc.perform(bearer(post("/floor/chat/threads"), hers).contentType(MediaType.APPLICATION_JSON)
                .content("{\"teacherUserId\":\"" + s.rami + "\"}")).andExpect(status().isNotFound());
        // Two ids, or none, is a 400: each names a different conversation.
        mvc.perform(bearer(post("/floor/chat/threads"), hers).contentType(MediaType.APPLICATION_JSON)
                .content("{\"childId\":\"" + s.hana + "\",\"teacherUserId\":\"" + s.maya + "\"}")).andExpect(status().isBadRequest());
        mvc.perform(bearer(post("/floor/chat/threads"), hers).contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isBadRequest());

        // A thread she is on neither end of — the Admin's with the off-floor teacher: 404 on all three routes.
        String elsewhere = json(mvc.perform(scoped(post("/admin/chat/threads"), adminToken(), s.id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"teacherUserId\":\"" + s.rami + "\"}")).andExpect(status().isCreated()).andReturn())
                .get("id").asText();
        mvc.perform(bearer(get("/floor/chat/threads/" + elsewhere + "/messages"), hers)).andExpect(status().isNotFound());
        mvc.perform(bearer(post("/floor/chat/threads/" + elsewhere + "/messages"), hers).contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"hello\"}")).andExpect(status().isNotFound());
        mvc.perform(bearer(post("/floor/chat/threads/" + elsewhere + "/read"), hers)).andExpect(status().isNotFound());
        // And her list never carries it.
        assertThat(ids(json(mvc.perform(bearer(get("/floor/chat/threads"), hers)).andExpect(status().isOk()).andReturn())))
                .doesNotContain(elsewhere);
    }

    @Test void with_chat_off_her_messages_are_not_found_even_with_the_role_switched_on() throws Exception {
        var s = school("nochat");
        s.flag(FlagKeys.CHAT, false);
        String hers = s.token(s.nadia);
        mvc.perform(bearer(get("/floor/chat/threads"), hers)).andExpect(status().isNotFound());
        mvc.perform(bearer(post("/floor/chat/threads"), hers).contentType(MediaType.APPLICATION_JSON)
                .content("{\"childId\":\"" + s.hana + "\"}")).andExpect(status().isNotFound());
        mvc.perform(bearer(get("/floor/me"), hers)).andExpect(status().isOk());   // the rest of her area is untouched
    }

    // ---------------------------------------------------------------- helpers

    private static MockHttpServletRequestBuilder bearer(MockHttpServletRequestBuilder builder, String token) {
        return builder.header("Authorization", "Bearer " + token);
    }

    private static MockHttpServletRequestBuilder scoped(MockHttpServletRequestBuilder builder, String token, String schoolId) {
        return bearer(builder, token).header(TenantContext.HEADER, schoolId);
    }

    private static List<String> ids(JsonNode rows) {
        var out = new ArrayList<String>();
        rows.forEach(r -> out.add(r.path("id").asText(null)));
        return out;
    }

    private static List<String> names(JsonNode rows) {
        var out = new ArrayList<String>();
        rows.forEach(r -> out.add(r.get("userId").asText()));
        return out;
    }

    private static JsonNode row(JsonNode rows, String staffId) {
        for (var r : rows) if (staffId.equals(r.path("teacherId").asText(null))) return r;
        throw new AssertionError("no thread with " + staffId);
    }

    private School school(String label) {
        String id = "f20c-" + label + "-" + UUID.randomUUID().toString().substring(0, 6);
        var row = new SchoolEntity();
        row.setId(id); row.setName(id);
        String code = id.replace("-", "").toUpperCase(Locale.ROOT);
        row.setCode(code.substring(0, Math.min(20, code.length())));
        row.setCurriculumOptionsJson("[\"british\",\"american\"]"); row.setGradeOptionsJson("[1,2,3]");
        row.setStatus("active"); row.setTimezone("Asia/Riyadh"); row.setCreatedAt(Instant.now());
        schools.save(row);
        var s = new School(id, row.getCode());
        s.flag(FlagKeys.FLOOR_SUPERVISOR, true);
        s.flag(FlagKeys.CHAT, true);
        try { s.build(); } catch (Exception e) { throw new IllegalStateException(e); }
        return s;
    }

    private final class School {
        final String id, code;
        String oneA, twoA, maya, rami, dalia, nadia, hana, omar;

        School(String id, String code) { this.id = id; this.code = code; }

        void build() throws Exception {
            oneA = section("1A", 1); twoA = section("2A", 2);
            maya = staff("maya", "TEACHER", "Maya"); rami = staff("rami", "TEACHER", "Rami");
            teacherProfile(maya); teacherProfile(rami);
            dalia = staff("dalia", "MANAGERIAL", "Dalia");
            assignment(maya, oneA, "math"); assignment(rami, twoA, "english");
            hana = parentChild("Hana", oneA);
            omar = child("omar", "Omar", twoA);
            String admin = adminToken();
            nadia = json(mvc.perform(scoped(post("/admin/floor-supervisors"), admin, id).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"fullName\":\"Nadia\",\"email\":\"nadia." + id + "@f20c.test\"}"))
                    .andExpect(status().isCreated()).andReturn()).get("supervisor").get("userId").asText();
            mvc.perform(scoped(post("/admin/floors"), admin, id).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"Second floor\",\"academy\":\"british\",\"classIds\":[\"" + oneA
                                    + "\"],\"supervisorIds\":[\"" + nadia + "\"]}"))
                    .andExpect(status().isCreated());
        }

        String token(String userId) { return jwt.issue(userId, userId + "@f20c.test", "FLOOR_SUPERVISOR", id).token(); }

        private String section(String name, int grade) {
            var k = new quest.server.tenancy.Entities.ClassEntity();
            k.setId(id + "-" + name); k.setSchoolId(id); k.setCurriculum("british"); k.setGrade(grade);
            k.setName(name); k.setActive(true); k.setCreatedAt(Instant.now());
            return classes.save(k).getId();
        }

        private String staff(String label, String role, String name) {
            String userId = id + "-" + label;
            var u = new UserEntity();
            u.setId(userId); u.setSchoolId(id); u.setEmail(userId + "@f20c.test"); u.setPasswordHash("x");
            u.setRole(role); u.setStatus("active"); u.setDisplayName(name);
            u.setCreatedAt(Instant.now()); u.setUpdatedAt(Instant.now());
            users.save(u);
            return userId;
        }

        private void teacherProfile(String userId) {
            var t = new TeacherEntity();
            t.setUserId(userId); t.setCurriculum("british"); t.setUpdatedAt(Instant.now());
            teachers.save(t);
        }

        private void assignment(String teacherId, String classId, String subject) {
            var a = new quest.server.tenancy.Entities.TeachingAssignmentEntity();
            a.setId(UUID.randomUUID().toString()); a.setSchoolId(id); a.setTeacherId(teacherId);
            a.setClassId(classId); a.setSubject(subject); a.setCreatedAt(Instant.now());
            assignments.save(a);
        }

        /** A child of this test's own parent, so the parent half of the thread is a real account. */
        private String parentChild(String name, String classId) throws Exception {
            String childId = parentPost("/children", "{\"name\":\"" + name + "\",\"avatarColor\":\"sun\","
                    + "\"curriculum\":\"british\",\"grade\":1,\"schoolCode\":\"" + code + "\"}").get("id").asText();
            childRows.findById(childId).ifPresent(c -> { c.setClassId(classId); childRows.save(c); });
            return childId;
        }

        private String child(String label, String name, String classId) {
            var c = new quest.server.children.Entities.ChildEntity();
            c.setId(id + "-" + label); c.setSchoolId(id); c.setName(name); c.setAvatarColor("sun");
            c.setCurriculum("british"); c.setGrade(2); c.setClassId(classId); c.setActive(true);
            c.setCreatedAt(Instant.now());
            return childRows.save(c).getId();
        }

        void flag(String key, boolean enabled) {
            var row = schoolFlags.findOne(id, key).orElseGet(() -> {
                var fresh = new quest.server.flags.Entities.SchoolFeatureFlagEntity();
                fresh.setSchoolId(id); fresh.setFlagKey(key);
                return fresh;
            });
            row.setEnabled(enabled); row.setUpdatedBy("test"); row.setUpdatedAt(Instant.now());
            schoolFlags.save(row);
            featureFlags.invalidate(id);
        }
    }
}
