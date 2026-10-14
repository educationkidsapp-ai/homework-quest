package quest.server.staffattendance;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import quest.api.staff.CheckInDay;
import quest.api.staff.CheckInMode;
import quest.api.staff.CheckInMonth;
import quest.api.staff.CheckInOutcome;
import quest.api.staff.CheckInRefusal;
import quest.api.staff.CheckInRequest;
import quest.api.staff.CheckInResult;
import quest.api.staff.CheckInRules;
import quest.api.staff.CheckInToday;
import quest.server.auth.Principals;
import quest.server.config.ApiException;
import quest.server.flags.FeatureFlags;
import quest.server.flags.FlagKeys;
import quest.server.platform.SchoolCalendar;
import quest.server.staffattendance.Entities.StaffCheckInAttemptEntity;
import quest.server.staffattendance.Entities.StaffCheckInEntity;
import quest.server.tenancy.TenantContext;

/**
 * Feature 15: an employee records her arrival and her departure from the phone, and the server decides whether the
 * attempt counts and what time it was.
 *
 * <p><strong>The order of the checks is the spec's</strong> (docs/features/15-geofence-check-in.md §"On the server"),
 * and it is the order of {@link #record}: she is an employee of this school and both switches are on, then the phone
 * is the one bound to her, then the fix is not mocked, then its accuracy is good enough, then the school has a pin at
 * all, and only then how far away she is. Each refusal is the first reason she can act on — telling someone she is
 * 400 m away when her phone is not approved would send her to the gate for nothing.
 *
 * <p><strong>A refusal is a 200.</strong> Every call writes a row in `staff_check_in_attempts`, which is how a mocked
 * fix or a stranger's phone is found afterwards, and a thrown exception would roll that row back with everything
 * else. So a refusal comes back as {@link CheckInOutcome#REFUSED} with a {@link CheckInRefusal} code and the
 * transaction commits. The only exceptions here are the ones that mean the caller has no business on the route at
 * all: not an employee (403), a switched-off feature (404), a malformed body (400).
 *
 * <p><strong>The server owns the clock.</strong> Times come from the injected {@link Clock}; the school day is that
 * instant's date in the school's time zone ({@link SchoolCalendar}), so a check-out at 21:30 UTC belongs to the next
 * day in Riyadh. {@link CheckInRequest#getPhoneTime()} is written to the attempt and read by nothing — a phone with
 * its clock moved back two hours changes the audit trail and not one recorded minute.
 *
 * <p><strong>Coordinates end here.</strong> The latitude and longitude live in the request body and in two local
 * variables inside {@link #record}; what is stored, logged, audited or put in a message is a distance in metres.
 */
@Service
public class CheckInService {
    static final String ACTION_CHECK_IN = "CHECK_IN", ACTION_CHECK_OUT = "CHECK_OUT";
    /** `CheckInDay.status`, which feature 16 will reuse for its alerts. */
    static final String ON_TIME = "ON_TIME", LATE = "LATE", ABSENT = "ABSENT", DAY_OFF = "DAY_OFF",
            HOLIDAY = "HOLIDAY", UPCOMING = "UPCOMING";
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter YYYY_MM = DateTimeFormatter.ofPattern("yyyy-MM");
    /** The phone's claimed time is audit material; a long string is not worth a column's width. */
    private static final int MAX_PHONE_TIME = 40, MAX_INSTALL_ID = 64;

    private final StaffCheckInRepository records; private final StaffCheckInAttemptRepository attempts;
    private final AttendanceDeviceService devices; private final AttendanceSettingsService settings;
    private final ScheduleResolver schedules; private final StaffAttendanceProfileRepository profiles;
    private final AttendanceStaff staff; private final TenantContext tenant; private final SchoolCalendar calendar;
    private final FeatureFlags flags; private final Clock clock;

    public CheckInService(StaffCheckInRepository records, StaffCheckInAttemptRepository attempts,
                          AttendanceDeviceService devices, AttendanceSettingsService settings, ScheduleResolver schedules,
                          StaffAttendanceProfileRepository profiles, AttendanceStaff staff, TenantContext tenant,
                          SchoolCalendar calendar, FeatureFlags flags, Clock clock) {
        this.records = records; this.attempts = attempts; this.devices = devices; this.settings = settings;
        this.schedules = schedules; this.profiles = profiles; this.staff = staff; this.tenant = tenant;
        this.calendar = calendar; this.flags = flags; this.clock = clock;
    }

