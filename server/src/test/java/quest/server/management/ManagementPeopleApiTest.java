package quest.server.management;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import quest.server.ClassFixtures;
import quest.server.grading.GradingTestSupport;
import quest.server.platform.SchoolCalendar;
import quest.server.tenancy.Entities.ClassEntity;
import quest.server.tenancy.StaffScopeRepository;

/**
 * RM5 (DR7) on {@code ManagementApiTest}'s shape: <strong>two managers, one per department</strong>. Nour runs British
 * — Maya teaches it and Lina coordinates it — and Sami runs American, with Rami and Omar.
 *
 * <p>What it proves: Nour marks her own teacher on a teaching day and the roster carries it back; Sami can neither
 * mark that teacher nor see her at all; a month with two marked days counts to the day and leaves the rest unmarked; a
 * day still to come is refused; the directory answers her British children with the contact the school holds and never
 * the American child; and `?q=` narrows every list by name or address.
 *
 * <p><strong>No date is hard-coded.</strong> The school week is a school setting (N2.1), so every day below is chosen
 * through {@link SchoolCalendar} — the last teaching day up to today, the first two of last month, the next one still
 * to come — and the suite passes whichever day it is run on.
 */
class ManagementPeopleApiTest extends GradingTestSupport {
    private static final String P = "rm5-";
    private static final String SCHOOL = P + "school", CODE = "RM5A01";
    private static final String MAYA = P + "maya", RAMI = P + "rami";
    private static final String NOUR = P + "nour", SAMI = P + "sami", LINA = P + "lina", OMAR = P + "omar";
    private static final String BRITISH = P + "1a-british", AMERICAN = P + "1a-american";

    @Override public String prefix() { return P; }

    @Autowired quest.server.auth.ParentRepository parentAccounts;
    @Autowired StaffScopeRepository staffScopes;
    @Autowired StaffAttendanceRepository register;
    @Autowired SchoolCalendar calendar;
    @Autowired StaffAttendanceRows rowWriter;

    private String nour, sami;
    private ClassEntity british;
    private LocalDate today, schoolDay, future;

    @BeforeEach void seed() throws Exception {
        school(SCHOOL, "Two Departments Academy", CODE);
        teacher(MAYA, SCHOOL, "Ms Maya"); teacher(RAMI, SCHOOL, "Mr Rami");
        staff(NOUR, "Nour", "MANAGERIAL"); staff(SAMI, "Sami", "MANAGERIAL");
        staff(LINA, "Lina", "COORDINATOR"); staff(OMAR, "Omar", "COORDINATOR");
        scopeRow(P + "dept-british", NOUR, null, "british");
        scopeRow(P + "dept-american", SAMI, null, "american");
        scopeRow(P + "scope-math", LINA, "math", "british");
        scopeRow(P + "scope-english", OMAR, "english", "american");

        british = klass(BRITISH, SCHOOL, MAYA, "1A British");
        var american = ClassFixtures.section(classes, assignments, AMERICAN, SCHOOL, "american", 1, "english", RAMI);
        american.setName("1A American"); classes.save(american);

        nour = token(NOUR, "MANAGERIAL", SCHOOL); sami = token(SAMI, "MANAGERIAL", SCHOOL);

        String hana = child("Hana", CODE, british);
        child("Sara", CODE, british);
        child("Yousef", CODE, american);
        // A name holding the two characters SQL reads as wildcards, so `?q=` can be proved literal.
        child("100%_off", CODE, british);
        // The roster's own contact column, which an import fills in and a parent account does not.
        childRows.findById(hana).ifPresent(c -> { c.setParentEmail("guardian@british.test"); childRows.save(c); });

        today = calendar.today(SCHOOL);
        schoolDay = lastSchoolDay(today);
        future = nextSchoolDay(today.plusDays(1));
    }

    @AfterEach void clean() {
        register.deleteAll(register.findAll().stream().filter(r -> r.getSchoolId().startsWith(P)).toList());
        staffScopes.deleteAll(staffScopes.findAll().stream().filter(r -> r.getId().startsWith(P)).toList());
        removeSeed();
    }

