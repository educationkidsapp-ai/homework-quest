package quest.server.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.DayOfWeek;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import quest.server.ApiTestSupport;

/**
 * §A and N2.1: the school week and the timezone are Platform settings, and they are <strong>public</strong> — the
 * teacher's week grid cannot lay out its columns before it knows how many days a week has and where the day turns
 * over, and neither is a secret.
 *
 * <p>What is worth pinning is that a bad value never reaches the database. A week of unknown day names or a
 * timezone `ZoneId` does not recognise would not fail here; it would fail later, in every teacher's grid, with no
 * way to see where it came from.
 */
class PlatformSettingsWeekTest extends ApiTestSupport {
    @Autowired PlatformSettingsService settings;
    @Autowired SchoolCalendar calendar;
    @Autowired quest.server.tenancy.SchoolRepository schools;
    @Autowired quest.server.auth.AdminJwtService jwt;

    @AfterEach void restore() throws Exception {
        save("{\"schoolWeek\":[\"SUN\",\"MON\",\"TUE\",\"WED\",\"THU\"],\"timezone\":\"Asia/Riyadh\"}", status().isOk());
    }

    @Test void the_week_and_the_timezone_are_public_and_default_to_the_gulf_week() throws Exception {
        var published = json(mvc.perform(get("/platform-settings")).andExpect(status().isOk()).andReturn());
        assertThat(published.get("schoolWeek")).hasSize(5);
        assertThat(published.get("schoolWeek").get(0).asText()).isEqualTo("SUN");
        assertThat(published.get("timezone").asText()).isEqualTo("Asia/Riyadh");
        assertThat(settings.schoolWeekDays()).isEqualTo(SchoolCalendar.DEFAULT_WEEK);
    }

    @Test void admin_can_move_the_platform_onto_a_monday_week() throws Exception {
        save("{\"schoolWeek\":[\"mon\",\"TUESDAY\",\"WED\",\"THU\",\"FRI\"],\"timezone\":\"Europe/London\"}", status().isOk());

        assertThat(settings.schoolWeekDays()).containsExactly(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                DayOfWeek.THURSDAY, DayOfWeek.FRIDAY);
        var published = json(mvc.perform(get("/platform-settings")).andExpect(status().isOk()).andReturn());
        assertThat(published.get("schoolWeek").get(0).asText()).isEqualTo("MON");
        assertThat(published.get("timezone").asText()).isEqualTo("Europe/London");
        // a school with no override of its own follows the platform
        assertThat(calendar.of(null).days()).hasSize(5).first().isEqualTo(DayOfWeek.MONDAY);
        assertThat(calendar.of(null).zone()).isEqualTo(ZoneId.of("Europe/London"));
    }

    /**
     * N2.3b: the dashboard lays a teacher's week out with these two fields, so a signed-in caller has to be
     * answered <em>her school's</em> week and zone rather than the platform's — the school override is the reason
     * both fields are overridable at all, and reading the platform row would have drawn the wrong grid for a
     * Monday–Friday school.
     */
    @Test void a_signed_in_caller_is_answered_her_own_schools_week_and_zone() throws Exception {
        var school = schools.findById("psw-school").orElseGet(() -> {
            var s = new quest.server.tenancy.Entities.SchoolEntity();
            s.setId("psw-school"); s.setName("Week Academy"); s.setCode("PSWSCH");
            s.setCurriculumOptionsJson("[\"british\"]"); s.setGradeOptionsJson("[1]");
            s.setStatus("active"); s.setCreatedAt(java.time.Instant.now());
            return schools.save(s);
        });
        school.setSchoolWeekJson("[\"MON\",\"TUE\",\"WED\",\"THU\",\"FRI\"]");
        school.setTimezone("Europe/London");
        schools.save(school);

        var hers = json(mvc.perform(get("/platform-settings")
                .header("Authorization", "Bearer " + jwt.issue("psw-teacher", "psw@week.test", "TEACHER", "psw-school").token()))
                .andExpect(status().isOk()).andReturn());
        assertThat(hers.get("schoolWeek").get(0).asText()).isEqualTo("MON");
        assertThat(hers.get("timezone").asText()).isEqualTo("Europe/London");

        // …while the sign-in page, which has no token and no school, still reads the platform's own
        var anonymous = json(mvc.perform(get("/platform-settings")).andExpect(status().isOk()).andReturn());
        assertThat(anonymous.get("schoolWeek").get(0).asText()).isEqualTo("SUN");
        assertThat(anonymous.get("timezone").asText()).isEqualTo("Asia/Riyadh");
    }

    @Test void a_week_that_names_no_day_and_a_zone_that_does_not_exist_are_refused() throws Exception {
        for (String body : List.of("{\"schoolWeek\":[\"FUNDAY\"]}", "{\"schoolWeek\":[]}",
                "{\"timezone\":\"Mars/Olympus\"}", "{\"timezone\":\"GMT+25\"}"))
            save(body, status().isBadRequest());
        assertThat(settings.schoolWeekDays()).as("nothing was written").isEqualTo(SchoolCalendar.DEFAULT_WEEK);
    }

    private void save(String body, org.springframework.test.web.servlet.ResultMatcher expected) throws Exception {
        mvc.perform(admin(put("/admin/platform-settings"), adminToken())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(expected);
    }
}
