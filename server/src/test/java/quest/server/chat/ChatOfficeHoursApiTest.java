package quest.server.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import quest.server.flags.FlagKeys;
import quest.server.push.PushProbe;
import quest.server.push.PushSender;

/**
 * Feature 19 end to end: a parent's message outside a teacher's office hours arrives quietly and is counted, one
 * summary goes out when her next working day starts, and everything else — inside her hours, a teacher who opted in,
 * a school with the flag off — is exactly as it was. Then the parent's half: the thread row that says when the
 * teacher is back and which number to ring meanwhile.
 *
 * <p>School A keeps feature 14's defaults (Sunday–Thursday, 07:00–14:00) in Asia/Riyadh, and the clock is pinned, so
 * "21:00" and "07:05" below are the school's own times. 2026-10-18 is a Sunday.
 */
class ChatOfficeHoursApiTest extends ChatTestSupport {
    private static final LocalDate SUNDAY = LocalDate.of(2026, 10, 18), MONDAY = SUNDAY.plusDays(1);
    private static final ZoneId RIYADH = ZoneId.of("Asia/Riyadh");

    @Autowired PushSender pushes;
    @Autowired quest.server.push.StaffDeviceRepository staffDevices;
    @Autowired ChatAfterHoursRepository afterHours;
    @Autowired OfficeHoursSummaryJob job;
    @Autowired quest.server.staffattendance.AttendanceHolidayRepository holidays;
    @Autowired JdbcTemplate jdbc;

    private String maya, phone;

    @BeforeEach void seed() throws Exception {
        seedSchools();
        maya = child("Maya", "CHSCHA", section1a);
        var school = schools.findById(A).orElseThrow();
        school.setTimezone(RIYADH.getId());
        schools.save(school);
        setFlag(adminToken, A, FlagKeys.STAFF_MOBILE_APP, true);
        setFlag(adminToken, A, FlagKeys.CHAT_OFFICE_HOURS, true);
        phone = PushProbe.token("oh-sara-phone");
        PushProbe.register(mvc, "Bearer " + sara, phone, "en");
    }

    @AfterEach void clean() {
        clock.release();
        staffDevices.deleteByTokenValue(phone);
        afterHours.deleteAll(afterHours.findAll().stream().filter(r -> r.getSchoolId().startsWith(prefix())).toList());
        // The bell rows outlive `removeSeed`, and every test below counts the summaries of the same teacher.
        jdbc.update("DELETE FROM notifications WHERE school_id = ? AND kind = 'chat.after_hours_summary'", A);
        holidays.deleteAll(holidays.findBySchoolIdOrderByFromDateAsc(A));
        users.findById(SARA).ifPresent(u -> { u.setAfterHoursAlerts(false); users.save(u); });
        setFlag(adminToken, A, FlagKeys.CHAT_OFFICE_HOURS, false);
        setFlag(adminToken, A, FlagKeys.STAFF_MOBILE_APP, false);
        removeSeed();
    }

    // ---------------------------------------------------------------- the quiet push and the summary

    @Test void a_message_at_night_is_quiet_counted_and_summarised_once_the_next_morning() throws Exception {
        at(SUNDAY, "21:00");
        parentPost(thread("messages"), send("Is Maya all right?"));

        var pushed = PushProbe.await(pushes, phone, 1);
        assertThat(pushed).hasSize(1);
        assertThat(pushed.get(0).message().getQuiet()).as("after hours: her phone does not ring").isTrue();
        assertThat(rows()).singleElement().satisfies(r -> {
            assertThat(r.getUserId()).isEqualTo(SARA);
            assertThat(r.getDueDay()).as("her next working day").isEqualTo(MONDAY);
            assertThat(r.getSummarizedAt()).isNull();
        });

        // A second line the same night is counted too, although the bell row is only refreshed and nothing is pushed.
        parentPost(thread("messages"), send("And her reading book?"));
        assertThat(rows()).hasSize(2);
        assertThat(PushProbe.await(pushes, phone, 2)).as("one unread row, one push").hasSize(1);

        at(MONDAY, "06:30");
        assertThat(job.run(A)).as("her day has not started yet").isZero();
        assertThat(summaries()).isEmpty();

        at(MONDAY, "07:05");
        assertThat(job.run(A)).isEqualTo(1);
        assertThat(job.run(A)).as("a rerun sends nothing").isZero();
        assertThat(summaries()).singleElement().satisfies(row -> {
            assertThat(row.get("BODY")).isEqualTo("You got 2 messages after hours");
            assertThat(row.get("LINK").toString()).isEqualTo("/teacher/chat?count=2");
        });
        assertThat(rows()).allSatisfy(r -> assertThat(r.getSummarizedAt()).isNotNull());
    }

    @Test void inside_her_hours_the_push_is_loud_and_nothing_is_counted() throws Exception {
        at(SUNDAY, "09:00");
        parentPost(thread("messages"), send("Good morning"));
        var pushed = PushProbe.await(pushes, phone, 1);
        assertThat(pushed).hasSize(1);
        assertThat(pushed.get(0).message().getQuiet()).isFalse();
        assertThat(rows()).isEmpty();
        at(MONDAY, "07:05");
        assertThat(job.run(A)).isZero();
    }

