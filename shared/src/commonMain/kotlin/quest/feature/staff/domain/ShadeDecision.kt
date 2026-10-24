package quest.feature.staff.domain

import quest.api.ApiException
import quest.api.dto.ApiError

/**
 * Feature 18: what a decision taken from the Android shade turned into. Feature 34 adds [REASON_NEEDED].
 *
 * **Why a fourth outcome.** The shade's **Reject** button posts no body at all, and a leave or a permission may not
 * be rejected without a reason — the server answers 400 (`AttendanceRequestsController.rejectAttendanceRequest`).
 * Collapsing that onto [FAILED] would have told the manager "open the app to decide" after a decision that was in
 * fact refused for a reason she can fix in one line, so it is its own outcome with its own words
 * ([quest.feature.staff.presentation.StaffStrings.rejectNeedsReason]) and the notification it replaces opens the app
 * on the request. **Approve** from the shade is unchanged: it carries no reason and never needed one.
 */
enum class ShadeDecision { APPROVED, REJECTED, REASON_NEEDED, FAILED }

/**
 * The outcome of one shade decision, from whether it was an approval and what (if anything) the call threw. Pure, so
 * the 400 fallback is a tested answer rather than a branch buried in the Android receiver.
 */
fun shadeDecision(approve: Boolean, failure: Throwable?): ShadeDecision = when {
    failure == null -> if (approve) ShadeDecision.APPROVED else ShadeDecision.REJECTED
    !approve && (failure as? ApiException)?.error?.code == ApiError.BAD_REQUEST -> ShadeDecision.REASON_NEEDED
    else -> ShadeDecision.FAILED
}
