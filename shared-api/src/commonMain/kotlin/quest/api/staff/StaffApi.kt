package quest.api.staff

import kotlinx.serialization.Serializable
import quest.api.dto.ChatMessage
import quest.api.dto.ChatReadReceipt
import quest.api.dto.ChatThread
import quest.api.dto.SendChatMessageRequest

/**
 * Feature 13 — **staff mode in the mobile app**. Staff (Admin, Manager, Coordinator, Teacher and the new Worker) sign
 * in to the same MySchool app the parents use and land on a staff home with four tabs: Attendance (feature 15),
 * Messages, Notifications and Account.
 *
 * `/staff` is a **facade, not a second API**: it is behind the `staffMobileApp` flag and the `staff.app`
 * permission, and every route hands the work to the service the dashboard already calls, so there is one set of
 * scope rules and one set of chat rows. That is also why only [StaffMe] is a new shape — the Messages tab speaks
 * [ChatThread], [ChatMessage], [SendChatMessageRequest] and [ChatReadReceipt], the very types the parent's and the
 * teacher's halves of the chat already use, and a client that renders one renders the other.
 *
 * What is deliberately *not* here:
 * - **Sign-in.** It is `POST /auth/sign-in`, `/auth/refresh` and `/auth/change-password`
 *   (`quest.api.dashboard.DashboardApi`), the same three the dashboard calls. The app tries staff first and Firebase
 *   second, so one screen signs in both sides.
 * - **Typing and live frames.** The existing `/ws/chat` socket, with the staff token and the `threadId` form of
 *   `ChatCommand`.
 * - **Uploads.** The existing `POST /media/chat-attachments`.
 * - **Notifications.** The existing `/me/notifications` routes, which WORKER now holds too.
 * - **Devices.** The existing `POST /me/devices` and `/me/devices/unregister`, which staff now reach as well
 *   (`device.register`); a staff token registers into `staff_devices` rather than `parent_devices`.
 */

/**
 * `GET /staff/me`: who is signed in, which school she is in, and what that school has switched on — everything the
 * staff shell needs before it can draw a tab.
 *
 * [role] is the wire word of her one role (`ADMIN`, `TEACHER`, `MANAGERIAL`, `COORDINATOR`, `WORKER`), and [flags]
 * is the school's effective flag map, the body of `GET /schools/{id}/flags`, so the app gates staff screens from the
 * **staff** school rather than from whichever school a child of hers happens to be in. [phone] is the number the
 * Admin recorded for her, absent when there is none.
 */
@Serializable
data class StaffMe(
    val userId: String,
    val displayName: String,
    val role: String,
    val phone: String? = null,
    val schoolId: String,
    val schoolName: String? = null,
    val flags: Map<String, Boolean> = emptyMap(),
    /** Feature 19: she keeps normal alerts for parent messages outside office hours. Off by default. */
    val afterHoursAlerts: Boolean = false,
)

/** Feature 19: `PUT /staff/me/preferences`. */
@Serializable
data class StaffPreferences(val afterHoursAlerts: Boolean)

/**
 * The routes the staff app calls, all under `staff.app` and the `staffMobileApp` flag. Nothing here takes a
 * school: it is the caller's own, from her token.
 *
 * Nothing in this module implements the interface — it is the shape the server serves and the app calls, as
 * `DashboardApi` is.
 */
interface StaffApi {
    suspend fun staffMe(): StaffMe

    /**
     * `GET /staff/chat/threads`: her conversations, merged from whichever lists her role has and keyed by
     * [ChatThread.id], newest activity first. A TEACHER gets her parent threads *and* her staff threads in one list,
     * a COORDINATOR, a MANAGERIAL or an ADMIN her own inbox, and a WORKER an empty list — workers have no chat
     * partners yet.
     */
    suspend fun staffThreads(): List<ChatThread>

    /** `GET /staff/chat/threads/{threadId}/messages`, paged exactly as the dashboard's lists are. */
    suspend fun staffMessages(threadId: String, before: String? = null, since: String? = null, limit: Int? = null): List<ChatMessage>

    suspend fun staffSend(threadId: String, request: SendChatMessageRequest): ChatMessage

    suspend fun staffRead(threadId: String): ChatReadReceipt

    /** Feature 19: `PUT /staff/me/preferences` — her own after-hours alerts choice; returns the updated [StaffMe]. */
    suspend fun staffPreferences(request: StaffPreferences): StaffMe
}