    @Test void a_teacher_who_asked_for_her_alerts_keeps_them() throws Exception {
        at(SUNDAY, "21:00");
        var me = json(mvc.perform(put("/staff/me/preferences").header("Authorization", "Bearer " + sara)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"afterHoursAlerts\":true}"))
                .andExpect(status().isOk()).andReturn());
        assertThat(me.get("afterHoursAlerts").asBoolean()).isTrue();
        assertThat(json(mvc.perform(get("/staff/me").header("Authorization", "Bearer " + sara)).andExpect(status().isOk()).andReturn())
                .get("afterHoursAlerts").asBoolean()).as("and it is hers to keep").isTrue();

        parentPost(thread("messages"), send("Is Maya all right?"));
        var pushed = PushProbe.await(pushes, phone, 1);
        assertThat(pushed).hasSize(1);
        assertThat(pushed.get(0).message().getQuiet()).isFalse();
        assertThat(rows()).as("she hears about it now, so there is nothing to summarise").isEmpty();
    }

    @Test void with_the_flag_off_a_night_message_is_what_it_always_was() throws Exception {
        setFlag(adminToken, A, FlagKeys.CHAT_OFFICE_HOURS, false);
        at(SUNDAY, "21:00");
        parentPost(thread("messages"), send("Is Maya all right?"));
        var pushed = PushProbe.await(pushes, phone, 1);
        assertThat(pushed).hasSize(1);
        assertThat(pushed.get(0).message().getQuiet()).isFalse();
        assertThat(rows()).isEmpty();
        at(MONDAY, "07:05");
        assertThat(job.run(A)).isZero();
    }

    @Test void a_holiday_pushes_the_summary_to_the_working_day_after_it() throws Exception {
        holiday(MONDAY);
        at(SUNDAY, "21:00");
        parentPost(thread("messages"), send("Is Maya all right?"));
        assertThat(rows()).singleElement().satisfies(r -> assertThat(r.getDueDay()).isEqualTo(MONDAY.plusDays(1)));

        at(MONDAY, "07:05");
        assertThat(job.run(A)).as("the school is closed: nothing is due").isZero();
        at(MONDAY.plusDays(1), "07:05");
        assertThat(job.run(A)).isEqualTo(1);
        assertThat(summaries()).singleElement()
                .satisfies(row -> assertThat(row.get("BODY")).isEqualTo("You got 1 message after hours"));
    }

    // ---------------------------------------------------------------- what the parent sees

    @Test void the_parents_thread_row_says_when_the_teacher_is_back_and_which_number_to_ring() throws Exception {
        var school = json(mvc.perform(admin(patch("/admin/schools/" + A).contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"011 234 5678\"}"), adminToken)).andExpect(status().isOk()).andReturn());
        assertThat(school.get("phone").asText()).as("normalised, as every number in the school is").isEqualTo("0112345678");

        at(SUNDAY, "21:00");
        var hours = row(parentGet("/children/" + maya + "/chat/threads"), SARA).get("officeHours");
        assertThat(hours.get("open").asBoolean()).isFalse();
        assertThat(hours.get("nextStart").asText()).isEqualTo("2026-10-19T07:00");
        assertThat(hours.get("today").asText()).isEqualTo("2026-10-18");
        assertThat(hours.get("schoolPhone").asText()).isEqualTo("0112345678");

        at(SUNDAY, "09:00");
        var open = row(parentGet("/children/" + maya + "/chat/threads"), SARA).get("officeHours");
        assertThat(open.get("open").asBoolean()).isTrue();
        assertThat(open.hasNonNull("nextStart")).as("she is at work; there is nothing to wait for").isFalse();

        setFlag(adminToken, A, FlagKeys.CHAT_OFFICE_HOURS, false);
        assertThat(row(parentGet("/children/" + maya + "/chat/threads"), SARA).hasNonNull("officeHours"))
                .as("the flag off is the list the app knew before feature 19").isFalse();
    }

    @Test void a_parent_cannot_set_a_staff_members_preferences() throws Exception {
        mvc.perform(put("/staff/me/preferences").header("Authorization", PARENT)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"afterHoursAlerts\":true}"))
                .andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------- helpers

    private void at(LocalDate day, String riyadhTime) {
        clock.pinTo(day.atTime(LocalTime.parse(riyadhTime)).atZone(RIYADH).toInstant());
    }

    private String thread(String tail) { return "/children/" + maya + "/chat/threads/" + SARA + "/" + tail; }

    private List<Entities.ChatAfterHoursEntity> rows() {
        return afterHours.findAll().stream().filter(r -> A.equals(r.getSchoolId())).toList();
    }

    private List<Map<String, Object>> summaries() {
        return jdbc.queryForList("SELECT * FROM notifications WHERE school_id = ? AND user_id = ? AND kind = 'chat.after_hours_summary'", A, SARA);
    }

    private void holiday(LocalDate day) {
        var row = new quest.server.staffattendance.Entities.AttendanceHolidayEntity();
        row.setId(UUID.randomUUID().toString()); row.setSchoolId(A); row.setName("Holiday");
        row.setFromDate(day); row.setToDate(day);
        holidays.save(row);
    }

    private static JsonNode row(JsonNode rows, String teacherId) {
        for (var r : rows) if (teacherId.equals(r.get("teacherId").asText())) return r;
        throw new AssertionError("no row for " + teacherId);
    }
}
