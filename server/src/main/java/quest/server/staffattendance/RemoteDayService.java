package quest.server.staffattendance;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import quest.api.dto.AttendanceLink;
import quest.api.dto.NotificationKind;
import quest.api.staff.AttendanceRequestStatus;
import quest.api.staff.AttendanceRequestType;
import quest.server.config.ApiException;
import quest.server.flags.FeatureFlags;
import quest.server.flags.FlagKeys;
import quest.server.notifications.NotificationService;
import quest.server.platform.SchoolCalendar;
import quest.server.staffattendance.Entities.RemoteStudyDayEntity;
import quest.server.staffattendance.Entities.StaffAttendanceAlertEntity;

/**
 * Whether a day is a day an employee may check in from anywhere, and the notice that tells her so.
 *
 * <p>Two things make a day remote for her, and they are separate on purpose:
 * <ul>
 *   <li><strong>Feature 23's remote study day</strong> — the Admin declared the whole school, or an academy she
 *       belongs to ({@link DirectManagerResolver#academies}), a remote study day. It is read from
 *       `remote_study_days` and only while `remoteStudyDays` is on for the school: with the flag off a school-wide
 *       remote day simply does not exist, and turning it on brings back every row V46 copied out of feature 18's
 *       table. The chosen grades of such a day narrow children and parents, never staff — a grade does not say who
 *       teaches it.</li>
 *   <li><strong>Feature 18's own request</strong> — her personal REMOTE_DAY request was approved. This is hers
 *       alone, needs no flag, and is untouched by feature 23.</li>
 * </ul>
 * Feature 15's check-in asks {@link #appliesTo} before it looks at the geofence.
 *
 * <p><strong>The notice.</strong> Everyone in scope is told "tomorrow is a remote working day" the evening before, at
 * {@link #EVENING}, by feature 16's job ({@link #eveningNotices}); a day declared for today or tomorrow is announced
 * as soon as it is created ({@link RemoteStudyDayService}). Either way the notice is claimed in
 * `staff_attendance_alerts` under (employee, day, {@link #NOTICE}) first, so nobody hears it twice — not from a
 * rerun, not from a second instance, and not from a school-wide day and an academy day that fall on the same date.
 */
@Service
public class RemoteDayService {
    /** When the evening-before notice goes out, in the school's zone. */
    static final LocalTime EVENING = LocalTime.of(18, 0);
    static final String NOTICE = "REMOTE_DAY";

    private final RemoteStudyDayRepository days; private final StaffAttendanceRequestRepository requests;
    private final DirectManagerResolver managers; private final AttendanceStaff staff;
    private final StaffAttendanceAlertRepository alerts; private final NotificationService notifications;
    private final SchoolCalendar calendar; private final FeatureFlags flags;

    public RemoteDayService(RemoteStudyDayRepository days, StaffAttendanceRequestRepository requests,
                            DirectManagerResolver managers, AttendanceStaff staff, StaffAttendanceAlertRepository alerts,
                            NotificationService notifications, SchoolCalendar calendar, FeatureFlags flags) {
        this.days = days; this.requests = requests; this.managers = managers; this.staff = staff; this.alerts = alerts;
        this.notifications = notifications; this.calendar = calendar; this.flags = flags;
    }

    // ---------------------------------------------------------------- the check-in's question

    /** Whether {@code date} is a remote working day for this employee: declared for her scope, or approved for her. */
    @Transactional(readOnly = true)
    public boolean appliesTo(String schoolId, AttendanceStaff.Employee employee, LocalDate date) {
        if (requests.existsBySchoolIdAndStaffKindAndStaffIdAndDayAndTypeAndStatus(schoolId, employee.kind(), employee.id(),
                date, AttendanceRequestType.REMOTE_DAY, AttendanceRequestStatus.APPROVED)) return true;
        if (!flags.isOn(schoolId, FlagKeys.REMOTE_STUDY_DAYS)) return false;
        var declared = days.findBySchoolIdAndDay(schoolId, date);
        if (declared.isEmpty()) return false;
        var academies = managers.academies(schoolId, employee.kind(), employee.id());
        return declared.stream().anyMatch(d -> inStaffScope(d, academies));
    }

    /** A remote study day reaches an employee by academy only: its grades say which children stay home, not who teaches. */
    private static boolean inStaffScope(RemoteStudyDayEntity day, Collection<String> academies) {
        return day.getAcademy() == null || academies.contains(day.getAcademy());
    }

    // ---------------------------------------------------------------- the notice

    /**
     * Feature 16's job, every run: from {@link #EVENING} on, tell everyone in scope of tomorrow's remote study days.
     * Answers how many notices this run sent.
     */
    int eveningNotices(String schoolId, LocalDate today, Instant now) {
        if (!flags.isOn(schoolId, FlagKeys.REMOTE_STUDY_DAYS)) return 0;
        ZoneId zone = calendar.of(schoolId).zone();
        if (now.isBefore(today.atTime(EVENING).atZone(zone).toInstant())) return 0;
        int sent = 0;
        for (var d : days.findBySchoolIdAndDay(schoolId, today.plusDays(1))) sent += announce(schoolId, d, today, now);
        return sent;
    }

    /** The staff half of a remote study day, in feature 18's words: the dashboard and the app already read them. */
    int announce(String schoolId, RemoteStudyDayEntity day, LocalDate today, Instant now) {
        boolean isToday = day.getDay().equals(today);
        String titleEn = "Remote day", titleAr = "يوم عن بعد";
        String bodyEn = (isToday ? "Today" : "Tomorrow") + " is a remote working day: " + day.getReason();
        String bodyAr = (isToday ? "اليوم" : "غدًا") + " يوم عمل عن بعد: " + day.getReason();
        var link = new LinkedHashMap<String, String>();
        link.put(AttendanceLink.TYPE, AttendanceLink.REMOTE_DAY); link.put(AttendanceLink.DATE, day.getDay().toString());
        String href = StaffAttendanceJob.link(link), collapse = "attendance:remote_day:" + day.getDay();
        int sent = 0;
        for (var employee : staff.all(schoolId)) {
            if (!inStaffScope(day, managers.academies(schoolId, employee.kind(), employee.id()))) continue;
            String to = staff.account(employee);
            if (to == null || !claim(schoolId, employee, day.getDay(), now)) continue;
            notifications.attendance(schoolId, to, NotificationKind.ATTENDANCE_REMOTE_DAY, titleEn, bodyEn, titleAr, bodyAr, href, collapse);
            sent++;
        }
        return sent;
    }

    private boolean claim(String schoolId, AttendanceStaff.Employee employee, LocalDate day, Instant now) {
        var row = new StaffAttendanceAlertEntity();
        row.setId(UUID.randomUUID().toString()); row.setSchoolId(schoolId); row.setStaffKind(employee.kind());
        row.setStaffId(employee.id()); row.setDay(day); row.setType(NOTICE); row.setSentAt(now);
        return alerts.claim(row) == 1;
    }

    // ---------------------------------------------------------------- helpers

    static LocalDate date(String value) {
        try { return LocalDate.parse(value == null ? "" : value.trim()); }
        catch (DateTimeParseException e) { throw ApiException.badRequest("date is a day such as 2026-10-18."); }
    }
}
