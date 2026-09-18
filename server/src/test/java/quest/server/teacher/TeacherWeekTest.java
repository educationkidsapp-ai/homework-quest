package quest.server.teacher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import quest.server.platform.SchoolCalendar;

/**
 * `docs/teacher-flow.md` §4 — the week grid. Four things are worth a test and the rest is arithmetic:
 *
 * <ul>
 *   <li>the default week is the Gulf Sunday–Thursday, and `start` is snapped back to its first day whichever day of
 *       the week the client asked for;</li>
 *   <li>a school that runs another week gets that week, because the grid's columns come from
 *       {@link SchoolCalendar} and not from a constant;</li>
 *   <li>"today" is read in the school's timezone, which is what decides whether an empty day is a gap or a day that
 *       has not happened yet — the bug a teacher in Riyadh would see at 00:30 every night;</li>
 *   <li>the whole response costs the same number of statements for ten assignments as for one.</li>
 * </ul>
 */
class TeacherWeekTest extends TeacherTestSupport {
    private static final String SCHOOL = "tw-school";
    private static final String TEACHER = "tw-teacher";

    @Override String prefix() { return "tw-"; }

    @Autowired jakarta.persistence.EntityManagerFactory emf;
    @Autowired SchoolCalendar calendar;
    @Autowired quest.server.platform.PlatformSettingsService platform;

    private String teacherToken;

    @BeforeEach void seed() throws Exception {
        school(SCHOOL, "Week Academy", "TWSCH1");
        teacher(TEACHER, SCHOOL, "week@tw.test", "Ms Sara", "[\"math\"]", "british", "[1]");
        section("tw-class-1", "1M");
        teacherToken = token(TEACHER, "TEACHER", SCHOOL);
    }

    @AfterEach void clean() {
        var school = schools.findById(SCHOOL).orElseThrow();
        school.setSchoolWeekJson(null); school.setTimezone(null); schools.save(school);
        platform.invalidate();
        removeSeed();
        // `removeSeed` puts back rows, not structure: a test that grows the fixture to ten sections would otherwise
        // leave them for the next one, and this class asserts on the number of rows in the grid. A child keeps her
        // row when she is soft-deleted, and it points at a section, so the roster is emptied before the sections go.
        childRows.findAll().stream().filter(c -> c.getSchoolId().startsWith(prefix()) && c.getClassId() != null)
                .forEach(c -> { c.setClassId(null); childRows.save(c); });
        assignments.deleteAll(assignments.findAll().stream().filter(a -> a.getClassId().startsWith(prefix())).toList());
        classes.deleteAll(classes.findAll().stream().filter(k -> k.getId().startsWith(prefix())).toList());
    }

    // ---------------------------------------------------------------- the week itself

    @Test void the_default_week_is_sunday_to_thursday_and_start_is_snapped_to_it() throws Exception {
        var wednesday = nextDayOfWeek(DayOfWeek.WEDNESDAY);
        var week = week(wednesday.toString());

        assertThat(week.get("start").asText()).as("any day of the week answers that week")
                .isEqualTo(wednesday.minusDays(3).toString());
        assertThat(days(week)).containsExactly(
                wednesday.minusDays(3).toString(), wednesday.minusDays(2).toString(), wednesday.minusDays(1).toString(),
                wednesday.toString(), wednesday.plusDays(1).toString());
        assertThat(week.get("rows")).hasSize(1);
        var row = week.get("rows").get(0);
        assertThat(row.get("className").asText()).isEqualTo("1M");
        assertThat(row.get("subject").asText()).isEqualTo("math");
        assertThat(row.get("cells")).hasSize(5);
    }

    @Test void a_school_on_a_monday_week_gets_a_monday_week() throws Exception {
        var school = schools.findById(SCHOOL).orElseThrow();
        school.setSchoolWeekJson("[\"MON\",\"TUE\",\"WED\",\"THU\",\"FRI\"]"); schools.save(school);

        var wednesday = nextDayOfWeek(DayOfWeek.WEDNESDAY);
        var week = week(wednesday.toString());
        assertThat(week.get("start").asText()).isEqualTo(wednesday.minusDays(2).toString());
        assertThat(days(week)).hasSize(5).first().isEqualTo(wednesday.minusDays(2).toString());
    }

