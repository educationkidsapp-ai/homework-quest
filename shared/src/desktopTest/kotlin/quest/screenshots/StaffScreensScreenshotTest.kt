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
import quest.api.staff.CheckInDay
import quest.api.staff.CheckInMonth
import quest.api.staff.CheckInToday
import quest.api.staff.AttendanceRequestStatus
import quest.api.staff.AttendanceRequestType
import quest.api.staff.AttendanceRequestView
import quest.api.staff.CorrectionKind
import quest.feature.staff.presentation.AttendanceRequestsContract
import quest.feature.staff.presentation.CheckInCardContent
import quest.feature.staff.presentation.CheckInContract
import quest.feature.staff.presentation.CheckInMessage
import quest.feature.staff.presentation.MyRecordContract
import quest.feature.staff.presentation.StaffMyRecordScreen
import quest.feature.staff.presentation.StaffAccountContract
import quest.feature.staff.presentation.StaffAccountScreen
import quest.api.dashboard.ManagementGradeGroup
import quest.api.dto.Curriculum
import quest.feature.staff.presentation.StaffHomeScreen
import quest.feature.staff.presentation.VoiceAnnouncementContract
import quest.feature.staff.presentation.VoiceAnnouncementScreen
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
 * Feature 13 — the four staff tabs: the Today card (feature 15's check-in), her one inbox, the bell and حسابي, in
 * English and Arabic, light and dark. The staff flags are provided here because every staff screen gates on them,
 * which is the point of the gate.
 */
class StaffScreensScreenshotTest {

    private val base = 1_760_000_000_000L

    /** Her school with the staff app switched on — what `GET /staff/me` answers. */
    private object StaffFlags : FlagStore {
        override val flags: StateFlow<Map<String, Boolean>> =
            MutableStateFlow(
                DEFAULT_FLAGS + mapOf(
                    Flags.STAFF_MOBILE_APP to true, Flags.CHAT to true, Flags.STAFF_ATTENDANCE to true,
                    Flags.ANNOUNCEMENTS to true, Flags.VOICE_ANNOUNCEMENT to true,
                ),
            )
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

    /** Feature 15: before the first tap of the day, and after a recorded arrival. */
    private fun checkInCard(state: CheckInContract.State): @Composable (StaffStrings) -> Unit = { staff ->
        CheckInCardContent(state, staff, onTap = {}, onRationaleAccepted = {}, onRationaleDismissed = {}, onMyRecord = {})
    }

    private val beforeArrival = CheckInContract.State(loading = false, today = CheckInToday(date = "2026-10-14"))

    private val afterArrival = CheckInContract.State(
        loading = false,
        today = CheckInToday(date = "2026-10-14", checkIn = "06:52", lateMinutes = 0),
        message = CheckInMessage.Recorded("06:52", 0),
    )

    @Test fun staffHome() {
        listOf("staff-home-en" to StaffStrings.en, "staff-home-ar" to StaffStrings.ar).forEach { (name, s) ->
            shot(name, s, dark = false, title = { it.attendance }) { staff, _ ->
                StaffHomeScreen(staff, unreadMessages = 3, hasChat = true, onMessages = {}, today = { checkInCard(beforeArrival)(staff) }, switch = {})
            }
        }
        shot("staff-home-dark", StaffStrings.en, dark = true, title = { it.attendance }) { staff, _ ->
            StaffHomeScreen(staff, unreadMessages = 0, hasChat = true, onMessages = {}, today = { checkInCard(afterArrival)(staff) }, switch = {})
        }
    }

    /** Feature 22: the recorder while she is speaking, and the preview the assistant prepared. */
    @Test fun staffVoiceAnnouncement() {
        val recording = VoiceAnnouncementContract.State(
            allowed = true, recording = true, elapsedSeconds = 37,
            transcript = "بكرة عندنا رحلة لطلاب الصف الثالث، ودوا معهم وجبة خفيفة وماء.",
        )
        val preview = VoiceAnnouncementContract.State(
            allowed = true,
            step = VoiceAnnouncementContract.Step.PREVIEW,
            draftId = "draft-1",
            transcript = recording.transcript,
            cells = listOf(Curriculum.BRITISH, Curriculum.AMERICAN).flatMap { track ->
                (1..3).map { grade -> ManagementGradeGroup(curriculum = track, grade = grade, sections = 1, children = 20) }
            },
            form = VoiceAnnouncementContract.Form(
                title = "رحلة الصف الثالث",
                bodyAr = "نحيط أولياء أمور طلاب الصف الثالث علمًا بأن الرحلة المدرسية غدًا، ونرجو تزويد الطالب بوجبة خفيفة وماء.",
                bodyEn = "Dear parents of Grade 3, the school trip is tomorrow. Please send a light snack and water.",
                curriculum = Curriculum.BRITISH,
                grade = 3,
                expiresOn = "2026-11-30",
            ),
        )
        listOf("staff-voice-record-ar" to StaffStrings.ar, "staff-voice-record-en" to StaffStrings.en).forEach { (name, s) ->
            shot(name, s, dark = false, title = { it.voiceAnnouncement }) { staff, _ ->
                VoiceAnnouncementScreen(recording, staff)
            }
        }
        shot("staff-voice-preview-ar", StaffStrings.ar, dark = false, title = { it.voiceAnnouncement }) { staff, _ ->
            VoiceAnnouncementScreen(preview, staff)
        }
        shot("staff-voice-preview-dark", StaffStrings.en, dark = true, title = { it.voiceAnnouncement }) { staff, _ ->
            VoiceAnnouncementScreen(preview, staff)
        }
    }

    /** Feature 15: the check-in card on its own, and "My record". */
    @Test fun staffCheckIn() {
        listOf("staff-check-in-en" to StaffStrings.en, "staff-check-in-ar" to StaffStrings.ar).forEach { (name, s) ->
            shot(name, s, dark = false, title = { it.today }) { staff, _ -> checkInCard(beforeArrival)(staff) }
        }
        shot("staff-check-in-outside", StaffStrings.ar, dark = false, title = { it.today }) { staff, _ ->
            checkInCard(beforeArrival.copy(message = CheckInMessage.Outside(400)))(staff)
        }
        shot("staff-check-in-dark", StaffStrings.en, dark = true, title = { it.today }) { staff, _ ->
            checkInCard(afterArrival)(staff)
        }
    }

    @Test fun staffMyRecord() {
        val month = MyRecordContract.State(
            loading = false,
            month = "2026-10",
            days = listOf(
                CheckInDay(date = "2026-10-12", checkIn = "06:50", checkOut = "14:02", status = "ON_TIME", lateMinutes = 0, mode = "onsite"),
                CheckInDay(date = "2026-10-13", checkIn = "07:04", checkOut = "14:00", status = "LATE", lateMinutes = 12, mode = "onsite"),
                CheckInDay(date = "2026-10-14", status = "DAY_OFF"),
            ),
        )
        listOf("staff-my-record-en" to StaffStrings.en, "staff-my-record-ar" to StaffStrings.ar).forEach { (name, s) ->
            shot(name, s, dark = false, title = { it.myRecord }) { staff, _ ->
                StaffMyRecordScreen(month, staff, onPreviousMonth = {}, onNextMonth = {})
            }
        }
        shot("staff-my-record-dark", StaffStrings.en, dark = true, title = { it.myRecord }) { staff, _ ->
            StaffMyRecordScreen(month, staff, onPreviousMonth = {}, onNextMonth = {})
        }
    }

    /**
     * Feature 18: a record whose days carry the remote and correction modes, with "My requests" under it — one
     * waiting, one approved — so both languages and the dark theme are seen with the new words in them.
     */
    @Test fun staffAttendanceRequests() {
        val month = MyRecordContract.State(
            loading = false,
            month = "2026-10",
            days = listOf(
                CheckInDay(date = "2026-10-12", checkIn = "06:50", checkOut = "14:02", status = "ON_TIME", lateMinutes = 0, mode = "remote"),
                CheckInDay(date = "2026-10-13", checkIn = "06:55", checkOut = "14:00", status = "ON_TIME", lateMinutes = 0, mode = "correction"),
            ),
        )
        val requests = AttendanceRequestsContract.State(
            loading = false,
            requests = listOf(
                AttendanceRequestView(
                    id = "r-1", type = AttendanceRequestType.REMOTE_DAY, status = AttendanceRequestStatus.PENDING,
                    staff = "USER:u-1", staffName = "Ms Noor", date = "2026-10-20", reason = "A rain day", createdAt = 0L,
                ),
                AttendanceRequestView(
                    id = "r-2", type = AttendanceRequestType.CORRECTION, status = AttendanceRequestStatus.APPROVED,
                    staff = "USER:u-1", staffName = "Ms Noor", date = "2026-10-13", reason = "The app would not open",
                    correctionKind = CorrectionKind.FORGOT_CHECK_IN, checkIn = "06:55", createdAt = 0L,
                    decidedBy = "Mr Adel", decidedAt = 1L,
                ),
            ),
        )
        listOf("staff-requests-en" to StaffStrings.en, "staff-requests-ar" to StaffStrings.ar).forEach { (name, s) ->
            shot(name, s, dark = false, title = { it.myRequests }) { staff, strings ->
                StaffMyRecordScreen(
                    month, staff, onPreviousMonth = {}, onNextMonth = {},
                    requests = requests, parentStrings = strings, today = "2026-10-14",
                )
            }
        }
        shot("staff-requests-dark", StaffStrings.en, dark = true, title = { it.myRequests }) { staff, strings ->
            StaffMyRecordScreen(
                month, staff, onPreviousMonth = {}, onNextMonth = {},
                requests = requests, parentStrings = strings, today = "2026-10-14",
            )
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