    // ---------------------------------------------------------------- the register

    @Test void nour_marks_her_teacher_late_and_the_roster_carries_it_back() throws Exception {
        var before = json(mvc.perform(as(get("/management/staff-attendance?day=" + schoolDay), nour))
                .andExpect(status().isOk()).andReturn());
        assertThat(before.get("day").asText()).isEqualTo(schoolDay.toString());
        assertThat(before.get("schoolDay").asBoolean()).isTrue();
        assertThat(before.get("editable").asBoolean()).as("a teaching day up to today may be marked").isTrue();
        assertThat(ids(before.get("people"), "userId")).containsExactly(MAYA, LINA);
        assertThat(before.get("people").get(0).get("status").isNull()).as("unmarked is no answer, not present").isTrue();
        assertThat(before.get("unmarked").asInt()).isEqualTo(2);

        var marked = json(mvc.perform(mark(nour, schoolDay, "[{\"userId\":\"" + MAYA + "\",\"status\":\"late\",\"note\":\"Traffic\"}]"))
                .andExpect(status().isOk()).andReturn());
        var maya = row(marked.get("people"), MAYA);
        assertThat(maya.get("status").asText()).isEqualTo("late");
        assertThat(maya.get("note").asText()).isEqualTo("Traffic");
        assertThat(maya.get("role").asText()).isEqualTo("TEACHER");
        assertThat(maya.get("markedBy").asText()).isEqualTo(NOUR);
        assertThat(maya.get("markedAt").asLong()).isPositive();
        assertThat(marked.get("late").asInt()).isOne();
        assertThat(marked.get("unmarked").asInt()).isOne();

        // The roster is a read of the same rows, and a second mark of the same day overwrites rather than duplicates.
        assertThat(row(json(mvc.perform(as(get("/management/staff-attendance?day=" + schoolDay), nour))
                .andExpect(status().isOk()).andReturn()).get("people"), MAYA).get("status").asText()).isEqualTo("late");
        mvc.perform(mark(nour, schoolDay, "[{\"userId\":\"" + MAYA + "\",\"status\":\"present\"}]")).andExpect(status().isOk());
        assertThat(register.findAll().stream().filter(r -> r.getUserId().equals(MAYA)).toList()).hasSize(1);

        // Her history is the same row, seen from the person's side.
        var history = json(mvc.perform(as(get("/management/staff-attendance/" + MAYA), nour)).andExpect(status().isOk()).andReturn());
        assertThat(history.get("displayName").asText()).isEqualTo("Ms Maya");
        assertThat(history.get("marks")).hasSize(1);
        assertThat(history.get("marks").get(0).get("day").asText()).isEqualTo(schoolDay.toString());
    }

    @Test void sami_can_neither_see_nor_mark_the_other_departments_teacher() throws Exception {
        var his = json(mvc.perform(as(get("/management/staff-attendance?day=" + schoolDay), sami))
                .andExpect(status().isOk()).andReturn());
        assertThat(ids(his.get("people"), "userId")).containsExactly(RAMI, OMAR).doesNotContain(MAYA, LINA);

        mvc.perform(mark(sami, schoolDay, "[{\"userId\":\"" + MAYA + "\",\"status\":\"absent\"}]"))
                .andExpect(status().isForbidden());
        mvc.perform(as(get("/management/staff-attendance/" + MAYA), sami)).andExpect(status().isForbidden());
        assertThat(register.findAll().stream().filter(r -> r.getUserId().equals(MAYA)).toList())
                .as("a refused register writes nothing").isEmpty();

        // And no other role reaches the area at all.
        for (String role : List.of("TEACHER", "COORDINATOR"))
            mvc.perform(as(get("/management/staff-attendance"), token(P + "who-" + role, role, SCHOOL)))
                    .andExpect(status().isForbidden());
        mvc.perform(get("/management/staff-attendance")).andExpect(status().isUnauthorized());
    }

