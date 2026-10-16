package quest.server.materials;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import quest.server.ApiTestSupport;
import quest.server.auth.AdminJwtService;
import quest.server.auth.Entities.TeacherEntity;
import quest.server.auth.Entities.UserEntity;
import quest.server.auth.TeacherRepository;
import quest.server.auth.UserRepository;
import quest.server.flags.FeatureFlags;
import quest.server.flags.FlagKeys;
import quest.server.flags.SchoolFlagRepository;
import quest.server.tenancy.Entities.SchoolEntity;
import quest.server.tenancy.Entities.StaffScopeEntity;
import quest.server.tenancy.SchoolRepository;
import quest.server.tenancy.StaffScopeRepository;
import quest.server.tenancy.TenantContext;

/**
 * Feature 31 end to end: what a teacher is expected to hand in, on time, late and missing around the deadline in the
 * school's own zone, the leaders' board with <strong>feature 17's own scope</strong>, the one review, the weekly
 * reminder, the file's readers, school isolation and the switch.
 *
 * <p>Each test builds its own school ({@link #school}) in Riyadh on the default Gulf week, with the clock pinned to
 * Sunday 2026-10-18 09:00. The material for the week beginning Sunday 2026-10-18 was therefore due on
 * <strong>Thursday 2026-10-15 at 12:00 Riyadh</strong> — the deadline falls in the week before the week it is for,
 * because a teacher prepares the coming week while the current one runs.
 *
 * <p>The cast is {@code AttendanceBoardApiTest}'s, so the two boards can be compared row for row:
 * <ul>
 *   <li>{@code director}: a manager whose department row names `british` — the British academy's director;</li>
 *   <li>{@code british}: a British teacher, two assignments (1A · math, 1A · english); her name is Arabic;</li>
 *   <li>{@code american}: an American teacher (2A · math) whose direct manager is {@code lead};</li>
 *   <li>{@code lead}: an American teacher (3A · math) who leads {@code american};</li>
 *   <li>{@code loner}: a teacher (4A · math) who leads nobody;</li>
 *   <li>{@code nadia}: a floor supervisor whose floor holds 1A.</li>
 * </ul>
 */
class TeachingMaterialsApiTest extends ApiTestSupport {
    private static final ZoneId RIYADH = ZoneId.of("Asia/Riyadh");
    private static final LocalDate SUNDAY = LocalDate.of(2026, 10, 18);
    private static final LocalDate DEADLINE_DAY = LocalDate.of(2026, 10, 15);
    private static final String WEEK = "?week=2026-10-18";

    @Autowired SchoolRepository schools;
    @Autowired UserRepository users;
    @Autowired TeacherRepository teachers;
    @Autowired StaffScopeRepository scopes;
    @Autowired SchoolFlagRepository schoolFlags;
    @Autowired FeatureFlags featureFlags;
    @Autowired AdminJwtService jwt;
    @Autowired quest.server.tenancy.ClassRepository sections;
    @Autowired quest.server.tenancy.TeachingAssignmentRepository teachingAssignments;
    @Autowired quest.server.staffattendance.StaffAttendanceProfileRepository profiles;
    @Autowired quest.server.floors.FloorRepository floors;
    @Autowired quest.server.floors.FloorClassRepository floorClasses;
    @Autowired quest.server.floors.FloorSupervisorRepository floorSupervisors;
    @Autowired MaterialReminderJob job;

    @BeforeEach void pin() { clock.pinTo(SUNDAY.atTime(9, 0).atZone(RIYADH).toInstant()); }
    @AfterEach void unpin() { clock.release(); }

    // ---------------------------------------------------------------- what is expected

    @Test void the_expected_rows_are_her_teaching_assignments_and_start_as_missing() throws Exception {
        var s = school("exp");
        var page = ok(bearer(get("/teacher/materials" + WEEK), s.token(s.british)));
        assertThat(page.get("week").asText()).isEqualTo("2026-10-18");
        assertThat(page.get("timeZone").asText()).isEqualTo("Asia/Riyadh");
        assertThat(page.get("dueAt").asText()).isEqualTo(DEADLINE_DAY.atTime(12, 0).atZone(RIYADH).toInstant().toString());
        assertThat(subjects(page)).as("both of her assignments on 1A, in subject order").containsExactly("english", "math");
        assertThat(page.get("rows").get(0).get("className").asText()).isEqualTo("1A");
        assertThat(page.get("rows")).allSatisfy(r -> assertThat(r.get("status").asText()).isEqualTo("MISSING"));
        assertThat(page.get("counts").get("missing").asInt()).isEqualTo(2);
        // The American teacher's own page is hers alone: one row, her own class, never 1A's.
        assertThat(subjects(ok(bearer(get("/teacher/materials" + WEEK), s.token(s.american))))).containsExactly("math");

        // No `week=` is the current teaching week, which on a pinned Sunday is this very week.
        assertThat(ok(bearer(get("/teacher/materials"), s.token(s.british))).get("week").asText()).isEqualTo("2026-10-18");
    }

