package quest.feature.staff.presentation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.koinInject
import quest.feature.parent.presentation.Chip
import quest.feature.parent.presentation.ParentButton
import quest.feature.parent.presentation.ParentCard
import quest.feature.parent.presentation.SectionTitle
import quest.feature.school.domain.Flags
import quest.feature.school.presentation.FeatureGate
import quest.feature.staff.domain.StaffBadges
import quest.feature.staff.domain.StaffSessionView
import quest.ui.design.DashboardTokens
import quest.ui.design.Dimens

/**
 * The staff home: the Attendance tab. Feature 15 puts today's state and the check-in button on the Today card; until
 * then the card says "قريبًا / Coming soon" behind the switch, and under it is the one thing a staff member opens
 * this app for today — her unread messages.
 */
@Composable
fun StaffHomeRoute(
    onMessages: () -> Unit,
    onNotifications: () -> Unit,
    onAccount: () -> Unit,
    onParentSide: () -> Unit,
) {
    val session: StaffSessionView = koinInject()
    val badges: StaffBadges = koinInject()
    val profile by session.profile.collectAsStateWithLifecycle()
    val counts by badges.counts.collectAsStateWithLifecycle()
    StaffShell(
        title = { profile?.displayName ?: it.attendance },
        currentTab = StaffTab.ATTENDANCE,
        onTabSelected = { tab ->
            when (tab) {
                StaffTab.ATTENDANCE -> Unit
                StaffTab.MESSAGES -> onMessages()
                StaffTab.NOTIFICATIONS -> onNotifications()
                StaffTab.ACCOUNT -> onAccount()
            }
        },
    ) { s, _ ->
        StaffHomeScreen(
            strings = s,
            unreadMessages = counts.messages,
            hasChat = profile?.hasChat ?: true,
            onMessages = onMessages,
            switch = { SideSwitch(onSwitched = { onParentSide() }) },
        )
    }
}

@Composable
fun StaffHomeScreen(
    strings: StaffStrings,
    unreadMessages: Int,
    hasChat: Boolean,
    onMessages: () -> Unit,
    /**
     * The Staff/Parent switch, as a slot: it reads the session itself, which a screenshot has no Koin graph for, so
     * the screen takes it rather than reaching for it.
     */
    switch: @Composable () -> Unit,
) {
    FeatureGate(Flags.STAFF_MOBILE_APP) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Dimens.s16)) {
            Spacer(Modifier.height(Dimens.s8))
            switch()
            SectionTitle(strings.today)
            ParentCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        strings.comingSoon,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = DashboardTokens.ink,
                        modifier = Modifier.weight(1f),
                    )
                    Chip(strings.attendance, DashboardTokens.secondarySoft)
                }
                Spacer(Modifier.height(Dimens.s4))
                Text(strings.comingSoonBody, style = MaterialTheme.typography.bodyMedium, color = DashboardTokens.inkSoft)
            }
            if (hasChat) {
                SectionTitle(strings.messages)
                ParentCard(onClick = onMessages) {
                    Text(
                        when (unreadMessages) {
                            0 -> strings.noUnreadMessages
                            1 -> strings.oneUnreadMessage
                            else -> strings.unreadMessages.replace("{n}", unreadMessages.toString())
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = DashboardTokens.ink,
                    )
                    Spacer(Modifier.height(Dimens.s8))
                    ParentButton(strings.openMessages, onMessages, primary = false, icon = "💬")
                }
            }
            Spacer(Modifier.height(Dimens.s16))
        }
    }
}
