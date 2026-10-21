package quest.screenshots

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import org.jetbrains.skia.Color
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import quest.api.dto.ChannelPost
import quest.api.dto.ChatAttachment
import quest.api.dto.ChatPeerRole
import quest.api.dto.OfficeHoursStatus
import quest.api.dto.TeamMember
import quest.feature.broadcasts.domain.AttachmentImages
import quest.feature.broadcasts.presentation.LocalAttachmentImages
import quest.feature.channel.presentation.ClassChannelContract
import quest.feature.channel.presentation.ClassChannelScreen
import quest.feature.parent.presentation.LocalStrings
import quest.feature.parent.presentation.Strings
import quest.feature.team.presentation.ChildTeamContract
import quest.feature.team.presentation.ChildTeamScreen
import quest.ui.design.LocalDarkTheme
import quest.ui.design.ParentTheme
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Feature 32 — the two pages a parent now opens from Messages: **My child's team** (her teachers, the coordinators,
 * the floor supervisor and the department manager, each with when she answers and a Message) and the **class
 * channel** (its teachers' posts, oldest first, with a photo, a worksheet, a post that was taken down and Reply
 * privately under each). English and Arabic, light and dark. Copied to `docs/screenshots/feature-32/`.
 */
class ChildTeamChannelScreenshotTest {
    private val now = 1_790_000_000_000L

    /** A drawn "photo of the board", so a post shows a real picture rather than the offline Try again card. */
    private val images = AttachmentImages { _ ->
        val surface = Surface.makeRasterN32Premul(800, 600)
        surface.canvas.clear(Color.makeRGB(246, 248, 244))
        val chalk = Paint().apply { color = Color.makeRGB(70, 110, 90) }
        for (row in 0 until 5) surface.canvas.drawRect(Rect.makeXYWH(70f, 90f + row * 95f, 560f - row * 60f, 24f), chalk)
        surface.canvas.drawCircle(650f, 170f, 80f, Paint().apply { color = Color.makeRGB(230, 180, 90) })
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

    // ---- My child's team

    /** One teacher inside her office hours and one outside, so both lines of feature 19 are on the page. */
    private fun team(ar: Boolean): ChildTeamContract.State {
        val open = OfficeHoursStatus(open = true, today = "2026-10-21")
        val closed = OfficeHoursStatus(open = false, nextStart = "2026-10-22T07:30", today = "2026-10-21", schoolPhone = "+966 11 555 0100")
        fun member(id: String, name: String, role: ChatPeerRole, subjects: String?, hours: OfficeHoursStatus?, canMessage: Boolean = true) =
            TeamMember(staffId = id, name = name, role = role, subjects = subjects, officeHours = hours, canMessage = canMessage)
        return ChildTeamContract.State(
            loading = false, childId = "c1",
            members = listOf(
                member("t-sara", if (ar) "أ. سارة" else "Ms. Sara", ChatPeerRole.TEACHER, "math", open),
                member("t-noor", if (ar) "أ. نور" else "Ms. Noor", ChatPeerRole.TEACHER, "english", closed),
                member("co-lina", if (ar) "أ. لينا" else "Ms. Lina", ChatPeerRole.COORDINATOR, "math", closed),
                member("fs-huda", if (ar) "أ. هدى" else "Ms. Huda", ChatPeerRole.FLOOR_SUPERVISOR, null, open),
                // A row she may read but not write to: the button is simply not there.
                member("mg-amal", if (ar) "أ. أمل" else "Ms. Amal", ChatPeerRole.MANAGERIAL, null, closed, canMessage = false),
            ),
        )
    }

    @Test fun childTeam() = shot("f32-01-child-team", Strings.en, dark = false) { s -> ChildTeamScreen(team(false), s) }
    @Test fun childTeamDark() = shot("f32-01b-child-team-dark", Strings.en, dark = true) { s -> ChildTeamScreen(team(false), s) }
    @Test fun childTeamArabic() = shot("f32-01c-child-team-ar", Strings.ar, dark = false) { s -> ChildTeamScreen(team(true), s) }
    @Test fun childTeamArabicDark() = shot("f32-01d-child-team-ar-dark", Strings.ar, dark = true) { s -> ChildTeamScreen(team(true), s) }

    // ---- the class channel

    private val photo = ChatAttachment("a1", "image/jpeg", "board.jpg", 380_000, width = 800, height = 600)
    private val worksheet = ChatAttachment("a2", "application/pdf", "Reading week 6.pdf", 1_140_000)

    private fun channel(ar: Boolean): ClassChannelContract.State {
        fun post(id: String, author: String, role: ChatPeerRole, subject: String?, body: String, at: Long, files: List<ChatAttachment>? = null, deleted: Boolean? = null) =
            ChannelPost(
                id = id, authorId = "s-$id", authorName = author, authorRole = role, subject = subject,
                body = body, attachments = files, createdAt = at, deleted = deleted,
            )
        return ClassChannelContract.State(
            loading = false, childId = "c1", className = if (ar) "1أ بريطاني" else "1A British",
            posts = listOf(
                post(
                    "ch-1", if (ar) "أ. سارة" else "Ms. Sara", ChatPeerRole.TEACHER, "math",
                    if (ar) "غدًا نبدأ العدّ بالخمسات. تدرّبوا في البيت على خط الأعداد." else "Tomorrow we start counting in fives. Please practise at home with the number line.",
                    now - 7_200_000,
                ),
                post(
                    "ch-2", if (ar) "أ. نور" else "Ms. Noor", ChatPeerRole.TEACHER, "english",
                    if (ar) "صورة للسبورة اليوم، لمن نسخها على عجل." else "A photo of today's board, for anyone who copied it in a hurry.",
                    now - 5_400_000, files = listOf(photo),
                ),
                post("ch-3", if (ar) "أ. سارة" else "Ms. Sara", ChatPeerRole.TEACHER, "math", "", now - 3_600_000, deleted = true),
                post(
                    "ch-4", if (ar) "أ. نور" else "Ms. Noor", ChatPeerRole.TEACHER, "english",
                    if (ar) "ورقة القراءة لهذا الأسبوع مرفقة، وتسليمها الخميس." else "This week's reading worksheet is attached. It is due on Thursday.",
                    now - 900_000, files = listOf(worksheet),
                ),
            ),
            hasEarlier = true,
        )
    }

    @Test fun classChannel() = shot("f32-02-class-channel", Strings.en, dark = false) { s -> ClassChannelScreen(channel(false), s) }
    @Test fun classChannelDark() = shot("f32-02b-class-channel-dark", Strings.en, dark = true) { s -> ClassChannelScreen(channel(false), s) }
    @Test fun classChannelArabic() = shot("f32-02c-class-channel-ar", Strings.ar, dark = false) { s -> ClassChannelScreen(channel(true), s) }
    @Test fun classChannelArabicDark() = shot("f32-02d-class-channel-ar-dark", Strings.ar, dark = true) { s -> ClassChannelScreen(channel(true), s) }
}
