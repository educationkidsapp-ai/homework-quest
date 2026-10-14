package quest.server.staffattendance;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import quest.api.dto.AttendanceLink;
import quest.api.dto.NotificationKind;
import quest.api.staff.CheckInMode;
import quest.server.auth.UserRepository;
import quest.server.flags.FeatureFlags;
import quest.server.flags.FlagKeys;
import quest.server.notifications.NotificationService;
import quest.server.platform.SchoolCalendar;
import quest.server.staffattendance.AttendanceRules.Event;
import quest.server.staffattendance.Entities.StaffAttendanceAlertEntity;
import quest.server.staffattendance.Entities.StaffCheckInEntity;
import quest.server.tenancy.SchoolRepository;

/**
 * Feature 16: every five minutes, for each school with `staffAttendance` on, turns the day's check-ins into statuses
 * ({@link AttendanceRules}), reminds employees, and tells the right leaders — the direct manager at once, the academy
 * director and the Admin as feature 14's settings say (`IMMEDIATE`, the morning `DIGEST`, or `OFF`).
 *
 * <p><strong>Once, whatever happens.</strong> Every notification is claimed in `staff_attendance_alerts` under (staff,
 * day, type) before it is sent, with ON CONFLICT DO NOTHING, and is sent only by the run whose insert wrote the row. A
 * rerun sends nothing new, and neither does a second Cloud Run instance running the job at the same moment.
 *
 * <p><strong>Who hears what.</strong>
 * <ul>
 *   <li>The employee: at start with no check-in, at end with no check-out, and at end + the missing-check-out
 *       threshold. A worker is reached through her `workers.user_id`; without an app account she has no bell.</li>
 *   <li>Her leaders: late, not arrived, early leave and missing check-out. The direct manager always, through
 *       {@link DirectManagerResolver#resolve} (direct → academy director → Admin); the academy director of her academy
 *       and the Admins as well when their policy is `IMMEDIATE`. One person in two of those roles is told once.</li>
 *   <li>The digest, at the digest time: the academy director gets her academy, the Admins the whole school.</li>
 * </ul>
 * The settings are read on every run, so a changed policy applies by the next run.
 *
 * <p><strong>Scope.</strong> The job has no caller and so no tenant ({@link quest.server.tenancy.TenantContext} is null on
 * the scheduler's thread): every query names the school, as every repository of this package requires.
 */
@Component
public class StaffAttendanceJob {
    private static final Logger log = LoggerFactory.getLogger(StaffAttendanceJob.class);
    static final String DIGEST_KIND = "DIGEST";

    private final SchoolRepository schools; private final FeatureFlags flags; private final SchoolCalendar calendar;
    private final AttendanceStaff staff;
    private final ScheduleResolver schedules; private final AttendanceSettingsService settings;
    private final StaffCheckInRepository checkIns;
    private final StaffAttendanceAlertRepository alerts; private final DirectManagerResolver managers;
    private final UserRepository users;
    private final NotificationService notifications; private final AttendanceDays attendanceDays;
    private final RemoteDayService remoteDays; private final Clock clock; private final boolean enabled;

    public StaffAttendanceJob(SchoolRepository schools, FeatureFlags flags, SchoolCalendar calendar, AttendanceStaff staff,
                              ScheduleResolver schedules,
                              AttendanceSettingsService settings, StaffCheckInRepository checkIns,
                              StaffAttendanceAlertRepository alerts,
                              DirectManagerResolver managers, UserRepository users,
                              NotificationService notifications, AttendanceDays attendanceDays,
                              RemoteDayService remoteDays, Clock clock,
                              @Value("${quest.staff-attendance.job.enabled:true}") boolean enabled) {
        this.schools = schools; this.flags = flags; this.calendar = calendar; this.staff = staff;
        this.schedules = schedules; this.settings = settings; this.checkIns = checkIns;
        this.alerts = alerts; this.managers = managers; this.users = users;
        this.notifications = notifications; this.attendanceDays = attendanceDays; this.remoteDays = remoteDays;
        this.clock = clock; this.enabled = enabled;
    }

