package quest.feature.journey.presentation

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.datetime.TimeZone
import quest.core.text.localDigits
import quest.feature.map.presentation.examTime
import quest.ui.design.DashboardTokens
import quest.ui.design.Dimens

/**
 * M5 (the owner, 2026-10-03: "Add timer for children"): the exam sitting counts down to the window's close. Only the
 * exam — a lesson or a homework still shows no clock (§7). The time is the server's ([quest.core.platform.ServerClock]),
 * and the server stays the authority: at zero the view model's own window watch ends the sitting on the submitted
 * screen exactly as before; this only draws the time.
 */
object ExamCountdown {
    /** The last five minutes are drawn in the warning colours — a colour, never a flash. */
    const val WARNING_MILLIS = 5 * 60_000L

    /** The minutes at which a screen reader is told how long is left, once each, politely. */
    val ANNOUNCE_AT_MINUTES = listOf(10, 5, 1)

    /**
     * `mm:ss`, or `h:mm:ss` above an hour. Seconds are rounded **up**, so `00:00` appears only once the window has
     * actually shut — a child is never shown zero while the server still takes her answer.
     */
    fun clock(leftMillis: Long): String {
        val total = ((leftMillis.coerceAtLeast(0) + 999) / 1000)
        val h = total / 3600; val m = (total % 3600) / 60; val sec = total % 60
        fun two(n: Long) = n.toString().padStart(2, '0')
        return if (total > 3600) "$h:${two(m)}:${two(sec)}" else "${two(m + h * 60)}:${two(sec)}"
    }

    fun warning(leftMillis: Long): Boolean = leftMillis <= WARNING_MILLIS

    /**
     * The milestone crossed between two ticks, if any: [before] was above it and [now] is at or below it. After a
     * device sleep that skipped several, the smallest is the one worth saying.
     */
    fun milestone(before: Long, now: Long): Int? =
        ANNOUNCE_AT_MINUTES.filter { before > it * 60_000L && now <= it * 60_000L }.minOrNull()

    fun announcement(minutes: Int, s: LessonStrings): String =
        if (minutes == 1) s.examAnnounceOneMinute else s.examAnnounceMinutes.replace("{n}", "$minutes")

    /** How long to wait for the displayed second to change: to the next whole-second boundary of the time left. */
    fun untilNextSecond(leftMillis: Long): Long = ((leftMillis - 1).mod(1000L)) + 1
}

/**
 * The countdown line of an exam sitting: "Time left 12:34" and, beside it, the time of day it closes. It owns the one
 * ticker: the remaining time is state read only here, so a tick recomposes this row and nothing else on the screen.
 */
@Composable
fun ExamCountdownRow(closesAt: Long, now: () -> Long, modifier: Modifier = Modifier) {
    val s = LocalLessonStrings.current
    val arabic = LocalLessonRtl.current
    var left by remember(closesAt) { mutableLongStateOf(closesAt - now()) }
    var announcement by remember(closesAt) { mutableStateOf("") }
    LaunchedEffect(closesAt) {
        while (true) {
            val next = closesAt - now()
            ExamCountdown.milestone(left, next)?.let { announcement = localDigits(ExamCountdown.announcement(it, s), arabic) }
            left = next
            if (next <= 0) break
            delay(ExamCountdown.untilNextSecond(next))
        }
    }
    val warning = ExamCountdown.warning(left)
    val clock = localDigits(ExamCountdown.clock(left), arabic)
    // Formatted once per window, not on every tick.
    val closes = remember(closesAt, s, arabic) { localDigits(s.examClosesAt.replace("{time}", examTime(closesAt, closesAt, TimeZone.currentSystemDefault(), emptyList(), true)), arabic) }
    val shape = RoundedCornerShape(DashboardTokens.radiusSm)
    Row(
        modifier.fillMaxWidth()
            .background(if (warning) DashboardTokens.warningBg else Color.Transparent, shape)
            .border(1.dp, if (warning) DashboardTokens.warningBorder else DashboardTokens.ruleControl, shape)
            .padding(horizontal = Dimens.s12, vertical = Dimens.s8)
            .testTag(if (warning) "exam-countdown-warning" else "exam-countdown"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.s8),
    ) {
        // Read on focus as one phrase; never a live region itself, so nothing is said every second.
        Row(Modifier.weight(1f).clearAndSetSemantics { contentDescription = "${s.examTimeLeftLabel} $clock" }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.s8)) {
            Text(s.examTimeLeftLabel, style = MaterialTheme.typography.labelMedium, color = if (warning) DashboardTokens.warning else DashboardTokens.inkSoft)
            Text(
                clock,
                style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum", fontWeight = FontWeight.SemiBold),
                color = if (warning) DashboardTokens.warning else DashboardTokens.inkStrong,
                maxLines = 1, softWrap = false,
            )
        }
        Text(closes, style = MaterialTheme.typography.labelMedium, color = DashboardTokens.inkSoft, maxLines = 1, modifier = Modifier.semantics { contentDescription = closes })
        // The polite live region: its words change only at 10, 5 and 1 minutes, and that change is what is announced.
        Box(Modifier.size(1.dp).semantics { liveRegion = LiveRegionMode.Polite; contentDescription = announcement })
    }
}