    @Test void a_month_with_two_marked_days_counts_to_the_day() throws Exception {
        // Last month, so the window is the whole month and never clipped at today.
        var days = firstSchoolDays(YearMonth.from(today).minusMonths(1), 2);
        mvc.perform(mark(nour, days.get(0), "[{\"userId\":\"" + MAYA + "\",\"status\":\"present\"},"
                + "{\"userId\":\"" + LINA + "\",\"status\":\"absent\"}]")).andExpect(status().isOk());
        mvc.perform(mark(nour, days.get(1), "[{\"userId\":\"" + MAYA + "\",\"status\":\"late\"}]")).andExpect(status().isOk());

        var month = YearMonth.from(days.get(0));
        var body = json(mvc.perform(as(get("/management/staff-attendance/summary?month=" + month), nour))
                .andExpect(status().isOk()).andReturn());
        assertThat(body.get("month").asText()).isEqualTo(month.toString());
        assertThat(body.get("from").asText()).isEqualTo(month.atDay(1).toString());
        assertThat(body.get("to").asText()).isEqualTo(month.atEndOfMonth().toString());
        int schoolDays = body.get("schoolDays").asInt();
        assertThat(schoolDays).as("a month holds at least three teaching weeks").isGreaterThan(14);

        var maya = row(body.get("people"), MAYA);
        assertThat(maya.get("present").asInt()).isOne();
        assertThat(maya.get("late").asInt()).isOne();
        assertThat(maya.get("absent").asInt()).isZero();
        assertThat(maya.get("unmarked").asInt()).isEqualTo(schoolDays - 2);
        assertThat(maya.get("rate").asDouble()).as("present + late over marked").isEqualTo(100.0);

        var lina = row(body.get("people"), LINA);
        assertThat(lina.get("role").asText()).isEqualTo("COORDINATOR");
        assertThat(lina.get("absent").asInt()).isOne();
        assertThat(lina.get("rate").asDouble()).isZero();
        assertThat(lina.get("unmarked").asInt()).isEqualTo(schoolDays - 1);

        // A month nobody marked is no answer rather than a perfect score.
        var quiet = json(mvc.perform(as(get("/management/staff-attendance/summary?month=" + month.minusMonths(6)), nour))
                .andExpect(status().isOk()).andReturn());
        assertThat(row(quiet.get("people"), MAYA).get("rate").isNull()).isTrue();
    }

    @Test void a_day_still_to_come_and_a_day_off_are_refused() throws Exception {
        mvc.perform(mark(nour, future, "[{\"userId\":\"" + MAYA + "\",\"status\":\"present\"}]"))
                .andExpect(status().isBadRequest()).andExpect(content().string(org.hamcrest.Matchers.containsString("still to come")));
        // A weekend is refused on the way in, and reads back as a day nothing was expected on.
        LocalDate off = dayOff();
        mvc.perform(mark(nour, off, "[{\"userId\":\"" + MAYA + "\",\"status\":\"present\"}]"))
                .andExpect(status().isBadRequest()).andExpect(content().string(org.hamcrest.Matchers.containsString("teaching day")));
        var weekend = json(mvc.perform(as(get("/management/staff-attendance?day=" + off), nour)).andExpect(status().isOk()).andReturn());
        assertThat(weekend.get("schoolDay").asBoolean()).isFalse();
        assertThat(weekend.get("editable").asBoolean()).isFalse();

        // And the rest of the refusals: an unknown status, a person named twice, a broken day.
        mvc.perform(mark(nour, schoolDay, "[{\"userId\":\"" + MAYA + "\",\"status\":\"holiday\"}]")).andExpect(status().isBadRequest());
        mvc.perform(mark(nour, schoolDay, "[{\"userId\":\"" + MAYA + "\",\"status\":\"present\"},"
                + "{\"userId\":\"" + MAYA + "\",\"status\":\"absent\"}]")).andExpect(status().isBadRequest());
        mvc.perform(as(get("/management/staff-attendance?day=yesterday"), nour)).andExpect(status().isBadRequest());
        mvc.perform(as(get("/management/staff-attendance/summary?month=2026"), nour)).andExpect(status().isBadRequest());
        assertThat(register.findAll().stream().filter(r -> r.getSchoolId().startsWith(P)).toList()).isEmpty();
    }

