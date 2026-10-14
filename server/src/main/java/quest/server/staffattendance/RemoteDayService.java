package quest.server.staffattendance;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import quest.api.dto.AttendanceLink;
import quest.api.dto.NotificationKind;
import quest.api.staff.AttendanceRequestStatus;
import quest.api.staff.AttendanceRequestType;
import quest.api.staff.RemoteDay;
import quest.api.staff.RemoteDayList;
import quest.server.auth.Principals;
import quest.server.config.ApiException;
import quest.server.config.Json;
import quest.server.notifications.NotificationService;
import quest.server.platform.SchoolCalendar;
import quest.server.staffattendance.Entities.AttendanceRemoteDayEntity;
import quest.server.staffattendance.Entities.StaffAttendanceAlertEntity;
import quest.server.tenancy.SchoolRepository;
import quest.server.tenancy.TenantContext;

/**
 * Feature 18: the days an employee may check in from anywhere. A day is remote for her when the Admin declared it for
 * the whole school, or for an academy she belongs to ({@link DirectManagerResolver#academies}), or when her own request
 * for it was approved. Feature 15's check-in asks {@link #appliesTo} before it looks at the geofence.
 *
 * <p><strong>The notice.</strong> Everyone in scope is told "tomorrow is a remote working day" the evening before, at
 * {@link #EVENING}, by feature 16's job ({@link #eveningNotices}); a day declared for today or tomorrow is announced as
 * soon as it is created. Either way the notice is claimed in `staff_attendance_alerts` under (employee, day,
 * {@link #NOTICE}) first, so nobody hears it twice — not from a rerun, not from a second instance, and not from a
 * school-wide day and an academy day that fall on the same date.
 */
@Service
public class RemoteDayService {
    /** When the evening-before notice goes out, in the school's zone. */
    static final LocalTime EVENING = LocalTime.of(18, 0);
    static final String NOTICE = "REMOTE_DAY";
    private static final int MAX_REASON = 200;

    private final AttendanceRemoteDayRepository days; private final StaffAttendanceRequestRepository requests;
    private final DirectManagerResolver managers; private final AttendanceStaff staff;
    private final StaffAttendanceAlertRepository alerts; private final NotificationService notifications;
    private final SchoolRepository schools; private final SchoolCalendar calendar; private final TenantContext tenant;
    private final Json json; private final Clock clock;

    public RemoteDayService(AttendanceRemoteDayRepository days, StaffAttendanceRequestRepository requests,
                            DirectManagerResolver managers, AttendanceStaff staff, StaffAttendanceAlertRepository alerts,
                            NotificationService notifications, SchoolRepository schools, SchoolCalendar calendar,
                            TenantContext tenant, Json json, Clock clock) {
        this.days = days; this.requests = requests; this.managers = managers; this.staff = staff; this.alerts = alerts;
        this.notifications = notifications; this.schools = schools; this.calendar = calendar; this.tenant = tenant;
        this.json = json; this.clock = clock;
    }

    // ---------------------------------------------------------------- the check-in's question

    /** Whether {@code date} is a remote working day for this employee: declared for her scope, or approved for her. */
    @Transactional(readOnly = true)
    public boolean appliesTo(String schoolId, AttendanceStaff.Employee employee, LocalDate date) {
        if (requests.existsBySchoolIdAndStaffKindAndStaffIdAndDayAndTypeAndStatus(schoolId, employee.kind(), employee.id(),
                date, AttendanceRequestType.REMOTE_DAY, AttendanceRequestStatus.APPROVED)) return true;
        var declared = days.findBySchoolIdAndDay(schoolId, date);
        if (declared.isEmpty()) return false;
        var academies = managers.academies(schoolId, employee.kind(), employee.id());
        return declared.stream().anyMatch(d -> d.getAcademy() == null || academies.contains(d.getAcademy()));
    }

    // ---------------------------------------------------------------- the Admin's list

    /** Today's and the coming remote days, in date order: a past remote day is history, not something to manage. */
    @Transactional(readOnly = true)
    public RemoteDayList list() {
        String schoolId = tenant.writeSchoolId();
        var out = new ArrayList<RemoteDay>();
        for (var d : days.findBySchoolIdAndDayGreaterThanEqualOrderByDayAsc(schoolId, today(schoolId)))
            out.add(view(d));
        return new RemoteDayList(out);
    }

