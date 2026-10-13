package quest.server.staffattendance;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import quest.server.auth.AuditService;
import quest.server.auth.Principals;
import quest.server.config.ApiException;
import quest.server.platform.SchoolCalendar;
import quest.server.staffattendance.Entities.AttendanceHolidayEntity;
import quest.server.staffattendance.Entities.AttendanceSettingsEntity;
import quest.server.staffattendance.Entities.WorkScheduleEntity;
import quest.server.staffattendance.StaffAttendanceDto.AttendanceHoliday;
import quest.server.staffattendance.StaffAttendanceDto.AttendanceSettings;
import quest.server.staffattendance.StaffAttendanceDto.UpdateAttendanceSettingsRequest;
import quest.server.staffattendance.StaffAttendanceDto.WorkSchedule;
import quest.server.tenancy.TenantContext;

/**
 * The Admin's Attendance settings page (feature 14): the school pin and radius, the hours of each staff category with
 * their Ramadan range, the holidays, the three rule thresholds and the notification policy.
 *
 * <p><strong>Defaults without a row.</strong> A school that never saved the page reads the spec's defaults — radius
 * 150 m, grace 10, both thresholds 60, digest at 07:30, Sunday–Thursday — so features 15 and 16 always have rules.
 *
 * <p><strong>The time zone is shown, not stored here.</strong> It is `schools.timezone` through
 * {@link SchoolCalendar}, the one place that already decides what "today" is for the school.
 *
 * <p><strong>Scope.</strong> The school is {@link TenantContext#writeSchoolId()}, never a request field; every query
 * names it, so one school's settings are invisible from another.
 */
@Service
public class AttendanceSettingsService {
    static final int RADIUS_MIN = 50, RADIUS_MAX = 500, RADIUS_DEFAULT = 150;
    static final int GRACE_DEFAULT = 10, NOT_ARRIVED_DEFAULT = 60, MISSING_CHECK_OUT_DEFAULT = 60, MINUTES_MAX = 600;
    static final LocalTime DIGEST_DEFAULT = LocalTime.of(7, 30);
    static final LocalTime START_DEFAULT = LocalTime.of(7, 0), END_DEFAULT = LocalTime.of(14, 0);
    static final int MAX_HOLIDAYS = 100, MAX_HOLIDAY_NAME = 80;
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");
    /** The order the page shows and stores working days in: the Gulf week starts on Sunday. */
    private static final List<DayOfWeek> WEEK_ORDER = List.of(DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY,
            DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY);

    private final AttendanceSettingsRepository settings; private final WorkScheduleRepository schedules;
    private final AttendanceHolidayRepository holidays; private final TenantContext tenant;
    private final SchoolCalendar calendar; private final AuditService audit; private final Clock clock;

    public AttendanceSettingsService(AttendanceSettingsRepository settings, WorkScheduleRepository schedules,
                                     AttendanceHolidayRepository holidays, TenantContext tenant, SchoolCalendar calendar,
                                     AuditService audit, Clock clock) {
        this.settings = settings; this.schedules = schedules; this.holidays = holidays; this.tenant = tenant;
        this.calendar = calendar; this.audit = audit; this.clock = clock;
    }

    /** The caller's school's page, defaults filled in. */
    public AttendanceSettings read() { return read(tenant.writeSchoolId()); }

    @Transactional
    public AttendanceSettings update(Principals.User caller, UpdateAttendanceSettingsRequest request) {
        String schoolId = tenant.writeSchoolId();
        var row = settings.findOneBySchoolId(schoolId).orElseGet(AttendanceSettingsEntity::new);
        row.setSchoolId(schoolId);
        applyLocation(row, request.latitude(), request.longitude(), or(request.radiusMeters(), RADIUS_DEFAULT));
        row.setGraceMinutes(minutes(or(request.graceMinutes(), GRACE_DEFAULT), "graceMinutes", 0));
        row.setNotArrivedMinutes(minutes(or(request.notArrivedMinutes(), NOT_ARRIVED_DEFAULT), "notArrivedMinutes", 1));
        row.setMissingCheckOutMinutes(minutes(or(request.missingCheckOutMinutes(), MISSING_CHECK_OUT_DEFAULT), "missingCheckOutMinutes", 1));
        row.setDirectorNotify(policy(request.directorNotify(), "directorNotify"));
        row.setAdminNotify(policy(request.adminNotify(), "adminNotify"));
        row.setDigestTime(request.digestTime() == null ? DIGEST_DEFAULT : time(request.digestTime(), "digestTime"));

        var byCategory = validSchedules(request.schedules());
        var holidayRows = validHolidays(request.holidays());

        row.setUpdatedBy(caller.userId()); row.setUpdatedAt(Instant.now(clock));
        settings.save(row);
        for (var schedule : byCategory.values()) {
            var existing = schedules.findOneBySchoolIdAndCategory(schoolId, schedule.getCategory()).orElse(null);
            schedule.setId(existing == null ? UUID.randomUUID().toString() : existing.getId());
            schedule.setSchoolId(schoolId);
            schedules.save(schedule);
        }
        holidays.deleteAll(holidays.findBySchoolIdOrderByFromDateAsc(schoolId));
        holidays.flush();
        for (var holiday : holidayRows) { holiday.setId(UUID.randomUUID().toString()); holiday.setSchoolId(schoolId); }
        holidays.saveAll(holidayRows);
        audit.record(caller.userId(), "attendance.settings.update", "school", schoolId, schoolId,
                Map.of("radiusMeters", row.getRadiusMeters(), "holidays", holidayRows.size()));
        return read(schoolId);
    }

