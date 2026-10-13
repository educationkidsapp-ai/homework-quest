package quest.feature.staff.data

import kotlinx.coroutines.flow.StateFlow
import quest.api.AuthProvider
import quest.api.AuthState

/**
 * Feature 13 (fix): the bearer of the side the app is **on**.
 *
 * Everything that fetches bytes rather than JSON — the attachment image loader, the PDF document store — takes an
 * [AuthProvider] and was wired with the parent's (Firebase) one. On a staff-only session that provider has no token,
 * so those loaders returned before they reached the network and a photo in a staff conversation could only say it
 * could not be downloaded. This delegates to the staff session whenever it is signed in and to the parent one
 * otherwise, so one loader serves both sides.
 *
 * [schoolId] goes with the token: an ADMIN's names no school, and the server resolves hers from `X-School-Id`,
 * exactly as [RemoteStaffApi] sends it. It is null on the parent side, which never carries that header.
 */
class SessionAuth(
    private val parent: AuthProvider,
    private val staff: StaffAuth,
) : AuthProvider {

    private val active: AuthProvider get() = if (staff.signedIn) staff else parent

    override val state: StateFlow<AuthState> get() = active.state

    /** The school a staff request names, or null on the parent side. */
    val schoolId: String? get() = if (staff.signedIn) staff.schoolId.value else null

    override suspend fun idToken(forceRefresh: Boolean): String? = active.idToken(forceRefresh)

    /** Signing in and out stay the sides' own business ([SessionRolesImpl]); this is a read-only view of the token. */
    override suspend fun signIn(email: String, password: String) = parent.signIn(email, password)

    override suspend fun signOut() = parent.signOut()
}
