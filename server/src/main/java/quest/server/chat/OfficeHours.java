package quest.server.chat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import quest.api.dto.OfficeHoursStatus;
import quest.server.auth.Entities.UserEntity;
import quest.server.auth.UserRepository;
import quest.server.platform.SchoolCalendar;
import quest.server.staffattendance.AttendanceSettingsService;
import quest.server.staffattendance.ScheduleResolver;
import quest.server.staffattendance.StaffAttendanceDto;
import quest.server.staffattendance.StaffAttendanceProfileService;
import quest.server.tenancy.SchoolRepository;

/**
 * Feature 19: whether a member of staff is inside her office hours right now, and when the next working period
 * starts. Her office hours are feature 14's work hours for her category — Ramadan hours included, holidays and days
 * off excluded — so there is no second notion of "working time" on the server and an Admin who moves the hours moves
 * the banner and the quiet pushes with them.
 *
 * <p><strong>Only `users` staff.</strong> A worker (`workers.id`) has no conversations at all, so nobody can write to
 * her outside her hours; everything here is keyed by a `users` id, which is what a chat thread names.
 *
 * <p>{@link #status(ScheduleResolver.Hours, List, ZoneId, Instant)} is pure, so `OfficeHoursTest` pins Thursday
 * evening, a holiday and Ramadan without a database. The walk forward is capped at {@link #MAX_LOOKAHEAD_DAYS} days:
 * a school whose holidays swallow a whole term answers "closed, and no next start" rather than looping.
 */
@Service
public class OfficeHours {
    /** Far enough for a summer holiday, short enough that a mis-saved schedule cannot spin. */
    static final int MAX_LOOKAHEAD_DAYS = 90;
    private static final DateTimeFormatter LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    /**
     * Where one member of staff stands. {@code open} is "inside her hours now"; {@code startedToday} is "today is a
     * working day and its start has passed", which is what the summary job waits for; {@code nextStart} is null when
     * she is open, and {@code nextStartLocal} the same moment in the school's zone, which is what the parent reads.
     *
     * <p>{@code closesAt} is the end of the period she is inside right now, and null whenever she is not — feature
     * 27's cap on "within four hours", derived from the very hours this status was resolved from (Ramadan's end
     * rather than the ordinary one, on the day the school is actually on) so that there is no second reading of the
     * schedule to disagree with the first.
     */
    public record Status(boolean open, boolean startedToday, Instant nextStart, LocalDateTime nextStartLocal, LocalDate today,
                         Instant closesAt) {}

    private final ScheduleResolver schedules; private final AttendanceSettingsService settings;
    private final StaffAttendanceProfileService categories; private final SchoolCalendar calendar;
    private final UserRepository users; private final SchoolRepository schools;

    public OfficeHours(ScheduleResolver schedules, AttendanceSettingsService settings, StaffAttendanceProfileService categories,
                       SchoolCalendar calendar, UserRepository users, SchoolRepository schools) {
        this.schedules = schedules; this.settings = settings; this.categories = categories;
        this.calendar = calendar; this.users = users; this.schools = schools;
    }

    /** One member of staff, in her school's zone and on her category's hours. */
    public Status status(String schoolId, String userId, Instant now) {
        String category = categoryOf(schoolId, userId, users.findById(userId).orElse(null));
        return status(settings.hours(schoolId, category), settings.holidays(schoolId), calendar.of(schoolId).zone(), now);
    }

    /**
     * The parent's banner for several members of staff at once — her child's teachers are one list and one read of
     * the school's holidays, its phone and each category's hours, rather than one read per row.
     */
    public Map<String, OfficeHoursStatus> banners(String schoolId, Collection<String> userIds, Instant now) {
        var out = new LinkedHashMap<String, OfficeHoursStatus>();
        if (userIds.isEmpty()) return out;
        var zone = calendar.of(schoolId).zone();
        var holidays = settings.holidays(schoolId);
        String phone = schools.findById(schoolId).map(s -> s.getPhone()).orElse(null);
        var byId = new HashMap<String, UserEntity>();
        for (var user : users.findAllById(userIds)) byId.put(user.getId(), user);
        var hours = new HashMap<String, ScheduleResolver.Hours>();
        for (String userId : userIds) {
            String category = categoryOf(schoolId, userId, byId.get(userId));
            var status = status(hours.computeIfAbsent(category, c -> settings.hours(schoolId, c)), holidays, zone, now);
            out.put(userId, new OfficeHoursStatus(status.open(), format(status.nextStartLocal()), status.today().toString(), phone));
        }
        return out;
    }

    /**
     * Today's expectation decides everything: inside the hours she is open; before the start the next start is today;
     * otherwise — after the end, on a day off or on a holiday — it is the first working day after today.
     */
    static Status status(ScheduleResolver.Hours hours, List<ScheduleResolver.Holiday> holidays, ZoneId zone, Instant now) {
        var local = LocalDateTime.ofInstant(now, zone);
        LocalDate today = local.toLocalDate();
        var expected = ScheduleResolver.resolve(hours, holidays, today);
        if (expected.kind() == ScheduleResolver.Kind.WORK) {
            if (local.toLocalTime().isBefore(expected.start())) return closed(today.atTime(expected.start()), zone, today, false);
            if (local.toLocalTime().isBefore(expected.end()))
                return new Status(true, true, null, null, today, today.atTime(expected.end()).atZone(zone).toInstant());
        }
        boolean startedToday = expected.kind() == ScheduleResolver.Kind.WORK;   // a work day whose start is behind us
        for (int ahead = 1; ahead <= MAX_LOOKAHEAD_DAYS; ahead++) {
            LocalDate day = today.plusDays(ahead);
            var next = ScheduleResolver.resolve(hours, holidays, day);
            if (next.kind() == ScheduleResolver.Kind.WORK) return closed(day.atTime(next.start()), zone, today, startedToday);
        }
        return new Status(false, startedToday, null, null, today, null);
    }

    private static Status closed(LocalDateTime nextStart, ZoneId zone, LocalDate today, boolean startedToday) {
        return new Status(false, startedToday, nextStart.atZone(zone).toInstant(), nextStart, today, null);
    }

    /** Her category (feature 14) or her role's default; an account that is gone is administrative, like the Admin. */
    private String categoryOf(String schoolId, String userId, UserEntity user) {
        String role = user == null || user.getRole() == null ? StaffAttendanceDto.ADMINISTRATIVE : user.getRole();
        return categories.categoryOf(schoolId, StaffAttendanceDto.KIND_USER, userId, role);
    }

    private static String format(LocalDateTime nextStart) { return nextStart == null ? null : LOCAL.format(nextStart); }
}
