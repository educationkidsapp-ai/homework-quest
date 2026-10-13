package quest.feature.auth.domain

import kotlinx.coroutines.flow.StateFlow
import quest.feature.staff.domain.SessionSide

/**
 * Feature 13: **one sign-in screen for everybody.**
 *
 * The same email (or a worker's generated username) and password are offered to the staff sign-in first and to
 * Firebase second, and each success adds a side. That order matters: a worker's login is not an email address, so
 * Firebase would refuse it out of hand, while a parent's address is unknown to `/auth/sign-in` — trying both is what
 * lets the two kinds of account share a screen without asking anybody which they are.
 *
 * The screen only ever sees this interface, so it holds no knowledge of either implementation.
 */
interface SessionRoles {
    /** The sides a restored or freshly signed-in session holds. Empty means signed out. */
    val sides: StateFlow<Set<SessionSide>>

    /** The side the app is showing, remembered across launches; null until there is a session. */
    val side: StateFlow<SessionSide?>

    /**
     * Her staff password is still the temporary one. True after a restore as well as after a sign-in, so the
     * change-password step cannot be skipped by killing the app on it.
     */
    val mustChangeStaffPassword: StateFlow<Boolean>

    suspend fun signIn(login: String, password: String): SignIn

    /** Switches the Staff/Parent view and remembers it. Ignored for a side that is not signed in. */
    suspend fun choose(side: SessionSide)

    /** `POST /auth/change-password` for the staff side, then the staff home. */
    suspend fun changeStaffPassword(currentPassword: String, newPassword: String)

    /** Signs both sides out, unregisters the push token and forgets the remembered side. */
    suspend fun signOut()
}

/**
 * What one attempt produced. [sides] empty with a null [error] cannot happen: either something signed in or the
 * screen is told why not.
 */
data class SignIn(
    val sides: Set<SessionSide> = emptySet(),
    /** Her temporary password must be replaced before she reaches the staff home (`SignInResponse`). */
    val mustChangePassword: Boolean = false,
    /**
     * Her credentials were right but her school has `staffMobileApp` off, so there is no staff side to show. With a
     * parent side beside it the app simply opens that one; alone, the screen says so in one line.
     */
    val staffDisabled: Boolean = false,
    val error: String? = null,
) {
    val signedIn: Boolean get() = sides.isNotEmpty()
}
