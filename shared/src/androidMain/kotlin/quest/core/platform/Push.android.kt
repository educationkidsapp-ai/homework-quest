package quest.core.platform

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.component.inject
import quest.api.dto.DevicePlatform
import quest.feature.push.domain.PushChannel
import quest.feature.push.domain.ATTENDANCE_REQUEST
import quest.feature.push.domain.decidableRequestId
import quest.feature.push.domain.PushLinks
import quest.feature.push.domain.PushNotice
import quest.feature.push.domain.NotificationTap
import quest.feature.push.domain.PushPayload
import quest.feature.push.domain.PushRegistration
import quest.feature.push.domain.PushTokens
import quest.feature.push.presentation.PushStrings
import quest.feature.staff.data.StaffAuth
import quest.feature.staff.domain.ShadeDecision
import quest.feature.staff.domain.shadeDecision
import quest.feature.staff.domain.StaffGateway
import quest.feature.staff.presentation.StaffStrings
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * M5: the FCM token. Push exists only where the app initialised Firebase from its flavor's `google-services.json`
 * (`QuestApplication`); a build without one — the prod flavor today, CI, a laptop — has no default `FirebaseApp`, and
 * [platform] is then null, so nothing is ever registered or asked.
 */
class FcmPushTokens(private val context: Context) : PushTokens {
    override val platform: DevicePlatform? get() = if (FirebaseApp.getApps(context).isNotEmpty()) DevicePlatform.ANDROID else null

    override val appVersion: String? get() = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()

    override suspend fun token(): String? = FirebaseMessaging.getInstance().token.await()

    override suspend fun delete() { FirebaseMessaging.getInstance().deleteToken().await() }
}

/**
 * Called by the app before anything asks for a token, with its flavor's four `google-services.json` identifiers. Any
 * blank value — a flavor with no file — leaves Firebase uninitialised, and push off.
 */
fun initialiseFirebasePush(context: Context, appId: String, projectId: String, senderId: String, apiKey: String) {
    if (listOf(appId, projectId, senderId, apiKey).any { it.isBlank() } || FirebaseApp.getApps(context).isNotEmpty()) return
    FirebaseApp.initializeApp(context, FirebaseOptions.Builder().setApplicationId(appId).setProjectId(projectId).setGcmSenderId(senderId).setApiKey(apiKey).build())
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnCompleteListener { task ->
        val error = task.exception
        if (error != null) cont.resumeWithException(error) else cont.resume(task.result)
    }
}

/** Whether a screen of the app is showing. `MainActivity` keeps it; a push that arrives while true is not posted. */
object AppVisibility {
    @Volatile var visible: Boolean = false
}

/** The extras a tapped push carries into `MainActivity`, and the one way they are read back. */
object PushIntents {
    const val EXTRA_PUSH = "quest.push"
    private const val PREFIX = "quest.push."

    fun put(intent: Intent, tap: NotificationTap): Intent = intent.apply {
        putExtra(EXTRA_PUSH, true)
        putExtra(PREFIX + "kind", tap.kind)
        tap.link?.let { putExtra(PREFIX + "link", it) }
        tap.notificationId?.let { putExtra(PREFIX + "notificationId", it) }
        tap.broadcastId?.let { putExtra(PREFIX + "broadcastId", it) }
        tap.childId?.let { putExtra(PREFIX + "childId", it) }
        tap.collapseKey?.let { putExtra(PREFIX + "collapseKey", it) }
        tap.complaintId?.let { putExtra(PREFIX + "complaintId", it) }
        // Feature 13: a staff push names its thread and nothing else — without this the tap would arrive with no target.
        tap.threadId?.let { putExtra(PREFIX + "threadId", it) }
    }

    /** Hands a tap to the shared UI, which follows it underneath the lock and through the parent gate. */
    fun follow(intent: Intent?) {
        val extras = intent?.extras ?: return
        if (!extras.getBoolean(EXTRA_PUSH)) return
        fun s(key: String) = extras.getString(PREFIX + key)
        PushLinks.open(NotificationTap(s("kind").orEmpty(), s("link"), s("notificationId"), s("broadcastId"), s("childId"), s("collapseKey"), outside = true, complaintId = s("complaintId"), threadId = s("threadId")))
        // Followed once: a configuration change or a later onNewIntent must not open it again.
        intent.removeExtra(EXTRA_PUSH)
    }
}

/**
 * M5: a received push becomes a notification the app draws itself — B4's messages are data-only — on one of five
 * channels named in the app's language (an unknown kind on School news, never dropped), with the monochrome mark, the server's title and body exactly as sent, and the
 * collapse key as its tag so a newer word about the same thread or lesson replaces the older one. While the app is on
 * screen nothing is posted: the badges are refreshed instead (the socket already moves the open screen).
 */
