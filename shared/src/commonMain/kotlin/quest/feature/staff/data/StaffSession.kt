package quest.feature.staff.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import quest.api.ApiException
import quest.api.DEFAULT_FLAGS
import quest.api.dto.ApiError
import quest.feature.school.domain.FlagStore
import quest.feature.school.domain.Flags
import quest.feature.staff.domain.StaffGateway
import quest.feature.staff.domain.StaffProfile
import quest.feature.staff.domain.StaffSessionView

/**
 * Feature 13: who is signed in on the staff side and what her school has switched on.
 *
 * It is a [FlagStore], so the staff subtree provides it to `LocalFlags` and every `FeatureGate(…)` on a staff screen
 * asks **her** school rather than whichever school a child of hers happens to be in — the one place the two sides of
 * a dual-role account could otherwise disagree.
 */
class StaffSession(private val api: StaffGateway) : FlagStore, StaffSessionView {

    private val _profile = MutableStateFlow<StaffProfile?>(null)
    override val profile: StateFlow<StaffProfile?> = _profile.asStateFlow()

    private val _flags = MutableStateFlow(DEFAULT_FLAGS)
    override val flags: StateFlow<Map<String, Boolean>> = _flags.asStateFlow()

    /** Her school has `staffMobileApp` off, so there is no staff side to show. Set by [load] when the route refuses. */
    private val _disabled = MutableStateFlow(false)
    override val disabled: StateFlow<Boolean> = _disabled.asStateFlow()

    override val flagStore: FlagStore get() = this

    /**
     * `GET /staff/me`. Answers false when the flag is off or the account may not have the staff app at all — the
     * caller then drops the staff side instead of drawing tabs that would 404 one by one.
     */
    suspend fun load(): Boolean {
        return try {
            val me = StaffProfile.of(api.staffMe())
            _profile.value = me
            _flags.value = DEFAULT_FLAGS + me.flags + mapOf(Flags.STAFF_MOBILE_APP to true)
            _disabled.value = false
            true
        } catch (e: ApiException) {
            // A school with the switch off hides the route altogether (`FeatureFlagInterceptor` answers the same
            // `404 not_found` an unknown path gets — which features a school has is not public), and an account
            // without `staff.app` is a 403. Either way there is no staff app here; a network blip is not one.
            val refused = e.error.code == ApiError.FORBIDDEN || e.error.code == ApiError.NOT_FOUND
            if (refused) { clear(); _disabled.value = true }
            false
        }
    }

    fun clear() {
        _profile.value = null
        _flags.value = DEFAULT_FLAGS
        _disabled.value = false
    }
}