    /** On Cloud Run an instance wakes up after a quiet spell; whatever fell due meanwhile is caught up at once. */
    @EventListener(ApplicationReadyEvent.class)
    public void runAtStartup() { if (enabled) run(); }

    @Scheduled(fixedDelayString = "${quest.staff-attendance.job.interval-seconds:300}s",
               initialDelayString = "${quest.staff-attendance.job.interval-seconds:300}s")
    public void runPeriodically() { if (enabled) run(); }

    /** One pass over every school with staff attendance on. Answers how many notifications were sent. */
    public int run() {
        int sent = 0;
        for (var school : schools.findAll()) {
            if (!on(school.getId())) continue;
            try { sent += run(school.getId()); }
            catch (RuntimeException e) { log.warn("staff attendance: school {} skipped this run: {}", school.getId(), e.toString()); }
        }
        return sent;
    }

    /** One school's pass, on the school's own today. */
    int run(String schoolId) {
        Instant now = clock.instant();
        ZoneId zone = calendar.of(schoolId).zone();
        LocalDate today = LocalDate.ofInstant(now, zone);
        var alertSettings = settings.alerts(schoolId);
        var byStaff = new HashMap<String, StaffCheckInEntity>();
        for (var row : checkIns.findBySchoolIdAndDay(schoolId, today)) byStaff.put(key(row.getStaffKind(), row.getStaffId()), row);
        List<String> admins = users.findActiveAdminIds();

        int sent = 0;
        var digestRows = new ArrayList<DigestRow>();
        for (var employee : staff.all(schoolId)) {
            var expected = schedules.expected(schoolId, attendanceDays.category(schoolId, employee), today);
            if (expected.kind() != ScheduleResolver.Kind.WORK) continue;              // holidays and days off: nothing
            var row = byStaff.get(key(employee.kind(), employee.id()));
            var day = AttendanceRules.evaluate(expected, alertSettings.thresholds(), today, zone,
                    row == null ? null : row.getCheckInAt(), row == null ? null : row.getCheckOutAt(), now);
            attendanceDays.save(schoolId, employee, today, day, now);
            String director = managers.academyDirector(schoolId, employee.kind(), employee.id());
            digestRows.add(new DigestRow(employee, director, day.lateMinutes() != null,
                    row == null && day.due().contains(Event.START_REMINDER),
                    row != null && CheckInMode.REMOTE.equals(row.getMode())));
            for (Event event : day.due()) sent += send(schoolId, employee, director, today, event, day, alertSettings, admins, now);
        }
        if (!digestRows.isEmpty() && !now.isBefore(today.atTime(alertSettings.digestTime()).atZone(zone).toInstant()))
            sent += digests(schoolId, today, digestRows, alertSettings, admins, now);
        sent += remoteDays.eveningNotices(schoolId, today, now);                     // feature 18: "tomorrow is remote"
        return sent;
    }

    // ---------------------------------------------------------------- one event

    private int send(String schoolId, AttendanceStaff.Employee employee, String director, LocalDate today, Event event,
                     AttendanceRules.Day day, AttendanceSettingsService.Alerts policy, List<String> admins, Instant now) {
        switch (event) {
            case ABSENT: return 0;                                                       // a status, not a message
            case START_REMINDER: case END_REMINDER: {
                String to = account(employee);
                if (to == null || !claim(schoolId, employee.kind(), employee.id(), today, event.name(), now)) return 0;
                remind(schoolId, to, employee, today, event);
                return 1;
            }
            case MISSING_CHECKOUT: {
                if (!claim(schoolId, employee.kind(), employee.id(), today, event.name(), now)) return 0;
                int n = 0;
                String to = account(employee);
                if (to != null) { remind(schoolId, to, employee, today, event); n++; }
                return n + alert(schoolId, employee, director, today, event, day, policy, admins);
            }
            default: {                                                                   // LATE, NOT_ARRIVED, EARLY_LEAVE
                if (!claim(schoolId, employee.kind(), employee.id(), today, event.name(), now)) return 0;
                return alert(schoolId, employee, director, today, event, day, policy, admins);
            }
        }
    }

