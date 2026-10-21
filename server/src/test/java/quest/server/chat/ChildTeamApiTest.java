package quest.server.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import quest.server.flags.FlagKeys;
import quest.server.tenancy.TenantContext;

/**
 * Feature 32, first half: `GET /children/{id}/team` — the four groups, each from the reach that already decides who a
 * parent may write to, and <strong>never a staff phone number</strong>.
 *
 * <p>The cast is {@link ChatTestSupport}'s: 1A is taught by Sara (math) and Noor (english), who is also 1B's; Maya is
 * the test parent's child on 1A and Omar hers on 1B. This test adds Lina, a coordinator of British maths; Nour, the
 * manager of the British department; and Nadia, the supervisor of a floor that holds 1A and not 1B. Every staff
 * member is given a phone number, which is the string that must appear nowhere.
 */
class ChildTeamApiTest extends ChatTestSupport {
    private static final String PHONE_SARA = "0500000111", PHONE_NOOR = "0500000222", PHONE_LINA = "0500000333",
            PHONE_NOUR = "0500000444", PHONE_NADIA = "0500000555", SCHOOL_PHONE = "0119999000";
    private static final String LINA = "ch-coordinator-lina", NOUR = "ch-manager-nour";

    @Autowired quest.server.tenancy.StaffScopeRepository staffScopes;
    @Autowired quest.server.floors.FloorRepository floorRows;
    @Autowired quest.server.floors.FloorClassRepository floorClassRows;
    @Autowired quest.server.floors.FloorSupervisorRepository floorSupervisorRows;
    @Autowired quest.server.evaluations.EvaluationRoundRepository evaluationRounds;
    @Autowired quest.server.evaluations.ChildEvaluationRepository childEvaluations;

    private String maya, omar, nadia;

    @BeforeEach void seed() throws Exception {
        seedSchools();
        maya = child("Maya", "CHSCHA", section1a);
        omar = child("Omar", "CHSCHA", section1b);
        phone(SARA, PHONE_SARA); phone(NOOR, PHONE_NOOR);
        staff(LINA, "COORDINATOR", "Ms Lina", "math", PHONE_LINA);
        staff(NOUR, "MANAGERIAL", "Ms Nour", null, PHONE_NOUR);
        nadia = supervisor();
        var school = schools.findById(A).orElseThrow();
        school.setPhone(SCHOOL_PHONE);
        schools.save(school);
    }

    @AfterEach void clean() {
        // The floors, her account and the scope rows outlive `removeSeed`, and every test here seeds its own.
        floorSupervisorRows.deleteAll(floorSupervisorRows.findBySchoolIdOrderByFloorIdAsc(A));
        floorClassRows.deleteAll(floorClassRows.findBySchoolIdOrderBySectionIdAsc(A));
        floorRows.deleteAll(floorRows.findBySchoolIdOrderByNameAsc(A));
        if (nadia != null) users.deleteById(nadia);
        staffScopes.deleteAll(staffScopes.findAll().stream().filter(r -> r.getSchoolId().startsWith(prefix())).toList());
        // Feature 33's rows outlive `removeSeed` too, and the one test that writes them must not leave a round
        // behind for the next test's team page to find.
        childEvaluations.deleteAll(childEvaluations.findAll().stream().filter(r -> r.getSchoolId().startsWith(prefix())).toList());
        evaluationRounds.deleteAll(evaluationRounds.findAll().stream().filter(r -> r.getSchoolId().startsWith(prefix())).toList());
        removeSeed();
    }

    // ---------------------------------------------------------------- the four groups

    @Test void the_team_is_her_teachers_her_coordinators_her_supervisor_and_her_manager() throws Exception {
        setFlag(adminToken, A, FlagKeys.FLOOR_SUPERVISOR, true);
        var team = parentGet("/children/" + maya + "/team");

        assertThat(roles(team)).containsExactly("TEACHER", "TEACHER", "COORDINATOR", "FLOOR_SUPERVISOR", "MANAGERIAL");
        assertThat(ids(team)).containsExactly(NOOR, SARA, LINA, nadia, NOUR);
        assertThat(row(team, SARA).get("name").asText()).isEqualTo("Ms Sara");
        assertThat(row(team, SARA).get("subjects").asText()).isEqualTo("math");
        assertThat(row(team, NOOR).get("subjects").asText()).isEqualTo("english");
        assertThat(row(team, LINA).get("subjects").asText()).isEqualTo("math");
        assertThat(row(team, NOUR).has("subjects")).as("a manager holds no subject on the section").isFalse();
        assertThat(team).allSatisfy(r -> assertThat(r.get("canMessage").asBoolean()).isTrue());
        // Nobody has written yet, so no row names a thread.
        assertThat(team).allSatisfy(r -> assertThat(r.has("threadId")).isFalse());
    }

