package quest.screenshots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import quest.api.dto.ChatMessage
import quest.api.dto.ChatPeerRole
import quest.api.dto.ChatSender
import quest.api.dto.ChatThreadStatus
import quest.api.dto.Child
import quest.api.dto.Complaint
import quest.api.dto.ComplaintActor
import quest.api.dto.ComplaintEvent
import quest.api.dto.ComplaintRecipient
import quest.api.dto.Curriculum
import quest.feature.complaints.domain.TimelineItem
import quest.feature.complaints.presentation.ComplaintContract
import quest.feature.complaints.presentation.ComplaintScreen
import quest.feature.complaints.presentation.ComplaintsContract
import quest.feature.complaints.presentation.ComplaintsScreen
import quest.feature.complaints.presentation.NewComplaintContract
import quest.feature.complaints.presentation.NewComplaintScreen
import quest.feature.parent.presentation.LocalStrings
import quest.feature.parent.presentation.Strings
import quest.ui.design.DashboardBottomNavigation
import quest.ui.design.DashboardTab
import quest.ui.design.DashboardTokens
import quest.ui.design.Dimens
import quest.ui.design.LocalDarkTheme
import quest.ui.design.ParentTheme
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * M8 — the Complaints tab, New complaint and one complaint (resolved, with its Reopen; open, with a reopen line), in
 * English and Arabic, light and dark. Copied to `docs/screenshots/complaints-page/`.
 */
class ComplaintsScreenshotTest {
    private val day = 86_400_000L
    private val base = 1_759_480_000_000L
    private val maya = Child("c1", "Maya", "sun", Curriculum.BRITISH, 1)
    private val omar = Child("c2", "Omar", "moon", Curriculum.BRITISH, 3)