    // ---------------------------------------------------------------- the directory

    @Test void the_directory_answers_her_children_with_their_contact_and_not_the_other_departments() throws Exception {
        var body = json(mvc.perform(as(get("/management/people/children"), nour)).andExpect(status().isOk()).andReturn());
        assertThat(body.get("total").asInt()).isEqualTo(3);
        assertThat(body.get("page").asInt()).isZero();
        assertThat(body.get("size").asInt()).isEqualTo(25);
        assertThat(ids(body.get("rows"), "name")).containsExactly("100%_off", "Hana", "Sara").doesNotContain("Yousef");

        var hana = body.get("rows").get(1);
        assertThat(hana.get("className").asText()).isEqualTo("1A British");
        assertThat(hana.get("curriculum").asText()).isEqualTo("british");
        assertThat(hana.get("grade").asInt()).isOne();
        assertThat(hana.get("rosterEmail").asText()).isEqualTo("guardian@british.test");
        assertThat(hana.get("parentEmail").asText()).as("the account a parent actually signed up with").isNotBlank();
        assertThat(hana.get("placedAt").asLong()).isPositive();

        // One section of the department, and a section of the other one.
        assertThat(field(get("/management/people/children?classId=" + BRITISH), nour, "name")).contains("Hana", "Sara");
        mvc.perform(as(get("/management/people/children?classId=" + AMERICAN), nour)).andExpect(status().isForbidden());
        assertThat(field(get("/management/people/children"), sami, "name")).containsExactly("Yousef");

        // A page past the end is empty and still says how many there are.
        var second = json(mvc.perform(as(get("/management/people/children?page=1&size=1"), nour)).andExpect(status().isOk()).andReturn());
        assertThat(second.get("total").asInt()).isEqualTo(3);
        assertThat(ids(second.get("rows"), "name")).containsExactly("Hana");
        mvc.perform(as(get("/management/people/children?size=500"), nour)).andExpect(status().isBadRequest());
        mvc.perform(as(get("/management/people/children?page=-1"), nour)).andExpect(status().isBadRequest());
    }

    @Test void q_is_literal_text_and_reaches_the_account_a_parent_signed_up_with() throws Exception {
        // `%` and `_` are characters, not wildcards: each matches only the child whose name holds it. Set through
        // `param` rather than in the path, because `MockMvcRequestBuilders.get` re-encodes a query string.
        assertThat(field(children("%"), nour, "name")).containsExactly("100%_off");
        assertThat(field(children("_"), nour, "name")).containsExactly("100%_off");
        assertThat(field(children("100%_o"), nour, "name")).containsExactly("100%_off");
        assertThat(field(children("Han%"), nour, "name")).as("a wildcard cannot be smuggled in").isEmpty();
        assertThat(field(children("_ana"), nour, "name")).as("nor can a single-character one").isEmpty();
        // The escape character itself is escaped too, so it matches text rather than breaking the statement.
        assertThat(field(children("\\"), nour, "name")).isEmpty();
        // And with no `q` at all every child of the department is still there.
        assertThat(field(children(null), nour, "name")).hasSize(3);

        // The other address the school holds: the account her parent actually signed up with, matched in the same
        // statement as the roster's own column.
        var hana = json(mvc.perform(as(get("/management/people/children?q=han"), nour)).andExpect(status().isOk()).andReturn())
                .get("rows").get(0);
        String account = hana.get("parentEmail").asText();
        assertThat(account).isNotBlank().isNotEqualTo("guardian@british.test");
        assertThat(field(get("/management/people/children?q=" + account), nour, "name")).contains("Hana");

        // S1: the name the Admin typed for that account is on the row, and `q` reaches it.
        var parent = parentAccounts.findFirstByEmailIgnoreCase(account).orElseThrow();
        parent.setDisplayName("Huda Rahman"); parentAccounts.save(parent);
        var named = json(mvc.perform(as(get("/management/people/children?q=rahman"), nour)).andExpect(status().isOk()).andReturn()).get("rows");
        assertThat(ids(named, "name")).contains("Hana");
        for (var row : named) assertThat(row.get("parentName").asText()).isEqualTo("Huda Rahman");
        assertThat(field(get("/management/people/children?q=nobody-by-that-name"), nour, "name")).isEmpty();
    }