    @Test void on_time_late_and_missing_are_decided_by_the_deadline_in_the_schools_zone() throws Exception {
        var s = school("when");
        // An hour before Thursday noon in Riyadh — which is still Thursday morning UTC, so the zone is what decides.
        clock.pinTo(DEADLINE_DAY.atTime(11, 0).atZone(RIYADH).toInstant());
        assertThat(hand(s, s.british, "math", "{\"note\":\"Unit 3 ready\"}").get("status").asText()).isEqualTo("ON_TIME");
        clock.pinTo(DEADLINE_DAY.atTime(12, 1).atZone(RIYADH).toInstant());
        assertThat(hand(s, s.british, "english", "{\"note\":\"A day late\"}").get("status").asText()).isEqualTo("LATE");

        clock.pinTo(SUNDAY.atTime(9, 0).atZone(RIYADH).toInstant());
        var page = ok(bearer(get("/teacher/materials" + WEEK), s.token(s.british)));
        assertThat(page.get("counts").get("onTime").asInt()).isEqualTo(1);
        assertThat(page.get("counts").get("late").asInt()).isEqualTo(1);
        assertThat(page.get("counts").get("missing").asInt()).isZero();
        // Replacing a row that nobody has reviewed is the same row, not a second one.
        assertThat(hand(s, s.british, "math", "{\"note\":\"Unit 3, corrected\"}").get("note").asText()).isEqualTo("Unit 3, corrected");
        assertThat(ok(bearer(get("/teacher/materials" + WEEK), s.token(s.british))).get("rows")).hasSize(2);
    }