    private void remind(String schoolId, String to, AttendanceStaff.Employee employee, LocalDate today, Event event) {
        String type; String titleEn, bodyEn, titleAr, bodyAr;
        switch (event) {
            case START_REMINDER -> { type = AttendanceLink.START_REMINDER; titleEn = "Check in"; bodyEn = "You have not checked in yet.";
                titleAr = "تسجيل الحضور"; bodyAr = "لم تسجل حضورك بعد"; }
            case END_REMINDER -> { type = AttendanceLink.END_REMINDER; titleEn = "Check out"; bodyEn = "Don't forget to check out.";
                titleAr = "تسجيل الانصراف"; bodyAr = "لا تنس تسجيل الانصراف"; }
            default -> { type = AttendanceLink.MISSING_CHECKOUT_REMINDER; titleEn = "Check out"; bodyEn = "Your check-out was not recorded today.";
                titleAr = "تسجيل الانصراف"; bodyAr = "لم يُسجَّل انصرافك اليوم"; }
        }
        var link = new LinkedHashMap<String, String>();
        link.put(AttendanceLink.TYPE, type); link.put(AttendanceLink.STAFF, employee.kind() + ":" + employee.id());
        link.put(AttendanceLink.DATE, today.toString());
        notifications.attendance(schoolId, to, NotificationKind.ATTENDANCE_REMINDER, titleEn, bodyEn, titleAr, bodyAr,
                link(link), "attendance:" + type + ":" + today);
    }

    private int alert(String schoolId, AttendanceStaff.Employee employee, String director, LocalDate today, Event event,
                      AttendanceRules.Day day, AttendanceSettingsService.Alerts policy, List<String> admins) {
        var to = new LinkedHashSet<String>();
        var resolved = managers.resolve(schoolId, employee.kind(), employee.id());
        if (resolved.source() == DirectManagerResolver.Source.ADMIN) to.addAll(admins);
        else to.add(resolved.userId());
        if ("IMMEDIATE".equals(policy.directorNotify()) && director != null) to.add(director);
        if ("IMMEDIATE".equals(policy.adminNotify())) to.addAll(admins);
        to.remove(null);
        if (StaffAttendanceDto.KIND_USER.equals(employee.kind())) to.remove(employee.id());   // never told about herself
        if (to.isEmpty()) return 0;

        String name = employee.fullName(), type, bodyEn, bodyAr;
        Integer minutes = null;
        switch (event) {
            case LATE -> { type = AttendanceLink.ALERT_LATE; minutes = day.lateMinutes();
                bodyEn = name + " is " + minutes + " minutes late"; bodyAr = name + " تأخر " + minutes + " دقيقة"; }
            case NOT_ARRIVED -> { type = AttendanceLink.ALERT_NOT_ARRIVED;
                bodyEn = name + " has not arrived yet"; bodyAr = name + " لم يصل بعد"; }
            case EARLY_LEAVE -> { type = AttendanceLink.ALERT_EARLY_LEAVE; minutes = day.earlyLeaveMinutes();
                bodyEn = name + " left " + minutes + " minutes early"; bodyAr = name + " انصرف قبل الموعد بـ " + minutes + " دقيقة"; }
            default -> { type = AttendanceLink.ALERT_MISSING_CHECKOUT;
                bodyEn = name + " did not check out today"; bodyAr = name + " لم يسجّل انصرافه اليوم"; }
        }
        var link = new LinkedHashMap<String, String>();
        link.put(AttendanceLink.TYPE, type); link.put(AttendanceLink.STAFF, employee.kind() + ":" + employee.id());
        link.put(AttendanceLink.NAME, name); link.put(AttendanceLink.DATE, today.toString());
        if (minutes != null) link.put(AttendanceLink.MINUTES, minutes.toString());
        String href = link(link), collapse = "attendance:" + type + ":" + employee.id() + ":" + today;
        for (String userId : to)
            notifications.attendance(schoolId, userId, NotificationKind.ATTENDANCE_ALERT, "Attendance", bodyEn, "الحضور", bodyAr, href, collapse);
        return to.size();
    }

    // ---------------------------------------------------------------- the digest

    private record DigestRow(AttendanceStaff.Employee employee, String director, boolean late, boolean notArrived, boolean remote) {}

