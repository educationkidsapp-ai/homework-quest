package quest.android

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import quest.core.platform.AndroidTodaySnapshotStore
import quest.feature.today.domain.TodayLink
import quest.feature.today.domain.TodaySnapshot
import quest.ui.design.DashboardPalette
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * M3 — the home-screen "Today" widget: the selected child, the lessons still to do, the next exam and the parent's
 * unread messages. It draws the snapshot the app last wrote and nothing else: no sign-in, no token, no network.
 *
 * Two layouts, chosen by the size the launcher gives it: small (2×2) shows the counts, medium (4×2) adds the first two
 * lesson titles. Colours are the app's own palette, light and dark with the device.
 */
class TodayWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(SMALL, MEDIUM))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = AndroidTodaySnapshotStore.read(context)
        provideContent { TodayContent(context, snapshot) }
    }

    companion object {
        val SMALL = DpSize(110.dp, 110.dp)
        val MEDIUM = DpSize(250.dp, 110.dp)
    }
}

private val light = DashboardPalette.Light
private val dark = DashboardPalette.Dark
private fun both(pick: (DashboardPalette) -> Color) = ColorProvider(day = pick(light), night = pick(dark))

@Composable
private fun TodayContent(context: Context, snapshot: TodaySnapshot?) {
    val wide = LocalSize.current.width >= TodayWidget.MEDIUM.width
    // The launcher lays the widget out for the device's locale; the words at least sit on the side their language reads from.
    val align = if (snapshot?.rtl == true) TextAlign.End else TextAlign.Start
    fun open(link: TodayLink) = actionStartActivity(
        Intent(context, MainActivity::class.java).putExtra(AndroidTodaySnapshotStore.EXTRA_LINK, link.key).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
    )
    Column(GlanceModifier.fillMaxSize().background(both { it.surface }).cornerRadius(16.dp).padding(12.dp).clickable(open(TodayLink.HOME))) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Image(ImageProvider(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = GlanceModifier.size(28.dp))
            Spacer(GlanceModifier.width(4.dp))
            Text(snapshot?.childName ?: context.getString(R.string.app_name), maxLines = 1, style = TextStyle(color = both { it.inkStrong }, fontSize = 15.sp, fontWeight = FontWeight.Bold))
        }
        Spacer(GlanceModifier.height(6.dp))
        if (snapshot == null) {
            // Signed out: nothing about anyone is left to show.
            Text(context.getString(R.string.widget_signed_out), style = TextStyle(color = both { it.inkSoft }, fontSize = 13.sp))
            return@Column
        }
        val l = snapshot.labels
        Text(
            when (snapshot.lessonsToDo) { 0 -> l.allDone; 1 -> l.oneLessonToDo; else -> l.lessonsToDo.replace("{n}", "${snapshot.lessonsToDo}") },
            maxLines = 1, style = TextStyle(color = both { it.brandInk }, fontSize = 14.sp, fontWeight = FontWeight.Medium, textAlign = align), modifier = GlanceModifier.fillMaxWidth(),
        )
        if (wide) snapshot.lessonTitles.forEach { Text("• $it", maxLines = 1, style = TextStyle(color = both { it.ink }, fontSize = 12.sp, textAlign = align), modifier = GlanceModifier.fillMaxWidth()) }
        snapshot.nextExamTitle?.let { title ->
            Spacer(GlanceModifier.height(4.dp))
            val until = snapshot.nextExamClosesAt?.let { " · " + l.examUntil.replace("{time}", TIME.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()))) }.orEmpty()
            Text("${l.exam}: $title$until", maxLines = if (wide) 1 else 2, style = TextStyle(color = both { it.secondaryInk }, fontSize = 12.sp, fontWeight = FontWeight.Medium, textAlign = align), modifier = GlanceModifier.fillMaxWidth())
        }
        if (snapshot.unreadMessages > 0) {
            Spacer(GlanceModifier.height(4.dp))
            Text(
                if (snapshot.unreadMessages == 1) l.oneUnread else l.unread.replace("{n}", "${snapshot.unreadMessages}"),
                maxLines = 1, style = TextStyle(color = both { it.inkSoft }, fontSize = 12.sp, textAlign = align), modifier = GlanceModifier.fillMaxWidth().clickable(open(TodayLink.MESSAGES)),
            )
        }
    }
}

private val TIME = DateTimeFormatter.ofPattern("HH:mm")

/** Redraws when the system asks and whenever the app has written a new snapshot (or removed it on sign-out). */
class TodayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayWidget()

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != AndroidTodaySnapshotStore.ACTION_CHANGED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch { try { TodayWidget().updateAll(context) } finally { pending.finish() } }
    }
}
