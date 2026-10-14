package quest.feature.staff.presentation

import kotlinx.datetime.Clock
import quest.api.ApiException
import quest.api.dto.ApiError
import quest.api.staff.CheckInOutcome
import quest.api.staff.CheckInRefusal
import quest.api.staff.CheckInRequest
import quest.api.staff.CheckInResult
import quest.api.staff.CheckInToday
import quest.core.mvi.MviEffect
import quest.core.mvi.MviIntent
import quest.core.mvi.MviState
import quest.core.mvi.MviViewModel
import quest.core.platform.BiometricAuthenticator
import quest.core.platform.BiometricResult
import quest.core.platform.LocationReader
import quest.feature.staff.domain.CheckInPrefs

/**
 * What the Today card says after a tap, as data rather than as a sentence: the view model never holds a string, so one
 * table of words ([StaffStrings.checkInMessage]) says it in Arabic and in English and a test can assert the outcome
 * without reading either.
 */
sealed interface CheckInMessage {
    /** A recorded check-in. [lateMinutes] is 0 or null for "on time" (the grace period is the server's). */
    data class Recorded(val time: String, val lateMinutes: Int?) : CheckInMessage
    data class CheckedOut(val time: String) : CheckInMessage
    /** The second tap of the day: nothing changed, and [time] is the check-in already on the record. */
    data class Already(val time: String) : CheckInMessage
    data class Outside(val distanceMeters: Int?) : CheckInMessage
    data object WeakAccuracy : CheckInMessage
    data object MockLocation : CheckInMessage
    data object DeviceNotApproved : CheckInMessage
    data object NoGeofence : CheckInMessage
    data object NotCheckedIn : CheckInMessage
    data object NoPermission : CheckInMessage
    data object Offline : CheckInMessage
    data object Failed : CheckInMessage
}

object CheckInContract {
    data class State(
        val loading: Boolean = true,
        val today: CheckInToday? = null,
        /** A tap is in flight: the permission, the fix, the prompt or the request. The button is one at a time. */
        val busy: Boolean = false,
        /** The privacy rationale, shown once before the first fix is ever taken. */
        val rationale: Boolean = false,
        val message: CheckInMessage? = null,
        /** The school has the feature but this employee's routes answered 404 — the switch went off under her. */
        val notEnabled: Boolean = false,
    ) : MviState {
        val checkedIn: Boolean get() = today?.checkIn != null
        val checkedOut: Boolean get() = today?.checkOut != null
        /** One main button: Check in before arrival, Check out after it. */
        val checkingOut: Boolean get() = checkedIn
    }

    sealed interface Intent : MviIntent {
        data object Load : Intent
        /** The one button. The second tap of the two is the device's own prompt. */
        data object Tap : Intent
        /** "Continue" on the privacy rationale: it is never shown again, and the tap goes on. */
        data object RationaleAccepted : Intent
        data object RationaleDismissed : Intent
        data object DismissMessage : Intent
    }

    sealed interface Effect : MviEffect
}

/**
 * Feature 15 on the phone: one tap, then the device's own prompt, then the server decides.
 *
 * The order is the spec's and it matters. The permission and the fix come first, because a refusal there is a state
 * the card words rather than an attempt; **the biometric comes last before the request**, so a cancelled prompt sends
 * nothing at all — no attempt row, no refusal, nothing for the employee to explain. The prompt is
 * [BiometricAuthenticator] directly and not `AppLock.confirmOwner`, which answers false when the app lock is off and
 * would make the check-in impossible for an employee who never turned it on.
 *
 * Nothing about the fix is judged here: the accuracy, the mock flag and the distance all go up as they were read, and
 * the only thing the app refuses on its own is a fix that never arrived. The time in every message is the server's.
 */
