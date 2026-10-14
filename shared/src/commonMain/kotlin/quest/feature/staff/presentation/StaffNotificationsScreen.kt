package quest.feature.staff.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import quest.api.dto.NotificationView
import quest.core.mvi.MviEffect
import quest.core.mvi.MviIntent
import quest.core.mvi.MviState
import quest.core.mvi.MviViewModel
import quest.core.runCancellable
import quest.core.text.isolate
import quest.feature.chat.presentation.formatClock
import quest.feature.parent.presentation.ParentButton
import quest.feature.parent.presentation.ParentCard
import quest.feature.school.domain.Flags
import quest.feature.school.presentation.FeatureGate
import quest.feature.staff.domain.StaffBadges
import quest.feature.staff.domain.StaffGateway
import quest.ui.design.AnimatedDotsLoader
import quest.ui.design.DashboardTokens
import quest.ui.design.Dimens

object StaffNotificationsContract {
    data class State(
        val loading: Boolean = true,
        val rows: List<NotificationView> = emptyList(),
        val errorMessage: String? = null,
    ) : MviState {
        val unread: Int get() = rows.count { it.readAt == null }
    }

    sealed interface Intent : MviIntent {
        data object Load : Intent
        data class Open(val row: NotificationView) : Intent
        data object MarkAllRead : Intent
    }

    sealed interface Effect : MviEffect
}

/**
 * The staff bell: the very rows the dashboard's bell shows (`GET /me/notifications`, which the five staff roles now
 * hold), with no child filter — a staff member's rows are not about one child.
 */
class StaffNotificationsViewModel(
    private val api: StaffGateway,
    private val badges: StaffBadges,
) : MviViewModel<StaffNotificationsContract.State, StaffNotificationsContract.Intent, StaffNotificationsContract.Effect>(StaffNotificationsContract.State()) {

    override suspend fun handle(intent: StaffNotificationsContract.Intent) {
        when (intent) {
            StaffNotificationsContract.Intent.Load -> load()
            is StaffNotificationsContract.Intent.Open -> {
                if (intent.row.readAt != null) return
                runCancellable { api.markNotificationRead(intent.row.id) }
                reduce { copy(rows = rows.map { if (it.id == intent.row.id) intent.row.copy(readAt = 1L) else it }) }
                badges.refresh()
            }
            StaffNotificationsContract.Intent.MarkAllRead -> {
                runCancellable { api.markAllNotificationsRead() }
                reduce { copy(rows = rows.map { if (it.readAt == null) it.copy(readAt = 1L) else it }) }
                badges.readAllNotifications()
            }
        }
    }

    private suspend fun load() {
        try {
            val rows = api.notifications()
            reduce { copy(loading = false, rows = rows, errorMessage = null) }
            badges.refresh()
        } catch (e: Throwable) {
            reduce { copy(loading = false, errorMessage = e.message, rows = emptyList()) }
        }
    }
}

@Composable
fun StaffNotificationsRoute(
    onOpenThread: (String) -> Unit,
    onHome: () -> Unit,
    onMessages: () -> Unit,
    onAccount: () -> Unit,
) {
    val vm: StaffNotificationsViewModel = koinViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.dispatch(StaffNotificationsContract.Intent.Load) }
    StaffShell(
        title = { it.notifications },
        currentTab = StaffTab.NOTIFICATIONS,
        onTabSelected = { tab ->
            when (tab) {
                StaffTab.ATTENDANCE -> onHome()
                StaffTab.MESSAGES -> onMessages()
                StaffTab.NOTIFICATIONS -> Unit
                StaffTab.ACCOUNT -> onAccount()
            }
        },
        onBack = onHome,
    ) { s, _ ->
        StaffNotificationsScreen(
            state = state,
            strings = s,
            onOpen = { row ->
                vm.dispatch(StaffNotificationsContract.Intent.Open(row))
                // A chat row names its thread in the link and nothing else does, so that is the one row that leads
                // somewhere; everything else is read where it stands.
                quest.feature.push.domain.threadOf(row.link)?.let(onOpenThread)
                // Feature 16: a reminder opens her Attendance tab; an alert or the digest is read where it stands
                // until feature 17's board gives it somewhere to go.
                if (row.kind == quest.api.dto.NotificationKind.ATTENDANCE_REMINDER) onHome()
            },
            onMarkAllRead = { vm.dispatch(StaffNotificationsContract.Intent.MarkAllRead) },
        )
    }
}

@Composable
fun StaffNotificationsScreen(
    state: StaffNotificationsContract.State,
    strings: StaffStrings,
    onOpen: (NotificationView) -> Unit,
    onMarkAllRead: () -> Unit,
) {
    FeatureGate(Flags.STAFF_MOBILE_APP) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Dimens.s16, vertical = Dimens.s8)) {
            if (state.loading) {
                Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                    AnimatedDotsLoader(dotSize = 12.dp, spacing = 8.dp)
                }
                return@FeatureGate
            }
            if (state.unread > 0) {
                ParentButton(strings.markAllRead, onMarkAllRead, primary = false, icon = "✓")
                Spacer(Modifier.height(Dimens.s8))
            }
            if (state.rows.isEmpty()) {
                ParentCard(Modifier.padding(vertical = Dimens.s8)) {
                    Text(
                        state.errorMessage ?: strings.noNotifications,
                        style = MaterialTheme.typography.bodyLarge,
                        color = DashboardTokens.inkSoft,
                    )
                }
                return@FeatureGate
            }
            state.rows.forEach { row -> StaffNotificationRow(row) { onOpen(row) } }
            Spacer(Modifier.height(Dimens.s16))
        }
    }
}

@Composable
private fun StaffNotificationRow(row: NotificationView, onClick: () -> Unit) {
    val worded = attendanceText(quest.api.dto.PushMessage.kindName(row.kind), row.link, LocalStaffStrings.current)
    val title = worded?.first ?: row.title
    val body = worded?.second ?: row.body
    ParentCard(Modifier.fillMaxWidth().padding(bottom = Dimens.s8), onClick = onClick) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (row.readAt == null) {
                        Box(Modifier.size(8.dp).background(DashboardTokens.secondary, CircleShape))
                        Spacer(Modifier.height(Dimens.s4))
                        Spacer(Modifier.padding(horizontal = 3.dp))
                    }
                    Text(
                        isolate(title),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = if (row.readAt == null) FontWeight.Bold else FontWeight.Medium),
                        color = DashboardTokens.ink,
                    )
                }
                body?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(Dimens.s4))
                    Text(isolate(it), style = MaterialTheme.typography.bodyMedium, color = DashboardTokens.inkSoft)
                }
            }
            Text(formatClock(row.createdAt), style = MaterialTheme.typography.bodySmall, color = DashboardTokens.inkSoft)
        }
    }
}
