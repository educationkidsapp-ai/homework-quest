package quest.server.staffattendance;

import java.util.ArrayList;
import java.util.List;
import quest.server.grading.GradingExports;

/**
 * Feature 17: the monthly report as the two files payroll opens, written by the gradebook's own writers
 * ({@link GradingExports#csv}, with the UTF-8 BOM that keeps Arabic names readable in Excel, and
 * {@link GradingExports#xlsx}). Headers carry both languages, because the file outlives the screen's language.
 */
final class AttendanceReportExport {
    private AttendanceReportExport() {}

    static final List<Object> HEADER = List.of(
            "Employee / الموظف", "Category / الفئة", "Academy / الأكاديمية", "Working days / أيام العمل",
            "Present onsite / حضور في المدرسة", "Present remote / حضور عن بعد", "Late count / مرات التأخر",
            "Late minutes / دقائق التأخر", "Absent days / أيام الغياب", "Early leave minutes / دقائق الانصراف المبكر",
            "Missing check-outs / انصراف غير مسجل", "Corrections / التصحيحات");

    static byte[] csv(StaffAttendanceDto.AttendanceReport report) { return GradingExports.csv(rows(report)); }

    static byte[] xlsx(StaffAttendanceDto.AttendanceReport report) { return GradingExports.xlsx("Attendance " + report.month(), rows(report)); }

    static List<List<Object>> rows(StaffAttendanceDto.AttendanceReport report) {
        var rows = new ArrayList<List<Object>>();
        rows.add(HEADER);
        for (var r : report.rows())
            rows.add(List.of(r.fullName(), r.category(), String.join(" ", r.academies()), r.workingDays(), r.presentOnsite(),
                    r.presentRemote(), r.lateCount(), r.lateMinutes(), r.absentDays(), r.earlyLeaveMinutes(),
                    r.missingCheckOuts(), r.corrections()));
        return rows;
    }

    static String filename(StaffAttendanceDto.AttendanceReport report, String extension) {
        return "staff-attendance-" + report.month() + "." + extension;
    }
}
