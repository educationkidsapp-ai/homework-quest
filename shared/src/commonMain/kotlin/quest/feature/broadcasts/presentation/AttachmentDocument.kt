package quest.feature.broadcasts.presentation

import quest.feature.broadcasts.domain.NoAttachmentDocuments
import quest.feature.broadcasts.domain.AttachmentDocuments
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import quest.api.dto.BroadcastAttachment
import quest.core.platform.DocumentViewer
import quest.core.platform.safeDocumentName
import quest.feature.parent.presentation.Strings
import quest.ui.design.DashboardButton
import quest.ui.design.DashboardButtonVariant
import quest.ui.design.DashboardTokens
import quest.ui.design.Dimens

private enum class Opening { IDLE, WORKING, FAILED }

/** Provided by the app root; [NoAttachmentDocuments] under tests, screenshots and previews, so nothing is fetched. */
val LocalAttachmentDocuments = staticCompositionLocalOf<AttachmentDocuments> { NoAttachmentDocuments }

/**
 * M1: a PDF attachment — a weekly plan the manager uploaded as a document. The card names the file and offers **Open**:
 * the bytes come through the app's own client with the parent's bearer (`/media/attachments/{id}` is authenticated, so
 * the system viewer alone would land on a 401), are streamed to the document cache — at most 10 MB, one copy — and
 * the system's PDF viewer is then pointed at that file. A failure is a sentence under the card and the button stays — no error colour (§7).
 */
@Composable
fun AttachmentDocument(
    attachment: BroadcastAttachment,
    strings: Strings,
    modifier: Modifier = Modifier,
    /** The line under the name — M7's chat card adds the size ("PDF document · 1.2 MB"). */
    detail: String = strings.pdfDocument,
    /** M7: a chat bubble is too narrow for name and button side by side, so the button goes under the name. */
    stacked: Boolean = false,
) {
    val documents = LocalAttachmentDocuments.current
    val scope = rememberCoroutineScope()
    var state by remember(attachment.id) { mutableStateOf(Opening.IDLE) }
    val name = safeDocumentName(attachment.name, "pdf")
    val shape = RoundedCornerShape(DashboardTokens.radiusSm)
    val open: () -> Unit = {
        state = Opening.WORKING
        scope.launch {
            // The store downloads and writes off the UI thread; opening is only an intent to the viewer.
            val file = documents.fetch(attachment)
            state = if (file != null && DocumentViewer.open(file, "application/pdf")) Opening.IDLE else Opening.FAILED
        }
    }
    val label = if (state == Opening.WORKING) strings.documentOpening else strings.openDocument
    Column(modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().background(DashboardTokens.bgSubtle, shape).border(1.dp, DashboardTokens.rule, shape).padding(Dimens.s12)
                .semantics(mergeDescendants = true) { contentDescription = "${strings.pdfDocument}, $name" },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.primaryContainer, shape), contentAlignment = Alignment.Center) {
                    Text("PDF", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = DashboardTokens.accentInk)
                }
                Spacer(Modifier.width(Dimens.s12))
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.titleMedium, color = DashboardTokens.inkStrong, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = DashboardTokens.inkSoft)
                }
                if (!stacked) {
                    Spacer(Modifier.width(Dimens.s12))
                    DashboardButton(label, open, variant = DashboardButtonVariant.PRIMARY, enabled = state != Opening.WORKING, modifier = Modifier.width(104.dp), height = 40.dp)
                }
            }
            if (stacked) {
                Spacer(Modifier.height(Dimens.s8))
                DashboardButton(label, open, variant = DashboardButtonVariant.PRIMARY, enabled = state != Opening.WORKING, modifier = Modifier.fillMaxWidth(), height = 44.dp)
            }
        }
        if (state == Opening.FAILED) {
            Text(strings.documentFailed, style = MaterialTheme.typography.bodySmall, color = DashboardTokens.inkSoft, modifier = Modifier.padding(top = Dimens.s4))
        }
    }
}
