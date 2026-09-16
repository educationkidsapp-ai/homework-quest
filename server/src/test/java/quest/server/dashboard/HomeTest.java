package quest.server.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import quest.server.tenancy.TenantContext;

/** §6 screen 2: one route, three shapes. What each role's Home counts, and what it says needs them. */
class HomeTest extends DashboardTestSupport {
    private static final String A = "home-school-a";
    private static final String TEACHER = "home-teacher-a", MANAGER = "home-manager-a", QUIET = "home-teacher-quiet";
    private static final String MATHS = A + ":british:1:math", ENGLISH = A + ":british:1:english";

    @Override String prefix() { return "home-"; }

    private String childId;

    @BeforeEach void seed() throws Exception {
        school(A, "Home Academy", "HOMEAA");
        user(TEACHER, A, "teacher@home.test", "TEACHER");
        teacherProfile(TEACHER, "[\"math\",\"english\"]", "british", "[1]");
        user(QUIET, A, "quiet@home.test", "TEACHER");
        teacherProfile(QUIET, "[\"math\"]", "british", "[1]");
        user(MANAGER, A, "manager@home.test", "MANAGERIAL");
        // An invitation that has been sitting unaccepted for a fortnight: the Admin's Home should mention it.
        user("home-stale-invite", A, "stale@home.test", "TEACHER", "invited", Instant.now().minus(14, ChronoUnit.DAYS));

        klass(MATHS, A, "british", 1, "math", TEACHER);
        klass(ENGLISH, A, "british", 1, "english", TEACHER);
        klass(A + ":british:2:math", A, "british", 2, "math", QUIET);

        // Maths has today's lesson; English has none, so it is what needs her.
        lessonWithSkill("home-maths-today", A, MATHS, "british", 1, "math", today(), "Counting to twenty");
        lesson("home-english-broken", A, ENGLISH, "british", 1, "english", today().minusDays(2), "error", null, 0);
        lesson("home-english-review", A, ENGLISH, "british", 1, "english", today().minusDays(1), "needs_review", null, 0);

        childId = child("Rana", "HOMEAA", "british", 1);
        // Three first tries, two of them wrong: enough for a band, and a weak one. One lands yesterday (which the
        // teacher's Home counts) and two today (which this week's active families counts, Mondays included).
        attempt(childId, "home-maths-today", stopId("home-maths-today"), false, noon(today().minusDays(1)));
        attempt(childId, "home-maths-today", stopId("home-maths-today"), false, noon(today()));
        attempt(childId, "home-maths-today", stopId("home-maths-today"), true, noon(today()).plusSeconds(60));
    }

    @AfterEach void clean() { removeSeed(); }

    // ---------------------------------------------------------------- ADMIN

    @Test void an_admin_home_counts_the_platform_and_lists_what_is_stuck() throws Exception {
        var home = json(mvc.perform(admin(get("/me/home"), adminToken())).andExpect(status().isOk()).andReturn());

        assertThat(home.get("role").asText()).isEqualTo("ADMIN");
        assertThat(home.get("schoolId").isNull()).as("no X-School-Id: the platform view").isTrue();
        assertThat(home.get("schoolName").isNull()).isTrue();
        assertThat(home.get("schoolLogoUrl").isNull()).isTrue();
        assertThat(home.get("platformName").asText()).isNotBlank();
        assertThat(cardKeys(home)).containsExactly("schools", "children", "lessonsThisWeek");
        assertThat(home.get("classes").isNull()).as("classes are a teacher's, not an admin's").isTrue();
        assertThat(home.get("weakSkills").isNull()).isTrue();

        assertThat(kinds(home)).contains("lesson.error", "lesson.needs_review", "user.staleInvite");
        assertThat(titles(home, "user.staleInvite")).contains("stale@home.test");
        assertThat(hrefs(home, "lesson.error")).anySatisfy(href -> assertThat(href).isEqualTo("/admin/lessons/home-english-broken"));
    }

    @Test void an_admin_who_picked_a_school_gets_that_school_s_home() throws Exception {
        var home = json(mvc.perform(admin(get("/me/home"), adminToken()).header(TenantContext.HEADER, A))
                .andExpect(status().isOk()).andReturn());
        assertThat(home.get("schoolId").asText()).isEqualTo(A);
        assertThat(home.get("schoolName").asText()).isEqualTo("Home Academy");
        assertThat(card(home, "schools")).isEqualTo("1");
        assertThat(kinds(home)).as("a school with teachers is not a school without one").doesNotContain("school.noTeacher");
    }

    // ---------------------------------------------------------------- TEACHER

