package quest.server.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import quest.server.tenancy.TenantContext;

/**
 * §6 screen 5 (the School page's Overview, Classes, Usage and Billing tabs) and §6 screens 19–20 (Managerial's own
 * school usage and her read-only staff list).
 */
class SchoolDataTest extends DashboardTestSupport {
    private static final String A = "data-school-a", B = "data-school-b";
    private static final String TEACHER_A = "data-teacher-a", MANAGER_A = "data-manager-a", TEACHER_B = "data-teacher-b";
    private static final String MATHS_A = A + ":british:1:math";

    @Override String prefix() { return "data-"; }

    private LocalDate day;

    @BeforeEach void seed() throws Exception {
        day = today();
        school(A, "Data Academy", "DATAAA");
        school(B, "Data Beta", "DATABB");
        user(TEACHER_A, A, "teacher@data-a.test", "TEACHER");
        teacherProfile(TEACHER_A, "[\"math\"]", "british", "[1]");
        user(MANAGER_A, A, "manager@data-a.test", "MANAGERIAL");
        user(TEACHER_B, B, "teacher@data-b.test", "TEACHER");
        klass(MATHS_A, A, "british", 1, "math", TEACHER_A);
        klass(B + ":british:1:math", B, "british", 1, "math", TEACHER_B);

        lesson("data-a-1", A, MATHS_A, "british", 1, "math", day.minusDays(1), "published",
                Instant.now().minus(1, ChronoUnit.DAYS), 4_000);
        lesson("data-a-2", A, MATHS_A, "british", 1, "math", day, "published", Instant.now(), 6_000);
        lessonWithSkill("data-a-3", A, MATHS_A, "british", 1, "math", day, "Adding to ten");

        var child = child("Sara", "DATAAA", "british", 1);
        // Both today, at a fixed point inside the day: the plays series is bucketed by UTC day, so "an hour ago"
        // would fall into yesterday's bucket whenever the suite runs just after midnight.
        attempt(child, "data-a-3", stopId("data-a-3"), true, noon(day));
        attempt(child, "data-a-3", stopId("data-a-3"), false, noon(day).plusSeconds(60));
    }

    @AfterEach void clean() { removeSeed(); }

    // ---------------------------------------------------------------- Overview counts