    @Transactional
    public CheckInResult checkIn(Principals.User caller, CheckInRequest request) { return record(caller, request, true); }

    @Transactional
    public CheckInResult checkOut(Principals.User caller, CheckInRequest request) { return record(caller, request, false); }

    // ---------------------------------------------------------------- the one path both taps take

    private CheckInResult record(Principals.User caller, CheckInRequest request, boolean in) {
        String schoolId = requireStaffApp();
        var employee = me(schoolId, caller);
        String installId = installId(request);
        coordinates(request);
        ZoneId zone = calendar.of(schoolId).zone();
        Instant now = Instant.now(clock);
        LocalDate day = LocalDate.ofInstant(now, zone);
        var attempt = new Attempt(schoolId, employee, in ? ACTION_CHECK_IN : ACTION_CHECK_OUT, now,
                request.getPhoneTime(), request.getAccuracyMeters(), request.getMocked(), installId);

        var bound = devices.active(schoolId, employee.kind(), employee.id()).orElse(null);
        if (bound != null && !bound.getInstallId().equals(installId)) return refuse(attempt, CheckInRefusal.DEVICE_NOT_APPROVED, null);
        if (request.getMocked()) return refuse(attempt, CheckInRefusal.MOCK_LOCATION, null);
        if (!(request.getAccuracyMeters() <= CheckInRules.MAX_ACCURACY_METERS))     // `!(…<=…)` so a NaN is weak, not good
            return refuse(attempt, CheckInRefusal.WEAK_ACCURACY, null);
        var rules = settings.rules(schoolId);
        if (rules.latitude() == null || rules.longitude() == null) return refuse(attempt, CheckInRefusal.NO_GEOFENCE, null);
        int distance = (int) Math.round(Geo.distanceMeters(request.getLatitude(), request.getLongitude(), rules.latitude(), rules.longitude()));
        if (distance > rules.radiusMeters()) return refuse(attempt, CheckInRefusal.OUTSIDE, distance);

        var existing = records.findOneBySchoolIdAndStaffKindAndStaffIdAndDay(schoolId, employee.kind(), employee.id(), day).orElse(null);
        if (in) {
            // The second tap of the day changes nothing and answers with the time of the first.
            if (existing != null) {
                log(attempt, CheckInOutcome.ALREADY, null, distance);
                return new CheckInResult(CheckInOutcome.ALREADY, null, HH_MM.format(existing.getCheckInAt().atZone(zone)), null, distance);
            }
            var row = new StaffCheckInEntity();
            row.setId(UUID.randomUUID().toString()); row.setSchoolId(schoolId);
            row.setStaffKind(employee.kind()); row.setStaffId(employee.id());
            row.setDay(day); row.setCheckInAt(now); row.setCheckInDistanceM(distance); row.setMode(CheckInMode.ONSITE);
            records.save(row);
            devices.bind(schoolId, employee.kind(), employee.id(), installId, now);  // the first success is what binds
            log(attempt, CheckInOutcome.RECORDED, null, distance);
            return new CheckInResult(CheckInOutcome.RECORDED, null, HH_MM.format(now.atZone(zone)),
                    lateMinutes(schoolId, employee, day, now, zone), distance);
        }
        if (existing == null) return refuse(attempt, CheckInRefusal.NOT_CHECKED_IN, distance);
        existing.setCheckOutAt(now); existing.setCheckOutDistanceM(distance);       // a later check-out replaces the earlier
        records.save(existing);
        log(attempt, CheckInOutcome.RECORDED, null, distance);
        return new CheckInResult(CheckInOutcome.RECORDED, null, HH_MM.format(now.atZone(zone)), null, distance);
    }

    // ---------------------------------------------------------------- what the Today card and My record read

