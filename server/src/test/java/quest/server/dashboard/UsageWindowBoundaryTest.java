package quest.server.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The edges of a usage window, one second either side, read through the two report endpoints on a pinned day.
 *
 * <p>`SchoolDataTest` used to fail on developers' machines on Friday and Saturday evenings: its fixture published
 * "now", and a timestamp written after 21:00 UTC on a UTC+3 machine came back dated tomorrow, outside a window that
 * ends today. That suite now seeds at noon, which proves nothing about the edges. This one does: a lesson published
 * and a play answered at 23:59:59 on the last day of the window are counted, on that day, and ones at 00:00:00 the
 * next day are not — on whatever day and in whatever zone the JVM happens to run. Two things make that hold:
 * timestamps are stored as UTC wall time (`hibernate.type.preferred_instant_jdbc_type` in `application.yml`) and the
 * window is bound as {@link Instant}s rather than a `LocalDateTime` ({@link Reports.Window}). CI runs on UTC and would
 * never see either regress, so run this with {@code TZ=Asia/Riyadh ./mvnw test -Dtest=UsageWindowBoundaryTest}
 * (or any zone west of UTC) when touching them.
 */
class UsageWindowBoundaryTest extends DashboardTestSupport {
    private static final String SCHOOL = "edge-school", TEACHER = "edge-teacher", CLASS = SCHOOL + ":british:1:math";
    /** A Saturday: the day of the week the old failure showed on, so the ISO-week bucketing is exercised at its end. */
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 19);
    private static final LocalDate FROM = TODAY.minusDays(6);

    /** Token counts chosen so a sum names exactly which lessons the window admitted. */
    private static final long FIRST_SECOND = 1_000, LAST_SECOND = 10_000, SECOND_BEFORE = 100_000, SECOND_AFTER = 1_000_000;

    @Override String prefix() { return "edge-"; }

    @BeforeEach void seed() throws Exception {
        clock.pinTo(TODAY);
        school(SCHOOL, "Edge School", "EDGEAA");
        user(TEACHER, SCHOOL, "teacher@edge.test", "TEACHER");
        klass(CLASS, SCHOOL, "british", 1, "math", TEACHER);
        // `lesson` sets created_at to the publish instant, so the platform report's created_at filter meets the same edges.
        publish("edge-first-second", FROM, startOf(FROM), FIRST_SECOND);
        publish("edge-last-second", TODAY, lastSecondOf(TODAY), LAST_SECOND);
        publish("edge-second-before", FROM.minusDays(1), lastSecondOf(FROM.minusDays(1)), SECOND_BEFORE);
        publish("edge-second-after", TODAY.plusDays(1), startOf(TODAY.plusDays(1)), SECOND_AFTER);

        // One play at each of the same four instants, so the per-day series meets the edges too — and a play at
        // 23:59:59 must land on that day's bucket, not the next one's.
        var child = child("Edge", "EDGEAA", "british", 1);
        attempt(child, "edge-first-second", stopId("edge-first-second"), true, startOf(FROM));
        attempt(child, "edge-last-second", stopId("edge-last-second"), true, lastSecondOf(TODAY));
        attempt(child, "edge-second-before", stopId("edge-second-before"), true, lastSecondOf(FROM.minusDays(1)));
        attempt(child, "edge-second-after", stopId("edge-second-after"), true, startOf(TODAY.plusDays(1)));
    }

    @AfterEach void clean() { removeSeed(); clock.release(); }

    @Test void a_named_window_counts_its_first_and_last_second_and_nothing_either_side() throws Exception {
        String range = "?from=" + FROM + "&to=" + TODAY;
        var usage = schoolUsage(range);
        assertThat(usage.get("from").asText()).isEqualTo(FROM.toString());
        assertThat(usage.get("to").asText()).isEqualTo(TODAY.toString());
        assertThat(total(usage.get("lessonsPublishedPerWeek"))).isEqualTo(2);
        assertThat(usage.get("teacherConsistency").get(0).get("lessonsPublished").asInt()).isEqualTo(2);

        var plays = usage.get("playsPerDay");
        assertThat(plays).hasSize(7);
        assertThat(count(plays, FROM)).as("a play at 00:00:00 on the first day").isEqualTo(1);
        assertThat(count(plays, TODAY)).as("a play at 23:59:59 on the last day, on that day").isEqualTo(1);
        assertThat(total(plays)).isEqualTo(2);

        var mine = schoolCost(platformUsage(range), SCHOOL);
        assertThat(mine.get("lessons").asInt()).isEqualTo(2);
        assertThat(mine.get("tokens").asLong()).isEqualTo(FIRST_SECOND + LAST_SECOND);
    }

    /** The default window ends today, and "today" must mean all of it — this is the edge the usage screen shows first. */
    @Test void the_default_window_includes_all_of_today_and_none_of_tomorrow() throws Exception {
        var usage = schoolUsage("");
        assertThat(usage.get("to").asText()).isEqualTo(TODAY.toString());
        assertThat(usage.get("from").asText()).isEqualTo(TODAY.minusDays(Reports.DEFAULT_DAYS - 1L).toString());
        assertThat(total(usage.get("lessonsPublishedPerWeek"))).as("first, last and the one a week ago; not tomorrow's").isEqualTo(3);

        var plays = usage.get("playsPerDay");
        assertThat(plays).hasSize(Reports.DEFAULT_DAYS);
        assertThat(count(plays, TODAY)).isEqualTo(1);
        assertThat(count(plays, FROM.minusDays(1))).as("23:59:59 a week ago is that day, not the next").isEqualTo(1);
        assertThat(total(plays)).isEqualTo(3);

        // Not windowed by design — the teacher's latest publish, full stop — and so the instant written comes back
        // to the millisecond whatever zone the JVM runs in.
        assertThat(usage.get("teacherConsistency").get(0).get("lastPublishedAt").asLong())
                .isEqualTo(startOf(TODAY.plusDays(1)).toEpochMilli());

        var platform = platformUsage("");
        assertThat(count(platform.get("playsPerDay"), TODAY)).isGreaterThanOrEqualTo(1);
        var mine = schoolCost(platform, SCHOOL);
        assertThat(mine.get("lessons").asInt()).isEqualTo(3);
        assertThat(mine.get("tokens").asLong()).isEqualTo(FIRST_SECOND + LAST_SECOND + SECOND_BEFORE);
    }

    @Test void a_window_ending_yesterday_takes_nothing_from_today() throws Exception {
        String range = "?from=" + FROM.minusDays(1) + "&to=" + TODAY.minusDays(1);
        var usage = schoolUsage(range);
        assertThat(total(usage.get("lessonsPublishedPerWeek"))).isEqualTo(2);
        assertThat(total(usage.get("playsPerDay"))).isEqualTo(2);
        assertThat(schoolCost(platformUsage(range), SCHOOL).get("tokens").asLong()).isEqualTo(SECOND_BEFORE + FIRST_SECOND);
    }

    // ---------------------------------------------------------------- helpers

    private void publish(String id, LocalDate teaches, Instant at, long tokens) {
        lesson(id, SCHOOL, CLASS, "british", 1, "math", teaches, "published", at, tokens);
    }

    private static Instant startOf(LocalDate day) { return day.atStartOfDay(ZoneOffset.UTC).toInstant(); }
    private static Instant lastSecondOf(LocalDate day) { return startOf(day.plusDays(1)).minusSeconds(1); }

    private JsonNode schoolUsage(String query) throws Exception {
        return json(mvc.perform(admin(get("/admin/schools/" + SCHOOL + "/usage" + query), adminToken())).andExpect(status().isOk()).andReturn());
    }

    private JsonNode platformUsage(String query) throws Exception {
        return json(mvc.perform(admin(get("/admin/usage/platform" + query), adminToken())).andExpect(status().isOk()).andReturn());
    }

    private static JsonNode schoolCost(JsonNode usage, String schoolId) {
        for (var s : usage.get("costPerSchool")) if (schoolId.equals(s.get("schoolId").asText())) return s;
        throw new AssertionError(schoolId + " is not in " + usage.get("costPerSchool"));
    }

    private static int count(JsonNode series, LocalDate day) {
        for (var point : series) if (day.toString().equals(point.get("date").asText())) return point.get("count").asInt();
        throw new AssertionError(day + " is not in " + series);
    }

    private static int total(JsonNode series) {
        int sum = 0;
        for (var point : series) sum += point.get("count").asInt();
        return sum;
    }
}
