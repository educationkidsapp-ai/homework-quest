package quest.server.staffattendance;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import quest.server.staffattendance.AttendanceBoardService.DayInput;
import quest.server.staffattendance.StaffAttendanceDto.BoardRow;

/**
 * Feature 17, without a database: the monthly aggregation against a fixture month with every status, the board's
 * counts, and the two export files with an Arabic name.
 *
 * <p>The fixture is the defaults (Sunday–Thursday, 07:00–14:00 in Riyadh, grace 10, both thresholds 60), in October
 * 2026, one employee, one day per status:
 * <pre>
 *   Sun  4  06:55–14:05  on time                      Sun 11  07:00–14:00  remote
 *   Mon  5  07:20–14:00  late 20                      Mon 12  —            holiday
 *   Tue  6  —            absent                       Tue 13  07:00–14:00  correction (feature 18)
 *   Wed  7  07:00–12:30  early leave 90
 *   Thu  8  07:05–       missing check-out
 *   Fri  9  —            day off
 *   Sat 10  09:00–10:00  day off, came in anyway — not a working day, counts for nothing
 * </pre>
 */
class AttendanceReportTest {
    private static final ZoneId RIYADH = ZoneId.of("Asia/Riyadh");
    private static final AttendanceRules.Thresholds DEFAULTS = new AttendanceRules.Thresholds(10, 60, 60);
    private static final ScheduleResolver.Hours HOURS = new ScheduleResolver.Hours(
            EnumSet.of(DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY),
            LocalTime.of(7, 0), LocalTime.of(14, 0), null, null, null, null);
    private static final List<ScheduleResolver.Holiday> HOLIDAYS =
            List.of(new ScheduleResolver.Holiday("National day", LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 12)));

    @Test void a_month_with_every_status_adds_up_to_the_hand_counts() {
        var days = new ArrayList<DayInput>();
        days.add(day(4, "06:55", "14:05", "onsite"));
        days.add(day(5, "07:20", "14:00", "onsite"));
        days.add(day(6, null, null, null));
        days.add(day(7, "07:00", "12:30", "onsite"));
        days.add(day(8, "07:05", null, "onsite"));
        days.add(day(9, null, null, null));
        days.add(day(10, "09:00", "10:00", "onsite"));
        days.add(day(11, "07:00", "14:00", "remote"));
        days.add(day(12, null, null, null));
        days.add(day(13, "07:00", "14:00", "correction"));

        var t = AttendanceBoardService.totals(days, DEFAULTS, RIYADH, Instant.parse("2026-10-31T12:00:00Z"));

        assertThat(t.workingDays()).as("Sun 4 – Thu 8, Sun 11, Tue 13").isEqualTo(7);
        assertThat(t.presentOnsite()).as("4, 5, 7, 8 and the correction on 13").isEqualTo(5);
        assertThat(t.presentRemote()).isEqualTo(1);
        assertThat(t.lateCount()).isEqualTo(1);
        assertThat(t.lateMinutes()).isEqualTo(20);
        assertThat(t.absentDays()).isEqualTo(1);
        assertThat(t.earlyLeaveMinutes()).isEqualTo(90);
        assertThat(t.missingCheckOuts()).isEqualTo(1);
        assertThat(t.corrections()).isEqualTo(1);
    }

    @Test void a_day_still_in_progress_is_neither_absent_nor_missing_its_check_out() {
        var morning = AttendanceBoardService.totals(List.of(day(18, null, null, null)), DEFAULTS, RIYADH, riyadh(18, "09:00"));
        assertThat(morning.workingDays()).isEqualTo(1);
        assertThat(morning.absentDays()).isZero();

        var afternoon = AttendanceBoardService.totals(List.of(day(18, "07:00", null, "onsite")), DEFAULTS, RIYADH, riyadh(18, "14:30"));
        assertThat(afternoon.missingCheckOuts()).as("the threshold is 60 minutes after 14:00").isZero();
        var evening = AttendanceBoardService.totals(List.of(day(18, "07:00", null, "onsite")), DEFAULTS, RIYADH, riyadh(18, "15:00"));
        assertThat(evening.missingCheckOuts()).isEqualTo(1);
    }

    @Test void each_card_counts_exactly_the_rows_its_filter_shows() {
        var rows = List.of(row("PRESENT", false), row("PRESENT", true), row("LATE", true), row("NOT_ARRIVED", false),
                row("REMOTE", false), row("ABSENT", false), row("OFF", false));
        var counts = AttendanceBoardService.counts(rows);
        assertThat(counts.present()).isEqualTo(2);
        assertThat(counts.late()).isEqualTo(1);
        assertThat(counts.notArrived()).isEqualTo(1);
        assertThat(counts.remote()).isEqualTo(1);
        assertThat(counts.noCheckOut()).isEqualTo(2);
    }

    // ---------------------------------------------------------------- the exports

    private static final StaffAttendanceDto.AttendanceReport REPORT = new StaffAttendanceDto.AttendanceReport("2026-10", "SCHOOL",
            List.of(new StaffAttendanceDto.ReportRow("USER", "u1", "تالا المعلمة", "TEACHER", List.of("british"),
                    7, 5, 1, 1, 20, 1, 90, 1, 1)));

    @Test void the_csv_has_the_columns_payroll_reads_and_keeps_an_arabic_name() {
        byte[] csv = AttendanceReportExport.csv(REPORT);
        assertThat(new byte[] {csv[0], csv[1], csv[2]}).as("UTF-8 BOM, so Excel reads Arabic")
                .containsExactly((byte) 0xEF, (byte) 0xBB, (byte) 0xBF);
        var lines = new String(csv, 3, csv.length - 3, StandardCharsets.UTF_8).split("\n");
        assertThat(lines[0]).isEqualTo(String.join(",", AttendanceReportExport.HEADER.stream().map(String::valueOf).toList()));
        assertThat(lines[0]).contains("Working days", "Present onsite", "Present remote", "Late count", "Late minutes",
                "Absent days", "Early leave minutes", "Missing check-outs", "Corrections", "الموظف");
        assertThat(lines[1]).isEqualTo("تالا المعلمة,TEACHER,british,7,5,1,1,20,1,90,1,1");
        assertThat(AttendanceReportExport.filename(REPORT, "csv")).isEqualTo("staff-attendance-2026-10.csv");
    }

    @Test void the_excel_file_reads_back_with_the_same_cells() throws Exception {
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(AttendanceReportExport.xlsx(REPORT)))) {
            var sheet = book.getSheetAt(0);
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Employee / الموظف");
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("تالا المعلمة");
            assertThat(sheet.getRow(1).getCell(3).getNumericCellValue()).isEqualTo(7);
            assertThat(sheet.getRow(1).getCell(7).getNumericCellValue()).isEqualTo(20);
            assertThat(sheet.getRow(0).getLastCellNum()).isEqualTo((short) AttendanceReportExport.HEADER.size());
        }
    }

    // ---------------------------------------------------------------- helpers

    private static DayInput day(int dayOfMonth, String in, String out, String mode) {
        LocalDate date = LocalDate.of(2026, 10, dayOfMonth);
        return new DayInput(date, ScheduleResolver.resolve(HOURS, HOLIDAYS, date),
                in == null ? null : riyadh(dayOfMonth, in), out == null ? null : riyadh(dayOfMonth, out), mode);
    }

    private static Instant riyadh(int dayOfMonth, String time) {
        return LocalDate.of(2026, 10, dayOfMonth).atTime(LocalTime.parse(time)).atZone(RIYADH).toInstant();
    }

    private static BoardRow row(String status, boolean noCheckOut) {
        return new BoardRow("USER", status + noCheckOut, "x", "TEACHER", "TEACHER", List.of(), null, null, status, null, noCheckOut, null);
    }
}