    // ---------------------------------------------------------------- what features 15 and 16 read

    /** One category's hours for one school: the saved row, or Sunday–Thursday 07:00–14:00. */
    public ScheduleResolver.Hours hours(String schoolId, String category) {
        return schedules.findOneBySchoolIdAndCategory(schoolId, category).map(AttendanceSettingsService::hours)
                .orElseGet(() -> new ScheduleResolver.Hours(defaultWeekdays(), START_DEFAULT, END_DEFAULT, null, null, null, null));
    }

    public List<ScheduleResolver.Holiday> holidays(String schoolId) {
        return holidays.findBySchoolIdOrderByFromDateAsc(schoolId).stream()
                .map(h -> new ScheduleResolver.Holiday(h.getName(), h.getFromDate(), h.getToDate())).toList();
    }

    // ---------------------------------------------------------------- read

    private AttendanceSettings read(String schoolId) {
        var row = settings.findOneBySchoolId(schoolId).orElse(null);
        var saved = new LinkedHashMap<String, WorkScheduleEntity>();
        for (var s : schedules.findBySchoolId(schoolId)) saved.put(s.getCategory(), s);
        List<WorkSchedule> scheduleDtos = StaffAttendanceDto.CATEGORIES.stream()
                .map(c -> saved.containsKey(c) ? dto(saved.get(c)) : defaultSchedule(c)).toList();
        List<AttendanceHoliday> holidayDtos = holidays.findBySchoolIdOrderByFromDateAsc(schoolId).stream()
                .map(h -> new AttendanceHoliday(h.getName(), h.getFromDate().toString(), h.getToDate().toString())).toList();
        String zone = calendar.of(schoolId).zone().getId();
        if (row == null) return new AttendanceSettings(null, null, RADIUS_DEFAULT, GRACE_DEFAULT, NOT_ARRIVED_DEFAULT,
                MISSING_CHECK_OUT_DEFAULT, "DIGEST", "DIGEST", HH_MM.format(DIGEST_DEFAULT), zone, scheduleDtos, holidayDtos);
        return new AttendanceSettings(row.getLatitude(), row.getLongitude(), row.getRadiusMeters(), row.getGraceMinutes(),
                row.getNotArrivedMinutes(), row.getMissingCheckOutMinutes(), row.getDirectorNotify(), row.getAdminNotify(),
                HH_MM.format(row.getDigestTime()), zone, scheduleDtos, holidayDtos);
    }

    private static WorkSchedule dto(WorkScheduleEntity s) {
        return new WorkSchedule(s.getCategory(), List.of(s.getWeekdays().split(",")), HH_MM.format(s.getStartTime()),
                HH_MM.format(s.getEndTime()), str(s.getRamadanFrom()), str(s.getRamadanTo()),
                s.getRamadanStart() == null ? null : HH_MM.format(s.getRamadanStart()),
                s.getRamadanEnd() == null ? null : HH_MM.format(s.getRamadanEnd()));
    }

    private static WorkSchedule defaultSchedule(String category) {
        return new WorkSchedule(category, SchoolCalendar.DEFAULT_WEEK.stream().map(DayOfWeek::name).toList(),
                HH_MM.format(START_DEFAULT), HH_MM.format(END_DEFAULT), null, null, null, null);
    }

    private static ScheduleResolver.Hours hours(WorkScheduleEntity s) {
        var days = EnumSet.noneOf(DayOfWeek.class);
        for (var d : s.getWeekdays().split(",")) days.add(DayOfWeek.valueOf(d));
        return new ScheduleResolver.Hours(days, s.getStartTime(), s.getEndTime(), s.getRamadanFrom(), s.getRamadanTo(),
                s.getRamadanStart(), s.getRamadanEnd());
    }

    private static EnumSet<DayOfWeek> defaultWeekdays() { return EnumSet.copyOf(SchoolCalendar.DEFAULT_WEEK); }

    // ---------------------------------------------------------------- validation

    static void applyLocation(AttendanceSettingsEntity row, Double latitude, Double longitude, int radius) {
        if ((latitude == null) != (longitude == null)) throw ApiException.badRequest("latitude and longitude are set together.");
        if (latitude != null && (latitude < -90 || latitude > 90)) throw ApiException.badRequest("latitude is between -90 and 90.");
        if (longitude != null && (longitude < -180 || longitude > 180)) throw ApiException.badRequest("longitude is between -180 and 180.");
        if (radius < RADIUS_MIN || radius > RADIUS_MAX)
            throw ApiException.badRequest("radiusMeters is between " + RADIUS_MIN + " and " + RADIUS_MAX + ".");
        row.setLatitude(latitude); row.setLongitude(longitude); row.setRadiusMeters(radius);
    }