    private int digests(String schoolId, LocalDate today, List<DigestRow> rows, AttendanceSettingsService.Alerts policy,
                        List<String> admins, Instant now) {
        int sent = 0;
        if ("DIGEST".equals(policy.directorNotify())) {
            var byDirector = new LinkedHashMap<String, List<DigestRow>>();
            for (var r : rows) if (r.director() != null) byDirector.computeIfAbsent(r.director(), d -> new ArrayList<>()).add(r);
            for (var entry : byDirector.entrySet()) sent += digest(schoolId, entry.getKey(), today, entry.getValue(), now);
        }
        if ("DIGEST".equals(policy.adminNotify()))
            for (String admin : admins) sent += digest(schoolId, admin, today, rows, now);
        return sent;
    }

    private int digest(String schoolId, String userId, LocalDate today, List<DigestRow> rows, Instant now) {
        if (!claim(schoolId, DIGEST_KIND, userId, today, AttendanceLink.DIGEST, now)) return 0;
        var late = rows.stream().filter(DigestRow::late).map(r -> r.employee().fullName()).toList();
        var notArrived = rows.stream().filter(DigestRow::notArrived).map(r -> r.employee().fullName()).toList();
        int remote = (int) rows.stream().filter(DigestRow::remote).count();            // feature 18's remote check-ins
        String bodyEn = "Today: " + late.size() + " late, " + notArrived.size() + " not arrived, " + remote + " remote"
                + names(" — Late: ", late, ", ") + names(" · Not arrived: ", notArrived, ", ");
        String bodyAr = "اليوم: " + late.size() + " متأخرون، " + notArrived.size() + " لم يصل، " + remote + " عن بعد"
                + names(" — متأخرون: ", late, "، ") + names(" · لم يصل: ", notArrived, "، ");
        var link = new LinkedHashMap<String, String>();
        link.put(AttendanceLink.TYPE, AttendanceLink.DIGEST); link.put(AttendanceLink.DATE, today.toString());
        link.put(AttendanceLink.LATE, Integer.toString(late.size()));
        link.put(AttendanceLink.NOT_ARRIVED, Integer.toString(notArrived.size()));
        link.put(AttendanceLink.REMOTE, Integer.toString(remote));
        if (!late.isEmpty()) link.put(AttendanceLink.LATE_NAMES, String.join(AttendanceLink.NAME_SEPARATOR, late));
        if (!notArrived.isEmpty()) link.put(AttendanceLink.NOT_ARRIVED_NAMES, String.join(AttendanceLink.NAME_SEPARATOR, notArrived));
        notifications.attendance(schoolId, userId, NotificationKind.ATTENDANCE_DIGEST, "Today's attendance", bodyEn,
                "حضور اليوم", bodyAr, link(link), "attendance:digest:" + today);
        return 1;
    }

    private static String names(String label, List<String> names, String separator) {
        return names.isEmpty() ? "" : label + String.join(separator, names);
    }

    // ---------------------------------------------------------------- helpers

    /** True when this call claimed the key and may send; false when an earlier run (or another instance) did. */
    private boolean claim(String schoolId, String staffKind, String staffId, LocalDate day, String type, Instant now) {
        var row = new StaffAttendanceAlertEntity();
        row.setId(UUID.randomUUID().toString()); row.setSchoolId(schoolId); row.setStaffKind(staffKind);
        row.setStaffId(staffId); row.setDay(day); row.setType(type); row.setSentAt(now);
        return alerts.claim(row) == 1;
    }


    /** The `users` id her bell and phone hang off: herself, or a worker's account. Null when she has none. */
    private String account(AttendanceStaff.Employee employee) { return staff.account(employee); }


    private boolean on(String schoolId) {
        try { return flags.isOn(schoolId, FlagKeys.STAFF_ATTENDANCE); }
        catch (RuntimeException e) { return false; }
    }

    static String link(Map<String, String> params) {
        var parts = new ArrayList<String>();
        for (var e : params.entrySet()) parts.add(e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        return "/notifications?" + String.join("&", parts);
    }

    private static String key(String kind, String id) { return kind + ":" + id; }
}
