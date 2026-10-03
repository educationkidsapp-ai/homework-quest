package quest.feature.complaints

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import quest.api.dto.ChatMessage
import quest.api.dto.ChatSender
import quest.feature.complaints.domain.TimelineItem
import quest.feature.complaints.presentation.ComplaintBubble
import quest.feature.parent.presentation.Strings
import quest.ui.design.ParentTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/** Review of #209: a failed reply's Retry is a real 48 dp target with a label that says what it does. */
@OptIn(ExperimentalTestApi::class)
class ComplaintBubbleUiTest {
    @Test fun retryIsAFullTargetWithItsOwnLabel() = runComposeUiTest {
        val retried = mutableListOf<String>()
        val failed = TimelineItem.Message(ChatMessage("c-1", "cp", ChatSender.PARENT, "p1", "Thank you.", 1L), failed = true, clientId = "c-1")
        setContent { ParentTheme(rtl = false) { ComplaintBubble(failed, Strings.en) { retried += it } } }
        onNodeWithContentDescription(Strings.en.complaints.retryReply)
            .assertHeightIsAtLeast(48.dp)
            .assertWidthIsAtLeast(48.dp)
            .performClick()
        assertEquals(listOf("c-1"), retried)
    }
}
