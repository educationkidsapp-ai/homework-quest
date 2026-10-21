package quest.server.evaluations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import quest.server.auth.Entities.TeacherEntity;
import quest.server.auth.Entities.UserEntity;
import quest.server.grading.GradingTestSupport;
import quest.server.flags.FlagKeys;
import quest.server.tenancy.Entities.ClassEntity;
import quest.server.tenancy.Entities.SchoolEntity;
import quest.server.tenancy.Entities.StaffScopeEntity;
import quest.server.tenancy.TenantContext;

/**
 * Feature 33 end to end: what a teacher is expected to write, draft and submit, on time and late around the round's
 * deadline <strong>in the school's own zone</strong>, the leaders' board with feature 17's own scope, the one review,
 * the reminder, the release — and above all <strong>what the parent is given</strong>, which is words and never a
 * number.
 *
 * <p>Each test builds its own school ({@link #school}) in Riyadh, with the clock pinned to 2026-11-01 09:00 Riyadh
 * and a round due on <strong>2026-11-05 at 12:00 Riyadh</strong> (09:00 UTC — the tests pin instants either side of
 * both, so a server reading the deadline in UTC fails them). The cast is feature 31's, plus the children, because
 * the two boards are compared row for row:
 * <ul>
 *   <li>{@code director}: a manager whose department row names `british` — the British academy's director;</li>
 *   <li>{@code sara}: a British teacher of 1A, two subjects (math and english); her name is Arabic;</li>
 *   <li>{@code amy}: an American teacher (2A · math) whose direct manager is {@code lead};</li>
 *   <li>{@code lead}: an American teacher (3A · math) who leads {@code amy};</li>
 *   <li>{@code nadia}: a floor supervisor whose floor holds 1A;</li>
 *   <li>children: {@code maya} on 1A and {@code zaid} on 2A and {@code lama} on 3A are the test parent's;
 *       {@code rania} on 1A belongs to another family.</li>
 * </ul>
 */
class ChildEvaluationsApiTest extends GradingTestSupport {
    private static final ZoneId RIYADH = ZoneId.of("Asia/Riyadh");
    private static final Instant NOW = Instant.parse("2026-11-01T06:00:00Z");          // 09:00 Riyadh
    private static final String DUE_LOCAL = "2026-11-05T12:00";                         // 09:00 UTC
    private static final Instant BEFORE_DUE = Instant.parse("2026-11-05T08:30:00Z");    // 11:30 Riyadh
    private static final Instant AFTER_DUE = Instant.parse("2026-11-05T11:30:00Z");     // 14:30 Riyadh
    private static final String FULL = "{\"participation\":4,\"effort\":3,\"behaviour\":4,\"understanding\":2,"
            + "\"comment\":\"Reads aloud with confidence.\",\"goingWell\":\"Counting\",\"nextStep\":\"Subtraction\"}";

    @Autowired quest.server.tenancy.StaffScopeRepository scopes;
    @Autowired quest.server.staffattendance.StaffAttendanceProfileRepository profiles;
    @Autowired quest.server.floors.FloorRepository floorRows;
    @Autowired quest.server.floors.FloorClassRepository floorClassRows;
    @Autowired quest.server.floors.FloorSupervisorRepository floorSupervisorRows;
    @Autowired ChildEvaluationRepository evaluationRows;
    @Autowired EvaluationRoundRepository roundRows;
    @Autowired EvaluationReminderRepository reminderRows;
    @Autowired EvaluationReminderJob job;

    private final String OTHER_PARENT = "Bearer fake-token-parent-" + UUID.randomUUID().toString().substring(0, 8);

    @Override public String prefix() { return "ev33-"; }

    @BeforeEach void pin() { clock.pinTo(NOW); }

    @AfterEach void clean() {
        clock.release();
        // Every row of this suite carries the school prefix, and the shared H2 database outlives the test: feature
        // 32's CI-only failures were leaked rows, so each table is emptied by prefix here.
        reminderRows.deleteAll(reminderRows.findAll().stream().filter(r -> r.getSchoolId().startsWith(prefix())).toList());
        evaluationRows.deleteAll(evaluationRows.findAll().stream().filter(r -> r.getSchoolId().startsWith(prefix())).toList());
        roundRows.deleteAll(roundRows.findAll().stream().filter(r -> r.getSchoolId().startsWith(prefix())).toList());
        floorSupervisorRows.deleteAll(floorSupervisorRows.findAll().stream().filter(r -> r.getSchoolId().startsWith(prefix())).toList());
        floorClassRows.deleteAll(floorClassRows.findAll().stream().filter(r -> r.getSchoolId().startsWith(prefix())).toList());
        floorRows.deleteAll(floorRows.findAll().stream().filter(r -> r.getSchoolId().startsWith(prefix())).toList());
        profiles.deleteAll(profiles.findAll().stream().filter(r -> r.getSchoolId().startsWith(prefix())).toList());
        scopes.deleteAll(scopes.findAll().stream().filter(r -> r.getSchoolId().startsWith(prefix())).toList());
        removeSeed();
    }