    @Test void the_school_page_carries_the_four_counts() throws Exception {
        var school = json(mvc.perform(admin(get("/admin/schools/" + A), adminToken())).andExpect(status().isOk()).andReturn());
        assertThat(school.get("lessons").asInt()).isGreaterThanOrEqualTo(3);
        assertThat(school.get("classes").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(school.get("teachers").asInt()).isEqualTo(1);
        assertThat(school.get("children").asInt()).isGreaterThanOrEqualTo(1);
    }

    @Test void the_users_list_carries_the_school_name() throws Exception {
        var users = json(mvc.perform(admin(get("/admin/users?schoolId=" + A), adminToken())).andExpect(status().isOk()).andReturn());
        assertThat(users).isNotEmpty().allSatisfy(u -> assertThat(u.get("schoolName").asText()).isEqualTo("Data Academy"));
    }

    // ---------------------------------------------------------------- Classes tab

    @Test void classes_are_listed_created_and_handed_to_a_teacher() throws Exception {
        var token = adminToken();
        var listed = json(mvc.perform(admin(get("/admin/schools/" + A + "/classes"), token)).andExpect(status().isOk()).andReturn());
        assertThat(ids(listed)).contains(MATHS_A);
        assertThat(classOf(listed, MATHS_A).get("teacherName").asText()).isEqualTo("teacher@data-a.test");

        var created = json(mvc.perform(admin(post("/admin/schools/" + A + "/classes").contentType(MediaType.APPLICATION_JSON)
                .content("{\"curriculum\":\"british\",\"grade\":2,\"subject\":\"english\"}"), token))
                .andExpect(status().isCreated()).andReturn());
        assertThat(created.get("schoolId").asText()).isEqualTo(A);
        assertThat(created.get("teacherId").isNull()).isTrue();

        var assigned = json(mvc.perform(admin(patch("/admin/schools/" + A + "/classes/" + created.get("id").asText())
                .contentType(MediaType.APPLICATION_JSON).content("{\"teacherId\":\"" + TEACHER_A + "\"}"), token))
                .andExpect(status().isOk()).andReturn());
        assertThat(assigned.get("teacherId").asText()).isEqualTo(TEACHER_A);
        assertThat(assigned.get("teacherName").asText()).isEqualTo("teacher@data-a.test");

        var cleared = json(mvc.perform(admin(patch("/admin/schools/" + A + "/classes/" + created.get("id").asText())
                .contentType(MediaType.APPLICATION_JSON).content("{\"clearTeacher\":true}"), token))
                .andExpect(status().isOk()).andReturn());
        assertThat(cleared.get("teacherId").isNull()).isTrue();
    }

    @Test void a_class_may_only_be_given_to_a_teacher_of_its_own_school() throws Exception {
        var token = adminToken();
        mvc.perform(admin(patch("/admin/schools/" + A + "/classes/" + MATHS_A).contentType(MediaType.APPLICATION_JSON)
                .content("{\"teacherId\":\"" + TEACHER_B + "\"}"), token)).andExpect(status().isBadRequest());
        mvc.perform(admin(patch("/admin/schools/" + A + "/classes/" + MATHS_A).contentType(MediaType.APPLICATION_JSON)
                .content("{\"teacherId\":\"" + MANAGER_A + "\"}"), token)).andExpect(status().isBadRequest());
        mvc.perform(admin(post("/admin/schools/" + A + "/classes").contentType(MediaType.APPLICATION_JSON)
                .content("{\"curriculum\":\"martian\",\"grade\":1,\"subject\":\"math\"}"), token)).andExpect(status().isBadRequest());
    }

    @Test void a_class_of_another_school_is_not_found() throws Exception {
        var token = adminToken();
        mvc.perform(admin(patch("/admin/schools/" + A + "/classes/" + B + ":british:1:math")
                .contentType(MediaType.APPLICATION_JSON).content("{\"clearTeacher\":true}"), token)).andExpect(status().isNotFound());
    }

    @Test void a_teacher_may_read_her_school_s_classes_but_not_change_them() throws Exception {
        var teacher = token(TEACHER_A, "TEACHER", A);
        assertThat(ids(json(mvc.perform(as(get("/admin/schools/" + A + "/classes"), teacher)).andExpect(status().isOk()).andReturn())))
                .contains(MATHS_A);
        mvc.perform(as(patch("/admin/schools/" + A + "/classes/" + MATHS_A).contentType(MediaType.APPLICATION_JSON)
                .content("{\"clearTeacher\":true}"), teacher)).andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------- Usage tab

    @Test void school_usage_counts_children_families_plays_and_publishing() throws Exception {
        var usage = json(mvc.perform(admin(get("/admin/schools/" + A + "/usage"), adminToken())).andExpect(status().isOk()).andReturn());

        assertThat(usage.get("schoolId").asText()).isEqualTo(A);
        assertThat(usage.get("schoolName").asText()).isEqualTo("Data Academy");
        assertThat(usage.get("children").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(usage.get("activeFamilies").asInt()).isEqualTo(1);

        // A gapless series ending today; the child's two answers to one lesson are one play, not two.
        var series = usage.get("playsPerDay");
        assertThat(series.size()).isEqualTo(30);
        assertThat(series.get(series.size() - 1).get("date").asText()).isEqualTo(day.toString());
        assertThat(series.get(series.size() - 1).get("count").asInt()).isEqualTo(1);

        assertThat(usage.get("lessonsPublishedPerWeek")).isNotEmpty();
        assertThat(total(usage.get("lessonsPublishedPerWeek"))).isGreaterThanOrEqualTo(3);

        var consistency = usage.get("teacherConsistency");
        assertThat(consistency).hasSize(1);
        assertThat(consistency.get(0).get("teacherId").asText()).isEqualTo(TEACHER_A);
        assertThat(consistency.get(0).get("lessonsPublished").asInt()).isEqualTo(3);
        assertThat(consistency.get(0).get("weeksWithALesson").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(consistency.get(0).get("lastPublishedAt").asLong()).isPositive();
    }

    @Test void a_usage_window_may_be_named_and_is_checked() throws Exception {
        var token = adminToken();
        var usage = json(mvc.perform(admin(get("/admin/schools/" + A + "/usage?from=" + day.minusDays(6) + "&to=" + day), token))
                .andExpect(status().isOk()).andReturn());
        assertThat(usage.get("from").asText()).isEqualTo(day.minusDays(6).toString());
        assertThat(usage.get("playsPerDay").size()).isEqualTo(7);

        mvc.perform(admin(get("/admin/schools/" + A + "/usage?from=" + day + "&to=" + day.minusDays(1)), token)).andExpect(status().isBadRequest());
        mvc.perform(admin(get("/admin/schools/" + A + "/usage?from=yesterday"), token)).andExpect(status().isBadRequest());
        mvc.perform(admin(get("/admin/schools/" + A + "/usage?from=" + day.minusYears(3)), token)).andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------- Billing tab

    @Test void billing_turns_this_school_s_tokens_into_months_of_cost() throws Exception {
        var billing = json(mvc.perform(admin(get("/admin/schools/" + A + "/billing?months=3"), adminToken()))
                .andExpect(status().isOk()).andReturn());

        assertThat(billing.get("schoolId").asText()).isEqualTo(A);
        assertThat(billing.get("currency").asText()).isEqualTo("USD");
        double price = billing.get("pricePer1kTokens").asDouble();
        assertThat(price).as("the rate is published with the figure it produced").isPositive();
        assertThat(billing.get("months").size()).isEqualTo(3);

        long tokens = billing.get("totalTokens").asLong();
        assertThat(tokens).as("4000 + 6000 + the lesson with a skill").isGreaterThanOrEqualTo(11_000);
        assertThat(billing.get("totalCostUsd").asDouble())
                .isEqualTo(Math.round(tokens / 1000.0 * price * 10_000.0) / 10_000.0);
        var thisMonth = billing.get("months").get(2);
        assertThat(thisMonth.get("month").asText()).isEqualTo(day.toString().substring(0, 7));
        assertThat(thisMonth.get("tokens").asLong()).isGreaterThanOrEqualTo(11_000);
    }

    @Test void billing_is_admin_only() throws Exception {
        mvc.perform(as(get("/admin/schools/" + A + "/billing"), token(MANAGER_A, "MANAGERIAL", A))).andExpect(status().isForbidden());
        mvc.perform(as(get("/admin/schools/" + A + "/billing"), token(TEACHER_A, "TEACHER", A))).andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------- Managerial's own school (§6 screens 19–20)

    @Test void a_managerial_user_reads_her_own_school_with_no_id_to_pass() throws Exception {
        var manager = token(MANAGER_A, "MANAGERIAL", A);
        var usage = json(mvc.perform(as(get("/school/usage"), manager)).andExpect(status().isOk()).andReturn());
        assertThat(usage.get("schoolId").asText()).isEqualTo(A);

        var teachers = json(mvc.perform(as(get("/school/teachers"), manager)).andExpect(status().isOk()).andReturn());
        assertThat(teachers).hasSize(1);
        var teacher = teachers.get(0);
        assertThat(teacher.get("userId").asText()).isEqualTo(TEACHER_A);
        assertThat(teacher.get("email").asText()).isEqualTo("teacher@data-a.test");
        assertThat(teacher.get("subjects").toString()).contains("math");
        assertThat(teacher.get("curriculum").asText()).isEqualTo("british");
        assertThat(ids(teacher.get("classes"))).contains(MATHS_A);
        assertThat(teacher.get("lessonsPublished").asInt()).isEqualTo(3);
        assertThat(teacher.get("lastPublishedAt").asLong()).isPositive();
    }

    @Test void an_admin_reads_school_routes_through_the_school_switcher() throws Exception {
        var token = adminToken();
        mvc.perform(admin(get("/school/usage"), token)).andExpect(status().isBadRequest());
        var usage = json(mvc.perform(admin(get("/school/usage"), token).header(TenantContext.HEADER, B))
                .andExpect(status().isOk()).andReturn());
        assertThat(usage.get("schoolId").asText()).isEqualTo(B);
    }

    @Test void a_teacher_may_not_read_usage_or_the_staff_list() throws Exception {
        var teacher = token(TEACHER_A, "TEACHER", A);
        mvc.perform(as(get("/school/usage"), teacher)).andExpect(status().isForbidden());
        mvc.perform(as(get("/school/teachers"), teacher)).andExpect(status().isForbidden());
        mvc.perform(as(get("/admin/schools/" + A + "/usage"), teacher)).andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------- Platform usage & cost (§6 screen 10)

    @Test void platform_usage_reports_calls_hit_rate_and_cost_per_school() throws Exception {
        var usage = json(mvc.perform(admin(get("/admin/usage/platform"), adminToken())).andExpect(status().isOk()).andReturn());

        assertThat(usage.get("schools").asInt()).isGreaterThanOrEqualTo(2);
        assertThat(usage.get("children").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(usage.get("playsPerDay").size()).isEqualTo(30);
        assertThat(usage.get("cacheHitRate").asDouble()).isBetween(0.0, 1.0);
        assertThat(usage.get("pricePer1kTokens").asDouble()).isPositive();

        var mine = schoolCost(usage, A);
        assertThat(mine.get("tokens").asLong()).isGreaterThanOrEqualTo(11_000);
        assertThat(mine.get("lessons").asInt()).isGreaterThanOrEqualTo(3);
        assertThat(mine.get("costUsd").asDouble()).isPositive();
        assertThat(schoolCost(usage, B).get("tokens").asLong()).as("school B published nothing").isZero();
    }

    @Test void platform_usage_is_admin_only() throws Exception {
        mvc.perform(as(get("/admin/usage/platform"), token(MANAGER_A, "MANAGERIAL", A))).andExpect(status().isForbidden());
        mvc.perform(as(get("/admin/usage/platform"), token(TEACHER_A, "TEACHER", A))).andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------- helpers

    private static List<String> ids(JsonNode array) {
        var out = new ArrayList<String>();
        array.forEach(node -> out.add(node.get("id").asText()));
        return out;
    }

    private static JsonNode classOf(JsonNode classes, String id) {
        for (var k : classes) if (id.equals(k.get("id").asText())) return k;
        throw new AssertionError(id + " is not in " + classes);
    }

    private static JsonNode schoolCost(JsonNode usage, String schoolId) {
        for (var s : usage.get("costPerSchool")) if (schoolId.equals(s.get("schoolId").asText())) return s;
        throw new AssertionError(schoolId + " is not in " + usage.get("costPerSchool"));
    }

    private static int total(JsonNode series) {
        int sum = 0;
        for (var point : series) sum += point.get("count").asInt();
        return sum;
    }
}
