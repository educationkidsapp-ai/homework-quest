package quest.server.dashboard;

import java.util.List;
import quest.api.staff.AttendanceRequestView;
import quest.server.staffattendance.StaffAttendanceDto;

/**
 * Feature 21: the Java mirror of `quest.api.dashboard.Today.kt`, as {@link HomeDto} mirrors the Home half of
 * `DashboardApi.kt`. One shape for `GET /me/today`, the same for both roles that reach it — what a manager may not
 * decide or may not read is missing from the <em>rows</em>, never from the shape, so one client renders both.
 *
 * <p>Nothing here is assembled prose, for {@link HomeDto}'s reason: the card titles live in the dashboard's Transloco
 * catalogue in both languages, and the only strings that cross are the data — a person's name, a child's name, a
 * complaint's title, what the school typed. {@code counts} reuses {@link HomeDto.HomeCard} and the attendance card
 * reuses feature 17's {@link StaffAttendanceDto.BoardCounts}, so the strip and the board cannot drift from the
 * screens they also feed. Times are epoch milliseconds; {@code day} is `yyyy-MM-dd` in {@code timeZone}, the
 * school's.
 */
public final class TodayDto {
    private TodayDto() {}

    /** Today's board in five numbers, with how wide the caller's view is (`SCHOOL` for the Admin, `ACADEMY`…). */
    public record TodayAttendance(String scope, StaffAttendanceDto.BoardCounts counts) {}

    /**
     * An employee whose check-in was refused today because her phone is not the bound one, and whose binding has not
     * been reset since. Admin only: the card's action is `DELETE /admin/staff/{kind}/{id}/attendance-device`, which
     * only she may call.
     */
    public record TodayDeviceBlock(String kind, String id, String fullName, long lastAttemptAt) {}

    /** The requests the caller may decide (feature 18) and the device bindings an Admin can clear. */
    public record TodayWaiting(List<AttendanceRequestView> requests, List<TodayDeviceBlock> devices) {}

    /** One complaint open for more than a day. `openedAt` is its newest re-open, else when it was written. */
    public record TodayComplaint(String id, String childName, String title, String recipientName, long openedAt,
                                 int ageHours) {}

    /** Every overdue complaint the caller reaches, and the oldest ten of them. */
    public record TodayComplaints(int total, List<TodayComplaint> items) {}

    public record TodaySender(String threadId, String name) {}

    /** The caller's own unread messages, and the three latest people waiting for a reply. */
    public record TodayUnread(int count, List<TodaySender> senders) {}

    /** `GET /me/today`. `attendance` is null — not empty — for a school with `staffAttendance` off. */
    public record TodayHome(String day, String timeZone, TodayAttendance attendance, TodayWaiting waiting,
                            TodayComplaints overdueComplaints, TodayUnread unread, List<HomeDto.HomeCard> counts) {}
}
