package quest.feature.children.domain

import quest.feature.broadcasts.domain.AttachmentDocuments
import quest.api.AuthProvider
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

/**
 * Signing out, in the one order that leaves nothing of the parent behind: the session, the selected child, and the
 * documents she downloaded (a weekly plan PDF sits in the cache the system viewer can read). Every sign-out button
 * goes through here so the three cannot drift apart.
 */
class SignOutUseCase(private val auth: AuthProvider, private val children: ChildrenRepository, private val documents: AttachmentDocuments) {
    suspend operator fun invoke() {
        auth.signOut()
        children.clear()
        documents.clear()
    }
}