    /**
     * The one assertion that has to be exact whatever hour CI runs at: the school's zone is set to an offset far
     * enough from UTC that the two are never on the same date, and "today" has to follow the school.
     */
    @Test void today_is_read_in_the_schools_timezone() throws Exception {
        var utcNow = java.time.Instant.now().atZone(ZoneOffset.UTC);
        boolean forward = utcNow.getHour() >= 6;
        String zone = forward ? "+18:00" : "-18:00";
        var expected = utcNow.toLocalDate().plusDays(forward ? 1 : -1);

        var school = schools.findById(SCHOOL).orElseThrow();
        school.setTimezone(zone); schools.save(school);

        assertThat(calendar.today(SCHOOL)).as("a school's day turns over in its own zone").isEqualTo(expected);
        assertThat(calendar.of(SCHOOL).zone()).isEqualTo(ZoneId.of(zone));
        // and the grid follows it: today's week is the week that contains the school's today, not the server's.
        assertThat(week(null).get("start").asText()).isEqualTo(calendar.of(SCHOOL).startOf(expected).toString());
    }

    @Test void a_school_day_without_a_lesson_is_a_gap_only_once_it_has_arrived() throws Exception {
        var today = calendar.today(SCHOOL);
        var taught = teachingDayThisWeek();
        lesson("tw-lesson-taught", SCHOOL, "tw-class-1", "british", 1, "math", taught, "review");

        var week = week(null);
        for (JsonNode gap : week.get("summary").get("gaps")) {
            assertThat(LocalDate.parse(gap.get("date").asText()))
                    .as("a day still ahead is not yet a gap").isBeforeOrEqualTo(today);
            assertThat(gap.get("date").asText()).isNotEqualTo(taught.toString());
            assertThat(gap.get("className").asText()).isEqualTo("1M");
        }
        assertThat(cellFor(week, taught)).as("the day with a lesson is never a gap").isNotNull();
        assertThat(cellFor(week, taught).get("status").asText()).isEqualTo("ready");
    }

    @Test void a_cell_carries_the_lesson_its_class_size_and_how_many_have_played_it() throws Exception {
        var taught = teachingDayThisWeek();
        lessonWithSkill("tw-lesson-played", SCHOOL, "tw-class-1", "british", 1, "math", taught, "Counting");
        var maya = inClass("Maya", "tw-class-1");
        child("Omar", "TWSCH1", "british", 1);                          // no section: not one of the class's children
        attempt(maya, "tw-lesson-played", stopId("tw-lesson-played"), true, java.time.Instant.now());

        var cell = cellFor(week(null), taught);
        assertThat(cell.get("id").asText()).isEqualTo("tw-lesson-played");
        assertThat(cell.get("status").asText()).isEqualTo("published");
        assertThat(cell.get("playedCount").asInt()).isEqualTo(1);
        assertThat(cell.get("childrenCount").asInt()).as("only the children of that class count").isEqualTo(1);
    }

    @Test void my_classes_is_todays_card_for_every_assignment() throws Exception {
        var today = calendar.today(SCHOOL);
        lessonWithSkill("tw-lesson-today", SCHOOL, "tw-class-1", "british", 1, "math", today, "Counting");
        var maya = inClass("Maya", "tw-class-1");
        attempt(maya, "tw-lesson-today", stopId("tw-lesson-today"), true, java.time.Instant.now());

        var cards = json(mvc.perform(as(get("/teacher/classes"), teacherToken)).andExpect(status().isOk()).andReturn());
        assertThat(cards).hasSize(1);
        assertThat(cards.get(0).get("className").asText()).isEqualTo("1M");
        assertThat(cards.get(0).get("todayLessonId").asText()).isEqualTo("tw-lesson-today");
        assertThat(cards.get(0).get("todayStatus").asText()).isEqualTo("published");
        assertThat(cards.get(0).get("playedToday").asInt()).isEqualTo(1);
        assertThat(cards.get(0).get("childrenCount").asInt()).isEqualTo(1);
    }

    // ---------------------------------------------------------------- the cost

    @Test void the_week_costs_the_same_for_ten_assignments_as_for_one() throws Exception {
        var today = calendar.today(SCHOOL);
        lesson("tw-lesson-a", SCHOOL, "tw-class-1", "british", 1, "math", today, "review");
        long one = statements(() -> mvc.perform(as(get("/teacher/week"), teacherToken)).andExpect(status().isOk()));
        long oneCards = statements(() -> mvc.perform(as(get("/teacher/classes"), teacherToken)).andExpect(status().isOk()));

        for (int i = 2; i <= 10; i++) {
            section("tw-class-" + i, "1M" + i);
            lesson("tw-lesson-" + i, SCHOOL, "tw-class-" + i, "british", 1, "math", today, "review");
        }

        assertThat(statements(() -> mvc.perform(as(get("/teacher/week"), teacherToken)).andExpect(status().isOk())))
                .as("the week grid must not run a query per assignment, day or lesson").isEqualTo(one);
        assertThat(statements(() -> mvc.perform(as(get("/teacher/classes"), teacherToken)).andExpect(status().isOk())))
                .as("My classes must not run a query per card").isEqualTo(oneCards);
    }