    @Test void a_teacher_home_shows_her_classes_todays_lesson_and_her_weakest_skills() throws Exception {
        var home = json(mvc.perform(admin(get("/me/home"), token(TEACHER, "TEACHER", A))).andExpect(status().isOk()).andReturn());

        assertThat(home.get("role").asText()).isEqualTo("TEACHER");
        assertThat(home.get("schoolId").asText()).isEqualTo(A);
        assertThat(home.get("schoolName").asText()).isEqualTo("Home Academy");
        assertThat(cardKeys(home)).containsExactly("playedYesterday", "lessonsThisWeek", "needsReview");
        assertThat(card(home, "playedYesterday")).as("the child answered yesterday").isEqualTo("1");
        assertThat(card(home, "needsReview")).isEqualTo("1");

        var classes = home.get("classes");
        assertThat(ids(classes, "classId")).containsExactlyInAnyOrder(MATHS, ENGLISH);
        assertThat(classOf(classes, MATHS).get("todayLessonId").asText()).isEqualTo("home-maths-today");
        assertThat(classOf(classes, MATHS).get("todayStatus").asText()).isEqualTo("published");
        assertThat(classOf(classes, ENGLISH).get("todayLessonId").isNull()).isTrue();

        // "Add today's lesson" on the class that is missing one, with the class already chosen.
        var addToday = hrefs(home, "class.noLessonToday");
        assertThat(addToday).hasSize(1);
        assertThat(addToday.get(0)).contains("classId=" + ENGLISH).contains("subject=english").contains("date=" + today());

        assertThat(kinds(home)).contains("lesson.error");
        assertThat(home.get("weakSkills")).isNotEmpty();
        assertThat(home.get("weakSkills").get(0).get("name").asText()).isEqualTo("Counting to twenty");
        assertThat(home.get("weakSkills").get(0).get("band").asText()).isEqualTo("NEEDS_ANOTHER_LOOK");
        assertThat(home.get("weakSkills").size()).as("§6 screen 11: three at most").isLessThanOrEqualTo(3);
    }

    /** In a school of its own, so the counts the other tests assert on school A stay the same whatever the run order. */
    @Test void a_teacher_with_no_classes_gets_an_empty_home_rather_than_an_error() throws Exception {
        school("home-school-b", "Home Beta", "HOMEBB");
        user("home-teacher-idle", "home-school-b", "idle@home.test", "TEACHER");
        var home = json(mvc.perform(admin(get("/me/home"), token("home-teacher-idle", "TEACHER", "home-school-b"))).andExpect(status().isOk()).andReturn());
        assertThat(home.get("classes")).isEmpty();
        assertThat(home.get("weakSkills")).isEmpty();
        assertThat(card(home, "playedYesterday")).isEqualTo("0");
    }

    // ---------------------------------------------------------------- MANAGERIAL

    @Test void a_managerial_home_counts_her_school_and_names_the_quiet_teachers() throws Exception {
        var home = json(mvc.perform(admin(get("/me/home"), token(MANAGER, "MANAGERIAL", A))).andExpect(status().isOk()).andReturn());

        assertThat(home.get("role").asText()).isEqualTo("MANAGERIAL");
        assertThat(cardKeys(home)).containsExactly("children", "activeFamilies", "teachers");
        assertThat(Integer.parseInt(card(home, "children"))).isGreaterThanOrEqualTo(1);
        assertThat(card(home, "activeFamilies")).isEqualTo("1");
        assertThat(card(home, "teachers")).as("two active teachers; the invited one is not counted").isEqualTo("2");

        assertThat(kinds(home)).containsOnly("teacher.quiet");
        assertThat(titles(home, "teacher.quiet")).contains("quiet@home.test")
                .as("the teacher who published today is not quiet").doesNotContain("teacher@home.test");

        // Phase 5 has not happened yet, so the Home says nothing about complaints at all.
        assertThat(cardKeys(home)).doesNotContain("openComplaints");
        assertThat(home.toString()).doesNotContain("complaint");
    }

    @Test void a_parent_token_has_no_dashboard_home() throws Exception {
        mvc.perform(get("/me/home").header("Authorization", PARENT)).andExpect(status().isForbidden());
        mvc.perform(get("/me/home")).andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------- helpers

    private List<String> cardKeys(JsonNode home) { return ids(home.get("cards"), "key"); }

    private String card(JsonNode home, String key) {
        for (var c : home.get("cards")) if (key.equals(c.get("key").asText())) return c.get("value").asText();
        throw new AssertionError(key + " is not a card of " + home.get("cards"));
    }

    private List<String> kinds(JsonNode home) { return ids(home.get("needsYou"), "kind"); }

    private List<String> titles(JsonNode home, String kind) { return field(home, kind, "title"); }

    private List<String> hrefs(JsonNode home, String kind) { return field(home, kind, "href"); }

    private List<String> field(JsonNode home, String kind, String name) {
        var out = new ArrayList<String>();
        for (var item : home.get("needsYou")) if (kind.equals(item.get("kind").asText())) out.add(item.get(name).asText());
        return out;
    }

    private static List<String> ids(JsonNode array, String field) {
        var out = new ArrayList<String>();
        array.forEach(node -> out.add(node.get(field).asText()));
        return out;
    }

    private static JsonNode classOf(JsonNode classes, String classId) {
        for (var k : classes) if (classId.equals(k.get("classId").asText())) return k;
        throw new AssertionError(classId + " is not in " + classes);
    }
}
