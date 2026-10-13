package quest.feature.staff.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import quest.api.AuthProvider
import quest.api.AuthState
import quest.core.db.SettingsStore
import quest.core.runCancellable
import quest.feature.auth.domain.SessionRoles
import quest.feature.auth.domain.SignIn
import quest.feature.staff.domain.SessionSide

/**
 * Feature 13: the orchestrator behind the one sign-in screen.
 *
 * The credentials go to the staff sign-in first and to Firebase second, and each success adds a side. Neither failure
 * is reported while the other succeeded: a teacher whose child is at the school holds both, a worker holds only the
 * staff side, a parent only the parent one, and the screen's error line is for the case where nothing worked.
 *
 * Signing the staff side in is not enough to *show* it: `GET /staff/me` has to answer, because that is where the
 * school's flags come from and a school with `staffMobileApp` off has no staff app at all. When it refuses, the staff
 * session is dropped again — otherwise the app would hold a token for tabs it cannot draw.
 */
class SessionRolesImpl(
    private val staff: StaffAuth,
    private val parent: AuthProvider,
    private val session: StaffSession,
    private val settings: SettingsStore,
    /** Feature 13: the device token follows the side that signed in (`PushRegistration.signedIn`). */
    private val onSignedIn: suspend () -> Unit = {},
    /** The parent half of signing out — today's `SignOutUseCase`, which also withdraws the push token. */
    private val parentSignOut: suspend () -> Unit = {},
) : SessionRoles {

    private val _sides = MutableStateFlow<Set<SessionSide>>(emptySet())
    override val sides: StateFlow<Set<SessionSide>> = _sides.asStateFlow()

    private val _side = MutableStateFlow<SessionSide?>(null)
    override val side: StateFlow<SessionSide?> = _side.asStateFlow()

    /** The staff session's own answer — it keeps the flag with the refresh token, so a restore has it too. */
    override val mustChangeStaffPassword: StateFlow<Boolean> = staff.mustChangePassword

    /** After `AppInitializer` restored both halves: which sides came back, and which one she was last on. */
    suspend fun restored() {
        if (staff.signedIn) {
            runCancellable { session.load() }
            // Only a *refusal* takes the staff side away: a launch with no network must still open her tabs, with
            // the flags they shipped with, rather than send her back to the sign-in screen.
            if (session.disabled.value) runCancellable { staff.signOut() }
        }
        val staffBack = staff.signedIn
        _sides.value = buildSet {
            if (staffBack) add(SessionSide.STAFF)
            if (parent.state.value is AuthState.SignedIn) add(SessionSide.PARENT)
        }
        val remembered = SessionSide.of(settings.get(SettingsStore.KEY_SESSION_SIDE))
        _side.value = quest.feature.staff.domain.RoleRouter.side(_sides.value, remembered, staff.mustChangePassword.value)
    }

    override suspend fun signIn(login: String, password: String): SignIn {
        val staffAttempt = attempt { staff.signIn(login, password) }
        var staffOk = staffAttempt == null
        var disabled = false
        if (staffOk) {
            // The flags, the name and the role all arrive here, and so does "this school does not have the staff app".
            if (!session.load()) { staffOk = false; disabled = session.disabled.value; runCancellable { staff.signOut() } }
        }
        val mustChange = staffOk && staff.mustChangePassword.value
        val parentAttempt = attempt { parent.signIn(login, password) }
        val parentOk = parentAttempt == null

        val found = buildSet {
            if (staffOk) add(SessionSide.STAFF)
            if (parentOk) add(SessionSide.PARENT)
        }
        _sides.value = found
        if (found.isEmpty()) {
            return SignIn(staffDisabled = disabled, error = if (disabled) null else (staffAttempt ?: parentAttempt))
        }
        // A fresh sign-in has nothing remembered yet: the staff side opens when she has one (the switch is on it).
        val preferred = if (SessionSide.STAFF in found) SessionSide.STAFF else SessionSide.PARENT
        remember(preferred)
        onSignedIn()
        return SignIn(sides = found, mustChangePassword = mustChange, staffDisabled = disabled)
    }

    override suspend fun choose(side: SessionSide) {
        if (side !in _sides.value) return
        remember(side)
    }

    override suspend fun changeStaffPassword(currentPassword: String, newPassword: String) {
        staff.changePassword(currentPassword, newPassword, login = (staff.state.value as? AuthState.SignedIn)?.email.orEmpty())
        session.load()
    }

    override suspend fun signOut() {
        parentSignOut()
        if (staff.signedIn) runCancellable { staff.signOut() }
        session.clear()
        settings.set(SettingsStore.KEY_SESSION_SIDE, null)
        _sides.value = emptySet()
        _side.value = null
    }

    private suspend fun remember(side: SessionSide) {
        settings.set(SettingsStore.KEY_SESSION_SIDE, side.key)
        _side.value = side
    }

    /** Null when it worked, the message to show when it did not. */
    private suspend fun attempt(block: suspend () -> Unit): String? = try {
        block()
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        e.message ?: "Could not sign in."
    }
}
