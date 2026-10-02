package quest.core.platform

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.serialization.encodeToString
import quest.feature.today.domain.TodayJson
import quest.feature.journey.presentation.LessonCopy
import quest.feature.today.domain.ExamSitting
import quest.feature.today.domain.ExamSittingPresenter
import quest.feature.today.domain.TodaySnapshot
import quest.feature.today.domain.TodaySnapshotStore

/**
 * The snapshot as one JSON string in the app's own preferences — the widget runs in the app's process, so nothing is
 * shared with anyone — followed by a broadcast to this package that the widget's receiver answers by redrawing.
 */
class AndroidTodaySnapshotStore(private val context: Context) : TodaySnapshotStore {
    override suspend fun write(snapshot: TodaySnapshot?) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (snapshot == null) prefs.remove(KEY) else prefs.putString(KEY, TodayJson.encodeToString(snapshot))
        prefs.apply()
        context.sendBroadcast(Intent(ACTION_CHANGED).setPackage(context.packageName))
    }

    companion object {
        const val PREFS = "today_widget"
        const val KEY = "snapshot"
        const val ACTION_CHANGED = "quest.today.SNAPSHOT_CHANGED"
        /** The intent extra a widget tap carries into `MainActivity`: a `TodayLink` key. */
        const val EXTRA_LINK = "quest.today.link"

        fun read(context: Context): TodaySnapshot? =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)?.let { runCatching { TodayJson.decodeFromString<TodaySnapshot>(it) }.getOrNull() }
    }
}

/**
 * An exam sitting as an ongoing notification: the exam, the student, how many questions are answered, and — when the
 * window's end is known — a countdown the *system* draws (a chronometer), so the app posts nothing per second. It is
 * silent, cannot be swiped away while the sitting lasts, and takes itself down when the window closes.
 *
 * Android 13+ asks before an app may post: the question is put the first time a sitting starts — the one moment its
 * purpose is obvious — and a "no" is respected; the exam itself never depends on it.
 */
class AndroidExamSittingPresenter(private val context: Context, private val copy: LessonCopy) : ExamSittingPresenter {
    private val manager get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private var asked = false

    override fun show(sitting: ExamSitting) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            if (!asked) { asked = true; BiometricHost.current()?.let { ActivityCompat.requestPermissions(it, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_CODE) } }
            return
        }
        val strings = copy.strings()
        manager.createNotificationChannel(NotificationChannel(CHANNEL, strings.examNotificationChannel, NotificationManager.IMPORTANCE_LOW))
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(context.resources.getIdentifier("ic_launcher_monochrome", "drawable", context.packageName).takeIf { it != 0 } ?: context.applicationInfo.icon)
            .setContentTitle(sitting.title)
            .setContentText("${sitting.childName} · ${strings.questionsAnswered.replace("{done}", "${sitting.answered}").replace("{total}", "${sitting.total}")}")
            .setSubText(strings.examInProgress)
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(sitting.total, sitting.answered, false)
            .setContentIntent(open?.let { PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) })
        sitting.closesAt?.let { end ->
            builder.setWhen(end).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
            builder.setTimeoutAfter((end - System.currentTimeMillis()).coerceAtLeast(1_000))
        }
        manager.notify(NOTIFICATION_ID, builder.build())
    }

    override fun end() = manager.cancel(NOTIFICATION_ID)

    private companion object {
        const val CHANNEL = "exam_sitting"
        const val NOTIFICATION_ID = 4101
        const val REQUEST_CODE = 4102
    }
}