    @Test void a_second_mark_of_the_same_day_overwrites_the_row_the_first_one_wrote() throws Exception {
        // The race, from the losing side: the row exists, so the insert `mark` would attempt is refused by the unique
        // `(user_id, date)` and the recovery overwrites it. Forced here rather than with two threads, as `ExamApiTest`
        // forces its own.
        var now = java.time.Instant.now();
        rowWriter.insert(SCHOOL, MAYA, schoolDay, "absent", null, NOUR, now);
        assertThatThrownBy(() -> rowWriter.insert(SCHOOL, MAYA, schoolDay, "present", null, NOUR, now))
                .as("the unique index is what makes read-then-insert unsafe")
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        // The endpoint takes the same day anyway: the last PUT decides, and there is still one row.
        var body = json(mvc.perform(mark(nour, schoolDay, "[{\"userId\":\"" + MAYA + "\",\"status\":\"late\"}]"))
                .andExpect(status().isOk()).andReturn());
        assertThat(row(body.get("people"), MAYA).get("status").asText()).isEqualTo("late");
        assertThat(register.findAll().stream().filter(r -> r.getUserId().equals(MAYA)).toList()).hasSize(1);
        assertThat(rowWriter.overwrite(MAYA, schoolDay, "leave", "Hajj", NOUR, now).getStatus()).isEqualTo("leave");
        assertThat(rowWriter.overwrite(RAMI, schoolDay, "leave", null, NOUR, now)).as("no row to overwrite").isNull();
    }