    @Test void a_teacher_cannot_hand_in_for_a_class_or_a_subject_that_is_not_hers() throws Exception {
        var s = school("mine");
        mvc.perform(bearer(put("/teacher/materials/" + s.classOf("2A") + "/math" + WEEK)
                .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"not mine\"}"), s.token(s.british)))
                .andExpect(status().isForbidden());
        mvc.perform(bearer(put("/teacher/materials/" + s.classOf("1A") + "/science" + WEEK)
                .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"not my subject\"}"), s.token(s.british)))
                .andExpect(status().isForbidden());
        // Neither an attachment nor a note is nothing to hand in.
        mvc.perform(bearer(put("/teacher/materials/" + s.classOf("1A") + "/math" + WEEK)
                .contentType(MediaType.APPLICATION_JSON).content("{}"), s.token(s.british)))
                .andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------- the leaders' board: feature 17's scope

    @Test void the_board_shows_exactly_the_teachers_the_attendance_board_shows_that_leader() throws Exception {
        var s = school("scope");
        String admin = adminToken();
        var all = ok(scoped(get("/materials/board" + WEEK), admin, s.id));
        assertThat(all.get("scope").asText()).isEqualTo("SCHOOL");
        assertThat(teacherIds(all)).containsExactlyInAnyOrder(s.british, s.american, s.lead, s.loner);
        assertThat(all.get("counts").get("missing").asInt()).as("five assignments, nothing handed in").isEqualTo(5);
        assertThat(ids(ok(scoped(get("/staff-attendance/board"), admin, s.id))))
                .as("the attendance board's rows are the same people, plus the ones who do not teach")
                .containsAll(teacherIds(all));

        var academy = ok(bearer(get("/materials/board" + WEEK), s.token(s.director, "MANAGERIAL")));
        assertThat(academy.get("scope").asText()).isEqualTo("ACADEMY");
        assertThat(teacherIds(academy)).containsExactly(s.british);

        var reports = ok(bearer(get("/materials/board" + WEEK), s.token(s.lead)));
        assertThat(reports.get("scope").asText()).isEqualTo("REPORTS");
        assertThat(teacherIds(reports)).as("her one direct report, never herself").containsExactly(s.american);

        var floor = ok(bearer(get("/materials/board" + WEEK), s.token(s.nadia, quest.server.tenancy.FloorScope.ROLE)));
        assertThat(teacherIds(floor)).as("the teacher of her floor's class").containsExactly(s.british);

        assertThat(ok(bearer(get("/materials/board" + WEEK), s.token(s.loner))).get("rows"))
                .as("a teacher who leads nobody sees an empty board").isEmpty();
    }

    @Test void a_row_outside_the_callers_scope_is_not_found() throws Exception {
        var s = school("outside");
        String id = hand(s, s.british, "math", "{\"note\":\"hers\"}").get("submissionId").asText();
        // The British teacher is not the American lead's report and not on nobody's floor: 404, as the board's own.
        mvc.perform(bearer(post("/materials/" + id + "/review").contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"no\"}"),
                s.token(s.lead))).andExpect(status().isNotFound());
        mvc.perform(bearer(post("/materials/" + UUID.randomUUID() + "/review").contentType(MediaType.APPLICATION_JSON).content("{}"),
                s.token(s.director, "MANAGERIAL"))).andExpect(status().isNotFound());
    }

    @Test void a_row_is_reviewed_once_and_then_neither_reviewed_nor_replaced_again() throws Exception {
        var s = school("review");
        String id = hand(s, s.british, "math", "{\"note\":\"Unit 3\"}").get("submissionId").asText();
        String director = s.token(s.director, "MANAGERIAL");
        var reviewed = ok(bearer(post("/materials/" + id + "/review").contentType(MediaType.APPLICATION_JSON)
                .content("{\"comment\":\"Well prepared\"}"), director));
        assertThat(reviewed.get("status").asText()).isEqualTo("REVIEWED");
        assertThat(reviewed.get("reviewedBy").asText()).isEqualTo(s.director);
        assertThat(reviewed.get("reviewedByName").asText()).isEqualTo("Dalia Director");
        assertThat(reviewed.get("reviewComment").asText()).isEqualTo("Well prepared");

        var again = mvc.perform(bearer(post("/materials/" + id + "/review").contentType(MediaType.APPLICATION_JSON)
                .content("{\"comment\":\"twice\"}"), director)).andExpect(status().isConflict()).andReturn();
        assertThat(json(again).toString()).contains("already_reviewed");
        var replaced = mvc.perform(bearer(put("/teacher/materials/" + s.classOf("1A") + "/math" + WEEK)
                .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"after the review\"}"), s.token(s.british)))
                .andExpect(status().isConflict()).andReturn();
        assertThat(json(replaced).toString()).contains("already_reviewed");

        var board = ok(bearer(get("/materials/board" + WEEK), director));
        assertThat(board.get("counts").get("reviewed").asInt()).isEqualTo(1);
        var csv = mvc.perform(bearer(get("/materials/board.csv" + WEEK), director)).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(csv.getContentType()).startsWith("text/csv");
        assertThat(csv.getHeader("Content-Disposition")).contains("teaching-materials-2026-10-18.csv");
        assertThat(new String(csv.getContentAsByteArray(), StandardCharsets.UTF_8))
                .contains("تالا المعلمة,1A,math,REVIEWED").contains("Well prepared");
    }

    // ---------------------------------------------------------------- the reminder

    @Test void the_reminder_reaches_a_teacher_once_for_a_week() throws Exception {
        var s = school("remind");
        // Four hours before Thursday noon is 08:00; 09:00 is inside the window and the deadline has not passed.
        clock.pinTo(DEADLINE_DAY.atTime(9, 0).atZone(RIYADH).toInstant());
        hand(s, s.american, "math", "{\"note\":\"done early\"}");

        assertThat(job.run(s.id)).as("the three teachers who have handed in nothing").isEqualTo(3);
        assertThat(job.run(s.id)).as("a rerun claims nothing").isZero();

        var bell = new ArrayList<JsonNode>();
        ok(bearer(get("/me/notifications"), s.token(s.british))).forEach(bell::add);
        assertThat(bell).anySatisfy(r -> {
            assertThat(r.get("kind").asText()).isEqualTo("material.reminder");
            assertThat(r.get("link").asText()).contains("material=reminder").contains("week=2026-10-18").contains("missing=2");
        });
        // Nothing for the teacher who handed hers in, and nothing at all once the deadline has passed.
        assertThat(ok(bearer(get("/me/notifications"), s.token(s.american))))
                .noneSatisfy(r -> assertThat(r.get("kind").asText()).isEqualTo("material.reminder"));
        clock.pinTo(DEADLINE_DAY.atTime(12, 30).atZone(RIYADH).toInstant());
        assertThat(job.run(school("late-remind").id)).isZero();
    }

    // ---------------------------------------------------------------- the file

    @Test void a_material_file_is_read_by_its_teacher_and_by_a_leader_who_sees_her() throws Exception {
        var s = school("file");
        String teacher = s.token(s.british);
        var upload = json(mvc.perform(bearer(multipart("/teacher/materials/attachments")
                .file(new MockMultipartFile("file", "prep.pdf", "application/pdf", "%PDF-1.4 teaching material".getBytes(StandardCharsets.UTF_8))), teacher))
                .andExpect(status().isOk()).andReturn());
        String fileId = upload.get("id").asText();
        var row = hand(s, s.british, "math", "{\"attachmentId\":\"" + fileId + "\"}");
        assertThat(row.get("attachmentId").asText()).isEqualTo(fileId);
        assertThat(row.get("attachmentName").asText()).isEqualTo("prep.pdf");

        mvc.perform(bearer(get("/media/attachments/" + fileId), teacher)).andExpect(status().isOk());
        mvc.perform(bearer(get("/media/attachments/" + fileId), s.token(s.director, "MANAGERIAL"))).andExpect(status().isOk());
        mvc.perform(bearer(get("/media/attachments/" + fileId), s.token(s.lead))).andExpect(status().isNotFound());
        mvc.perform(get("/media/attachments/" + fileId).header("Authorization", PARENT)).andExpect(status().isNotFound());
        // An upload that is not hers cannot be put on her row.
        mvc.perform(bearer(put("/teacher/materials/" + s.classOf("2A") + "/math" + WEEK).contentType(MediaType.APPLICATION_JSON)
                .content("{\"attachmentId\":\"" + fileId + "\"}"), s.token(s.american))).andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------- roles, the switch and isolation

    @Test void a_parent_a_worker_and_a_manager_never_reach_the_teachers_page() throws Exception {
        var s = school("deny");
        String worker = s.token(s.user("wacct", "WORKER", "Worker account"), "WORKER");
        for (String path : List.of("/teacher/materials", "/materials/board", "/materials/board.csv")) {
            // `SecurityConfig` refuses her at the filter chain, before any handler — as it does feature 17's board.
            mvc.perform(get(path).header("Authorization", PARENT)).andExpect(status().isForbidden());
            mvc.perform(bearer(get(path), worker)).andExpect(status().isForbidden());
        }
        // `materials.self` is the teacher's alone: a manager reads the board instead.
        mvc.perform(bearer(get("/teacher/materials"), s.token(s.director, "MANAGERIAL"))).andExpect(status().isForbidden());
        mvc.perform(scoped(get("/admin/material-settings"), adminToken(), s.id)).andExpect(status().isOk());
        mvc.perform(bearer(put("/admin/material-settings").contentType(MediaType.APPLICATION_JSON)
                .content("{\"dueWeekday\":\"WEDNESDAY\"}"), s.token(s.director, "MANAGERIAL"))).andExpect(status().isForbidden());
    }

    @Test void a_school_with_the_switch_off_has_no_teaching_material_at_all() throws Exception {
        var s = school("off");
        s.flag(FlagKeys.TEACHING_MATERIALS, false);
        mvc.perform(bearer(get("/teacher/materials"), s.token(s.british))).andExpect(status().isNotFound());
        mvc.perform(bearer(get("/materials/board"), s.token(s.director, "MANAGERIAL"))).andExpect(status().isNotFound());
        mvc.perform(scoped(get("/admin/material-settings"), adminToken(), s.id)).andExpect(status().isNotFound());
        assertThat(job.run(s.id)).as("the job skips a school that does not use the feature").isZero();
    }

    @Test void the_admin_moves_the_deadline_and_every_row_is_read_against_the_new_one() throws Exception {
        var s = school("deadline");
        clock.pinTo(DEADLINE_DAY.atTime(13, 0).atZone(RIYADH).toInstant());          // after Thursday noon: late
        assertThat(hand(s, s.british, "math", "{\"note\":\"late by the old rule\"}").get("status").asText()).isEqualTo("LATE");
        var moved = json(mvc.perform(scoped(put("/admin/material-settings").contentType(MediaType.APPLICATION_JSON)
                .content("{\"dueWeekday\":\"THURSDAY\",\"dueTime\":\"18:00\",\"remindHoursBefore\":2}"), adminToken(), s.id))
                .andExpect(status().isOk()).andReturn());
        assertThat(moved.get("dueTime").asText()).isEqualTo("18:00");
        assertThat(moved.get("remindHoursBefore").asInt()).isEqualTo(2);
        assertThat(ok(bearer(get("/teacher/materials" + WEEK), s.token(s.british))).get("rows").get(1).get("status").asText())
                .as("the same row, re-read against the deadline the school now holds").isEqualTo("ON_TIME");
        mvc.perform(scoped(put("/admin/material-settings").contentType(MediaType.APPLICATION_JSON)
                .content("{\"dueWeekday\":\"FUNDAY\"}"), adminToken(), s.id)).andExpect(status().isBadRequest());
        mvc.perform(bearer(get("/teacher/materials?week=October"), s.token(s.british))).andExpect(status().isBadRequest());
    }

    @Test void one_school_never_sees_another_schools_teaching_material() throws Exception {
        var a = school("isoa");
        var b = school("isob");
        String id = hand(a, a.british, "math", "{\"note\":\"A's own\"}").get("submissionId").asText();
        String admin = adminToken();
        assertThat(teacherIds(ok(scoped(get("/materials/board" + WEEK), admin, b.id)))).doesNotContain(a.british);
        mvc.perform(scoped(post("/materials/" + id + "/review").contentType(MediaType.APPLICATION_JSON).content("{}"), admin, b.id))
                .andExpect(status().isNotFound());
        // A manager's token is her school's: another school's header is refused outright.
        mvc.perform(scoped(get("/materials/board"), b.token(b.director, "MANAGERIAL"), a.id)).andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------- helpers

    private JsonNode hand(School s, String teacherId, String subject, String body) throws Exception {
        return ok(bearer(put("/teacher/materials/" + s.classOf(teacherId.equals(s.british) ? "1A"
                        : teacherId.equals(s.american) ? "2A" : teacherId.equals(s.lead) ? "3A" : "4A")
                + "/" + subject + WEEK).contentType(MediaType.APPLICATION_JSON).content(body), s.token(teacherId)));
    }

    private JsonNode ok(MockHttpServletRequestBuilder request) throws Exception {
        return json(mvc.perform(request).andExpect(status().isOk()).andReturn());
    }

    private static MockHttpServletRequestBuilder scoped(MockHttpServletRequestBuilder builder, String token, String schoolId) {
        return builder.header("Authorization", "Bearer " + token).header(TenantContext.HEADER, schoolId);
    }

    private static MockHttpServletRequestBuilder bearer(MockHttpServletRequestBuilder builder, String token) {
        return builder.header("Authorization", "Bearer " + token);
    }

    private static List<String> subjects(JsonNode page) {
        var out = new ArrayList<String>();
        page.get("rows").forEach(r -> out.add(r.get("subject").asText()));
        return out;
    }

    private static List<String> teacherIds(JsonNode board) {
        var out = new ArrayList<String>();
        board.get("rows").forEach(r -> out.add(r.get("teacherId").asText()));
        return out;
    }

    private static List<String> ids(JsonNode board) {
        var out = new ArrayList<String>();
        board.get("rows").forEach(r -> out.add(r.get("id").asText()));
        return out;
    }

    /** A school of the test's own, with the cast described on the class. */
    private School school(String label) {
        String id = "mat31-" + label + "-" + UUID.randomUUID().toString().substring(0, 6);
        var row = new SchoolEntity();
        row.setId(id); row.setName(id);
        String code = id.replace("-", "").toUpperCase(Locale.ROOT);
        row.setCode(code.substring(0, Math.min(20, code.length())));
        row.setCurriculumOptionsJson("[\"british\",\"american\"]"); row.setGradeOptionsJson("[1,2,3,4]");
        row.setStatus("active"); row.setTimezone("Asia/Riyadh"); row.setCreatedAt(Instant.now());
        schools.save(row);
        var s = new School(id);
        s.flag(FlagKeys.TEACHING_MATERIALS, true);
        s.flag(FlagKeys.STAFF_ATTENDANCE, true);                 // feature 17's board, compared with this one
        s.flag(FlagKeys.FLOOR_SUPERVISOR, true);
        return s;
    }

    private final class School {
        final String id, director, british, american, lead, loner, nadia;

        School(String id) {
            this.id = id;
            director = user("director", "MANAGERIAL", "Dalia Director");
            var scope = new StaffScopeEntity();
            scope.setId(UUID.randomUUID().toString()); scope.setSchoolId(id); scope.setUserId(director);
            scope.setSubject(null); scope.setCurriculum("british"); scope.setCreatedAt(Instant.now());
            scopes.save(scope);
            british = teacher("british", "تالا المعلمة", "british");
            american = teacher("american", "Amy American", "american");
            lead = teacher("lead", "Lina Lead", "american");
            loner = teacher("loner", "Lone Teacher", "american");
            nadia = user("nadia", quest.server.tenancy.FloorScope.ROLE, "Nadia Floor");

            section("1A"); section("2A"); section("3A"); section("4A");
            assign(british, "1A", "math"); assign(british, "1A", "english");
            assign(american, "2A", "math"); assign(lead, "3A", "math"); assign(loner, "4A", "math");
            reportsTo(american, lead);
            floor(nadia, classOf("1A"));
        }

        String classOf(String name) { return id + "-" + name; }

        String token(String userId) { return token(userId, "TEACHER"); }

        String token(String userId, String role) { return jwt.issue(userId, userId + "@mat31.test", role, id).token(); }

        String teacher(String label, String name, String curriculum) {
            String userId = user(label, "TEACHER", name);
            var t = new TeacherEntity();
            t.setUserId(userId); t.setCurriculum(curriculum); t.setUpdatedAt(Instant.now());
            teachers.save(t);
            return userId;
        }

        void section(String name) {
            var k = new quest.server.tenancy.Entities.ClassEntity();
            k.setId(classOf(name)); k.setSchoolId(id); k.setCurriculum("british"); k.setGrade(1);
            k.setName(name); k.setActive(true); k.setCreatedAt(Instant.now());
            sections.save(k);
        }

        void assign(String teacherId, String className, String subject) {
            var a = new quest.server.tenancy.Entities.TeachingAssignmentEntity();
            a.setId(UUID.randomUUID().toString()); a.setSchoolId(id); a.setTeacherId(teacherId);
            a.setClassId(classOf(className)); a.setSubject(subject); a.setCreatedAt(Instant.now());
            teachingAssignments.save(a);
        }

        void floor(String supervisorId, String classId) {
            var f = new quest.server.floors.Entities.FloorEntity();
            f.setId(id + "-floor"); f.setSchoolId(id); f.setName("Second floor"); f.setAcademy("british");
            f.setCreatedAt(Instant.now());
            floors.save(f);
            var on = new quest.server.floors.Entities.FloorClassEntity();
            on.setId(UUID.randomUUID().toString()); on.setFloorId(f.getId()); on.setSchoolId(id);
            on.setSectionId(classId); on.setCreatedAt(Instant.now());
            floorClasses.save(on);
            var holder = new quest.server.floors.Entities.FloorSupervisorEntity();
            holder.setId(UUID.randomUUID().toString()); holder.setFloorId(f.getId()); holder.setSchoolId(id);
            holder.setUserId(supervisorId); holder.setCreatedAt(Instant.now());
            floorSupervisors.save(holder);
        }

        void reportsTo(String employee, String manager) {
            var p = new quest.server.staffattendance.Entities.StaffAttendanceProfileEntity();
            p.setId(UUID.randomUUID().toString()); p.setSchoolId(id); p.setStaffKind("USER"); p.setStaffId(employee);
            p.setDirectManagerId(manager); p.setUpdatedAt(Instant.now());
            profiles.save(p);
        }

        String user(String label, String role, String name) {
            String userId = id + "-" + label;
            var u = new UserEntity();
            u.setId(userId); u.setSchoolId(id); u.setEmail(userId + "@mat31.test"); u.setPasswordHash("x");
            u.setRole(role); u.setStatus("active"); u.setDisplayName(name);
            u.setCreatedAt(Instant.now()); u.setUpdatedAt(Instant.now());
            users.save(u);
            return userId;
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