    /** One row per category; a category the request leaves out keeps the default hours. */
    static Map<String, WorkScheduleEntity> validSchedules(List<WorkSchedule> requested) {
        var out = new LinkedHashMap<String, WorkScheduleEntity>();
        if (requested == null) return out;
        for (var s : requested) {
            String category = s.category() == null ? "" : s.category().trim().toUpperCase(Locale.ROOT);
            if (!StaffAttendanceDto.CATEGORIES.contains(category)) throw ApiException.badRequest("category is one of " + StaffAttendanceDto.CATEGORIES + ".");
            if (out.containsKey(category)) throw ApiException.badRequest("category " + category + " appears twice.");
            var days = new ArrayList<DayOfWeek>();
            for (var d : s.weekdays() == null ? List.<String>of() : s.weekdays()) {
                try {
                    var day = DayOfWeek.valueOf(d.trim().toUpperCase(Locale.ROOT));
                    if (!days.contains(day)) days.add(day);
                } catch (IllegalArgumentException e) { throw ApiException.badRequest("weekdays holds day names such as SUNDAY."); }
            }
            if (days.isEmpty()) throw ApiException.badRequest("weekdays needs at least one day for " + category + ".");
            days.sort(java.util.Comparator.comparingInt(WEEK_ORDER::indexOf));
            LocalTime start = time(s.start(), "start"), end = time(s.end(), "end");
            if (!start.isBefore(end)) throw ApiException.badRequest("start is before end for " + category + ".");

            var row = new WorkScheduleEntity();
            row.setCategory(category); row.setStartTime(start); row.setEndTime(end);
            row.setWeekdays(String.join(",", days.stream().map(DayOfWeek::name).toList()));
            boolean anyRamadan = s.ramadanFrom() != null || s.ramadanTo() != null || s.ramadanStart() != null || s.ramadanEnd() != null;
            if (anyRamadan) {
                if (s.ramadanFrom() == null || s.ramadanTo() == null || s.ramadanStart() == null || s.ramadanEnd() == null)
                    throw ApiException.badRequest("Ramadan needs a from date, a to date, a start and an end for " + category + ".");
                LocalDate from = date(s.ramadanFrom(), "ramadanFrom"), to = date(s.ramadanTo(), "ramadanTo");
                if (to.isBefore(from)) throw ApiException.badRequest("ramadanFrom is on or before ramadanTo.");
                LocalTime rStart = time(s.ramadanStart(), "ramadanStart"), rEnd = time(s.ramadanEnd(), "ramadanEnd");
                if (!rStart.isBefore(rEnd)) throw ApiException.badRequest("ramadanStart is before ramadanEnd for " + category + ".");
                row.setRamadanFrom(from); row.setRamadanTo(to); row.setRamadanStart(rStart); row.setRamadanEnd(rEnd);
            }
            out.put(category, row);
        }
        return out;
    }

    static List<AttendanceHolidayEntity> validHolidays(List<AttendanceHoliday> requested) {
        var out = new ArrayList<AttendanceHolidayEntity>();
        if (requested == null) return out;
        if (requested.size() > MAX_HOLIDAYS) throw ApiException.badRequest("At most " + MAX_HOLIDAYS + " holidays.");
        for (var h : requested) {
            String name = h.name() == null ? "" : h.name().trim();
            if (name.isEmpty() || name.length() > MAX_HOLIDAY_NAME) throw ApiException.badRequest("A holiday name is 1–" + MAX_HOLIDAY_NAME + " characters.");
            LocalDate from = date(h.from(), "from"), to = date(h.to(), "to");
            if (to.isBefore(from)) throw ApiException.badRequest("A holiday's from is on or before its to.");
            var row = new AttendanceHolidayEntity();
            row.setName(name); row.setFromDate(from); row.setToDate(to);
            out.add(row);
        }
        return out;
    }

    private static String policy(String value, String field) {
        if (value == null) return "DIGEST";
        String v = value.trim().toUpperCase(Locale.ROOT);
        if (!StaffAttendanceDto.NOTIFY_POLICIES.contains(v)) throw ApiException.badRequest(field + " is one of " + StaffAttendanceDto.NOTIFY_POLICIES + ".");
        return v;
    }

    private static int minutes(int value, String field, int min) {
        if (value < min || value > MINUTES_MAX) throw ApiException.badRequest(field + " is between " + min + " and " + MINUTES_MAX + " minutes.");
        return value;
    }

    private static LocalTime time(String value, String field) {
        try { return LocalTime.parse(value == null ? "" : value.trim(), HH_MM); }
        catch (DateTimeParseException e) { throw ApiException.badRequest(field + " is a time such as 07:30."); }
    }

    private static LocalDate date(String value, String field) {
        try { return LocalDate.parse(value == null ? "" : value.trim()); }
        catch (DateTimeParseException e) { throw ApiException.badRequest(field + " is a date such as 2027-02-18."); }
    }

    private static int or(Integer value, int fallback) { return value == null ? fallback : value; }
    private static String str(LocalDate d) { return d == null ? null : d.toString(); }
}