    @Test void a_month_that_has_not_started_is_refused() throws Exception {
        mvc.perform(as(get("/management/staff-attendance/summary?month=" + java.time.YearMonth.from(today).plusMonths(1)), nour))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("has not started")));
        mvc.perform(as(get("/management/staff-attendance/summary?month=" + java.time.YearMonth.from(today)), nour))
                .andExpect(status().isOk());
    }

    @Test void the_staff_lists_are_the_department_and_q_narrows_every_one_of_them() throws Exception {
        var teachers = json(mvc.perform(as(get("/management/people/teachers"), nour)).andExpect(status().isOk()).andReturn());
        assertThat(teachers.get("total").asInt()).isOne();
        var maya = teachers.get("rows").get(0);
        assertThat(maya.get("userId").asText()).isEqualTo(MAYA);
        assertThat(ids(maya.get("subjects"), null)).containsExactly("math");
        assertThat(ids(maya.get("sections"), "className")).containsExactly("1A British");

        var coordinators = json(mvc.perform(as(get("/management/people/coordinators"), nour)).andExpect(status().isOk()).andReturn());
        assertThat(ids(coordinators.get("rows"), "userId")).containsExactly(LINA);
        assertThat(ids(json(mvc.perform(as(get("/management/people/coordinators"), sami)).andExpect(status().isOk()).andReturn())
                .get("rows"), "userId")).containsExactly(OMAR);

        // `q` matches a name or an address, on all three lists, and narrows nothing away when it is blank.
        assertThat(field(get("/management/people/children?q=han"), nour, "name")).containsExactly("Hana");
        assertThat(field(get("/management/people/children?q=GUARDIAN@british"), nour, "name")).containsExactly("Hana");
        assertThat(json(mvc.perform(as(get("/management/people/children?q=yousef"), nour)).andExpect(status().isOk()).andReturn())
                .get("total").asInt()).as("q never widens the department").isZero();
        assertThat(field(get("/management/people/teachers?q=maya"), nour, "userId")).containsExactly(MAYA);
        assertThat(field(get("/management/people/teachers?q=rami"), nour, "userId")).isEmpty();
        assertThat(field(get("/management/people/coordinators?q=" + P + "lina@seed.test"), nour, "userId")).containsExactly(LINA);
        assertThat(field(get("/management/people/teachers?q="), nour, "userId")).containsExactly(MAYA);
    }

    // ---------------------------------------------------------------- fixture helpers

    /** `GET /management/people/children` with `q` set as a parameter, so no URI encoding stands between the two. */
    private MockHttpServletRequestBuilder children(String q) {
        var request = get("/management/people/children");
        return q == null ? request : request.param("q", q);
    }

    private MockHttpServletRequestBuilder mark(String token, LocalDate day, String body) {
        return as(put("/management/staff-attendance?day=" + day).contentType(MediaType.APPLICATION_JSON).content(body), token);
    }

    /** The last teaching day up to and including `from`. */
    private LocalDate lastSchoolDay(LocalDate from) {
        var week = calendar.of(SCHOOL);
        LocalDate d = from;
        while (!week.isSchoolDay(d)) d = d.minusDays(1);
        return d;
    }

    private LocalDate nextSchoolDay(LocalDate from) {
        var week = calendar.of(SCHOOL);
        LocalDate d = from;
        while (!week.isSchoolDay(d)) d = d.plusDays(1);
        return d;
    }

    /** A day the school does not teach on; the default week leaves two of them. */
    private LocalDate dayOff() {
        var week = calendar.of(SCHOOL);
        for (int i = 0; i < 7; i++) if (!week.isSchoolDay(today.minusDays(i))) return today.minusDays(i);
        throw new AssertionError("this school teaches every day");
    }

    private List<LocalDate> firstSchoolDays(YearMonth month, int howMany) {
        var week = calendar.of(SCHOOL);
        var days = new ArrayList<LocalDate>(howMany);
        for (LocalDate d = month.atDay(1); !d.isAfter(month.atEndOfMonth()) && days.size() < howMany; d = d.plusDays(1))
            if (week.isSchoolDay(d)) days.add(d);
        return days;
    }

    private static JsonNode row(JsonNode rows, String userId) {
        for (var row : rows) if (userId.equals(row.get("userId").asText())) return row;
        throw new AssertionError(userId + " is not on the roster");
    }

    private List<String> ids(JsonNode rows, String field) {
        var out = new ArrayList<String>();
        rows.forEach(row -> out.add((field == null ? row : row.get(field)).asText()));
        return out;
    }

    /** The named field of every row of a paged body, so a directory read is one line in a test. */
    private List<String> field(MockHttpServletRequestBuilder request, String token, String name) throws Exception {
        return ids(json(mvc.perform(as(request, token)).andExpect(status().isOk()).andReturn()).get("rows"), name);
    }

    /** A staff account of one of the two scoped roles, with no teacher profile behind it. */
    private void staff(String id, String displayName, String role) {
        var u = users.findById(id).orElseGet(quest.server.auth.Entities.UserEntity::new);
        u.setId(id); u.setSchoolId(SCHOOL); u.setEmail(id + "@seed.test"); u.setPasswordHash("x");
        u.setRole(role); u.setStatus("active"); u.setDisplayName(displayName);
        if (u.getCreatedAt() == null) u.setCreatedAt(Instant.now());
        u.setUpdatedAt(Instant.now());
        users.save(u);
    }

    /** One `staff_scopes` row: a manager's is a department (`subject` null), a coordinator's a subject. */
    private void scopeRow(String id, String userId, String subject, String curriculum) {
        var row = staffScopes.findById(id).orElseGet(quest.server.tenancy.Entities.StaffScopeEntity::new);
        row.setId(id); row.setSchoolId(SCHOOL); row.setUserId(userId);
        row.setSubject(subject); row.setCurriculum(curriculum);
        if (row.getCreatedAt() == null) row.setCreatedAt(Instant.now());
        staffScopes.save(row);
    }
}