    // ---------------------------------------------------------------- what is expected

    @Test void the_expected_rows_are_her_assignments_times_the_children_in_each_class() throws Exception {
        var s = school("exp");
        var page = ok(bearer(get("/teacher/evaluations"), s.token(s.sara)));
        assertThat(page.get("round").get("nameEn").asText()).isEqualTo("Term 1 — mid-term");
        assertThat(page.get("round").get("timeZone").asText()).isEqualTo("Asia/Riyadh");
        assertThat(page.get("round").get("dueAt").asText()).isEqualTo("2026-11-05T09:00:00Z");
        assertThat(page.get("round").get("released").asBoolean()).isFalse();
        assertThat(page.get("rows")).as("two subjects on 1A × the two children in it").hasSize(4);
        assertThat(page.get("rows")).allSatisfy(r -> assertThat(r.get("status").asText()).isEqualTo("MISSING"));
        assertThat(page.get("counts").get("missing").asInt()).isEqualTo(4);
        assertThat(childNames(page)).containsExactly("Maya", "Rania", "Maya", "Rania");
        assertThat(page.get("rows").get(0).get("className").asText()).isEqualTo("1A");

        // The American teacher's page is hers alone: her own class's one child, and never 1A's.
        var hers = ok(bearer(get("/teacher/evaluations"), s.token(s.amy)));
        assertThat(childNames(hers)).containsExactly("Zaid");
        // The round picker carries every round of the school, for the dashboard's own chooser.
        assertThat(page.get("rounds")).hasSize(1);
    }

    @Test void a_draft_counts_as_missing_and_submitting_needs_all_four_criteria_and_a_comment() throws Exception {
        var s = school("draft");
        var draft = ok(bearer(put(path(s, s.maya, "math")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"participation\":3,\"comment\":\"A start\"}"), s.token(s.sara)));
        assertThat(draft.get("state").asText()).isEqualTo("DRAFT");
        assertThat(draft.get("status").asText()).as("a draft is nothing handed in").isEqualTo("MISSING");
        assertThat(draft.get("participation").asInt()).isEqualTo(3);
        assertThat(ok(bearer(get("/teacher/evaluations"), s.token(s.sara))).get("counts").get("missing").asInt()).isEqualTo(4);

        // One criterion is not four, and four criteria without a comment are not an evaluation either. Both are
        // tried on the child nothing has been written about yet, so each request stands on its own body.
        mvc.perform(bearer(post(path(s, s.maya, "math") + "/submit").contentType(MediaType.APPLICATION_JSON)
                .content("{}"), s.token(s.sara))).andExpect(status().isBadRequest());
        mvc.perform(bearer(post(path(s, s.rania, "math") + "/submit").contentType(MediaType.APPLICATION_JSON)
                .content("{\"participation\":4,\"effort\":4,\"behaviour\":4,\"understanding\":4}"), s.token(s.sara)))
                .andExpect(status().isBadRequest());
        var submitted = ok(bearer(post(path(s, s.maya, "math") + "/submit").contentType(MediaType.APPLICATION_JSON)
                .content(FULL), s.token(s.sara)));
        assertThat(submitted.get("state").asText()).isEqualTo("SUBMITTED");
        assertThat(submitted.get("status").asText()).isEqualTo("ON_TIME");
        assertThat(submitted.get("comment").asText()).isEqualTo("Reads aloud with confidence.");
        // A rating outside 1–4 is not a rating.
        mvc.perform(bearer(put(path(s, s.rania, "math")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"participation\":9}"), s.token(s.sara))).andExpect(status().isBadRequest());
    }

    @Test void on_time_and_late_are_decided_by_the_deadline_in_the_schools_zone() throws Exception {
        var s = school("when");
        // 11:30 Riyadh on the deadline's own day — which is 08:30 UTC, before noon UTC, so the zone is what decides.
        clock.pinTo(BEFORE_DUE);
        assertThat(submit(s, s.maya, "math").get("status").asText()).isEqualTo("ON_TIME");
        clock.pinTo(AFTER_DUE);
        assertThat(submit(s, s.maya, "english").get("status").asText()).isEqualTo("LATE");

        clock.pinTo(NOW);
        var page = ok(bearer(get("/teacher/evaluations"), s.token(s.sara)));
        assertThat(page.get("counts").get("onTime").asInt()).isEqualTo(1);
        assertThat(page.get("counts").get("late").asInt()).isEqualTo(1);
        assertThat(page.get("counts").get("missing").asInt()).isEqualTo(2);
    }