    @Test void a_row_names_the_thread_the_parent_already_has() throws Exception {
        mvc.perform(post("/children/" + maya + "/chat/threads/" + SARA + "/messages").header("Authorization", PARENT)
                .contentType(MediaType.APPLICATION_JSON).content(send("Good morning"))).andExpect(status().isCreated());
        var team = parentGet("/children/" + maya + "/team");
        assertThat(row(team, SARA).get("threadId").asText()).isNotBlank();
        assertThat(row(team, NOOR).has("threadId")).isFalse();
    }

    @Test void the_supervisor_row_follows_the_switch_and_the_floor() throws Exception {
        setFlag(adminToken, A, FlagKeys.FLOOR_SUPERVISOR, false);
        assertThat(ids(parentGet("/children/" + maya + "/team"))).doesNotContain(nadia);

        setFlag(adminToken, A, FlagKeys.FLOOR_SUPERVISOR, true);
        assertThat(ids(parentGet("/children/" + maya + "/team"))).contains(nadia);
        // Omar's 1B is on no floor at all, so his team has no supervisor row whatever the switch says.
        assertThat(roles(parentGet("/children/" + omar + "/team"))).doesNotContain("FLOOR_SUPERVISOR");
    }

    @Test void the_office_hours_follow_their_own_switch() throws Exception {
        assertThat(parentGet("/children/" + maya + "/team")).allSatisfy(r -> assertThat(r.has("officeHours")).isFalse());
        setFlag(adminToken, A, FlagKeys.CHAT_OFFICE_HOURS, true);
        var team = parentGet("/children/" + maya + "/team");
        assertThat(team).allSatisfy(r -> assertThat(r.get("officeHours").has("today")).isTrue());
        assertThat(row(team, SARA).get("officeHours").get("schoolPhone").asText())
                .as("the school's number for urgent matters, which is the one number a parent is given")
                .isEqualTo(SCHOOL_PHONE);
    }

    // ---------------------------------------------------------------- feature 33: "Latest evaluation"

    /**
     * Feature 33's hook on this page: a teacher's row carries the newest evaluation of this child that the parent may
     * read, and <strong>only</strong> once the round is released and the evaluation approved. A draft, a submitted or
     * an approved evaluation of a round nobody has released is not there, and no row carries a rating, a state or a
     * reviewer — the field is a cue to open the evaluations screen.
     */
    @Test void a_teachers_row_names_her_latest_evaluation_only_after_the_release_and_the_approval() throws Exception {
        var round = round();
        var evaluation = evaluation(round.getId(), SARA, "math", "SUBMITTED");

        assertThat(row(parentGet("/children/" + maya + "/team"), SARA).has("latestEvaluation"))
                .as("submitted, and the round not released").isFalse();

        evaluation.setState("APPROVED");
        childEvaluations.save(evaluation);
        assertThat(row(parentGet("/children/" + maya + "/team"), SARA).has("latestEvaluation"))
                .as("approved, but the round is still the school's own business").isFalse();

        round.setReleasedAt(java.time.Instant.now());
        evaluationRounds.save(round);
        var latest = row(parentGet("/children/" + maya + "/team"), SARA).get("latestEvaluation");
        assertThat(latest.get("roundName").asText()).isEqualTo("Term 1 — mid-term");
        assertThat(latest.get("roundNameAr").asText()).isEqualTo("الفصل الأول");
        assertThat(latest.get("subject").asText()).isEqualTo("math");
        assertThat(keys(latest)).containsExactlyInAnyOrder("roundName", "roundNameAr", "subject");
        // Nobody else's row carries one, and Omar — whom she wrote nothing about — has none either.
        assertThat(row(parentGet("/children/" + maya + "/team"), NOOR).has("latestEvaluation")).isFalse();
        assertThat(row(parentGet("/children/" + omar + "/team"), NOOR).has("latestEvaluation")).isFalse();

        // And nothing at all while the school has the switch off.
        setFlag(adminToken, A, FlagKeys.CHILD_EVALUATIONS, false);
        assertThat(row(parentGet("/children/" + maya + "/team"), SARA).has("latestEvaluation")).isFalse();
        setFlag(adminToken, A, FlagKeys.CHILD_EVALUATIONS, true);
    }

