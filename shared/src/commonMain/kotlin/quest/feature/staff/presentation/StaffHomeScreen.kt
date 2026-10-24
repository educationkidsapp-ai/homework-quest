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
 * The staff home: the Attendance tab. Feature 15's Today card carries today's times and the one check-in button
 * (behind `staffAttendance`, so a school that does not use it sees no card at all), and under it is the other thing a
 * staff member opens this app for — her unread messages.
 */
@Composable
fun StaffHomeRoute(
    onMessages: () -> Unit,
    onMyRecord: () -> Unit,
    onMyBalance: () -> Unit,
    onMyRequests: () -> Unit,
    onNotifications: () -> Unit,
    onAccount: () -> Unit,
    onParentSide: () -> Unit,
    onVoiceAnnouncement: () -> Unit,
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
            canVoiceAnnounce = profile?.canVoiceAnnounce == true,
            onMessages = onMessages,
            onVoiceAnnouncement = onVoiceAnnouncement,
            today = { CheckInCard(onMyRecord = onMyRecord, onMyBalance = onMyBalance, onMyRequests = onMyRequests) },
            switch = { SideSwitch(onSwitched = { onParentSide() }) },
        )
    }
}

@Composable
fun StaffHomeScreen(
    strings: StaffStrings,
    unreadMessages: Int,
    hasChat: Boolean,
    /**
     * Feature 22: she is the Admin or a department manager and her school has both switches on. The role is checked
     * here and the flag again by the gate below, because the card is the only door to the recorder on this phone.
     */
    canVoiceAnnounce: Boolean = false,
    onMessages: () -> Unit,
    onVoiceAnnouncement: () -> Unit = {},
    /** Feature 15's check-in card, as a slot for the same reason the switch is one: it reads Koin for its view model. */
    today: @Composable () -> Unit,
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
            today()
            if (canVoiceAnnounce) {
                FeatureGate(Flags.VOICE_ANNOUNCEMENT) {
                    SectionTitle(strings.voiceAnnouncement)
                    ParentCard(onClick = onVoiceAnnouncement) {
                        Text(
                            strings.voiceRecordHint,
                            style = MaterialTheme.typography.bodyMedium,
                            color = DashboardTokens.inkSoft,
                        )
                        Spacer(Modifier.height(Dimens.s8))
                        ParentButton(strings.voiceRecord, onVoiceAnnouncement, primary = false, icon = "🎙")
                    }
                }
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
