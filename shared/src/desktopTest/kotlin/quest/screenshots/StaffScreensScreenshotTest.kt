package quest.screenshots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import quest.api.DEFAULT_FLAGS
import quest.api.dto.ChatMessage
import quest.api.dto.ChatPeerRole
import quest.api.dto.ChatSender
import quest.api.dto.ChatStaffRole
import quest.api.dto.ChatThread
import quest.api.dto.NotificationKind
import quest.api.dto.NotificationView
import quest.feature.parent.domain.Appearance
import quest.feature.parent.presentation.LocalStrings
import quest.feature.parent.presentation.Strings
import quest.feature.school.domain.FlagStore
import quest.feature.school.domain.Flags
import quest.feature.school.presentation.LocalFlags
import quest.feature.staff.domain.StaffProfile
import quest.feature.staff.domain.StaffRole
import quest.feature.staff.presentation.LocalStaffStrings
import quest.feature.staff.presentation.StaffAccountContract
import quest.feature.staff.presentation.StaffAccountScreen
import quest.feature.staff.presentation.StaffHomeScreen
import quest.feature.staff.presentation.StaffMessagesContract
import quest.feature.staff.presentation.StaffMessagesScreen
import quest.feature.staff.presentation.StaffNotificationsContract
import quest.feature.staff.presentation.StaffNotificationsScreen
import quest.feature.staff.presentation.StaffStrings
import quest.ui.design.DashboardTokens
import quest.ui.design.Dimens
import quest.ui.design.LocalDarkTheme
import quest.ui.design.ParentTheme
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Feature 13 — the four staff tabs: the Today card with "Coming soon", her one inbox, the bell and حسابي, in English
 * and Arabic, light and dark. The staff flag is provided here because every staff screen gates on it, which is the
 * point of the gate.
 */
class StaffScreensScreenshotTest {

    private val base = 1_760_000_000_000L

    /** Her school with the staff app switched on — what `GET /staff/me` answers. */
    private object StaffFlags : FlagStore {
        override val flags: StateFlow<Map<String, Boolean>> =
            MutableStateFlow(DEFAULT_FLAGS + mapOf(Flags.STAFF_MOBILE_APP to true, Flags.CHAT to true))
    }

    private val profile = StaffProfile(
        userId = "u-noor", displayName = "Ms Noor Al-Harbi", role = StaffRole.TEACHER, roleWire = "TEACHER",
        phone = "+966 50 000 0000", schoolId = "s-1", schoolName = "Learning International School",
        flags = emptyMap(),
    )

    private fun parentThread() = ChatThread(
        id = "th-1", childId = "c-1", childName = "Lina Hassan", teacherId = "u-noor", teacherName = "Ms Noor",
        className = "3A", subject = "math", unread = 2, staffRole = ChatStaffRole.TEACHER, parentName = "Hassan Ali",
        peerRole = ChatPeerRole.PARENT,
        lastMessage = ChatMessage("m-1", "th-1", ChatSender.PARENT, "p-1", "Good morning — will there be homework today?", base),
    )

    private fun staffThread() = ChatThread(
        id = "th-2", childId = "", childName = "", teacherId = "u-adel", teacherName = "Mr Adel Saleh",
        staffRole = ChatStaffRole.MANAGERIAL, peerRole = ChatPeerRole.MANAGERIAL,
        lastMessage = ChatMessage("m-2", "th-2", ChatSender.TEACHER, "u-adel", "Please send this week's plan.", base - 3_600_000L),
    )

    private fun rows() = listOf(
        NotificationView("n-1", NotificationKind.CHAT_MESSAGE, "Message from Hassan Ali", "Good morning — will there be homework today?", "/teacher/chat?thread=th-1", createdAt = base),
        NotificationView("n-2", NotificationKind.BROADCAST_POSTED, "Staff meeting on Thursday", "The department meets at 13:00.", createdAt = base - 7_200_000L, readAt = base - 3_600_000L),
    )

    private fun shot(name: String, staff: StaffStrings, dark: Boolean, title: (StaffStrings) -> String, content: @Composable (StaffStrings, Strings) -> Unit) {
        val strings = if (staff.isRtl) Strings.ar else Strings.en
        val file = Screenshots.render(name) {
            CompositionLocalProvider(LocalDarkTheme provides dark, LocalFlags provides StaffFlags) {
                ParentTheme(rtl = staff.isRtl) {
                    CompositionLocalProvider(LocalStrings provides strings, LocalStaffStrings provides staff) {
                        Column(Modifier.fillMaxSize().background(DashboardTokens.bg)) {
                            Text(
                                title(staff),
                                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                                color = DashboardTokens.inkStrong,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.s16, vertical = Dimens.s12),
                            )
                            content(staff, strings)
                        }
                    }
                }
            }
        }
        assertTrue(file.length() > 0, "${file.name} was not written")
    }

    @Test fun staffHome() {
        listOf("staff-home-en" to StaffStrings.en, "staff-home-ar" to StaffStrings.ar).forEach { (name, s) ->
            shot(name, s, dark = false, title = { it.attendance }) { staff, _ ->
                StaffHomeScreen(staff, unreadMessages = 3, hasChat = true, onMessages = {}, switch = {})
            }
        }
        shot("staff-home-dark", StaffStrings.en, dark = true, title = { it.attendance }) { staff, _ ->
            StaffHomeScreen(staff, unreadMessages = 0, hasChat = true, onMessages = {}, switch = {})
        }
    }

    @Test fun staffMessages() {
        val state = StaffMessagesContract.State(loading = false, threads = listOf(parentThread(), staffThread()))
        listOf("staff-messages-en" to StaffStrings.en, "staff-messages-ar" to StaffStrings.ar).forEach { (name, s) ->
            shot(name, s, dark = false, title = { it.messages }) { staff, strings ->
                StaffMessagesScreen(state, staff, strings, onOpenThread = {})
            }
        }
        shot("staff-messages-dark", StaffStrings.en, dark = true, title = { it.messages }) { staff, strings ->
            StaffMessagesScreen(state, staff, strings, onOpenThread = {})
        }
        // A worker has no chat partners yet: the tab says so rather than showing an empty list.
        shot("staff-messages-worker", StaffStrings.en, dark = false, title = { it.messages }) { staff, strings ->
            StaffMessagesScreen(StaffMessagesContract.State(loading = false, noChat = true), staff, strings, onOpenThread = {})
        }
    }

    @Test fun staffNotifications() {
        val state = StaffNotificationsContract.State(loading = false, rows = rows())
        listOf("staff-notifications-en" to StaffStrings.en, "staff-notifications-ar" to StaffStrings.ar).forEach { (name, s) ->
            shot(name, s, dark = false, title = { it.notifications }) { staff, _ ->
                StaffNotificationsScreen(state, staff, onOpen = {}, onMarkAllRead = {})
            }
        }
    }

    @Test fun staffAccount() {
        val state = StaffAccountContract.State(profile = profile, language = "en", appearance = Appearance.SYSTEM)
        shot("staff-account-en", StaffStrings.en, dark = false, title = { it.account }) { staff, strings ->
            StaffAccountScreen(state, staff, strings, dispatch = {}, switch = {})
        }
        shot("staff-account-ar", StaffStrings.ar, dark = false, title = { it.account }) { staff, strings ->
            StaffAccountScreen(state.copy(language = "ar"), staff, strings, dispatch = {}, switch = {})
        }
        shot("staff-account-dark", StaffStrings.en, dark = true, title = { it.account }) { staff, strings ->
            StaffAccountScreen(state.copy(appearance = Appearance.DARK), staff, strings, dispatch = {}, switch = {})
        }
    }
}