    private quest.server.evaluations.Entities.EvaluationRoundEntity round() {
        var round = new quest.server.evaluations.Entities.EvaluationRoundEntity();
        round.setId("ch-round-1"); round.setSchoolId(A); round.setNameEn("Term 1 — mid-term"); round.setNameAr("الفصل الأول");
        round.setOpensOn(java.time.LocalDate.of(2026, 11, 1)); round.setDueAt(Instant.now().plusSeconds(86_400));
        round.setRemindHoursBefore(24); round.setCreatedAt(Instant.now()); round.setUpdatedAt(Instant.now());
        return evaluationRounds.save(round);
    }

    private quest.server.evaluations.Entities.ChildEvaluationEntity evaluation(String roundId, String teacherId,
                                                                               String subject, String state) {
        var row = new quest.server.evaluations.Entities.ChildEvaluationEntity();
        row.setId("ch-evaluation-" + teacherId + "-" + subject); row.setSchoolId(A); row.setRoundId(roundId);
        row.setTeacherId(teacherId); row.setClassId(CLASS_1A); row.setSubject(subject); row.setChildId(maya);
        row.setParticipation((short) 4); row.setEffort((short) 3); row.setBehaviour((short) 4);
        row.setUnderstanding((short) 3); row.setComment("A pleasure to teach."); row.setState(state);
        row.setSubmittedAt(Instant.now()); row.setCreatedAt(Instant.now()); row.setUpdatedAt(Instant.now());
        return childEvaluations.save(row);
    }

    // ---------------------------------------------------------------- no staff phone number, ever

    @Test void the_team_has_no_phone_key_and_no_staff_phone_number_in_it() throws Exception {
        setFlag(adminToken, A, FlagKeys.FLOOR_SUPERVISOR, true);
        for (boolean officeHours : List.of(false, true)) {
            setFlag(adminToken, A, FlagKeys.CHAT_OFFICE_HOURS, officeHours);
            String body = mvc.perform(get("/children/" + maya + "/team").header("Authorization", PARENT))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(keys(mapper.readTree(body))).as("with office hours %s", officeHours).doesNotContain("phone");
            assertThat(body).doesNotContain(PHONE_SARA).doesNotContain(PHONE_NOOR).doesNotContain(PHONE_LINA)
                    .doesNotContain(PHONE_NOUR).doesNotContain(PHONE_NADIA);
        }
    }

    // ---------------------------------------------------------------- who may ask, and about whom

    @Test void another_parents_child_is_not_found() throws Exception {
        String hers = someoneElsesChild("Rania");
        mvc.perform(get("/children/" + hers + "/team").header("Authorization", PARENT)).andExpect(status().isNotFound());
    }

    @Test void with_the_switch_off_the_team_is_not_found() throws Exception {
        setFlag(adminToken, A, FlagKeys.CHILD_TEAM, false);
        mvc.perform(get("/children/" + maya + "/team").header("Authorization", PARENT)).andExpect(status().isNotFound());
        setFlag(adminToken, A, FlagKeys.CHILD_TEAM, true);
        mvc.perform(get("/children/" + maya + "/team").header("Authorization", PARENT)).andExpect(status().isOk());
    }

