package quest.api.dashboard

import kotlinx.serialization.Serializable
import quest.api.staff.AttendanceRequestView

/**
 * Feature 21 `admin-today-home` (docs/features/21-admin-today-home.md): one answer to "what needs me today?" for the
 * Admin and for a department manager — `GET /me/today`, permission `home.today`, behind the `todayHome` flag.
 *
 * **One call, cards in order.** Today's staff attendance, what waits for the caller's decision, the complaints that
 * have been open for more than a day, her unread messages, and last the counts her Home used to lead with. Every
 * section is built by the service that already owns it and is already narrowed to the caller: the Admin reads the
 * school, a manager reads her academy ([TodayAttendance.scope]), and a request she may not decide or a complaint
 * outside her reach is simply not in the answer. A card with nothing in it is empty, not absent, so a client draws
 * the calm "nothing needs you now" line when all four are.
 *
 * [TodayHome.day] is `yyyy-MM-dd` in [TodayHome.timeZone], the school's zone — never the server's and never the
 * browser's. Every instant on these shapes is epoch milliseconds, as everywhere else in the contract.
 */

/**
 * The Staff attendance board's five counts for today, with how wide the caller's view of it is ([BoardScope]).
 * Absent from [TodayHome] altogether when the school has `staffAttendance` off.
 */
@Serializable
data class TodayAttendance(val scope: String, val counts: BoardCounts)

/**
 * An employee whose check-in was refused today because the phone in her hand is not the one bound to her
 * (`CheckInRefusal.DEVICE_NOT_APPROVED`), and whose binding has not been reset since. There is no "device reset
 * request" in the system — this *is* the signal — so the card's action is the Admin's existing
 * `DELETE /admin/staff/{kind}/{id}/attendance-device`. [kind] is `USER` or `WORKER`, as in [StaffAttendanceProfile].
 *
 * **Admin only.** Only she holds `attendance.device.reset`, so a manager's answer never carries one.
 */
@Serializable
data class TodayDeviceBlock(val kind: String, val id: String, val fullName: String, val lastAttemptAt: Long)

/**
 * What waits for the caller's decision: the remote-day and correction requests she may decide (feature 18, already
 * limited to those by the server) and the device bindings only an Admin can clear.
 */
@Serializable
data class TodayWaiting(
    val requests: List<AttendanceRequestView> = emptyList(),
    val devices: List<TodayDeviceBlock> = emptyList(),
)

/**
 * One complaint open for more than 24 hours. [openedAt] is when it was last opened — the newest re-open in its
 * history, else when the parent wrote it — and [ageHours] the whole hours since, so a client shows the age without
 * doing the arithmetic in the browser's own zone.
 */
@Serializable
data class TodayComplaint(
    val id: String,
    val childName: String,
    val title: String,
    val recipientName: String,
    val openedAt: Long,
    val ageHours: Int,
)

/** [total] is every overdue complaint the caller reaches; [items] the oldest ten of them, oldest first. */
@Serializable
data class TodayComplaints(val total: Int, val items: List<TodayComplaint> = emptyList())

/** One of the latest people waiting for a reply, named by the thread to open. */
@Serializable
data class TodaySender(val threadId: String, val name: String)

/** [count] is the caller's own unread messages across her Messages threads; [senders] the three latest of them. */
@Serializable
data class TodayUnread(val count: Int, val senders: List<TodaySender> = emptyList())

/** `GET /me/today`. [counts] is the strip the old Home led with ([HomeResponse.cards] for the caller's role). */
@Serializable
data class TodayHome(
    val day: String,
    val timeZone: String,
    val attendance: TodayAttendance? = null,
    val waiting: TodayWaiting = TodayWaiting(),
    val overdueComplaints: TodayComplaints = TodayComplaints(0),
    val unread: TodayUnread = TodayUnread(0),
    val counts: List<HomeCard> = emptyList(),
)
