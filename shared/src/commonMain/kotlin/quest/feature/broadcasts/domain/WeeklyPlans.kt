package quest.feature.broadcasts.domain

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.plus
import quest.api.dto.BroadcastAttachment
import quest.api.dto.BroadcastView
import quest.api.dto.WeeklyPlanArchive

/**
 * MH3: the weekly-plan archive as the page draws it. Pure, so the shape of the page is a unit test rather than a
 * screenshot — the same bargain [BroadcastGroups] makes for the announcements feed.
 *
 * A plan is now "one grade's week as an image" (MH1): `GET /children/{id}/weekly-plans` answers the weeks the child's
 * section was an audience of, newest first, past weeks included, and the page pins the week the parent is in and
 * collapses the rest.
 */

/** `isoDayNumber` is Monday 1 … Sunday 7, so Saturday is 6 — the eve of the school week the server snaps to. */
private const val SATURDAY = 6

/** One week of the archive: its Sunday, and the plans in it that are not the pinned one. */
data class PlanWeek(val weekStart: String, val plans: List<BroadcastView>)

/**
 * [current] is the plan for the week [weeklyPlans] was asked about — the pinned image. [earlier] is every other week,
 * newest first, as collapsed rows.
 */
data class WeeklyPlans(val current: BroadcastView? = null, val earlier: List<PlanWeek> = emptyList()) {
    val isEmpty: Boolean get() = current == null && earlier.isEmpty()
}

/**
 * Splits [archive] into the pinned plan and the earlier weeks. Within a week the plan for [grade] wins over the
 * department-wide plan an older server may still be serving, because the grade's is the one the parent's child is in.
 *
 * **On Saturday next week's plan is pinned when it exists** — #171's rule, which arrived on the announcements feed and
 * moves here with the plans. Saturday is the eve of the school week, the manager has usually posted by then, and a
 * device west of the school is on Saturday while the school is already on Sunday; either way the plan the parent wants
 * on Saturday is the one for the week about to start. Without it, this week's is pinned as on any other day.
 */
fun weeklyPlans(archive: WeeklyPlanArchive, today: LocalDate, grade: Int?): WeeklyPlans {
    val thisWeek = weekStartOf(today).toString()
    val nextWeek = weekStartOf(today.plus(7, DateTimeUnit.DAY)).toString()
    val weeks = archive.weeks.map { week -> PlanWeek(week.weekStart, week.items.map { it.plan }) }
    fun pin(week: String) = weeks.firstOrNull { it.weekStart == week }
        ?.let { found -> found.plans.firstOrNull { it.grade == grade } ?: found.plans.firstOrNull() }
    val current = (if (today.dayOfWeek.isoDayNumber == SATURDAY) pin(nextWeek) else null) ?: pin(thisWeek)
    return WeeklyPlans(
        current = current,
        earlier = weeks.mapNotNull { week ->
            week.plans.filter { it.id != current?.id }.takeIf { it.isNotEmpty() }?.let { PlanWeek(week.weekStart, it) }
        },
    )
}

/**
 * Whether an attachment is an image this app can draw. The server sniffs [BroadcastAttachment.type] from the bytes, so
 * it is the honest answer; the extension is the fallback for a row written before MH1, which carries no type.
 */
val BroadcastAttachment.isImage: Boolean
    get() = type?.startsWith("image/") == true ||
        (type == null && name?.substringAfterLast('.', "")?.lowercase() in setOf("png", "jpg", "jpeg", "webp"))

/**
 * Whether an attachment is somebody else's page rather than ours: #171 lets the platform open an `http(s)` URL a
 * composer typed, and only those — `/media/attachments/{id}` is authenticated, so the system viewer would send no
 * token and land on a 401.
 */
val BroadcastAttachment.isWebUrl: Boolean
    get() = url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)

/**
 * The bytes of an image attachment, downloaded with the parent's bearer and kept on the device. Implemented in `data`
 * over the app's Ktor client; [NoAttachmentImages] under tests, screenshots and previews, so nothing is ever fetched
 * off-app from a composition.
 */
fun interface AttachmentImages {
    /** The image, or null when there is nothing to fetch, the fetch failed, or the parent is signed out. */
    suspend fun load(attachment: BroadcastAttachment): ByteArray?
}

object NoAttachmentImages : AttachmentImages {
    override suspend fun load(attachment: BroadcastAttachment): ByteArray? = null
}