    /** N2.1's budget: both landing screens answer in under 300 ms on a school the size of the seed. */
    @Test void both_screens_answer_within_the_budget_on_a_full_school() throws Exception {
        for (int i = 2; i <= 8; i++) {
            section("tw-big-" + i, "1B" + i);
            for (int d = 0; d < 5; d++)
                lesson("tw-big-lesson-" + i + "-" + d, SCHOOL, "tw-big-" + i, "british", 1, "math",
                        calendar.today(SCHOOL).minusDays(d), "published");
            for (int c = 0; c < 6; c++) {
                var id = inClass("Child " + i + "-" + c, "tw-big-" + i);
                attempt(id, "tw-big-lesson-" + i + "-0", stopId("tw-big-lesson-" + i + "-0"), true, java.time.Instant.now());
            }
        }
        assertThat(p95("/teacher/week")).as("GET /teacher/week p95").isLessThan(300);
        assertThat(p95("/teacher/classes")).as("GET /teacher/classes p95").isLessThan(300);
    }

    // ---------------------------------------------------------------- helpers

    private JsonNode week(String start) throws Exception {
        String path = "/teacher/week" + (start == null ? "" : "?start=" + start);
        return json(mvc.perform(as(get(path), teacherToken)).andExpect(status().isOk()).andReturn());
    }

    private static List<String> days(JsonNode week) {
        var out = new ArrayList<String>();
        week.get("days").forEach(d -> out.add(d.asText()));
        return out;
    }

    /** The lesson in the only row's cell for that date, or null. */
    private static JsonNode cellFor(JsonNode week, LocalDate date) {
        for (JsonNode cell : week.get("rows").get(0).get("cells"))
            if (cell.get("date").asText().equals(date.toString())) return cell.get("lesson").isNull() ? null : cell.get("lesson");
        return null;
    }

    /**
     * A V7 section with a teaching assignment on it — not {@link #klass}, whose pre-V7 `subject`/`teacherId` columns
     * are covered by a unique index that a second class of the same grade and subject would collide with.
     */
    private void section(String id, String name) {
        var existing = classes.findById(id);
        var k = existing.orElseGet(() -> {
            var fresh = new quest.server.tenancy.Entities.ClassEntity();
            fresh.setId(id); fresh.setSchoolId(SCHOOL); fresh.setCurriculum("british"); fresh.setGrade(1);
            fresh.setName(name); fresh.setJoinCode("TW" + Math.abs(id.hashCode() % 100000));
            fresh.setActive(true); fresh.setJoinCodeEnabled(true); fresh.setCreatedAt(java.time.Instant.now());
            return classes.save(fresh);
        });
        quest.server.ClassFixtures.assign(assignments, k, "math", TEACHER);
    }

    /** A teaching day of the current week that has already arrived — the last one on or before today. */
    private LocalDate teachingDayThisWeek() {
        var week = calendar.of(SCHOOL);
        var today = calendar.today(SCHOOL);
        var dates = week.datesFrom(week.startOf(today));
        LocalDate chosen = dates.getFirst();
        for (LocalDate d : dates) if (!d.isAfter(today)) chosen = d;
        return chosen;
    }

    /** A child of the school, put on one class's roster. */
    private String inClass(String name, String classId) throws Exception {
        var id = child(name, "TWSCH1", "british", 1);
        childRows.findById(id).ifPresent(c -> { c.setClassId(classId); childRows.save(c); });
        return id;
    }

    private LocalDate nextDayOfWeek(DayOfWeek day) {
        LocalDate d = calendar.today(SCHOOL);
        while (d.getDayOfWeek() != day) d = d.plusDays(1);
        return d;
    }

    private long p95(String path) throws Exception {
        for (int i = 0; i < 5; i++) mvc.perform(as(get(path), teacherToken)).andExpect(status().isOk());
        var timings = new ArrayList<Long>();
        for (int i = 0; i < 20; i++) {
            long at = System.nanoTime();
            mvc.perform(as(get(path), teacherToken)).andExpect(status().isOk());
            timings.add((System.nanoTime() - at) / 1_000_000);
        }
        timings.sort(Long::compareTo);
        return timings.get(18);
    }

    private interface Call { void run() throws Exception; }

    private long statements(Call call) throws Exception {
        var stats = emf.unwrap(org.hibernate.SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true); stats.clear();
        call.run();
        return stats.getPrepareStatementCount();
    }
}