class CheckInViewModel(
    private val api: quest.feature.staff.domain.StaffGateway,
    private val location: LocationReader,
    private val biometrics: BiometricAuthenticator,
    private val prefs: CheckInPrefs,
) : MviViewModel<CheckInContract.State, CheckInContract.Intent, CheckInContract.Effect>(CheckInContract.State()) {

    override suspend fun handle(intent: CheckInContract.Intent) {
        when (intent) {
            CheckInContract.Intent.Load -> load()
            CheckInContract.Intent.Tap -> {
                if (current.busy) return
                if (!prefs.rationaleSeen()) {
                    reduce { copy(rationale = true, message = null) }
                    return
                }
                record()
            }
            CheckInContract.Intent.RationaleAccepted -> {
                prefs.markRationaleSeen()
                reduce { copy(rationale = false) }
                record()
            }
            CheckInContract.Intent.RationaleDismissed -> reduce { copy(rationale = false) }
            CheckInContract.Intent.DismissMessage -> reduce { copy(message = null) }
        }
    }

    private suspend fun load() {
        try {
            val today = api.checkInToday()
            reduce { copy(loading = false, today = today, notEnabled = false) }
        } catch (e: ApiException) {
            if (e.error.code == ApiError.NOT_FOUND) reduce { copy(loading = false, notEnabled = true) }
            else reduce { copy(loading = false, message = e.asMessage()) }
        } catch (_: Throwable) {
            reduce { copy(loading = false, message = CheckInMessage.Offline) }
        }
    }

    private suspend fun record() {
        val checkingOut = current.checkingOut
        reduce { copy(busy = true, message = null) }
        try {
            if (!location.ensurePermission()) {
                reduce { copy(busy = false, message = CheckInMessage.NoPermission) }
                return
            }
            val fix = location.freshFix()
            if (fix == null) {
                // No fix inside the timeout: indoors, GPS off, or an accuracy the provider never settled on. The
                // employee is told to try again, in the same words a weak fix gets, because it is the same remedy.
                reduce { copy(busy = false, message = CheckInMessage.WeakAccuracy) }
                return
            }
            val confirmed = biometrics.authenticate(StaffStrings.forLanguage(prefs.language()).confirmIdentity)
            if (confirmed != BiometricResult.SUCCESS) {
                // Cancelled, failed or unavailable: nothing is sent, and nothing is said — she knows she cancelled.
                reduce { copy(busy = false) }
                return
            }
            val request = CheckInRequest(
                latitude = fix.latitude,
                longitude = fix.longitude,
                accuracyMeters = fix.accuracyMeters,
                mocked = fix.mocked,
                installId = prefs.installId(),
                phoneTime = Clock.System.now().toString(),
            )
            val result = if (checkingOut) api.checkOut(request) else api.checkIn(request)
            reduce { copy(busy = false, message = result.asMessage(checkingOut)) }
            if (result.result != CheckInOutcome.REFUSED) load()
        } catch (e: ApiException) {
            reduce { copy(busy = false, message = e.asMessage()) }
        } catch (_: Throwable) {
            reduce { copy(busy = false, message = CheckInMessage.Offline) }
        }
    }

    /** A refusal is a `200`, so this maps only the outcomes — never an error status. */
    private fun CheckInResult.asMessage(checkingOut: Boolean): CheckInMessage = when (result) {
        CheckInOutcome.RECORDED ->
            if (checkingOut) CheckInMessage.CheckedOut(serverTime.orEmpty())
            else CheckInMessage.Recorded(serverTime.orEmpty(), lateMinutes)
        CheckInOutcome.ALREADY -> CheckInMessage.Already(serverTime.orEmpty())
        else -> when (reason) {
            CheckInRefusal.OUTSIDE -> CheckInMessage.Outside(distanceMeters)
            CheckInRefusal.WEAK_ACCURACY -> CheckInMessage.WeakAccuracy
            CheckInRefusal.MOCK_LOCATION -> CheckInMessage.MockLocation
            CheckInRefusal.DEVICE_NOT_APPROVED -> CheckInMessage.DeviceNotApproved
            CheckInRefusal.NO_GEOFENCE -> CheckInMessage.NoGeofence
            CheckInRefusal.NOT_CHECKED_IN -> CheckInMessage.NotCheckedIn
            else -> CheckInMessage.Failed
        }
    }

    private fun ApiException.asMessage(): CheckInMessage =
        if (error.code == ApiError.NETWORK) CheckInMessage.Offline else CheckInMessage.Failed
}

/**
 * "My record": the current month, one line per day. It asks for nothing but a month, and the month it shows is the
 * one the server answered with — a phone whose clock is wrong still reads the right month's rows.
 */
object MyRecordContract {
    data class State(
        val loading: Boolean = true,
        val month: String? = null,
        val days: List<quest.api.staff.CheckInDay> = emptyList(),
        val failed: Boolean = false,
    ) : MviState

    sealed interface Intent : MviIntent {
        data object Load : Intent
        data object PreviousMonth : Intent
        data object NextMonth : Intent
    }

    sealed interface Effect : MviEffect
}

class MyRecordViewModel(
    private val api: quest.feature.staff.domain.StaffGateway,
) : MviViewModel<MyRecordContract.State, MyRecordContract.Intent, MyRecordContract.Effect>(MyRecordContract.State()) {

    override suspend fun handle(intent: MyRecordContract.Intent) {
        when (intent) {
            MyRecordContract.Intent.Load -> load(null)
            MyRecordContract.Intent.PreviousMonth -> load(shift(current.month, -1) ?: return)
            MyRecordContract.Intent.NextMonth -> load(shift(current.month, +1) ?: return)
        }
    }

    private suspend fun load(month: String?) {
        reduce { copy(loading = true, failed = false) }
        try {
            val answer = api.checkInMonth(month)
            reduce { copy(loading = false, month = answer.month, days = answer.days, failed = false) }
        } catch (_: Throwable) {
            reduce { copy(loading = false, failed = true) }
        }
    }

    private companion object {
        /** `YYYY-MM` a month either way; null while the first month has not been answered yet. */
        fun shift(month: String?, by: Int): String? {
            val parts = month?.split("-") ?: return null
            val year = parts.getOrNull(0)?.toIntOrNull() ?: return null
            val index = parts.getOrNull(1)?.toIntOrNull() ?: return null
            val zeroBased = (year * 12 + (index - 1)) + by
            val nextMonth = zeroBased % 12 + 1
            return "${zeroBased / 12}-${if (nextMonth < 10) "0$nextMonth" else "$nextMonth"}"
        }
    }
}
