package quest.api.dto

import kotlinx.serialization.Serializable

/**
 * B4 `backend/fcm-push` — push notifications for parents over Firebase Cloud Messaging. `docs/runbook.md` "Push
 * notifications" is the client contract.
 *
 * The app registers its FCM registration token with `POST /me/devices` after the parent signs in (and again whenever
 * Firebase hands it a new one), and takes it back with `DELETE /me/devices/{token}` on sign-out. A token belongs to one
 * parent at a time: registering it as somebody else moves it, so a shared phone never shows the previous parent's news.
 */
@Serializable
enum class DevicePlatform { ANDROID, IOS }

/**
 * `POST /me/devices` — upsert by [token]. [locale] is a BCP 47 tag (`en`, `ar`, `ar-SA`): the push's [PushMessage.title]
 * and [PushMessage.body] are in Arabic when it starts with `ar` and the server holds an Arabic text, English otherwise.
 * Answers 204; a parent keeps at most ten devices and the one seen longest ago is dropped for an eleventh.
 */
@Serializable
data class RegisterDeviceRequest(
    val token: String,
    val platform: DevicePlatform,
    val appVersion: String? = null,
    val locale: String? = null,
)

/**
 * What one push carries: the FCM **data** map (string → string), the same on every platform. On Android the message is
 * data-only at high priority, so `FirebaseMessagingService.onMessageReceived` runs in the foreground, the background and
 * after the app was swiped away, and the app draws the notification itself — its own channel, its own wording from
 * [kind] in the language the phone is in now, and a tap that opens [link]. On iOS (once an APNs key is uploaded to the
 * Firebase project) the same map rides beside an APNs alert built from [title] and [body], because iOS does not wake a
 * closed app for a data-only push.
 *
 * - [kind] — the [NotificationKind] serial name: `chat.message`, `exam.released`, `homework.published` or
 *   `broadcast.posted` (a weekly plan, announcement or event that reached her child).
 * - [notificationId] — the `/me/notifications` row the push is about (mark it read with
 *   `POST /me/notifications/{id}/read`); absent on `broadcast.posted`, which has no row — its id is [broadcastId] and it
 *   is read with `POST /children/{childId}/broadcasts/{broadcastId}/read`.
 * - [childId] — the child it is about; [link] — the same app path `/me/notifications` carries
 *   (`/children/{id}/chat/{staffId}`, `…/progress`, `…/map`, and `…/broadcasts?open={broadcastId}`).
 * - [collapseKey] — one per thread (`chat:{threadId}`), lesson (`lesson:{lessonId}`) or broadcast
 *   (`broadcast:{broadcastId}`); use it as the notification tag so a newer push replaces the older one in the shade.
 */
data class PushMessage(
    val kind: NotificationKind,
    val title: String,
    val body: String? = null,
    val notificationId: String? = null,
    val childId: String? = null,
    val link: String? = null,
    val broadcastId: String? = null,
    val collapseKey: String,
) {
    /** The FCM data map; absent values are left out rather than sent as empty strings. */
    fun toData(): Map<String, String> = buildMap {
        put(KIND, kindName(kind)); put(TITLE, title); put(COLLAPSE_KEY, collapseKey)
        body?.let { put(BODY, it) }; notificationId?.let { put(NOTIFICATION_ID, it) }; childId?.let { put(CHILD_ID, it) }
        link?.let { put(LINK, it) }; broadcastId?.let { put(BROADCAST_ID, it) }
    }

    companion object {
        const val KIND = "kind"; const val TITLE = "title"; const val BODY = "body"; const val NOTIFICATION_ID = "notificationId"
        const val CHILD_ID = "childId"; const val LINK = "link"; const val BROADCAST_ID = "broadcastId"; const val COLLAPSE_KEY = "collapseKey"

        /** The wire name of a kind (`chat.message`), as `/me/notifications` writes it. */
        fun kindName(kind: NotificationKind): String = NotificationKind.serializer().descriptor.getElementName(kind.ordinal)

        /** The push behind an FCM data map, or null when it is not one of ours (an unknown kind, no title). */
        fun fromData(data: Map<String, String>): PushMessage? {
            val kind = NotificationKind.entries.firstOrNull { kindName(it) == data[KIND] } ?: return null
            return PushMessage(kind, data[TITLE] ?: return null, data[BODY], data[NOTIFICATION_ID], data[CHILD_ID], data[LINK],
                data[BROADCAST_ID], data[COLLAPSE_KEY] ?: return null)
        }
    }
}
