package quest.feature.staff.presentation

import quest.api.staff.MyLeaveBalance
import quest.core.mvi.MviEffect
import quest.core.mvi.MviIntent
import quest.core.mvi.MviState
import quest.core.mvi.MviViewModel
import quest.feature.staff.domain.StaffGateway

/**
 * Feature 34, "رصيدي / My balance": `GET /staff/attendance/leave/balance` and nothing else. The year, her category,
 * the school's chain and one row per leave type — the allowance, what approved leave has used of it and what is
 * left. Nothing is computed here: a type with no allowance (unpaid, other) carries nulls and the screen says so,
 * rather than printing a zero that would read as "none left".
 */
object MyBalanceContract {
    data class State(
        val loading: Boolean = true,
        val balance: MyLeaveBalance? = null,
        val failed: Boolean = false,
    ) : MviState

    sealed interface Intent : MviIntent {
        data object Load : Intent
    }

    sealed interface Effect : MviEffect
}

class MyLeaveBalanceViewModel(private val api: StaffGateway) :
    MviViewModel<MyBalanceContract.State, MyBalanceContract.Intent, MyBalanceContract.Effect>(MyBalanceContract.State()) {

    override suspend fun handle(intent: MyBalanceContract.Intent) {
        when (intent) {
            MyBalanceContract.Intent.Load -> {
                reduce { copy(loading = true, failed = false) }
                try {
                    val answer = api.myLeaveBalance()
                    reduce { copy(loading = false, balance = answer, failed = false) }
                } catch (_: Throwable) {
                    reduce { copy(loading = false, failed = true) }
                }
            }
        }
    }
}
