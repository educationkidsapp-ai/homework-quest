package quest.feature.broadcasts.domain

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import quest.api.dto.BroadcastKind
import quest.api.dto.BroadcastView

/**
 * RM4: how one flat feed becomes the screen — pure, so the shape of the list is a unit test rather than a screenshot.
 * MH3 narrowed it to the **Announcements** page: the weekly plans left for [WeeklyPlans], which reads the archive.
 */

/**
 * The Sunday that starts [date]'s week. `isoDayNumber` is Monday 1 … Sunday 7, and `% 7` turns Sunday back into 0 —
 * the same `previousOrSame(SUNDAY)` rule the server applies to a plan's `weekStart`.
 *
 * **The rule is the same; the input is not.** The server snaps a date the composer typed, with no zone in it at all,
 * while the caller here passes `Today.date()`, which is the *device's* zone (`TimeZone.currentSystemDefault()`).
 * On Saturday, if next week's plan is already published (or the device sits in a timezone west of the school on
 * Saturday night), [weeklyPlans] pins next week's plan if present, falling back to this week's plan — the rule #171
 * put on the feed, which MH3 moved with the plans onto their own page. Expiry is unaffected — that comparison is in
 * epoch millis and carries no zone.
 */
fun weekStartOf(date: LocalDate): LocalDate = date.minus(date.dayOfWeek.isoDayNumber % 7, DateTimeUnit.DAY)

/**
 * The Announcements page: what the school said, and what is happening. **No weekly plans** — MH3 gave those a page of
 * their own, and [announcementRows] drops them here.
 */
data class BroadcastGroups(
    val announcements: List<BroadcastView> = emptyList(),
    val events: List<BroadcastView> = emptyList(),
) {
    val isEmpty: Boolean get() = announcements.isEmpty() && events.isEmpty()
}

/**
 * The rows the Announcements page owns: not a weekly plan, and not expired by [nowMillis].
 *
 * **The plans are filtered here rather than server-side.** `BroadcastService.forChild` answers every live row for the
 * child's section, plans included, and MH1 did not change that — so the page that no longer shows them has to say so
 * itself. Expiry is dropped a second time against the device clock: a feed fetched before lunch must not still be
 * showing an event that finished at noon while the parent keeps the screen open.
 */
fun announcementRows(items: List<BroadcastView>, nowMillis: Long): List<BroadcastView> =
    items.filter { it.kind != BroadcastKind.WEEKLY_PLAN && (it.expiresAt?.let { at -> at > nowMillis } ?: true) }

/**
 * The Announcements badge. [quest.api.dto.BroadcastFeed.unread] counts the plans too, and those are the Weekly plan
 * badge's business, so the count the parent home shows beside *Announcements* is made here from the rows themselves.
 */
fun unreadAnnouncements(items: List<BroadcastView>, nowMillis: Long): Int =
    announcementRows(items, nowMillis).count { !it.read }

/** Groups a feed by kind, hiding the plans and whatever has expired by [nowMillis]. */
fun groupBroadcasts(items: List<BroadcastView>, nowMillis: Long): BroadcastGroups {
    val live = announcementRows(items, nowMillis)
    return BroadcastGroups(
        announcements = live.filter { it.kind == BroadcastKind.ANNOUNCEMENT },
        events = live.filter { it.kind == BroadcastKind.EVENT },
    )
}

/**
 * The body in the parent's language. A manager writing only English is the normal case, so Arabic falls back to it
 * rather than leaving the card blank — the same rule the announcements card has always used.
 */
fun broadcastBody(view: BroadcastView, arabic: Boolean): String =
    if (arabic) view.bodyAr?.takeIf { it.isNotBlank() } ?: view.bodyEn else view.bodyEn
