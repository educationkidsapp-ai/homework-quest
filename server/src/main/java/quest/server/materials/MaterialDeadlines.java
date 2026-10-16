package quest.server.materials;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import quest.server.auth.AuditService;
import quest.server.auth.Principals;
import quest.server.config.ApiException;
import quest.server.materials.Entities.MaterialSettingsEntity;
import quest.server.platform.SchoolCalendar;
import quest.server.tenancy.TenantContext;

/**
 * Feature 31's deadline, in one place: the Admin's `/admin/material-settings` card and the instant one teaching
 * week's material was due.
 *
 * <p><strong>Defaults without a row.</strong> A school that never saved the card is held to Thursday 12:00 with a
 * reminder four hours before, so the board and the reminder job always have a rule.
 *
 * <p><strong>The material for a week is due in the week before it.</strong> A teacher prepares the coming week while
 * the current one runs, so {@link #dueAt} walks back one week from the week's first teaching day and takes the
 * deadline's weekday inside <em>that</em> week — whatever day a school's week starts on, and in the school's own
 * zone, never the server's.
 *
 * <p><strong>Scope.</strong> The school is {@link TenantContext#writeSchoolId()} on a request and the job's own
 * argument otherwise, never a request field; every query names it.
 */
@Service
public class MaterialDeadlines {
    static final DayOfWeek DUE_WEEKDAY_DEFAULT = DayOfWeek.THURSDAY;
    static final LocalTime DUE_TIME_DEFAULT = LocalTime.of(12, 0);
    static final int REMIND_HOURS_DEFAULT = 4, REMIND_HOURS_MAX = 72;
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private final MaterialSettingsRepository settings; private final TenantContext tenant;
    private final SchoolCalendar calendar; private final AuditService audit; private final Clock clock;

    public MaterialDeadlines(MaterialSettingsRepository settings, TenantContext tenant, SchoolCalendar calendar,
                             AuditService audit, Clock clock) {
        this.settings = settings; this.tenant = tenant; this.calendar = calendar; this.audit = audit; this.clock = clock;
    }

    /** One school's deadline rule: the saved row, or the defaults above. */
    public record Rule(DayOfWeek dueWeekday, LocalTime dueTime, int remindHoursBefore) {}

    public Rule rule(String schoolId) {
        return settings.findOneBySchoolId(schoolId)
                .map(r -> new Rule(weekday(r.getDueWeekday(), "dueWeekday"), r.getDueTime(), r.getRemindHoursBefore()))
                .orElseGet(() -> new Rule(DUE_WEEKDAY_DEFAULT, DUE_TIME_DEFAULT, REMIND_HOURS_DEFAULT));
    }

    /** When the material for the teaching week beginning at {@code weekStart} was due, in the school's zone. */
    public Instant dueAt(String schoolId, LocalDate weekStart) {
        var week = calendar.of(schoolId);
        return dueAt(rule(schoolId), week.zone(), weekStart);
    }

    static Instant dueAt(Rule rule, ZoneId zone, LocalDate weekStart) {
        LocalDate previous = weekStart.minusDays(7);
        LocalDate day = previous;
        for (int i = 0; i < 7 && day.getDayOfWeek() != rule.dueWeekday(); i++) day = day.plusDays(1);
        return day.atTime(rule.dueTime()).atZone(zone).toInstant();
    }

    // ---------------------------------------------------------------- the Admin's card

    public MaterialDto.MaterialSettings read() { return read(tenant.writeSchoolId()); }

    MaterialDto.MaterialSettings read(String schoolId) {
        var rule = rule(schoolId);
        return new MaterialDto.MaterialSettings(rule.dueWeekday().name(), HH_MM.format(rule.dueTime()),
                rule.remindHoursBefore(), calendar.of(schoolId).zone().getId());
    }

    @Transactional
    public MaterialDto.MaterialSettings update(Principals.User caller, MaterialDto.UpdateMaterialSettingsRequest request) {
        String schoolId = tenant.writeSchoolId();
        var current = rule(schoolId);
        var row = settings.findOneBySchoolId(schoolId).orElseGet(MaterialSettingsEntity::new);
        row.setSchoolId(schoolId);
        row.setDueWeekday(request.dueWeekday() == null ? current.dueWeekday().name()
                : weekday(request.dueWeekday(), "dueWeekday").name());
        row.setDueTime(request.dueTime() == null ? current.dueTime() : time(request.dueTime()));
        row.setRemindHoursBefore(hours(request.remindHoursBefore() == null ? current.remindHoursBefore() : request.remindHoursBefore()));
        row.setUpdatedBy(caller.userId()); row.setUpdatedAt(Instant.now(clock));
        settings.save(row);
        audit.record(caller.userId(), "material.settings.update", "school", schoolId, schoolId,
                Map.of("dueWeekday", row.getDueWeekday(), "dueTime", HH_MM.format(row.getDueTime())));
        return read(schoolId);
    }

    // ---------------------------------------------------------------- parsing

    /** The teaching week a `?week=` names: any day of it answers its first teaching day, as `GET /teacher/week` does. */
    public LocalDate week(String schoolId, String requested) {
        var week = calendar.of(schoolId);
        LocalDate date = requested == null || requested.isBlank()
                ? LocalDate.now(clock.withZone(week.zone()))
                : date(requested);
        return week.startOf(date);
    }

    static LocalDate date(String value) {
        try { return LocalDate.parse(value.trim()); }
        catch (DateTimeParseException e) { throw ApiException.badRequest("`week` is a date, as 2026-10-18."); }
    }

    private static DayOfWeek weekday(String value, String field) {
        if (value == null || value.isBlank()) throw ApiException.badRequest("`" + field + "` is a day of the week, as THURSDAY.");
        String name = value.trim().toUpperCase(Locale.ROOT);
        for (DayOfWeek d : DayOfWeek.values()) if (d.name().equals(name) || name.length() >= 3 && d.name().startsWith(name)) return d;
        throw ApiException.badRequest("`" + value + "` is not a day of the week (use SUNDAY … SATURDAY).");
    }

    private static LocalTime time(String value) {
        try { return LocalTime.parse(value.trim(), HH_MM); }
        catch (DateTimeParseException e) { throw ApiException.badRequest("`dueTime` is a time of day, as 12:00."); }
    }

    private static int hours(int value) {
        if (value < 1 || value > REMIND_HOURS_MAX)
            throw ApiException.badRequest("`remindHoursBefore` is between 1 and " + REMIND_HOURS_MAX + " hours.");
        return value;
    }
}
