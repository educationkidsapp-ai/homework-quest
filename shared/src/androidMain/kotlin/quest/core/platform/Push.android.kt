package quest.core.platform

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import org.koin.core.component.inject
import quest.api.dto.DevicePlatform
import quest.feature.push.domain.PushChannel
import quest.feature.push.domain.PushLinks
import quest.feature.push.domain.PushNotice
import quest.feature.push.domain.PushOpen
import quest.feature.push.domain.PushPayload
import quest.feature.push.domain.PushRegistration
import quest.feature.push.domain.PushTokens
import quest.feature.push.presentation.PushStrings
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
    const val EXTRA_LINK = "quest.push.link"
    const val EXTRA_CHILD_ID = "quest.push.childId"
    const val EXTRA_NOTIFICATION_ID = "quest.push.notificationId"
    const val EXTRA_BROADCAST_ID = "quest.push.broadcastId"

    /** Hands a tap to the shared UI, which follows it underneath the lock and through the parent area's gate. */
    fun follow(intent: Intent?) {
        val extras = intent?.extras ?: return
        if (!extras.getBoolean(EXTRA_PUSH)) return
        PushLinks.open(PushOpen(extras.getString(EXTRA_LINK), extras.getString(EXTRA_NOTIFICATION_ID), extras.getString(EXTRA_BROADCAST_ID), extras.getString(EXTRA_CHILD_ID)))
        // Followed once: a configuration change or a later onNewIntent must not open it again.
        listOf(EXTRA_PUSH, EXTRA_LINK, EXTRA_NOTIFICATION_ID, EXTRA_BROADCAST_ID, EXTRA_CHILD_ID).forEach(intent::removeExtra)
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
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(PushIntents.EXTRA_PUSH, true)
            notice.open.link?.let { putExtra(PushIntents.EXTRA_LINK, it) }
            notice.open.childId?.let { putExtra(PushIntents.EXTRA_CHILD_ID, it) }
            notice.open.notificationId?.let { putExtra(PushIntents.EXTRA_NOTIFICATION_ID, it) }
            notice.open.broadcastId?.let { putExtra(PushIntents.EXTRA_BROADCAST_ID, it) }
        }
        val builder = NotificationCompat.Builder(context, notice.channel.id)
            .setSmallIcon(context.resources.getIdentifier("ic_launcher_monochrome", "drawable", context.packageName).takeIf { it != 0 } ?: context.applicationInfo.icon)
            .setContentTitle(notice.title ?: strings.genericTitle)
            .setAutoCancel(true)
            .setCategory(if (notice.channel == PushChannel.MESSAGES) NotificationCompat.CATEGORY_MESSAGE else NotificationCompat.CATEGORY_EVENT)
            .setContentIntent(open?.let { PendingIntent.getActivity(context, notice.tag.hashCode(), it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) })
        notice.body?.let { builder.setContentText(it).setStyle(NotificationCompat.BigTextStyle().bigText(it)) }
        manager.notify(notice.tag, NOTIFICATION_ID, builder.build())
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
            }
            manager.createNotificationChannel(NotificationChannel(channel.id, name, NotificationManager.IMPORTANCE_DEFAULT))
        }
    }

    private companion object { const val NOTIFICATION_ID = 4201 }
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
