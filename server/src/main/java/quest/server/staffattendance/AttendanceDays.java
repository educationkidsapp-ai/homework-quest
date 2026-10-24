package quest.server.staffattendance;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import quest.server.platform.SchoolCalendar;
import quest.server.staffattendance.Entities.StaffAttendanceDayEntity;

/**
 * One employee's working day turned into its `staff_attendance_days` row, in one place: feature 16's job writes today's
 * on every run, and feature 18 rewrites a past day once a correction is approved, so a corrected late arrival stops
 * being late without waiting for anything. The rules are {@link AttendanceRules}; the schedule is
 * {@link ScheduleResolver}'s.
 */
@Component
class AttendanceDays {
    private final ScheduleResolver schedules; private final AttendanceSettingsService settings;
    private final StaffAttendanceProfileRepository profiles; private final StaffCheckInRepository checkIns;
    private final StaffAttendanceDayRepository days; private final SchoolCalendar calendar;

    AttendanceDays(ScheduleResolver schedules, AttendanceSettingsService settings, StaffAttendanceProfileRepository profiles,
                   StaffCheckInRepository checkIns, StaffAttendanceDayRepository days, SchoolCalendar calendar) {
        this.schedules = schedules; this.settings = settings; this.profiles = profiles; this.checkIns = checkIns;
        this.days = days; this.calendar = calendar;
    }

    /**
     * Feature 18: re-evaluate {@code date} from its record as it stands now and write the row. A holiday, a day off
     * or — feature 34 — a day of approved leave has no row and gets none, which is also how approving a leave for a
     * day that was already recorded clears the late or absent it carried. Answers the statuses, or
     * {@link AttendanceRules.Day#NONE} on a day nothing is expected.
     */
    AttendanceRules.Day recompute(String schoolId, AttendanceStaff.Employee employee, LocalDate date, Instant now) {
        var expected = schedules.expected(schoolId, category(schoolId, employee), date, employee.kind(), employee.id());
        if (expected.kind() != ScheduleResolver.Kind.WORK) { clear(schoolId, employee, date); return AttendanceRules.Day.NONE; }
        var record = checkIns.findOneBySchoolIdAndStaffKindAndStaffIdAndDay(schoolId, employee.kind(), employee.id(), date).orElse(null);
        var day = AttendanceRules.evaluate(expected, settings.alerts(schoolId).thresholds(), date, calendar.of(schoolId).zone(),
                record == null ? null : record.getCheckInAt(), record == null ? null : record.getCheckOutAt(), now);
        save(schoolId, employee, date, day, now);
        return day;
    }

    /** Writes the row when it changed; an unchanged day is not rewritten, so `updated_at` means "last changed". */
    void save(String schoolId, AttendanceStaff.Employee employee, LocalDate date, AttendanceRules.Day day, Instant now) {
        var row = days.findOneBySchoolIdAndStaffKindAndStaffIdAndDay(schoolId, employee.kind(), employee.id(), date).orElse(null);
        if (row != null && Objects.equals(row.getLateMinutes(), day.lateMinutes()) && row.isNotArrived() == day.notArrived()
                && row.isAbsent() == day.absent() && Objects.equals(row.getEarlyLeaveMinutes(), day.earlyLeaveMinutes())
                && row.isMissingCheckout() == day.missingCheckout() && row.getExcusedMinutes() == day.excusedMinutes()) return;
        if (row == null) {
            row = new StaffAttendanceDayEntity();
            row.setId(UUID.randomUUID().toString()); row.setSchoolId(schoolId);
            row.setStaffKind(employee.kind()); row.setStaffId(employee.id()); row.setDay(date);
        }
        row.setLateMinutes(day.lateMinutes()); row.setNotArrived(day.notArrived()); row.setAbsent(day.absent());
        row.setEarlyLeaveMinutes(day.earlyLeaveMinutes()); row.setMissingCheckout(day.missingCheckout());
        row.setExcusedMinutes(day.excusedMinutes());                             // feature 34: a permission's minutes
        row.setUpdatedAt(now);
        days.save(row);
    }

    /**
     * Feature 34: a day that stops expecting anything — approved leave, or a holiday the Admin has just declared —
     * loses the row it had, so the late or the absent it carried is gone from feature 25's numbers as well as from
     * the board, which re-evaluates on read. Nothing to delete is the normal case.
     */
    private void clear(String schoolId, AttendanceStaff.Employee employee, LocalDate date) {
        days.findOneBySchoolIdAndStaffKindAndStaffIdAndDay(schoolId, employee.kind(), employee.id(), date)
                .ifPresent(days::delete);
    }

    /** Her category: the one an Admin set on her profile (feature 14), or her role's default. */
    String category(String schoolId, AttendanceStaff.Employee employee) {
        return profiles.findOneBySchoolIdAndStaffKindAndStaffId(schoolId, employee.kind(), employee.id())
                .map(Entities.StaffAttendanceProfileEntity::getCategory).filter(Objects::nonNull)
                .orElse(StaffAttendanceProfileService.defaultCategory(employee.role()));
    }
}
