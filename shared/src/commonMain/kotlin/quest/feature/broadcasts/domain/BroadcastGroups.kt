package quest.feature.broadcasts.domain

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import quest.api.dto.BroadcastKind
import quest.api.dto.BroadcastView

/**
 * RM4: how one flat feed becomes the screen — pure, so the shape of the list is a unit test rather than a screenshot.
 *
 * The server already drops expired rows, and the app drops them again against its own clock: a feed fetched before
 * lunch must not still be showing an event that finished at noon while the parent keeps the screen open.
 */

/**
 * The Sunday that starts [date]'s week. `isoDayNumber` is Monday 1 … Sunday 7, and `% 7` turns Sunday back into 0 —
 * the same `previousOrSame(SUNDAY)` rule the server applies to a plan's `weekStart`.
 *
 * **The rule is the same; the input is not.** The server snaps a date the composer typed, with no zone in it at all,
 * while the caller here passes `Today.date()`, which is the *device's* zone (`TimeZone.currentSystemDefault()`).
 * `shared/` has no notion of the school's zone, so a parent whose device sits west of the school can be on Saturday
 * while the school is on Sunday, and this week's plan then falls into [BroadcastGroups.earlierPlans] instead of the
 * pinned card. It is a limitation to record, not something this function can fix: fixing it needs the school's zone
 * on the wire. Expiry is unaffected — that comparison is in epoch millis and carries no zone.
 */
fun weekStartOf(date: LocalDate): LocalDate = date.minus(date.dayOfWeek.isoDayNumber % 7, DateTimeUnit.DAY)

/**
 * [weeklyPlan] is the plan for the week [today] is in — the pinned card. [earlierPlans] are the ones from previous
 * weeks that have not expired: still worth reading, but not the plan.
 */
data class BroadcastGroups(
    val weeklyPlan: BroadcastView? = null,
    val announcements: List<BroadcastView> = emptyList(),
    val events: List<BroadcastView> = emptyList(),
    val earlierPlans: List<BroadcastView> = emptyList(),
) {
    val isEmpty: Boolean
        get() = weeklyPlan == null && announcements.isEmpty() && events.isEmpty() && earlierPlans.isEmpty()
}

/** Groups a feed by kind, hiding whatever has expired by [nowMillis], and pins this week's plan. */
fun groupBroadcasts(items: List<BroadcastView>, today: LocalDate, nowMillis: Long): BroadcastGroups {
    val live = items.filter { row -> row.expiresAt?.let { it > nowMillis } ?: true }
    val thisWeek = weekStartOf(today).toString()
    val plans = live.filter { it.kind == BroadcastKind.WEEKLY_PLAN }
    val pinned = plans.firstOrNull { it.weekStart == thisWeek }
    return BroadcastGroups(
        weeklyPlan = pinned,
        announcements = live.filter { it.kind == BroadcastKind.ANNOUNCEMENT },
        events = live.filter { it.kind == BroadcastKind.EVENT },
        earlierPlans = plans.filter { it.id != pinned?.id },
    )
}

/**
 * The body in the parent's language. A manager writing only English is the normal case, so Arabic falls back to it
 * rather than leaving the card blank — the same rule the announcements card has always used.
 */
fun broadcastBody(view: BroadcastView, arabic: Boolean): String =
    if (arabic) view.bodyAr?.takeIf { it.isNotBlank() } ?: view.bodyEn else view.bodyEn
