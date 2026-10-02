package quest.feature.broadcasts

import quest.api.dto.BroadcastAttachment
import quest.core.platform.safeDocumentName
import quest.core.platform.safeFileName
import quest.core.platform.safeDocumentPath
import quest.feature.broadcasts.domain.isImage
import quest.feature.broadcasts.domain.isPdf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** M1: a weekly plan's attachment is an image or a PDF, and the app tells them apart by the stored content type. */
class PdfAttachmentTest {
    private fun attachment(name: String?, type: String?, id: String? = "att-1") = BroadcastAttachment("/media/attachments/att-1", name, id, type)

    @Test fun aPdfIsKnownByItsContentType() {
        assertTrue(attachment("plan", "application/pdf").isPdf)
        assertTrue(attachment("plan.bin", "application/pdf; charset=binary").isPdf)
        assertFalse(attachment("plan.pdf", "application/pdf").isImage)
    }

    @Test fun anImageIsNotAPdf() {
        assertFalse(attachment("plan.png", "image/png").isPdf)
        assertTrue(attachment("plan.png", "image/png").isImage)
        // The stored type is the honest answer; a misleading name does not overrule it.
        assertFalse(attachment("plan.pdf", "image/png").isPdf)
    }

    @Test fun theExtensionOnlyDecidesForARowWithNoStoredType() {
        assertTrue(attachment("Grade 1 plan.PDF", null).isPdf)
        assertFalse(attachment("plan.docx", null).isPdf)
    }

    @Test fun aRowWithNoIdIsNotOursToFetch() {
        assertFalse(attachment("plan.pdf", "application/pdf", id = null).isPdf)
        assertFalse(attachment("plan.pdf", "application/pdf", id = " ").isPdf)
    }

    @Test fun theFileNameIsSafeAndAlwaysEndsInTheExtension() {
        assertEquals("Grade 1 weekly plan.pdf", safeDocumentName("Grade 1 weekly plan.pdf", "pdf"))
        assertEquals("plan.pdf", safeDocumentName("plan.PDF", "pdf"))
        assertEquals("passwd.pdf", safeDocumentName("../../etc/passwd", "pdf"))
        assertEquals("a_b_c.pdf", safeDocumentName("a:b*c", "pdf"))
        assertEquals("document.pdf", safeDocumentName(null, "pdf"))
        assertEquals("document.pdf", safeDocumentName("   ", "pdf"))
        assertEquals("document.pdf", safeDocumentName("..", "pdf"))
    }

    /** `DocumentViewer.open` reduces whatever it is given to one path segment before it touches the file system. */
    @Test fun aNameIsAlwaysOnePathSegment() {
        assertEquals("passwd", safeFileName("../../etc/passwd"))
        assertEquals("plan.pdf", safeFileName("C:\\Users\\x\\plan.pdf"))
        assertEquals("document", safeFileName("../.."))
        assertEquals("document", safeFileName(""))
    }

    /** M4 (D13): a folder and a file, never more, and never outside the cache directory. */
    @Test fun aDocumentPathIsAtMostOneFolderAndOneFile() {
        assertEquals("attachment-1/plan-last-week.pdf", safeDocumentPath("attachment-1/plan-last-week.pdf"))
        assertEquals("etc/passwd", safeDocumentPath("../../etc/passwd"))
        assertEquals("b/c.pdf", safeDocumentPath("/a/b/c.pdf"))
        assertEquals("plan.pdf", safeDocumentPath("plan.pdf"))
        assertEquals("document", safeDocumentPath("../.."))
        assertEquals("document", safeDocumentPath(""))
    }
}
