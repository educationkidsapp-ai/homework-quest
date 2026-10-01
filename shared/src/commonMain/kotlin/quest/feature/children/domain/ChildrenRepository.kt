package quest.feature.children.domain

import kotlinx.coroutines.flow.StateFlow
import quest.api.dto.Child

/**
 * The children the school linked to the signed-in parent; one is "current" (whose home page the app shows). The
 * admin adds and removes them — the app only lists what `GET /children` answers and remembers it for offline use.
 */
interface ChildrenRepository {
    val currentChild: StateFlow<Child?>
    suspend fun refresh(): List<Child>
    suspend fun children(): List<Child>
    suspend fun select(id: String)
    suspend fun clear()
}