    private fun shot(name: String, strings: Strings = Strings.en, dark: Boolean = false, tab: Boolean = false, title: (Strings) -> String, content: @Composable (Strings) -> Unit) {
        val f = Screenshots.render(name) {
            CompositionLocalProvider(LocalDarkTheme provides dark) {
                ParentTheme(rtl = strings.isRtl) {
                    CompositionLocalProvider(LocalStrings provides strings) {
                        Column(Modifier.fillMaxSize().background(DashboardTokens.bg)) {
                            Text(
                                title(strings), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = DashboardTokens.inkStrong,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.s16, vertical = Dimens.s12),
                            )
                            Box(Modifier.weight(1f)) { content(strings) }
                            if (tab) DashboardBottomNavigation(DashboardTab.COMPLAINTS, {}, isRtl = strings.isRtl, unreadComplaints = 1)
                        }
                    }
                }
            }
        }
        assertTrue(f.length() > 1000, "screenshot $name is empty")
    }

    private fun msg(id: String, thread: String, staff: String?, body: String, at: Long) =
        ChatMessage(id, thread, if (staff == null) ChatSender.PARENT else ChatSender.TEACHER, staff ?: "p1", body, at, readAt = at + 60_000)

    private val bus = Complaint(
        "cp-bus", "c1", "Maya", "The school bus arrives late", ChatThreadStatus.OPEN, "mg-nour", "Ms. Nour", ChatPeerRole.MANAGERIAL, base - day,
        lastMessage = msg("m2", "cp-bus", "mg-nour", "Thank you for telling us. I am checking the route today.", base - day + 3_600_000), unread = 1, canReply = true,
    )
    private val homework = Complaint(
        "cp-homework", "c1", "Maya", "Homework is too long every night", ChatThreadStatus.RESOLVED, "co-lina", "Ms. Lina", ChatPeerRole.COORDINATOR,
        base - 3 * day, subject = "math", lastMessage = msg("m4", "cp-homework", null, "Thank you, it is much better now.", base - 2 * day),
        resolvedAt = base - 2 * day - 1_800_000, resolvedByName = "Ms. Lina", canReply = true,
    )
    private val lunch = Complaint(
        "cp-lunch", "c1", "Maya", "Lunch break is too short", ChatThreadStatus.OPEN, "t-sara", "Ms. Sara", ChatPeerRole.TEACHER,
        base - 5 * day, subject = "math", lastMessage = msg("m5", "cp-lunch", null, "Maya says she cannot finish her lunch.", base - 5 * day), canReply = true,
    )
    private val list = ComplaintsContract.State(loading = false, children = listOf(maya, omar), childId = "c1", complaints = listOf(bus, homework, lunch), open = 2, resolved = 1)

    @Test fun list() = shot("m8-01-complaints", tab = true, title = { it.complaints.title }) { s -> ComplaintsScreen(list, s, {}, {}) }
    @Test fun listDark() = shot("m8-01b-complaints-dark", dark = true, tab = true, title = { it.complaints.title }) { s -> ComplaintsScreen(list, s, {}, {}) }
    @Test fun listArabic() = shot("m8-01c-complaints-ar", Strings.ar, tab = true, title = { it.complaints.title }) { s -> ComplaintsScreen(list, s, {}, {}) }
    @Test fun listEmpty() = shot("m8-01d-complaints-empty", tab = true, title = { it.complaints.title }) { s ->
        ComplaintsScreen(ComplaintsContract.State(loading = false, children = listOf(maya), childId = "c1"), s, {}, {})
    }

    private val recipients = listOf(
        ComplaintRecipient("t-sara", "Ms. Sara", ChatPeerRole.TEACHER, "math"),
        ComplaintRecipient("t-noor", "Ms. Noor", ChatPeerRole.TEACHER, "english"),
        ComplaintRecipient("co-lina", "Ms. Lina", ChatPeerRole.COORDINATOR, "math"),
        ComplaintRecipient("mg-nour", "Ms. Nour", ChatPeerRole.MANAGERIAL),
    )
    private val form = NewComplaintContract.State(
        loading = false, children = listOf(maya, omar), childId = "c1", curriculum = Curriculum.BRITISH, recipients = recipients,
        recipientId = "co-lina", title = "Homework is too long every night", body = "Maya spends more than an hour on math homework every night.",
    )
    @Test fun newComplaint() = shot("m8-02-new-complaint", title = { it.complaints.newComplaint }) { s -> NewComplaintScreen(form, s) }
    @Test fun newComplaintDark() = shot("m8-02b-new-complaint-dark", dark = true, title = { it.complaints.newComplaint }) { s -> NewComplaintScreen(form, s) }
    @Test fun newComplaintArabic() = shot("m8-02c-new-complaint-ar", Strings.ar, title = { it.complaints.newComplaint }) { s ->
        NewComplaintScreen(form.copy(title = "الواجب طويل جدًا كل ليلة", body = "تقضي مايا أكثر من ساعة في واجب الرياضيات كل ليلة."), s)
    }

    private val resolved = ComplaintContract.State(
        childId = "c1", complaintId = "cp-homework", loading = false, complaint = homework.copy(lastMessage = null),
        messages = listOf(
            TimelineItem.Message(msg("m1", "cp-homework", null, "Maya spends more than an hour on math homework every night.", base - 3 * day)),
            TimelineItem.Message(msg("m2", "cp-homework", "co-lina", "We have shortened the Grade 1 worksheets from this week. Please tell me how it goes.", base - 2 * day - 3_600_000)),
        ),
        events = listOf(ComplaintEvent(ChatThreadStatus.RESOLVED, ComplaintActor.STAFF, "co-lina", "Ms. Lina", base - 2 * day - 1_800_000)),
    )
    @Test fun complaintResolved() = shot("m8-03-complaint-resolved", title = { resolved.complaint!!.title }) { s -> ComplaintScreen(resolved, s) }
    @Test fun complaintResolvedDark() = shot("m8-03b-complaint-resolved-dark", dark = true, title = { resolved.complaint!!.title }) { s -> ComplaintScreen(resolved, s) }
    @Test fun complaintResolvedArabic() = shot("m8-03c-complaint-resolved-ar", Strings.ar, title = { resolved.complaint!!.title }) { s -> ComplaintScreen(resolved, s) }

    /** Review of #209: Reopen did not reach the server, and a reply waits to be sent again (48 dp Retry). */
    private val failures = resolved.copy(
        reopenFailed = true,
        messages = resolved.messages + TimelineItem.Message(msg("c-1", "cp-homework", null, "It is long again this week.", base - day), failed = true, clientId = "c-1"),
    )
    @Test fun complaintFailures() = shot("m8-03d-complaint-reopen-failed", title = { failures.complaint!!.title }) { s -> ComplaintScreen(failures, s) }
    @Test fun complaintFailuresArabic() = shot("m8-03e-complaint-reopen-failed-ar", Strings.ar, title = { failures.complaint!!.title }) { s -> ComplaintScreen(failures, s) }

    /** Reopened by the parent, then answered again — the two status lines between the messages. */
    private val reopened = resolved.copy(
        complaint = homework.copy(status = ChatThreadStatus.OPEN, resolvedAt = null, resolvedByName = null, lastMessage = null),
        messages = resolved.messages + listOf(
            TimelineItem.Message(msg("m3", "cp-homework", null, "It was better for two days, but this week it is long again.", base - day)),
            TimelineItem.Message(msg("m4", "cp-homework", "co-lina", "Thank you — I am looking at this week's sheets now.", base - day + 7_200_000)),
        ),
        events = resolved.events + ComplaintEvent(ChatThreadStatus.OPEN, ComplaintActor.PARENT, "p1", "Hala", base - day - 60_000),
        input = "Thank you.",
    )
    /** B5: a reply that was a PDF and a few words, and two files waiting in the composer (one still uploading). */
    private val withFiles = reopened.copy(
        messages = reopened.messages + TimelineItem.Message(
            msg("m5", "cp-homework", null, "This week's sheet, for comparison.", base - day + 8_000_000).copy(
                attachments = listOf(quest.api.dto.ChatAttachment("att-1", "application/pdf", "Week 6 math sheet.pdf", 412_000)),
            ),
        ),
        drafts = listOf(
            quest.feature.chat.presentation.AttachmentDraft("d1", "homework-photo.jpg", "image/jpeg", 1_800_000, progress = 1f, ref = quest.api.dto.AttachmentRef("att-2", "homework-photo.jpg", "image/jpeg", 1_800_000)),
            quest.feature.chat.presentation.AttachmentDraft("d2", "Week 7 math sheet.pdf", "application/pdf", 380_000, progress = 0.4f),
        ),
        input = "",
    )
    @Test fun complaintFiles() = shot("m8-06-complaint-files", title = { withFiles.complaint!!.title }) { s -> ComplaintScreen(withFiles, s) }
    @Test fun complaintFilesArabic() = shot("m8-06b-complaint-files-ar", Strings.ar, title = { withFiles.complaint!!.title }) { s -> ComplaintScreen(withFiles, s) }

    @Test fun complaintReopened() = shot("m8-04-complaint-reopened", title = { reopened.complaint!!.title }) { s -> ComplaintScreen(reopened, s) }
    @Test fun complaintReopenedDark() = shot("m8-04b-complaint-reopened-dark", dark = true, title = { reopened.complaint!!.title }) { s -> ComplaintScreen(reopened, s) }
    @Test fun complaintReopenedArabic() = shot("m8-04c-complaint-reopened-ar", Strings.ar, title = { reopened.complaint!!.title }) { s -> ComplaintScreen(reopened, s) }
}