    @Test void a_teacher_may_not_read_a_parents_team_page() throws Exception {
        mvc.perform(get("/children/" + maya + "/team").header("Authorization", "Bearer " + sara))
                .andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------- the one new reach: her floor supervisor

    @Test void a_parent_may_now_start_a_thread_with_her_childs_floor_supervisor() throws Exception {
        setFlag(adminToken, A, FlagKeys.FLOOR_SUPERVISOR, true);
        var message = json(mvc.perform(post("/children/" + maya + "/chat/threads/" + nadia + "/messages").header("Authorization", PARENT)
                        .contentType(MediaType.APPLICATION_JSON).content(send("Maya left her bag on the floor.")))
                .andExpect(status().isCreated()).andReturn());
        assertThat(message.get("body").asText()).isEqualTo("Maya left her bag on the floor.");
        var row = row(parentGet("/children/" + maya + "/chat/threads"), nadia);
        assertThat(row.get("staffRole").asText()).isEqualTo("FLOOR_SUPERVISOR");

        // Omar's class is on no floor of hers, so she is 404 to him exactly as any other member of staff is.
        mvc.perform(post("/children/" + omar + "/chat/threads/" + nadia + "/messages").header("Authorization", PARENT)
                .contentType(MediaType.APPLICATION_JSON).content(send("hello"))).andExpect(status().isNotFound());
    }

    @Test void with_the_floor_switch_off_her_supervisor_is_not_someone_a_parent_may_write_to() throws Exception {
        setFlag(adminToken, A, FlagKeys.FLOOR_SUPERVISOR, false);
        mvc.perform(post("/children/" + maya + "/chat/threads/" + nadia + "/messages").header("Authorization", PARENT)
                .contentType(MediaType.APPLICATION_JSON).content(send("hello"))).andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- the fixture

    private void phone(String userId, String number) {
        users.findById(userId).ifPresent(u -> { u.setPhone(number); users.save(u); });
    }

    /** A school-A staff member of the British department: a coordinator of one subject, or a manager (no subject). */
    private void staff(String userId, String role, String name, String subject, String number) {
        var u = users.findById(userId).orElseGet(quest.server.auth.Entities.UserEntity::new);
        u.setId(userId); u.setSchoolId(A); u.setEmail(userId + "@seed.test"); u.setPasswordHash("x");
        u.setRole(role); u.setStatus("active"); u.setDisplayName(name); u.setPhone(number);
        if (u.getCreatedAt() == null) u.setCreatedAt(Instant.now());
        u.setUpdatedAt(Instant.now());
        users.save(u);
        var row = staffScopes.findById(userId + ":british").orElseGet(quest.server.tenancy.Entities.StaffScopeEntity::new);
        row.setId(userId + ":british"); row.setSchoolId(A); row.setUserId(userId); row.setSubject(subject); row.setCurriculum("british");
        if (row.getCreatedAt() == null) row.setCreatedAt(Instant.now());
        staffScopes.save(row);
    }

    /** Nadia, supervising a floor that holds 1A and not 1B — through the Admin's own routes, as feature 20 does. */
    private String supervisor() throws Exception {
        setFlag(adminToken, A, FlagKeys.FLOOR_SUPERVISOR, true);
        String id = json(mvc.perform(post("/admin/floor-supervisors").header("Authorization", "Bearer " + adminToken)
                        .header(TenantContext.HEADER, A).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Nadia\",\"email\":\"nadia." + java.util.UUID.randomUUID().toString().substring(0, 8) + "@f32.test\"}"))
                .andExpect(status().isCreated()).andReturn()).get("supervisor").get("userId").asText();
        mvc.perform(post("/admin/floors").header("Authorization", "Bearer " + adminToken).header(TenantContext.HEADER, A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"First floor\",\"academy\":\"british\",\"classIds\":[\"" + CLASS_1A
                                + "\"],\"supervisorIds\":[\"" + id + "\"]}"))
                .andExpect(status().isCreated());
        phone(id, PHONE_NADIA);
        return id;
    }

    // ---------------------------------------------------------------- reading the answer

    private static List<String> ids(JsonNode team) {
        var out = new ArrayList<String>();
        team.forEach(r -> out.add(r.get("staffId").asText()));
        return out;
    }

    private static List<String> roles(JsonNode team) {
        var out = new ArrayList<String>();
        team.forEach(r -> out.add(r.get("role").asText()));
        return out;
    }

    private static JsonNode row(JsonNode rows, String staffId) {
        for (var r : rows) {
            var id = r.has("staffId") ? r.get("staffId") : r.get("teacherId");
            if (id != null && staffId.equals(id.asText())) return r;
        }
        throw new AssertionError("no row for " + staffId);
    }

    /** Every field name in the answer, however deep — what "the DTO has no phone field" is asserted against. */
    private static List<String> keys(JsonNode node) {
        var out = new ArrayList<String>();
        node.fieldNames().forEachRemaining(out::add);
        node.forEach(child -> out.addAll(keys(child)));
        return out;
    }
}
