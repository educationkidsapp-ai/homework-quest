package quest.screenshots

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import org.jetbrains.skia.Color
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import quest.api.dto.AttachmentRef
import quest.api.dto.ChatAttachment
import quest.api.dto.ChatMessage
import quest.api.dto.ChatSender
import quest.api.dto.ChatStaffRole
import quest.api.dto.ChatThread
import quest.api.dto.Curriculum
import quest.feature.broadcasts.domain.AttachmentImages
import quest.feature.broadcasts.presentation.LocalAttachmentImages
import quest.feature.chat.domain.AttachmentRefusal
import quest.feature.chat.domain.Resolver
import quest.feature.chat.presentation.ChatConversationContract
import quest.feature.chat.presentation.ChatConversationScreen
import quest.feature.chat.presentation.ChatThreadsContract
import quest.feature.chat.presentation.ChatThreadsScreen
import quest.feature.parent.presentation.LocalStrings
import quest.feature.parent.presentation.Strings
import quest.ui.design.LocalDarkTheme
import quest.ui.design.ParentTheme
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * M7 — a manager's photo and PDF as the parent now sees them (the photo itself, the PDF as a card to open), her
 * "is typing" by name, the parent's own photo, and the composer's tray mid-upload with a refusal; then the thread
 * list with a photo preview and a typing row. English and Arabic, light and dark. Copied to
 * `docs/screenshots/chat-attachments/`.
 */
class ChatAttachmentsScreenshotTest {
    private val now = 1_790_000_000_000L

    /** A drawn "worksheet" so the bubble shows a real picture rather than the offline Try again card. */
    private val images = AttachmentImages { _ ->
        val surface = Surface.makeRasterN32Premul(800, 600)
        surface.canvas.clear(Color.makeRGB(250, 246, 236))
        val ink = Paint().apply { color = Color.makeRGB(60, 90, 160) }
        for (row in 0 until 6) surface.canvas.drawRect(Rect.makeXYWH(60f, 70f + row * 85f, 520f - row * 40f, 26f), ink)
        surface.canvas.drawCircle(660f, 140f, 70f, Paint().apply { color = Color.makeRGB(240, 170, 60) })
        surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)?.bytes
    }

    private fun shot(name: String, strings: Strings, dark: Boolean, content: @Composable (Strings) -> Unit) {
        val f = Screenshots.render(name, frames = 10) {
            CompositionLocalProvider(LocalDarkTheme provides dark, LocalAttachmentImages provides images) {
                ParentTheme(rtl = strings.isRtl) { CompositionLocalProvider(LocalStrings provides strings) { content(strings) } }
            }
        }
        assertTrue(f.length() > 1000, "screenshot $name is empty")
    }

    private val photo = ChatAttachment("a1", "image/jpeg", "homework.jpg", 412_000, width = 800, height = 600)
    private val pdf = ChatAttachment("a2", "application/pdf", "Week 6 plan.pdf", 1_240_000)

    private fun conversation(ar: Boolean) = ChatConversationContract.State(
        childId = "c", teacherId = "nour", teacherName = if (ar) "أ. نور" else "Ms. Nour", loading = false,
        staffRole = ChatStaffRole.MANAGERIAL, threadId = "t", peerOnline = true, isTeacherTyping = true, resolver = Resolver.MANAGER,
        inputText = if (ar) "هذه واجبات هلا" else "Here is Hala's homework",
        messages = listOf(
            ChatConversationContract.UiMessage("m1", if (ar) "هذه ورقة العمل لهذا الأسبوع." else "This week's worksheet.", false, now - 900_000, attachments = listOf(photo)),
            ChatConversationContract.UiMessage("m2", "", false, now - 800_000, attachments = listOf(pdf)),
            ChatConversationContract.UiMessage("m3", if (ar) "شكراً!" else "Thank you!", true, now - 600_000, readAt = now - 500_000,
                attachments = listOf(photo.copy(id = "a3", name = "hala.jpg"))),
        ),
        drafts = listOf(
            ChatConversationContract.Draft("d1", "IMG_2041.jpg", "image/jpeg", 2_300_000, progress = 0.6f),
            ChatConversationContract.Draft("d2", "reading-log.pdf", "application/pdf", 640_000, failed = true),
            ChatConversationContract.Draft("d3", "spelling.png", "image/png", 300_000, progress = 1f, ref = AttachmentRef("r3", "spelling.png", "image/png", 300_000)),
        ),
        refusal = AttachmentRefusal.PHOTO_TOO_LARGE,
    )

    @Test fun conversation() = shot("m7-01-chat-files-typing", Strings.en, dark = false) { s -> ChatConversationScreen(conversation(false), s, {}, {}, {}, {}) }
    @Test fun conversationDark() = shot("m7-01b-chat-files-typing-dark", Strings.en, dark = true) { s -> ChatConversationScreen(conversation(false), s, {}, {}, {}, {}) }
    @Test fun conversationArabic() = shot("m7-01c-chat-files-typing-ar", Strings.ar, dark = false) { s -> ChatConversationScreen(conversation(true), s, {}, {}, {}, {}) }
    @Test fun conversationArabicDark() = shot("m7-01d-chat-files-typing-ar-dark", Strings.ar, dark = true) { s -> ChatConversationScreen(conversation(true), s, {}, {}, {}, {}) }

    private fun list(ar: Boolean): ChatThreadsContract.State {
        fun msg(body: String, vararg files: ChatAttachment) = ChatMessage("m", "t", ChatSender.TEACHER, "s", body, now - 300_000, attachments = files.toList())
        return ChatThreadsContract.State(
            loading = false, curriculum = Curriculum.BRITISH, typing = setOf("t-nour"),
            threads = listOf(
                ChatThread("t-sara", "c", "Hala", "sara", if (ar) "أ. سارة" else "Ms. Sara", "1A British", "math", unread = 1, lastMessage = msg("", photo)),
                ChatThread("t-lina", "c", "Hala", "lina", if (ar) "أ. لينا" else "Ms. Lina", "1A British", "english", lastMessage = msg(if (ar) "الخطة مرفقة" else "Plan attached", pdf), staffRole = ChatStaffRole.COORDINATOR),
                ChatThread("t-nour", "c", "Hala", "nour", if (ar) "أ. نور" else "Ms. Nour", "1A British", lastMessage = msg("Hello"), staffRole = ChatStaffRole.MANAGERIAL),
            ),
        )
    }

    @Test fun threads() = shot("m7-02-threads-preview-typing", Strings.en, dark = false) { s -> ChatThreadsScreen(list(false), s, {}) }
    @Test fun threadsDark() = shot("m7-02b-threads-preview-typing-dark", Strings.en, dark = true) { s -> ChatThreadsScreen(list(false), s, {}) }
    @Test fun threadsArabic() = shot("m7-02c-threads-preview-typing-ar", Strings.ar, dark = false) { s -> ChatThreadsScreen(list(true), s, {}) }
    @Test fun threadsArabicDark() = shot("m7-02d-threads-preview-typing-ar-dark", Strings.ar, dark = true) { s -> ChatThreadsScreen(list(true), s, {}) }
}