    @Transactional(readOnly = true)
    public CheckInToday today(Principals.User caller) {
        String schoolId = requireStaffApp();
        var employee = me(schoolId, caller);
        ZoneId zone = calendar.of(schoolId).zone();
        LocalDate day = LocalDate.ofInstant(Instant.now(clock), zone);
        var expected = schedules.expected(schoolId, category(schoolId, employee), day);
        var row = records.findOneBySchoolIdAndStaffKindAndStaffIdAndDay(schoolId, employee.kind(), employee.id(), day).orElse(null);
        return new CheckInToday(day.toString(), time(row == null ? null : row.getCheckInAt(), zone),
                time(row == null ? null : row.getCheckOutAt(), zone),
                row == null ? null : lateness(expected, settings.rules(schoolId).graceMinutes(), row.getCheckInAt(), zone),
                expected.kind() == ScheduleResolver.Kind.WORK);
    }

    /**
     * Every day of the month in order, so "My record" needs no second call to know which days were holidays. Today
     * and any day after it is {@link #UPCOMING} rather than {@link #ABSENT}: the day is not over, and a feature that
     * called a teacher absent at 06:00 would be wrong every morning.
     */
    @Transactional(readOnly = true)
    public CheckInMonth month(Principals.User caller, String month) {
        String schoolId = requireStaffApp();
        var employee = me(schoolId, caller);
        ZoneId zone = calendar.of(schoolId).zone();
        LocalDate today = LocalDate.ofInstant(Instant.now(clock), zone);
        YearMonth ym = month(month, today);
        return new CheckInMonth(ym.format(YYYY_MM), calendar(schoolId, employee, ym, today, zone));
    }

    /**
     * One employee's month, day by day — "My record" for herself and, since feature 17, the board's employee view for
     * her leaders, so both draw the same calendar from the same rule.
     */
    java.util.List<CheckInDay> calendar(String schoolId, AttendanceStaff.Employee employee, YearMonth ym,
                                        LocalDate today, ZoneId zone) {
        int grace = settings.rules(schoolId).graceMinutes();
        String category = category(schoolId, employee);

        var byDay = new HashMap<LocalDate, StaffCheckInEntity>();
        for (var row : records.findBySchoolIdAndStaffKindAndStaffIdAndDayBetweenOrderByDayAsc(
                schoolId, employee.kind(), employee.id(), ym.atDay(1), ym.atEndOfMonth()))
            byDay.put(row.getDay(), row);

        var days = new ArrayList<CheckInDay>(ym.lengthOfMonth());
        for (LocalDate date = ym.atDay(1); !date.isAfter(ym.atEndOfMonth()); date = date.plusDays(1)) {
            var expected = schedules.expected(schoolId, category, date);
            var row = byDay.get(date);
            Integer late = row == null ? null : lateness(expected, grace, row.getCheckInAt(), zone);
            days.add(new CheckInDay(date.toString(), time(row == null ? null : row.getCheckInAt(), zone),
                    time(row == null ? null : row.getCheckOutAt(), zone),
                    status(expected, row, late, date, today), late, row == null ? null : row.getMode()));
        }
        return days;
    }

    private static String status(ScheduleResolver.Expected expected, StaffCheckInEntity row, Integer late,
                                 LocalDate date, LocalDate today) {
        if (row != null) return late != null && late > 0 ? LATE : ON_TIME;
        return switch (expected.kind()) {
            case HOLIDAY -> HOLIDAY;
            case DAY_OFF -> DAY_OFF;
            case WORK -> date.isBefore(today) ? ABSENT : UPCOMING;
        };
    }

    // ---------------------------------------------------------------- lateness

    private Integer lateMinutes(String schoolId, AttendanceStaff.Employee employee, LocalDate day, Instant at, ZoneId zone) {
        return lateness(schedules.expected(schoolId, category(schoolId, employee), day), settings.rules(schoolId).graceMinutes(), at, zone);
    }

    /**
     * How late she was, in minutes <em>after the expected start</em> — not after the grace period. The grace decides
     * whether she is late at all; once she is, "late 12 min" means the day started twelve minutes before she
     * arrived, which is the number a manager and a payroll run both expect. A day off or a holiday is never late.
     */
    private static Integer lateness(ScheduleResolver.Expected expected, int graceMinutes, Instant at, ZoneId zone) {
        // One definition of late for the whole feature: feature 16's job applies the same rule (`AttendanceRules`).
        Integer late = AttendanceRules.lateMinutes(expected, graceMinutes, at, zone);
        return late == null ? 0 : late;
    }

