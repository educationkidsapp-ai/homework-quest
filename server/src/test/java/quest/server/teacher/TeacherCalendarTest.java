package quest.server.teacher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.DayOfWeek;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * §6 screen 12: her lessons by class and date, and the calendar that shows the gaps.
 *
 * <p>The month is fixed (April 2026) rather than "now", so the assertions about which days are school days and
 * which are gaps do not depend on the day the suite runs.
 */
class TeacherCalendarTest extends TeacherTestSupport {
    private static final String A = "tc-school-a", B = "tc-school-b";
    private static final String TEACHER_A = "tc-teacher-a", TEACHER_A2 = "tc-teacher-a2", TEACHER_B = "tc-teacher-b";
    private static final String CLASS_A1 = "tc-school-a:british:1:math", CLASS_A2 = "tc-school-a:british:2:math";
    private static final String CLASS_OTHER = "tc-school-a:british:3:english", CLASS_B1 = "tc-school-b:british:1:math";
    /**
     * A Thursday and the Friday after it in April 2026 — a month that is over, so every day of it has "already
     * arrived" and a school day without a lesson really is a gap. A future month would report none (see
     * {@link #a_school_day_still_in_the_future_is_not_yet_a_gap}), which is a different assertion.
     */
    private static final LocalDate THURSDAY = LocalDate.of(2026, 4, 9), FRIDAY = LocalDate.of(2026, 4, 10);
    /** April 2026 runs Wednesday to Thursday: 30 days, 8 of them Friday or Saturday. */
    private static final int SCHOOL_DAYS_IN_APRIL = 22;

    @Override String prefix() { return "tc-"; }

    private String teacherToken, otherTeacherToken, teacherBToken;

    @BeforeEach void seed() {
        school(A, "Calendar Academy", "TCSCHA");
        school(B, "Calendar Beta", "TCSCHB");
        teacher(TEACHER_A, A, "a@tc.test", "Ms Sara", "[\"math\"]", "british", "[1,2]");
        teacher(TEACHER_A2, A, "a2@tc.test", "Ms Dana", "[\"english\"]", "british", "[3]");
        teacher(TEACHER_B, B, "b@tc.test", "Ms Lina", "[\"math\"]", "british", "[1]");
        klass(CLASS_A1, A, "british", 1, "math", TEACHER_A);
        klass(CLASS_A2, A, "british", 2, "math", TEACHER_A);
        klass(CLASS_OTHER, A, "british", 3, "english", TEACHER_A2);
        klass(CLASS_B1, B, "british", 1, "math", TEACHER_B);
        lesson("tc-lesson-thu", A, CLASS_A1, "british", 1, "math", THURSDAY, "published");
        lesson("tc-lesson-fri", A, CLASS_A1, "british", 1, "math", FRIDAY, "draft");
        lesson("tc-lesson-b", B, CLASS_B1, "british", 1, "math", THURSDAY, "published");

        teacherToken = token(TEACHER_A, "TEACHER", A);
        otherTeacherToken = token(TEACHER_A2, "TEACHER", A);
        teacherBToken = token(TEACHER_B, "TEACHER", B);
    }

    @AfterEach void clean() { removeSeed(); }

    @Test void the_month_shows_every_day_with_its_lesson_and_its_status() throws Exception {
        var month = april(teacherToken, CLASS_A1);
        assertThat(month.get("classId").asText()).isEqualTo(CLASS_A1);
        assertThat(month.get("curriculum").asText()).isEqualTo("british");
        assertThat(month.get("grade").asInt()).isEqualTo(1);
        assertThat(month.get("subject").asText()).isEqualTo("math");
        assertThat(month.get("days")).hasSize(30);

        var thursday = day(month, THURSDAY);
        assertThat(thursday.get("lessonId").asText()).isEqualTo("tc-lesson-thu");
        assertThat(thursday.get("status").asText()).isEqualTo("published");
        assertThat(thursday.get("schoolDay").asBoolean()).isTrue();
        assertThat(thursday.get("gap").asBoolean()).isFalse();

        // a draft counts as planned: the day is not a gap, and the calendar says which state it is in
        var friday = day(month, FRIDAY);
        assertThat(friday.get("status").asText()).isEqualTo("draft");
    }