class AndroidPushNotifier(
    private val context: Context,
    private val language: () -> String,
    private val onForeground: () -> Unit,
    private val foreground: () -> Boolean = { AppVisibility.visible },
) {
    private val manager get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun handle(data: Map<String, String>) {
        val notice = PushPayload.parse(data) ?: return
        if (foreground()) onForeground() else post(notice)
    }

    fun post(notice: PushNotice) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val strings = PushStrings.forLanguage(language())
        createChannels(strings)
        // The launcher activity by component, under an action of its own: an intent equal to the task's root (MAIN /
        // LAUNCHER) would only bring the old task forward, re-created from its first intent, and the tap's extras would
        // never arrive.
        val activity = context.packageManager.getLaunchIntentForPackage(context.packageName)?.component
        val open = activity?.let { PushIntents.put(Intent(ACTION_OPEN).setComponent(it), notice.tap) }?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val builder = NotificationCompat.Builder(context, notice.channel.id)
            .setSmallIcon(context.resources.getIdentifier("ic_launcher_monochrome", "drawable", context.packageName).takeIf { it != 0 } ?: context.applicationInfo.icon)
            .setContentTitle(notice.title ?: strings.genericTitle)
            .setAutoCancel(true)
            .setCategory(if (notice.channel == PushChannel.MESSAGES || notice.channel == PushChannel.MESSAGES_QUIET) NotificationCompat.CATEGORY_MESSAGE else NotificationCompat.CATEGORY_EVENT)
            // Feature 19: the channel is what silences it from Android 8 on (the app's floor), and these two say the
            // same thing to the compat builder — low in the shade, no sound and no vibration.
            .setPriority(if (notice.channel.quiet) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_DEFAULT)
            .setSilent(notice.channel.quiet)
            .setContentIntent(open?.let { PendingIntent.getActivity(context, notice.tag.hashCode(), it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) })
        notice.body?.let { builder.setContentText(it).setStyle(NotificationCompat.BigTextStyle().bigText(it)) }
        // Feature 18: a request the reader decides carries Approve and Reject, so a manager settles it from the shade.
        decidableRequestId(notice.tap.kind, notice.tap.link)?.let { requestId ->
            val staff = StaffStrings.forLanguage(language())
            builder.addAction(0, staff.approve, decision(requestId, approve = true, notice = notice))
            builder.addAction(0, staff.reject, decision(requestId, approve = false, notice = notice))
        }
        manager.notify(notice.tag, NOTIFICATION_ID, builder.build())
    }

    /**
     * Feature 18: the same notification, replaced by what the decision did — the tag and the id are the ones the
     * request's own notice used, so the row the manager tapped turns into its outcome rather than stacking under it.
     */
    fun postDecision(tag: String, title: String?, outcome: DecisionOutcome, link: String? = null) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val strings = PushStrings.forLanguage(language())
        val staff = StaffStrings.forLanguage(language())
        createChannels(strings)
        val text = when (outcome) {
            DecisionOutcome.APPROVED -> staff.approved
            DecisionOutcome.REJECTED -> staff.rejected
            DecisionOutcome.FAILED -> staff.openAppToDecide
            // Feature 34: the one outcome she can fix herself, and the words say how.
            DecisionOutcome.REASON_NEEDED -> staff.rejectNeedsReason
        }
        val builder = NotificationCompat.Builder(context, PushChannel.SCHOOL_NEWS.id)
            .setSmallIcon(context.resources.getIdentifier("ic_launcher_monochrome", "drawable", context.packageName).takeIf { it != 0 } ?: context.applicationInfo.icon)
            .setContentTitle(title ?: staff.attTitle)
            .setContentText(text)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
        // Tapping the outcome opens the app, so a failed decision has somewhere to go: her requests inbox.
        val activity = context.packageManager.getLaunchIntentForPackage(context.packageName)?.component
        activity?.let {
            val open = PushIntents.put(
                Intent(ACTION_OPEN).setComponent(it),
                // Feature 34: with the request's own link the tap lands on the notice itself rather than a bare list.
                NotificationTap(ATTENDANCE_REQUEST, link = link, outside = true),
            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP) }
            builder.setContentIntent(
                PendingIntent.getActivity(context, "$tag:decided".hashCode(), open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
            )
        }
        manager.notify(tag, NOTIFICATION_ID, builder.build())
    }

    /** One action's broadcast, immutable and with a request code of its own so Approve never replaces Reject. */
    private fun decision(requestId: String, approve: Boolean, notice: PushNotice): PendingIntent {
        val intent = Intent(context, AttendanceDecisionReceiver::class.java)
            .putExtra(AttendanceDecisionReceiver.EXTRA_REQUEST, requestId)
            .putExtra(AttendanceDecisionReceiver.EXTRA_APPROVE, approve)
            .putExtra(AttendanceDecisionReceiver.EXTRA_TAG, notice.tag)
            .putExtra(AttendanceDecisionReceiver.EXTRA_TITLE, notice.title)
            .putExtra(AttendanceDecisionReceiver.EXTRA_LINK, notice.tap.link)
        return PendingIntent.getBroadcast(
            context,
            "${notice.tag}:${if (approve) "approve" else "reject"}".hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Re-created on every post: an existing channel keeps its settings and only takes the new name (a language change). */
    private fun createChannels(s: PushStrings) {
        PushChannel.entries.forEach { channel ->
            val name = when (channel) {
                PushChannel.MESSAGES -> s.channelMessages
                PushChannel.EXAMS -> s.channelExams
                PushChannel.HOMEWORK -> s.channelHomework
                PushChannel.COMPLAINTS -> s.channelComplaints
                PushChannel.SCHOOL_NEWS -> s.channelSchoolNews
                // Feature 19: named so she can find it in the system settings and make it loud again if she wants.
                PushChannel.MESSAGES_QUIET -> s.channelMessagesQuiet
            }
            val importance = if (channel.quiet) NotificationManager.IMPORTANCE_LOW else NotificationManager.IMPORTANCE_DEFAULT
            manager.createNotificationChannel(
                NotificationChannel(channel.id, name, importance).apply {
                    if (channel.quiet) { setSound(null, null); enableVibration(false) }
                },
            )
        }
    }

    private companion object {
        const val NOTIFICATION_ID = 4201
        const val ACTION_OPEN = "quest.push.OPEN"
    }
}

/**
 * Feature 18: what a decision taken from the shade turned into, now [ShadeDecision] in `commonMain` so feature 34's
 * fourth outcome — a rejection the server refused for want of a reason — is decided by a tested pure function.
 */
typealias DecisionOutcome = ShadeDecision

/**
 * Feature 18: Approve and Reject on an `attendance.request` push. The manager decides without opening the app — the
 * decision goes up on her own staff bearer and the notification is replaced by its outcome, or by "open the app"
 * when the request was already decided, she is not the decider, or the network was not there.
 *
 * `goAsync()` keeps the receiver alive for the one request; an Admin's token names no school, so `GET /staff/me` is
 * asked first to let `RemoteStaffApi` learn the `X-School-Id` it has to send.
 */
class AttendanceDecisionReceiver : BroadcastReceiver(), KoinComponent {
    override fun onReceive(context: Context, intent: Intent) {
        val requestId = intent.getStringExtra(EXTRA_REQUEST)?.takeIf { it.isNotBlank() } ?: return
        val approve = intent.getBooleanExtra(EXTRA_APPROVE, false)
        val tag = intent.getStringExtra(EXTRA_TAG) ?: "attendance:request:$requestId"
        val title = intent.getStringExtra(EXTRA_TITLE)
        // Feature 34: the request's own link, so a rejection that needs a reason reopens the right notice.
        val link = intent.getStringExtra(EXTRA_LINK)
        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            val notifier = get<AndroidPushNotifier>()
            val outcome = runCatching {
                val auth = get<StaffAuth>()
                if (!auth.signedIn) auth.restore()
                if (!auth.signedIn) error("no staff session")
                val api = get<StaffGateway>()
                // An Admin's session knows no school until `/staff/me` answers; the decision routes need the header.
                if (auth.schoolId.value == null) api.staffMe()
                if (approve) api.approveAttendanceRequest(requestId) else api.rejectAttendanceRequest(requestId)
            }
            // Feature 34: the bare Reject above sends no reason, and a leave or a permission may not be rejected
            // without one, so the 400 becomes "open to add a reason" rather than a flat failure.
            notifier.postDecision(tag, title, shadeDecision(approve, outcome.exceptionOrNull()), link)
            result.finish()
        }
    }

    companion object {
        const val EXTRA_REQUEST = "quest.attendance.request"
        const val EXTRA_APPROVE = "quest.attendance.approve"
        const val EXTRA_TAG = "quest.attendance.tag"
        const val EXTRA_TITLE = "quest.attendance.title"
        const val EXTRA_LINK = "quest.attendance.link"
    }
}

/** FCM's entry points: a new token, and a message. Both hand over to the shared rules at once. */
class QuestMessagingService : FirebaseMessagingService(), KoinComponent {
    private val registration: PushRegistration by inject()
    private val notifier: AndroidPushNotifier by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) { scope.launch { registration.newToken(token) } }

    override fun onMessageReceived(message: RemoteMessage) = notifier.handle(message.data)
}

/** Android 13+ asks before an app may post; earlier versions do not, and the card is then never shown. */
@Composable
actual fun rememberNotificationPermission(): NotificationPermission {
    val context = LocalContext.current
    val pending = remember { arrayOfNulls<CompletableDeferred<Boolean>>(1) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> pending[0]?.complete(granted) }
    return remember {
        object : NotificationPermission {
            override val needed: Boolean = Build.VERSION.SDK_INT >= 33
            override fun granted(): Boolean =
                Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            override suspend fun request(): Boolean {
                if (granted()) return true
                val answer = CompletableDeferred<Boolean>().also { pending[0] = it }
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return answer.await()
            }
        }
    }
}
