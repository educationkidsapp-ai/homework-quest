package quest.feature.chat

import quest.feature.chat.presentation.threadPreview
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * MH4: the thread list showed the raw `[attachment:…]` tag the conversation's own bubble hides behind a card. The row
 * decodes it with the same parser, so the parent reads the file's name and not the wire format.
 */
class ChatThreadPreviewTest {
    @Test fun attachmentOnlyReadsAsTheFileName() {
        assertEquals("📎 report.png", threadPreview("[attachment:a1:image:report.png:1 KB]"))
    }

    @Test fun attachmentWithTextKeepsBoth() {
        assertEquals(
            "📎 plan.pdf · Here is the plan",
            threadPreview("Here is the plan [attachment:a2:pdf:plan.pdf:240 KB]"),
        )
    }

    @Test fun markdownImageReadsAsItsAltText() {
        assertEquals("📎 Homework", threadPreview("![Homework](https://example.test/h.png)"))
    }

    @Test fun plainMessageIsUntouched() {
        assertEquals("Thank you!", threadPreview("Thank you!"))
    }
}
