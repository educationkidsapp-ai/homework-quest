package quest.feature.staff.domain

import kotlinx.coroutines.flow.SharedFlow
import quest.api.dto.ChatFrame
import quest.api.dto.ChatThread

/**
 * Her Messages tab's half of the chat: the one inbox, and the live frames that move it. It is a port rather than the
 * gateway itself so the socket (which is wiring, not a route) stays out of the view model.
 */
interface StaffThreads {
    val frames: SharedFlow<ChatFrame>

    suspend fun list(): List<ChatThread>

    /** False for a WORKER — nobody is on the other end of a conversation of hers yet. */
    fun hasChat(): Boolean

    fun connect()
}
