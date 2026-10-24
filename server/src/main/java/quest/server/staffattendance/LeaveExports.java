package quest.server.staffattendance;

import java.util.ArrayList;
import java.util.List;
import quest.server.grading.GradingExports;

/**
 * Feature 34's two files, written by the gradebook's own writers ({@link GradingExports#csv}, with the UTF-8 BOM that
 * keeps Arabic names readable in Excel, and {@link GradingExports#xlsx}), exactly as feature 17's monthly report is.
 * Headers carry both languages, because the file outlives the screen's language.
 */
final class LeaveExports {
    private LeaveExports() {}

    // ---------------------------------------------------------------- balance per employee

    /** One group of three columns per leave type the school offers, so payroll reads a type across one row. */
    static List<List<Object>> balanceRows(StaffAttendanceDto.LeaveBalanceReport report) {
        var header = new ArrayList<Object>(List.of("Employee / الموظف", "Category / الفئة", "Academy / الأكاديمية"));
        for (String type : report.types()) {
            header.add(type + " allowance / الرصيد السنوي");
            header.add(type + " used / المستخدم");
            header.add(type + " remaining / المتبقي");
        }
        var rows = new ArrayList<List<Object>>();
        rows.add(header);
        for (var r : report.rows()) {
            var row = new ArrayList<Object>(List.of(r.fullName(), r.category(), String.join(" ", r.academies())));
            for (var cell : r.balances()) {
                row.add(cell.allowanceDays() == null ? "—" : cell.allowanceDays());
                row.add(cell.usedDays());
                row.add(cell.remainingDays() == null ? "—" : cell.remainingDays());
            }
            rows.add(row);
        }
        return rows;
    }

    static byte[] balanceCsv(StaffAttendanceDto.LeaveBalanceReport report) { return GradingExports.csv(balanceRows(report)); }

    static byte[] balanceXlsx(StaffAttendanceDto.LeaveBalanceReport report) {
        return GradingExports.xlsx("Leave balances " + report.year(), balanceRows(report));
    }

    static String balanceFilename(StaffAttendanceDto.LeaveBalanceReport report, String extension) {
        return "staff-leave-balances-" + report.year() + "." + extension;
    }

    // ---------------------------------------------------------------- requests per month

    static final List<Object> REQUEST_HEADER = List.of(
            "Employee / الموظف", "Category / الفئة", "Request / الطلب", "Leave type / نوع الإجازة",
            "From / من", "To / إلى", "From time / من الساعة", "To time / إلى الساعة", "Days / الأيام",
            "Minutes / الدقائق", "Status / الحالة", "Waiting for / في انتظار", "Reason / السبب",
            "Rejection reason / سبب الرفض", "Decided by / البتّ بواسطة", "Decided on / تاريخ القرار");

    static List<List<Object>> requestRows(StaffAttendanceDto.LeaveRequestReport report) {
        var rows = new ArrayList<List<Object>>();
        rows.add(REQUEST_HEADER);
        for (var r : report.rows())
            rows.add(List.of(r.fullName(), r.category(), r.type(), or(r.leaveType()), r.date(), or(r.toDate()),
                    or(r.fromTime()), or(r.toTime()), r.days() == null ? "" : r.days(), r.minutes() == null ? "" : r.minutes(),
                    r.status(), or(r.stage()), r.reason(), or(r.rejectReason()), or(r.decidedBy()), or(r.decidedOn())));
        return rows;
    }

    static byte[] requestCsv(StaffAttendanceDto.LeaveRequestReport report) { return GradingExports.csv(requestRows(report)); }

    static byte[] requestXlsx(StaffAttendanceDto.LeaveRequestReport report) {
        return GradingExports.xlsx("Requests " + report.month(), requestRows(report));
    }

    static String requestFilename(StaffAttendanceDto.LeaveRequestReport report, String extension) {
        return "staff-requests-" + report.month() + "." + extension;
    }

    /** {@link List#of} refuses a null, and an empty cell is what a missing value means in both files. */
    private static String or(String value) { return value == null ? "" : value; }
}