    @Test void a_submitted_or_approved_evaluation_is_no_longer_hers_to_change() throws Exception {
        var s = school("locked");
        String id = submit(s, s.maya, "math").get("evaluationId").asText();
        var refused = mvc.perform(bearer(put(path(s, s.maya, "math")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"comment\":\"second thoughts\"}"), s.token(s.sara))).andExpect(status().isConflict()).andReturn();
        assertThat(json(refused).toString()).contains("already_submitted");

        ok(bearer(post("/evaluations/" + id + "/review").contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"APPROVE\"}"), s.token(s.director, "MANAGERIAL")));
        var afterApproval = mvc.perform(bearer(put(path(s, s.maya, "math")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"comment\":\"too late\"}"), s.token(s.sara))).andExpect(status().isConflict()).andReturn();
        assertThat(json(afterApproval).toString()).contains("already_approved");
    }

    @Test void a_class_a_subject_or_a_child_that_is_not_hers_is_refused() throws Exception {
        var s = school("mine");
        // Another teacher's class, a subject she does not hold on her own class, and a child of another class.
        mvc.perform(bearer(put("/teacher/evaluations/" + s.round + "/" + s.classOf("2A") + "/math/" + s.zaid)
                .contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"not mine\"}"), s.token(s.sara)))
                .andExpect(status().isForbidden());
        mvc.perform(bearer(put("/teacher/evaluations/" + s.round + "/" + s.classOf("1A") + "/science/" + s.maya)
                .contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"not my subject\"}"), s.token(s.sara)))
                .andExpect(status().isForbidden());
        mvc.perform(bearer(put("/teacher/evaluations/" + s.round + "/" + s.classOf("1A") + "/math/" + s.zaid)
                .contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"not in 1A\"}"), s.token(s.sara)))
                .andExpect(status().isForbidden());
        // A round of no school at all is 404, as another school's would be.
        mvc.perform(bearer(get("/teacher/evaluations?round=" + UUID.randomUUID()), s.token(s.sara)))
                .andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- the leaders' board: feature 17's scope

    @Test void the_board_shows_exactly_the_teachers_the_attendance_board_shows_that_leader() throws Exception {
        var s = school("scope");
        String admin = adminToken();
        var all = ok(scoped(get("/evaluations/board"), admin, s.id));
        assertThat(all.get("scope").asText()).isEqualTo("SCHOOL");
        assertThat(teacherIds(all)).containsExactlyInAnyOrder(s.sara, s.amy, s.lead);
        assertThat(all.get("counts").get("missing").asInt()).as("4 + 1 + 1 expected, nothing written").isEqualTo(6);
        assertThat(ids(ok(scoped(get("/staff-attendance/board"), admin, s.id))))
                .as("the attendance board's rows are the same people, plus the ones who do not teach")
                .containsAll(teacherIds(all));
        assertThat(all.get("rows").get(0).get("rows")).as("the board itself is counts only").isEmpty();

        var academy = ok(bearer(get("/evaluations/board"), s.token(s.director, "MANAGERIAL")));
        assertThat(academy.get("scope").asText()).isEqualTo("ACADEMY");
        assertThat(teacherIds(academy)).containsExactly(s.sara);

        var reports = ok(bearer(get("/evaluations/board"), s.token(s.lead)));
        assertThat(reports.get("scope").asText()).isEqualTo("REPORTS");
        assertThat(teacherIds(reports)).as("her one direct report, never herself").containsExactly(s.amy);

        var floor = ok(bearer(get("/evaluations/board"), s.token(s.nadia, quest.server.tenancy.FloorScope.ROLE)));
        assertThat(teacherIds(floor)).as("the teacher of her floor's class").containsExactly(s.sara);

        // One teacher's own page carries her rows; a teacher outside the caller's view is 404, as the board's own.
        submit(s, s.maya, "math");
        var one = ok(bearer(get("/evaluations/board/" + s.sara), s.token(s.director, "MANAGERIAL")));
        assertThat(one.get("rows").get(0).get("rows")).hasSize(4);
        assertThat(one.get("counts").get("onTime").asInt()).isEqualTo(1);
        mvc.perform(bearer(get("/evaluations/board/" + s.sara), s.token(s.lead))).andExpect(status().isNotFound());
        mvc.perform(bearer(get("/evaluations/board/" + UUID.randomUUID()), s.token(s.director, "MANAGERIAL")))
                .andExpect(status().isNotFound());
    }

    @Test void a_leader_approves_or_returns_once_and_the_teacher_edits_what_came_back() throws Exception {
        var s = school("review");
        String id = submit(s, s.maya, "math").get("evaluationId").asText();
        String director = s.token(s.director, "MANAGERIAL");

        var returned = ok(bearer(post("/evaluations/" + id + "/review").contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"RETURN\",\"comment\":\"Please add a next step.\"}"), director));
        assertThat(returned.get("state").asText()).isEqualTo("RETURNED");
        assertThat(returned.get("status").asText()).isEqualTo("RETURNED");
        assertThat(returned.get("reviewedByName").asText()).isEqualTo("Dalia Director");
        assertThat(returned.get("reviewComment").asText()).isEqualTo("Please add a next step.");
        var again = mvc.perform(bearer(post("/evaluations/" + id + "/review").contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"APPROVE\"}"), director)).andExpect(status().isConflict()).andReturn();
        assertThat(json(again).toString()).contains("already_reviewed");

        // The teacher hears about it, and a returned evaluation is hers again: edited and submitted a second time.
        assertThat(ok(bearer(get("/me/notifications"), s.token(s.sara)))).anySatisfy(r -> {
            assertThat(r.get("kind").asText()).isEqualTo("evaluation.returned");
            assertThat(r.get("link").asText()).contains("evaluation=returned").contains("round=" + s.round);
        });
        ok(bearer(put(path(s, s.maya, "math")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"nextStep\":\"Subtraction to 20\"}"), s.token(s.sara)));
        var edited = ok(bearer(get(path(s, s.maya, "math")), s.token(s.sara)));
        assertThat(edited.get("state").asText()).as("still returned while she works on it, with the comment on it")
                .isEqualTo("RETURNED");
        assertThat(edited.get("reviewComment").asText()).isEqualTo("Please add a next step.");
        var resubmitted = submit(s, s.maya, "math");
        assertThat(resubmitted.get("state").asText()).isEqualTo("SUBMITTED");
        assertThat(resubmitted.get("status").asText()).isEqualTo("ON_TIME");

        var approved = ok(bearer(post("/evaluations/" + id + "/review").contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"APPROVE\",\"comment\":\"Thank you\"}"), director));
        assertThat(approved.get("state").asText()).isEqualTo("APPROVED");
        assertThat(ok(bearer(get("/evaluations/board"), director)).get("counts").get("approved").asInt()).isEqualTo(1);

        // A decision nobody asked for, and a decision on a row nobody submitted.
        mvc.perform(bearer(post("/evaluations/" + id + "/review").contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"MAYBE\"}"), director)).andExpect(status().isBadRequest());
        ok(bearer(put(path(s, s.rania, "math")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"comment\":\"a draft\"}"), s.token(s.sara)));
        String draftId = draftOf(ok(bearer(get("/evaluations/board/" + s.sara), director)));
        var notSubmitted = mvc.perform(bearer(post("/evaluations/" + draftId + "/review").contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"APPROVE\"}"), director)).andExpect(status().isConflict()).andReturn();
        assertThat(json(notSubmitted).toString()).contains("not_submitted");
    }

    @Test void the_board_csv_carries_the_counts_and_no_rating_or_comment() throws Exception {
        var s = school("csv");
        submit(s, s.maya, "math");
        var csv = mvc.perform(bearer(get("/evaluations/board.csv"), s.token(s.director, "MANAGERIAL")))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(csv.getContentType()).startsWith("text/csv");
        assertThat(csv.getHeader("Content-Disposition")).contains("child-evaluations-" + s.round + ".csv");
        String body = new String(csv.getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(body).contains("تالا المعلمة").contains("Total / الإجمالي")
                .doesNotContain("Reads aloud with confidence.").doesNotContain("Counting");
    }

    // ---------------------------------------------------------------- the reminder

    @Test void the_reminder_reaches_a_teacher_once_for_a_round() throws Exception {
        var s = school("remind");
        clock.pinTo(Instant.parse("2026-11-05T06:00:00Z"));                 // 09:00 Riyadh, inside the 24-hour window
        submit(s, s.zaid, "math");                                          // the American teacher is done

        assertThat(job.run(s.id)).as("the two teachers who have written nothing").isEqualTo(2);
        assertThat(job.run(s.id)).as("a rerun claims nothing").isZero();

        assertThat(ok(bearer(get("/me/notifications"), s.token(s.sara)))).anySatisfy(r -> {
            assertThat(r.get("kind").asText()).isEqualTo("evaluation.reminder");
            assertThat(r.get("link").asText()).contains("evaluation=reminder").contains("round=" + s.round)
                    .contains("missing=4");
        });
        assertThat(ok(bearer(get("/me/notifications"), s.token(s.amy))))
                .noneSatisfy(r -> assertThat(r.get("kind").asText()).isEqualTo("evaluation.reminder"));

        // Nothing before the window, nothing after the deadline, and nothing for a round already released.
        clock.pinTo(Instant.parse("2026-11-05T11:00:00Z"));
        assertThat(job.run(school("late-remind").id)).isZero();
        clock.pinTo(Instant.parse("2026-11-01T06:00:00Z"));
        assertThat(job.run(school("early-remind").id)).isZero();
    }

    // ---------------------------------------------------------------- the parent

    @Test void the_parent_sees_nothing_before_the_release_and_only_approved_evaluations_after_it() throws Exception {
        var s = school("parent");
        String director = s.token(s.director, "MANAGERIAL");
        String approved = submit(s, s.maya, "math").get("evaluationId").asText();
        ok(bearer(post("/evaluations/" + approved + "/review").contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"APPROVE\"}"), director));
        String returned = submit(s, s.maya, "english").get("evaluationId").asText();
        ok(bearer(post("/evaluations/" + returned + "/review").contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"RETURN\",\"comment\":\"More detail please\"}"), director));

        assertThat(parentGet("/children/" + s.maya + "/evaluations")).as("nothing at all before the release").isEmpty();
        release(s);

        var mine = parentGet("/children/" + s.maya + "/evaluations");
        assertThat(mine).hasSize(1);
        var row = mine.get(0);
        assertThat(row.get("roundName").asText()).isEqualTo("Term 1 — mid-term");
        assertThat(row.get("roundNameAr").asText()).isEqualTo("الفصل الأول — منتصف الفصل");
        assertThat(row.get("subject").asText()).isEqualTo("math");
        assertThat(row.get("teacherId").asText()).isEqualTo(s.sara);
        assertThat(row.get("teacherName").asText()).isEqualTo("تالا المعلمة");
        assertThat(row.get("comment").asText()).isEqualTo("Reads aloud with confidence.");
        assertThat(row.get("goingWell").asText()).isEqualTo("Counting");
        assertThat(row.get("nextStep").asText()).isEqualTo("Subtraction");
        assertThat(row.get("releasedAt").asText()).isNotBlank();
        assertThat(words(row)).as("4, 3, 4 and 2 as the words a parent reads")
                .containsExactly("participation=excellent", "effort=good", "behaviour=excellent", "understanding=developing");

        // Her parent is told once per child, and the row opens her evaluations screen.
        var bell = new ArrayList<JsonNode>();
        parentGet("/me/notifications").forEach(bell::add);
        assertThat(bell).filteredOn(r -> "evaluation.released".equals(r.get("kind").asText()))
                .as("one row for Maya, and none for a child with nothing approved").hasSize(1)
                .allSatisfy(r -> {
                    assertThat(r.get("childId").asText()).isEqualTo(s.maya);
                    assertThat(r.get("link").asText()).isEqualTo("/children/" + s.maya + "/evaluations");
                });
    }

    @Test void the_parent_json_has_no_number_and_no_rating_state_or_reviewer_field_anywhere() throws Exception {
        var s = school("sweep");
        String director = s.token(s.director, "MANAGERIAL");
        for (String subject : List.of("math", "english")) {
            String id = submit(s, s.maya, subject).get("evaluationId").asText();
            ok(bearer(post("/evaluations/" + id + "/review").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"decision\":\"APPROVE\",\"comment\":\"Well written\"}"), director));
        }
        release(s);

        String body = mvc.perform(get("/children/" + s.maya + "/evaluations").header("Authorization", PARENT))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var answer = mapper.readTree(body);
        assertThat(answer).hasSize(2);
        assertThat(keys(answer)).as("a parent's evaluation names none of the staff's fields")
                .doesNotContain("rating", "ratings", "score", "state", "reviewer", "reviewedBy", "reviewedByName",
                        "reviewComment", "status", "participation", "effort", "behaviour", "understanding",
                        "submittedAt", "evaluationId");
        assertThat(numbers(answer)).as("not one number anywhere in the answer — the words are the whole point").isEmpty();
        assertThat(body).doesNotContain("Well written");            // the leader's comment is staff business
    }

    @Test void an_evaluation_approved_after_the_release_appears_at_once_and_is_notified_once() throws Exception {
        var s = school("after");
        String director = s.token(s.director, "MANAGERIAL");
        String first = submit(s, s.maya, "math").get("evaluationId").asText();
        ok(bearer(post("/evaluations/" + first + "/review").contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"APPROVE\"}"), director));
        release(s);
        assertThat(parentGet("/children/" + s.maya + "/evaluations")).hasSize(1);

        String late = submit(s, s.maya, "english").get("evaluationId").asText();
        assertThat(parentGet("/children/" + s.maya + "/evaluations")).as("submitted is not approved").hasSize(1);
        ok(bearer(post("/evaluations/" + late + "/review").contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"APPROVE\"}"), director));
        assertThat(parentGet("/children/" + s.maya + "/evaluations")).as("approved after the release, so readable now").hasSize(2);

        var rows = new ArrayList<JsonNode>();
        parentGet("/me/notifications").forEach(rows::add);
        assertThat(rows).filteredOn(r -> "evaluation.released".equals(r.get("kind").asText()))
                .as("the release told her once, and the late approval once more — and neither twice").hasSize(2);
    }

    @Test void another_familys_child_is_not_found() throws Exception {
        var s = school("hers");
        mvc.perform(get("/children/" + s.rania + "/evaluations").header("Authorization", PARENT))
                .andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- the Admin's rounds

    @Test void the_admin_edits_a_round_until_she_releases_it_and_releases_it_once() throws Exception {
        var s = school("rounds");
        String admin = adminToken();
        var moved = json(mvc.perform(scoped(put("/admin/evaluation-rounds/" + s.round).contentType(MediaType.APPLICATION_JSON)
                .content("{\"dueAt\":\"2026-11-06T18:00\",\"remindHoursBefore\":2}"), admin, s.id))
                .andExpect(status().isOk()).andReturn());
        assertThat(moved.get("dueAt").asText()).as("18:00 Riyadh is 15:00 UTC").isEqualTo("2026-11-06T15:00:00Z");
        assertThat(moved.get("remindHoursBefore").asInt()).isEqualTo(2);
        // The same row, re-read against the deadline the school now holds.
        clock.pinTo(AFTER_DUE);
        assertThat(submit(s, s.maya, "math").get("status").asText()).isEqualTo("ON_TIME");
        clock.pinTo(NOW);

        assertThat(json(mvc.perform(scoped(get("/admin/evaluation-rounds"), admin, s.id)).andExpect(status().isOk()).andReturn()))
                .hasSize(1);
        var released = json(mvc.perform(scoped(post("/admin/evaluation-rounds/" + s.round + "/release"), admin, s.id))
                .andExpect(status().isOk()).andReturn());
        assertThat(released.get("released").asBoolean()).isTrue();
        assertThat(released.get("releasedAt").asText()).isNotBlank();
        var twice = mvc.perform(scoped(post("/admin/evaluation-rounds/" + s.round + "/release"), admin, s.id))
                .andExpect(status().isConflict()).andReturn();
        assertThat(json(twice).toString()).contains("already_released");
        var edited = mvc.perform(scoped(put("/admin/evaluation-rounds/" + s.round).contentType(MediaType.APPLICATION_JSON)
                .content("{\"nameEn\":\"Too late\"}"), admin, s.id)).andExpect(status().isConflict()).andReturn();
        assertThat(json(edited).toString()).contains("already_released");

        // A round needs a name and a deadline it can read.
        mvc.perform(scoped(post("/admin/evaluation-rounds").contentType(MediaType.APPLICATION_JSON)
                .content("{\"nameEn\":\"No deadline\",\"nameAr\":\"بلا موعد\",\"dueAt\":\"someday\"}"), admin, s.id))
                .andExpect(status().isBadRequest());
        mvc.perform(scoped(post("/admin/evaluation-rounds").contentType(MediaType.APPLICATION_JSON)
                .content("{\"nameAr\":\"بلا اسم\",\"dueAt\":\"2026-12-01T12:00\"}"), admin, s.id))
                .andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------- roles, the switch and isolation

    @Test void a_parent_a_worker_and_a_manager_never_reach_the_staff_routes() throws Exception {
        var s = school("deny");
        String worker = s.token(s.user("wacct", "WORKER", "Worker account"), "WORKER");
        for (String path : List.of("/teacher/evaluations", "/evaluations/board", "/evaluations/board.csv")) {
            // `SecurityConfig` refuses both at the filter chain, before any handler — as it does feature 31's board.
            mvc.perform(get(path).header("Authorization", PARENT)).andExpect(status().isForbidden());
            mvc.perform(bearer(get(path), worker)).andExpect(status().isForbidden());
        }
        // `evaluations.self` is the teacher's alone: a manager reads the board instead.
        mvc.perform(bearer(get("/teacher/evaluations"), s.token(s.director, "MANAGERIAL"))).andExpect(status().isForbidden());
        // And the rounds are the Admin's alone.
        mvc.perform(bearer(get("/admin/evaluation-rounds"), s.token(s.director, "MANAGERIAL"))).andExpect(status().isForbidden());
        // A teacher may not read a parent's screen.
        mvc.perform(bearer(get("/children/" + s.maya + "/evaluations"), s.token(s.sara))).andExpect(status().isForbidden());
    }

    @Test void a_school_with_the_switch_off_has_no_evaluations_at_all() throws Exception {
        var s = school("off");
        s.flag(FlagKeys.CHILD_EVALUATIONS, false);
        mvc.perform(bearer(get("/teacher/evaluations"), s.token(s.sara))).andExpect(status().isNotFound());
        mvc.perform(bearer(get("/evaluations/board"), s.token(s.director, "MANAGERIAL"))).andExpect(status().isNotFound());
        mvc.perform(scoped(get("/admin/evaluation-rounds"), adminToken(), s.id)).andExpect(status().isNotFound());
        mvc.perform(get("/children/" + s.maya + "/evaluations").header("Authorization", PARENT)).andExpect(status().isNotFound());
        assertThat(job.run(s.id)).as("the job skips a school that does not use the feature").isZero();
    }

    @Test void one_school_never_sees_another_schools_evaluations() throws Exception {
        var a = school("isoa");
        var b = school("isob");
        String id = submit(a, a.maya, "math").get("evaluationId").asText();
        String admin = adminToken();
        assertThat(teacherIds(ok(scoped(get("/evaluations/board?round=" + b.round), admin, b.id)))).doesNotContain(a.sara);
        mvc.perform(scoped(post("/evaluations/" + id + "/review").contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"APPROVE\"}"), admin, b.id)).andExpect(status().isNotFound());
        // A manager's token is her school's: another school's header is refused outright.
        mvc.perform(scoped(get("/evaluations/board"), b.token(b.director, "MANAGERIAL"), a.id)).andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------- helpers

    private String path(School s, String childId, String subject) {
        return "/teacher/evaluations/" + s.round + "/" + s.classOf(childId.equals(s.zaid) ? "2A"
                : childId.equals(s.lama) ? "3A" : "1A") + "/" + subject + "/" + childId;
    }

    private JsonNode submit(School s, String childId, String subject) throws Exception {
        String teacher = childId.equals(s.zaid) ? s.amy : childId.equals(s.lama) ? s.lead : s.sara;
        return ok(bearer(post(path(s, childId, subject) + "/submit").contentType(MediaType.APPLICATION_JSON)
                .content(FULL), s.token(teacher)));
    }

    private void release(School s) throws Exception {
        mvc.perform(scoped(post("/admin/evaluation-rounds/" + s.round + "/release"), adminToken(), s.id))
                .andExpect(status().isOk());
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

    /** The one row of a teacher's board page that is still a draft — its id, for a review nobody may make. */
    private static String draftOf(JsonNode board) {
        for (var row : board.get("rows").get(0).get("rows"))
            if ("DRAFT".equals(row.path("state").asText())) return row.get("evaluationId").asText();
        throw new AssertionError("no draft on that page");
    }

    private static List<String> childNames(JsonNode page) {
        var out = new ArrayList<String>();
        page.get("rows").forEach(r -> out.add(r.get("childName").asText()));
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

    private static List<String> words(JsonNode evaluation) {
        var out = new ArrayList<String>();
        evaluation.get("criteria").forEach(c -> out.add(c.get("criterion").asText() + "=" + c.get("word").asText()));
        return out;
    }

    /** Every field name in the answer, however deep — what "the DTO has no rating field" is asserted against. */
    private static List<String> keys(JsonNode node) {
        var out = new ArrayList<String>();
        node.fieldNames().forEachRemaining(out::add);
        node.forEach(child -> out.addAll(keys(child)));
        return out;
    }

    /** Every numeric value in the answer, however deep — the sweep the spec asks for. */
    private static List<String> numbers(JsonNode node) {
        var out = new ArrayList<String>();
        if (node.isNumber()) out.add(node.asText());
        node.forEach(child -> out.addAll(numbers(child)));
        return out;
    }

    // ---------------------------------------------------------------- the fixture

    /** A school of the test's own, with the cast described on the class and one open round. */
    private School school(String label) throws Exception {
        String id = prefix() + label + "-" + UUID.randomUUID().toString().substring(0, 6);
        var row = new SchoolEntity();
        row.setId(id); row.setName(id);
        String code = id.replace("-", "").toUpperCase(Locale.ROOT);
        row.setCode(code.substring(0, Math.min(20, code.length())));
        row.setCurriculumOptionsJson("[\"british\",\"american\"]"); row.setGradeOptionsJson("[1,2,3,4]");
        row.setStatus("active"); row.setTimezone("Asia/Riyadh"); row.setCreatedAt(Instant.now());
        schools.save(row);
        var s = new School(id, row.getCode());
        s.flag(FlagKeys.CHILD_EVALUATIONS, true);
        s.flag(FlagKeys.STAFF_ATTENDANCE, true);                 // feature 17's board, compared with this one
        s.flag(FlagKeys.FLOOR_SUPERVISOR, true);
        s.seed();
        return s;
    }

    private final class School {
        final String id, code;
        String director, sara, amy, lead, nadia, maya, rania, zaid, lama, round;

        School(String id, String code) { this.id = id; this.code = code; }

        void seed() throws Exception {
            director = user("director", "MANAGERIAL", "Dalia Director");
            var scope = new StaffScopeEntity();
            scope.setId(UUID.randomUUID().toString()); scope.setSchoolId(id); scope.setUserId(director);
            scope.setSubject(null); scope.setCurriculum("british"); scope.setCreatedAt(Instant.now());
            scopes.save(scope);
            sara = teacher("sara", "تالا المعلمة", "british");
            amy = teacher("amy", "Amy American", "american");
            lead = teacher("lead", "Lina Lead", "american");
            nadia = user("nadia", quest.server.tenancy.FloorScope.ROLE, "Nadia Floor");

            var oneA = section("1A"); var twoA = section("2A"); var threeA = section("3A");
            assign(sara, "1A", "math"); assign(sara, "1A", "english");
            assign(amy, "2A", "math"); assign(lead, "3A", "math");
            reportsTo(amy, lead);
            floor(nadia, classOf("1A"));

            maya = child("Maya", code, oneA);
            zaid = child("Zaid", code, twoA);
            lama = child("Lama", code, threeA);
            rania = otherFamilysChild("Rania", oneA);
            round = json(mvc.perform(scoped(post("/admin/evaluation-rounds").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"nameEn\":\"Term 1 — mid-term\",\"nameAr\":\"الفصل الأول — منتصف الفصل\","
                            + "\"opensOn\":\"2026-11-01\",\"dueAt\":\"" + DUE_LOCAL + "\"}"), adminToken(), id))
                    .andExpect(status().isOk()).andReturn()).get("id").asText();
        }

        String classOf(String name) { return id + "-" + name; }

        String token(String userId) { return token(userId, "TEACHER"); }

        String token(String userId, String role) { return jwt.issue(userId, userId + "@ev33.test", role, id).token(); }

        String teacher(String label, String name, String curriculum) {
            String userId = user(label, "TEACHER", name);
            var t = new TeacherEntity();
            t.setUserId(userId); t.setCurriculum(curriculum); t.setUpdatedAt(Instant.now());
            teacherProfiles.save(t);
            return userId;
        }

        ClassEntity section(String name) {
            var k = new ClassEntity();
            k.setId(classOf(name)); k.setSchoolId(id); k.setCurriculum("british"); k.setGrade(1);
            k.setName(name); k.setActive(true); k.setCreatedAt(Instant.now());
            return classes.save(k);
        }

        void assign(String teacherId, String className, String subject) {
            var a = new quest.server.tenancy.Entities.TeachingAssignmentEntity();
            a.setId(UUID.randomUUID().toString()); a.setSchoolId(id); a.setTeacherId(teacherId);
            a.setClassId(classOf(className)); a.setSubject(subject); a.setCreatedAt(Instant.now());
            assignments.save(a);
        }

        void floor(String supervisorId, String classId) {
            var f = new quest.server.floors.Entities.FloorEntity();
            f.setId(id + "-floor"); f.setSchoolId(id); f.setName("Second floor"); f.setAcademy("british");
            f.setCreatedAt(Instant.now());
            floorRows.save(f);
            var on = new quest.server.floors.Entities.FloorClassEntity();
            on.setId(UUID.randomUUID().toString()); on.setFloorId(f.getId()); on.setSchoolId(id);
            on.setSectionId(classId); on.setCreatedAt(Instant.now());
            floorClassRows.save(on);
            var holder = new quest.server.floors.Entities.FloorSupervisorEntity();
            holder.setId(UUID.randomUUID().toString()); holder.setFloorId(f.getId()); holder.setSchoolId(id);
            holder.setUserId(supervisorId); holder.setCreatedAt(Instant.now());
            floorSupervisorRows.save(holder);
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
            u.setId(userId); u.setSchoolId(id); u.setEmail(userId + "@ev33.test"); u.setPasswordHash("x");
            u.setRole(role); u.setStatus("active"); u.setDisplayName(name);
            u.setCreatedAt(Instant.now()); u.setUpdatedAt(Instant.now());
            users.save(u);
            return userId;
        }

        /** A child of a different family, on 1A — the 404 every `/children/{id}/…` route owes her. */
        String otherFamilysChild(String name, ClassEntity section) throws Exception {
            String childId = json(mvc.perform(post("/children").header("Authorization", OTHER_PARENT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"" + name + "\",\"avatarColor\":\"sun\",\"curriculum\":\"" + section.getCurriculum()
                            + "\",\"grade\":" + section.getGrade() + ",\"schoolCode\":\"" + code + "\"}"))
                    .andExpect(status().is2xxSuccessful()).andReturn()).get("id").asText();
            childRows.findById(childId).ifPresent(c -> { c.setClassId(section.getId()); childRows.save(c); });
            return childId;
        }

        void flag(String key, boolean enabled) { setFlag(null, id, key, enabled); }
    }
}