    @Transactional
    public RemoteDay create(Principals.User caller, RemoteDay request) {
        String schoolId = tenant.writeSchoolId();
        LocalDate date = date(request.getDate());
        LocalDate today = today(schoolId);
        if (date.isBefore(today)) throw ApiException.badRequest("A remote day is today or later.");
        String reason = request.getReason() == null ? "" : request.getReason().trim();
        if (reason.isEmpty() || reason.length() > MAX_REASON) throw ApiException.badRequest("reason is 1–" + MAX_REASON + " characters.");
        String academy = academy(schoolId, request.getAcademy());

        var row = new AttendanceRemoteDayEntity();
        row.setId(UUID.randomUUID().toString()); row.setSchoolId(schoolId); row.setDay(date); row.setAcademy(academy);
        row.setReason(reason); row.setCreatedBy(caller.userId()); row.setCreatedAt(Instant.now(clock));
        days.save(row);
        if (!date.isAfter(today.plusDays(1))) announce(schoolId, row, today, Instant.now(clock));   // too late for the evening
        return view(row);
    }

    @Transactional
    public void delete(String id) {
        String schoolId = tenant.writeSchoolId();
        var row = days.findOneBySchoolIdAndId(schoolId, id).orElseThrow(() -> ApiException.notFound("Remote day"));
        days.delete(row);
    }

    // ---------------------------------------------------------------- the notice

    /**
     * Feature 16's job, every run: from {@link #EVENING} on, tell everyone in scope of tomorrow's remote days. Answers
     * how many notices this run sent.
     */
    int eveningNotices(String schoolId, LocalDate today, Instant now) {
        ZoneId zone = calendar.of(schoolId).zone();
        if (now.isBefore(today.atTime(EVENING).atZone(zone).toInstant())) return 0;
        int sent = 0;
        for (var d : days.findBySchoolIdAndDay(schoolId, today.plusDays(1))) sent += announce(schoolId, d, today, now);
        return sent;
    }

    private int announce(String schoolId, AttendanceRemoteDayEntity day, LocalDate today, Instant now) {
        boolean isToday = day.getDay().equals(today);
        String titleEn = "Remote day", titleAr = "يوم عن بعد";
        String bodyEn = (isToday ? "Today" : "Tomorrow") + " is a remote working day: " + day.getReason();
        String bodyAr = (isToday ? "اليوم" : "غدًا") + " يوم عمل عن بعد: " + day.getReason();
        var link = new LinkedHashMap<String, String>();
        link.put(AttendanceLink.TYPE, AttendanceLink.REMOTE_DAY); link.put(AttendanceLink.DATE, day.getDay().toString());
        String href = StaffAttendanceJob.link(link), collapse = "attendance:remote_day:" + day.getDay();
        int sent = 0;
        for (var employee : staff.all(schoolId)) {
            if (day.getAcademy() != null && !managers.academies(schoolId, employee.kind(), employee.id()).contains(day.getAcademy())) continue;
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

    /** Null or blank is the whole school; anything else must be one of the school's curricula, compared lower-cased. */
    private String academy(String schoolId, String value) {
        if (value == null || value.isBlank()) return null;
        String wanted = value.trim().toLowerCase(Locale.ROOT);
        var school = schools.findById(schoolId).orElseThrow(() -> ApiException.notFound("School"));
        List<String> curricula = school.getCurriculumOptionsJson() == null ? List.of() : json.strings(school.getCurriculumOptionsJson());
        if (curricula.stream().noneMatch(c -> c != null && c.trim().toLowerCase(Locale.ROOT).equals(wanted)))
            throw ApiException.badRequest("academy is one of the school's curricula, or empty for the whole school.");
        return wanted;
    }

    private LocalDate today(String schoolId) { return LocalDate.ofInstant(Instant.now(clock), calendar.of(schoolId).zone()); }

    static LocalDate date(String value) {
        try { return LocalDate.parse(value == null ? "" : value.trim()); }
        catch (DateTimeParseException e) { throw ApiException.badRequest("date is a day such as 2026-10-18."); }
    }

    private static RemoteDay view(AttendanceRemoteDayEntity d) {
        return new RemoteDay(d.getId(), d.getDay().toString(), d.getAcademy(), d.getReason());
    }
}