    /**
     * The Sunday–Thursday assumption, pinned so that changing it is a deliberate act rather than a silent one (see
     * {@link TeacherCalendarService#SCHOOL_WEEK}).
     */
    @Test void friday_and_saturday_are_not_school_days_and_never_gaps() throws Exception {
        var month = april(teacherToken, CLASS_A2);                              // a class with no lessons at all
        month.get("days").forEach(d -> {
            var date = LocalDate.parse(d.get("date").asText());
            boolean weekend = date.getDayOfWeek() == DayOfWeek.FRIDAY || date.getDayOfWeek() == DayOfWeek.SATURDAY;
            assertThat(d.get("schoolDay").asBoolean()).as("%s", date).isEqualTo(!weekend);
            if (weekend) assertThat(d.get("gap").asBoolean()).as("%s is a weekend, not a gap", date).isFalse();
        });
        // every Sunday-to-Thursday day of a month that is over, and this class has no lesson on any of them
        assertThat(month.get("gaps").asInt()).isEqualTo(SCHOOL_DAYS_IN_APRIL);
    }

    @Test void a_school_day_still_in_the_future_is_not_yet_a_gap() throws Exception {
        var next = LocalDate.now().plusMonths(1);
        var month = json(mvc.perform(as(get("/teacher/classes/" + CLASS_A2 + "/calendar?year=" + next.getYear()
                + "&month=" + next.getMonthValue()), teacherToken)).andExpect(status().isOk()).andReturn());
        assertThat(month.get("gaps").asInt()).as("a month that has not happened yet is not a month of failures").isZero();
    }

    @Test void the_calendar_is_scoped_like_every_other_class_route() throws Exception {
        mvc.perform(as(get("/teacher/classes/" + CLASS_OTHER + "/calendar?year=2026&month=4"), teacherToken))
                .andExpect(status().isForbidden());
        mvc.perform(as(get("/teacher/classes/" + CLASS_A1 + "/calendar?year=2026&month=4"), otherTeacherToken))
                .andExpect(status().isForbidden());
        var refused = mvc.perform(as(get("/teacher/classes/" + CLASS_A1 + "/calendar?year=2026&month=4"), teacherBToken))
                .andExpect(status().isNotFound()).andReturn();
        assertThat(refused.getResponse().getContentAsString()).doesNotContain("tc-lesson-thu");
    }

    @Test void a_bad_month_is_refused() throws Exception {
        mvc.perform(as(get("/teacher/classes/" + CLASS_A1 + "/calendar?year=2027&month=13"), teacherToken))
                .andExpect(status().isBadRequest());
        mvc.perform(as(get("/teacher/classes/" + CLASS_A1 + "/calendar?year=1900&month=4"), teacherToken))
                .andExpect(status().isBadRequest());
    }

    /** §6 screen 12's "her lessons only, by class": the new `classId` filter on the list she already had. */
    @Test void the_lesson_list_narrows_to_one_class() throws Exception {
        var mine = mvc.perform(as(get("/admin/lessons?classId=" + CLASS_A1), teacherToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(mine).contains("tc-lesson-thu").doesNotContain("tc-lesson-b");

        var empty = mvc.perform(as(get("/admin/lessons?classId=" + CLASS_A2), teacherToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(empty).doesNotContain("tc-lesson-thu");

        // a class of another school narrows to nothing rather than widening what she can see
        var other = mvc.perform(as(get("/admin/lessons?classId=" + CLASS_B1), teacherToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(other).doesNotContain("tc-lesson-b");
    }

    private com.fasterxml.jackson.databind.JsonNode april(String token, String classId) throws Exception {
        return json(mvc.perform(as(get("/teacher/classes/" + classId + "/calendar?year=2026&month=4"), token))
                .andExpect(status().isOk()).andReturn());
    }

    private static com.fasterxml.jackson.databind.JsonNode day(com.fasterxml.jackson.databind.JsonNode month, LocalDate date) {
        for (var d : month.get("days")) if (d.get("date").asText().equals(date.toString())) return d;
        throw new AssertionError("no day " + date + " in the month");
    }
}
