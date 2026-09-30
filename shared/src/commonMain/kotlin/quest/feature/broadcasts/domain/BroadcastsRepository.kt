package quest.feature.broadcasts.domain

import quest.api.dto.BroadcastFeed
import quest.api.dto.BroadcastView
import quest.api.dto.WeeklyPlanArchive

/**
 * RM4 (DR4, DR6): the one feed a parent reads — the department's weekly plan, and the announcements and events her
 * child's coordinator or manager posted. `docs/runbook.md` "Broadcasts" is the contract.
 *
 * The feed is evaluated server-side on every read against the child's section as it is *now*, so there is nothing to
 * merge here and nothing to invalidate: the app asks, and what comes back is the answer. It is not cached on the
 * device — chat is not either, and a stale plan is worse than an empty screen.
 */
interface BroadcastsRepository {
    /** `GET /children/{id}/broadcasts`, newest first, with the parent's own unread count. 404 while the flag is off. */
    suspend fun feed(childId: String): BroadcastFeed

    /**
     * MH3 `GET /children/{id}/weekly-plans` — the last twelve weeks of plans for the child's grade, past weeks
     * included, with the parent's own unread count. The same flag, so the same 404 while it is off.
     */
    suspend fun plans(childId: String): WeeklyPlanArchive

    /** `POST /children/{id}/broadcasts/{broadcastId}/read` — the row comes back with `read = true`. */
    suspend fun markRead(childId: String, broadcastId: String): BroadcastView
}