    // ---------------------------------------------------------------- the attempt log

    /** What every call is logged with, whatever it ends up answering. No coordinate is among the fields. */
    private record Attempt(String schoolId, AttendanceStaff.Employee employee, String action, Instant serverAt,
                           String phoneTime, double accuracyMeters, boolean mocked, String installId) {}

    private CheckInResult refuse(Attempt attempt, String reason, Integer distance) {
        log(attempt, CheckInOutcome.REFUSED, reason, distance);
        return new CheckInResult(CheckInOutcome.REFUSED, reason, null, null, distance);
    }

    private void log(Attempt attempt, String result, String reason, Integer distance) {
        var row = new StaffCheckInAttemptEntity();
        row.setId(UUID.randomUUID().toString()); row.setSchoolId(attempt.schoolId());
        row.setStaffKind(attempt.employee().kind()); row.setStaffId(attempt.employee().id());
        row.setServerAt(attempt.serverAt()); row.setPhoneAt(clip(attempt.phoneTime(), MAX_PHONE_TIME));
        row.setAction(attempt.action()); row.setDistanceM(distance);
        row.setAccuracyM(Double.isFinite(attempt.accuracyMeters()) ? attempt.accuracyMeters() : null);
        row.setMocked(attempt.mocked()); row.setInstallId(attempt.installId());
        row.setResult(result); row.setReason(reason);
        attempts.save(row);
    }

    // ---------------------------------------------------------------- who, where and what is switched on

    /**
     * Both switches, and the school they are read for. `staffAttendance` is the controller's {@code @FeatureFlag} and
     * is already checked by the interceptor; `staffMobileApp` is checked here because every route of this feature
     * lives under `/staff`, which exists only when staff mode does — a school with staff mode off must not have a
     * reachable attendance route, and one annotation gates one key.
     */
    private String requireStaffApp() {
        String schoolId = tenant.writeSchoolId();
        if (!flags.isOn(schoolId, FlagKeys.STAFF_MOBILE_APP))
            throw new ApiException(HttpStatus.NOT_FOUND, "not_found", "No such endpoint.");
        return schoolId;
    }

    private AttendanceStaff.Employee me(String schoolId, Principals.User caller) {
        if (caller == null) throw ApiException.unauthorized("Sign in first.");
        return staff.forPrincipal(schoolId, caller)
                .orElseThrow(() -> ApiException.forbidden("Only an active employee of this school records her own attendance."));
    }

    /** Her category: the one an Admin set on her profile (feature 14), or her role's default. */
    private String category(String schoolId, AttendanceStaff.Employee employee) {
        return profiles.findOneBySchoolIdAndStaffKindAndStaffId(schoolId, employee.kind(), employee.id())
                .map(Entities.StaffAttendanceProfileEntity::getCategory)
                .orElse(StaffAttendanceProfileService.defaultCategory(employee.role()));
    }

    private static String installId(CheckInRequest request) {
        String id = request.getInstallId() == null ? "" : request.getInstallId().trim();
        if (id.isEmpty() || id.length() > MAX_INSTALL_ID)
            throw ApiException.badRequest("installId is the app install's own id, 1–" + MAX_INSTALL_ID + " characters.");
        return id;
    }

    /** The only check on the fix itself. The message names no value, because the value is a coordinate. */
    private static void coordinates(CheckInRequest request) {
        double lat = request.getLatitude(), lon = request.getLongitude();
        if (!Double.isFinite(lat) || lat < -90 || lat > 90) throw ApiException.badRequest("latitude is between -90 and 90.");
        if (!Double.isFinite(lon) || lon < -180 || lon > 180) throw ApiException.badRequest("longitude is between -180 and 180.");
    }

    private static YearMonth month(String month, LocalDate today) {
        if (month == null || month.isBlank()) return YearMonth.from(today);
        try { return YearMonth.parse(month.trim(), YYYY_MM); }
        catch (DateTimeParseException e) { throw ApiException.badRequest("month is a year and a month such as 2026-10."); }
    }

    private static String time(Instant at, ZoneId zone) { return at == null ? null : HH_MM.format(at.atZone(zone)); }

    private static String clip(String value, int max) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}
