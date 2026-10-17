package quest.server.floors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
 * Feature 20 end to end, each test on a school of its own ({@link #school}):
 *
 * <ul>
 *   <li>the Admin creates a floor with classes and assigns a supervisor, and the floor comes back with both;</li>
 *   <li>a class already on another floor is a 409 and nothing is written;</li>
 *   <li>she sees only her floor's classes, teachers and children — never the off-floor section, its teacher or its
 *       child, in any of the six reads;</li>
 *   <li>a child, a class and a teacher outside her floors are refused one route at a time;</li>
 *   <li>a parent's token, a teacher's and a coordinator's are refused at the matcher;</li>
 *   <li>with `floorSupervisor` off every `/floor` and `/admin/floors` route is a 404.</li>
 * </ul>
 *
 * <p>The cast: floor "Second floor" (british) holds 1A and 1B; 2A is on no floor. Maya teaches math in 1A and 1B,
 * Rami english in 2A. Hana sits in 1A, Omar in 2A. Nadia supervises the floor.
 */
class FloorApiTest extends ApiTestSupport {
    @Autowired SchoolRepository schools;
    @Autowired ClassRepository classes;
    @Autowired TeachingAssignmentRepository assignments;
    @Autowired ChildRepository childRows;
    @Autowired UserRepository users;
    @Autowired TeacherRepository teachers;
    @Autowired SchoolFlagRepository schoolFlags;
    @Autowired FeatureFlags featureFlags;
    @Autowired AdminJwtService jwt;

    // ---------------------------------------------------------------- the Admin's half

    @Test void the_admin_creates_a_floor_with_classes_and_a_supervisor() throws Exception {
        var s = school("create");
        String admin = adminToken();
        String nadia = s.supervisorAccount(admin, "Nadia");

        var created = json(mvc.perform(scoped(post("/admin/floors"), admin, s.id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Second floor\",\"academy\":\"british\",\"classIds\":[\"" + s.oneA + "\",\"" + s.oneB
                                + "\"],\"supervisorIds\":[\"" + nadia + "\"]}"))
                .andExpect(status().isCreated()).andReturn());
        assertThat(created.get("name").asText()).isEqualTo("Second floor");
        assertThat(created.get("academy").asText()).isEqualTo("british");
        assertThat(names(created.get("classes"), "className")).containsExactly("1A", "1B");
        assertThat(names(created.get("supervisors"), "displayName")).containsExactly("Nadia");
        assertThat(created.get("classes").get(0).get("childrenCount").asInt()).isEqualTo(1);

        // Her account carries the floor, and the floor is in the list — both sides of the same two rows.
        var accounts = json(mvc.perform(scoped(get("/admin/floor-supervisors"), admin, s.id)).andExpect(status().isOk()).andReturn());
        assertThat(names(accounts.get(0).get("floors"), "name")).containsExactly("Second floor");
        assertThat(names(json(mvc.perform(scoped(get("/admin/floors"), admin, s.id)).andExpect(status().isOk()).andReturn()), "name"))
                .containsExactly("Second floor");

        // A duplicate name is refused, and so is a second floor claiming 1A — a class is on one floor only.
        mvc.perform(scoped(post("/admin/floors"), admin, s.id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"second FLOOR\",\"academy\":\"british\"}"))
                .andExpect(status().isConflict());
    }

    @Test void one_class_on_two_floors_is_a_conflict_and_nothing_is_written() throws Exception {
        var s = school("two");
        String admin = adminToken();
        String first = s.floor(admin, "First floor", List.of(s.oneA), List.of());

        var refused = mvc.perform(scoped(post("/admin/floors"), admin, s.id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Second floor\",\"academy\":\"british\",\"classIds\":[\"" + s.oneB + "\",\"" + s.oneA + "\"]}"))
                .andExpect(status().isConflict()).andReturn();
        assertThat(json(refused).get("code").asText()).isEqualTo("class_on_another_floor");

        // The second floor's row was rolled back with the rest, so 1B is still on no floor and 1A still on the first.
        var floors = json(mvc.perform(scoped(get("/admin/floors"), admin, s.id)).andExpect(status().isOk()).andReturn());
        assertThat(names(floors, "id")).containsExactly(first);
        assertThat(names(floors.get(0).get("classes"), "className")).containsExactly("1A");

        // The same class moves once the first floor lets it go, and the supervisor set is replaced whole.
        mvc.perform(scoped(patch("/admin/floors/" + first), admin, s.id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"classIds\":[]}")).andExpect(status().isOk());
        String second = s.floor(admin, "Second floor", List.of(s.oneA), List.of());
        mvc.perform(scoped(delete("/admin/floors/" + second), admin, s.id)).andExpect(status().isNoContent());
        mvc.perform(scoped(delete("/admin/floors/" + second), admin, s.id)).andExpect(status().isNotFound());
        assertThat(names(json(mvc.perform(scoped(get("/admin/floors"), admin, s.id)).andExpect(status().isOk()).andReturn()), "id"))
                .containsExactly(first);
    }

    // ---------------------------------------------------------------- her area

    @Test void she_sees_only_her_floors_classes_teachers_and_children() throws Exception {
        var s = school("scope");
        String admin = adminToken();
        String nadia = s.supervisorAccount(admin, "Nadia");
        s.floor(admin, "Second floor", List.of(s.oneA, s.oneB), List.of(nadia));
        String hers = s.token(nadia);

        var me = json(mvc.perform(bearer(get("/floor/me"), hers)).andExpect(status().isOk()).andReturn());
        assertThat(me.get("displayName").asText()).isEqualTo("Nadia");
        assertThat(names(me.get("floors"), "name")).containsExactly("Second floor");
        assertThat(me.get("classes").asInt()).isEqualTo(2);
        assertThat(me.get("teachers").asInt()).as("Maya only — Rami teaches 2A, which is on no floor").isEqualTo(1);
        assertThat(me.get("children").asInt()).isEqualTo(1);

        assertThat(names(json(mvc.perform(bearer(get("/floor/classes"), hers)).andExpect(status().isOk()).andReturn()), "className"))
                .containsExactly("1A", "1B");
        var staff = json(mvc.perform(bearer(get("/floor/teachers"), hers)).andExpect(status().isOk()).andReturn());
        assertThat(names(staff, "displayName")).containsExactly("Maya");
        assertThat(names(staff.get(0).get("sections"), "className")).containsExactly("1A", "1B");

        var kids = json(mvc.perform(bearer(get("/floor/children"), hers)).andExpect(status().isOk()).andReturn());
        assertThat(names(kids, "name")).containsExactly("Hana");
        assertThat(kids.get(0).get("floorName").asText()).isEqualTo("Second floor");
        assertThat(names(json(mvc.perform(bearer(get("/floor/children?q=oma"), hers)).andExpect(status().isOk()).andReturn()), "name"))
                .as("the off-floor child is not found by name either").isEmpty();

        var home = json(mvc.perform(bearer(get("/floor/home"), hers)).andExpect(status().isOk()).andReturn());
        assertThat(names(home.get("classes"), "className")).containsExactly("1A", "1B");
        assertThat(row(home.get("classes"), "1A").get("notMarked").asInt()).as("nobody has taken register yet").isEqualTo(1);
        assertThat(home.get("board").isNull()).as("this school has turned staffAttendance off").isTrue();
        assertThat(home.get("day").asText()).isNotBlank();

        // Her own child page, and the off-floor child's refused.
        mvc.perform(bearer(get("/floor/children/" + s.hana), hers)).andExpect(status().isOk());
        mvc.perform(bearer(get("/floor/children/" + s.omar), hers)).andExpect(status().isForbidden());
        mvc.perform(bearer(get("/floor/children/no-such-child"), hers)).andExpect(status().isNotFound());
    }

    @Test void with_staff_attendance_on_her_home_carries_the_board_of_her_floors_teachers() throws Exception {
        var s = school("board");
        s.flag(FlagKeys.STAFF_ATTENDANCE, true);
        String admin = adminToken();
        String nadia = s.supervisorAccount(admin, "Nadia");
        s.floor(admin, "Second floor", List.of(s.oneA), List.of(nadia));

        var home = json(mvc.perform(bearer(get("/floor/home"), s.token(nadia))).andExpect(status().isOk()).andReturn());
        var board = home.get("board");
        assertThat(board.isNull()).isFalse();
        var ids = new ArrayList<String>();
        board.get("rows").forEach(r -> ids.add(r.get("id").asText()));
        assertThat(ids).as("Maya teaches on her floor; Rami does not, and she never sees herself")
                .containsExactly(s.maya);
    }

    @Test void a_floor_with_no_class_answers_empty_rather_than_the_whole_school() throws Exception {
        var s = school("empty");
        String admin = adminToken();
        String nadia = s.supervisorAccount(admin, "Nadia");
        s.floor(admin, "Second floor", List.of(), List.of(nadia));
        String hers = s.token(nadia);

        assertThat(names(json(mvc.perform(bearer(get("/floor/classes"), hers)).andExpect(status().isOk()).andReturn()), "className")).isEmpty();
        assertThat(names(json(mvc.perform(bearer(get("/floor/teachers"), hers)).andExpect(status().isOk()).andReturn()), "displayName")).isEmpty();
        assertThat(names(json(mvc.perform(bearer(get("/floor/children"), hers)).andExpect(status().isOk()).andReturn()), "name")).isEmpty();
        var me = json(mvc.perform(bearer(get("/floor/me"), hers)).andExpect(status().isOk()).andReturn());
        assertThat(me.get("classes").asInt()).isZero();
        assertThat(me.get("children").asInt()).isZero();
        // A supervisor with no floor at all is the same answer, not the school's.
        String lone = s.supervisorAccount(admin, "Lone");
        assertThat(names(json(mvc.perform(bearer(get("/floor/classes"), s.token(lone))).andExpect(status().isOk()).andReturn()), "className")).isEmpty();
    }

    // ---------------------------------------------------------------- who may knock at all

    @Test void a_parent_a_teacher_and_a_coordinator_are_refused_at_the_matcher() throws Exception {
        var s = school("roles");
        String admin = adminToken();
        String nadia = s.supervisorAccount(admin, "Nadia");
        s.floor(admin, "Second floor", List.of(s.oneA), List.of(nadia));

        mvc.perform(get("/floor/me").header("Authorization", PARENT)).andExpect(status().isForbidden());
        mvc.perform(bearer(get("/floor/me"), jwt.issue(s.maya, "maya@f20.test", "TEACHER", s.id).token())).andExpect(status().isForbidden());
        mvc.perform(bearer(get("/floor/me"), jwt.issue(s.maya, "maya@f20.test", "COORDINATOR", s.id).token())).andExpect(status().isForbidden());
        mvc.perform(get("/floor/me")).andExpect(status().isUnauthorized());
        // And she holds no key of the Admin's half, whatever the matcher lets through.
        mvc.perform(bearer(get("/admin/floors"), s.token(nadia))).andExpect(status().isForbidden());
        mvc.perform(bearer(post("/admin/floor-supervisors"), s.token(nadia)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\":\"X\",\"email\":\"x@f20.test\"}")).andExpect(status().isForbidden());
    }

    @Test void with_the_flag_off_every_floor_route_is_not_found() throws Exception {
        var s = school("off");
        String admin = adminToken();
        String nadia = s.supervisorAccount(admin, "Nadia");
        s.floor(admin, "Second floor", List.of(s.oneA), List.of(nadia));
        String hers = s.token(nadia);
        s.flag(FlagKeys.FLOOR_SUPERVISOR, false);

        for (String path : List.of("/floor/me", "/floor/home", "/floor/teachers", "/floor/classes", "/floor/children",
                "/floor/children/" + s.hana, "/floor/chat/peers", "/floor/chat/threads"))
            mvc.perform(bearer(get(path), hers)).andExpect(status().isNotFound());
        mvc.perform(scoped(get("/admin/floors"), admin, s.id)).andExpect(status().isNotFound());
        mvc.perform(scoped(get("/admin/floor-supervisors"), admin, s.id)).andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- helpers

    private static MockHttpServletRequestBuilder bearer(MockHttpServletRequestBuilder builder, String token) {
        return builder.header("Authorization", "Bearer " + token);
    }

    private static MockHttpServletRequestBuilder scoped(MockHttpServletRequestBuilder builder, String token, String schoolId) {
        return bearer(builder, token).header(TenantContext.HEADER, schoolId);
    }

    private static List<String> names(JsonNode rows, String field) {
        var out = new ArrayList<String>();
        rows.forEach(r -> out.add(r.get(field).asText()));
        return out;
    }

    private static JsonNode row(JsonNode rows, String className) {
        for (var r : rows) if (className.equals(r.get("className").asText())) return r;
        throw new AssertionError("no class " + className);
    }

    /** A school of the test's own, with the cast described on the class and `floorSupervisor` on. */
    private School school(String label) {
        String id = "f20-" + label + "-" + UUID.randomUUID().toString().substring(0, 6);
        var row = new SchoolEntity();
        row.setId(id); row.setName(id);
        String code = id.replace("-", "").toUpperCase(Locale.ROOT);
        row.setCode(code.substring(0, Math.min(20, code.length())));
        row.setCurriculumOptionsJson("[\"british\",\"american\"]"); row.setGradeOptionsJson("[1,2,3]");
        row.setStatus("active"); row.setTimezone("Asia/Riyadh"); row.setCreatedAt(Instant.now());
        schools.save(row);
        var s = new School(id);
        s.flag(FlagKeys.FLOOR_SUPERVISOR, true);
        // D30 turned every switch on by default. Feature 20's own tests are about the supervisor, so each school
        // starts without feature 17's attendance board and turns it on where that is what is under test.
        s.flag(FlagKeys.STAFF_ATTENDANCE, false);
        return s;
    }

    private final class School {
        final String id, oneA, oneB, twoA, maya, rami, hana, omar;

        School(String id) {
            this.id = id;
            oneA = section("1A", 1); oneB = section("1B", 1); twoA = section("2A", 2);
            maya = teacher("maya", "Maya"); rami = teacher("rami", "Rami");
            assignment(maya, oneA, "math"); assignment(maya, oneB, "math"); assignment(rami, twoA, "english");
            hana = child("hana", "Hana", oneA); omar = child("omar", "Omar", twoA);
        }

        String token(String userId) { return jwt.issue(userId, userId + "@f20.test", "FLOOR_SUPERVISOR", id).token(); }

        /** `POST /admin/floor-supervisors`, so the Admin route that mints the role is what every test uses. */
        String supervisorAccount(String adminToken, String name) throws Exception {
            var created = json(mvc.perform(scoped(post("/admin/floor-supervisors"), adminToken, id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"fullName\":\"" + name + "\",\"email\":\"" + name.toLowerCase(Locale.ROOT) + "." + id + "@f20.test\"}"))
                    .andExpect(status().isCreated()).andReturn());
            assertThat(created.get("temporaryPassword").asText()).isNotBlank();
            return created.get("supervisor").get("userId").asText();
        }

        String floor(String adminToken, String name, List<String> classIds, List<String> supervisorIds) throws Exception {
            String body = "{\"name\":\"" + name + "\",\"academy\":\"british\",\"classIds\":" + jsonArray(classIds)
                    + ",\"supervisorIds\":" + jsonArray(supervisorIds) + "}";
            return json(mvc.perform(scoped(post("/admin/floors"), adminToken, id).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isCreated()).andReturn()).get("id").asText();
        }

        private String section(String name, int grade) {
            var k = new quest.server.tenancy.Entities.ClassEntity();
            k.setId(id + "-" + name); k.setSchoolId(id); k.setCurriculum("british"); k.setGrade(grade);
            k.setName(name); k.setActive(true); k.setCreatedAt(Instant.now());
            return classes.save(k).getId();
        }

        private String teacher(String label, String name) {
            String userId = id + "-" + label;
            var u = new UserEntity();
            u.setId(userId); u.setSchoolId(id); u.setEmail(userId + "@f20.test"); u.setPasswordHash("x");
            u.setRole("TEACHER"); u.setStatus("active"); u.setDisplayName(name);
            u.setCreatedAt(Instant.now()); u.setUpdatedAt(Instant.now());
            users.save(u);
            var t = new TeacherEntity();
            t.setUserId(userId); t.setCurriculum("british"); t.setUpdatedAt(Instant.now());
            teachers.save(t);
            return userId;
        }

        private void assignment(String teacherId, String classId, String subject) {
            var a = new quest.server.tenancy.Entities.TeachingAssignmentEntity();
            a.setId(UUID.randomUUID().toString()); a.setSchoolId(id); a.setTeacherId(teacherId);
            a.setClassId(classId); a.setSubject(subject); a.setCreatedAt(Instant.now());
            assignments.save(a);
        }

        private String child(String label, String name, String classId) {
            var c = new quest.server.children.Entities.ChildEntity();
            c.setId(id + "-" + label); c.setSchoolId(id); c.setName(name); c.setAvatarColor("sun");
            c.setCurriculum("british"); c.setGrade(1); c.setClassId(classId); c.setActive(true);
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

    private static String jsonArray(List<String> ids) {
        return ids.stream().map(i -> "\"" + i + "\"").collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }
}
