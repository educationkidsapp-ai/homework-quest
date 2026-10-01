package quest.feature.broadcasts.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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

/**
 * M1: a PDF attachment — a weekly plan the manager uploaded as a document. The card names the file and offers **Open**:
 * the bytes come through the app's own client with the parent's bearer (`/media/attachments/{id}` is authenticated, so
 * the system viewer alone would land on a 401), are kept on the device like a plan image, and are then handed to the
 * system's PDF viewer. A failure is a sentence under the card and the button stays — no error colour (§7).
 */
@Composable
fun AttachmentDocument(attachment: BroadcastAttachment, strings: Strings, modifier: Modifier = Modifier) {
    val files = LocalAttachmentImages.current
    val scope = rememberCoroutineScope()
    var state by remember(attachment.id) { mutableStateOf(Opening.IDLE) }
    val name = safeDocumentName(attachment.name, "pdf")
    val shape = RoundedCornerShape(DashboardTokens.radiusSm)
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().background(DashboardTokens.bgSubtle, shape).border(1.dp, DashboardTokens.rule, shape).padding(Dimens.s12)
                .semantics(mergeDescendants = true) { contentDescription = "${strings.pdfDocument}, $name" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.primaryContainer, shape), contentAlignment = Alignment.Center) {
                Text("PDF", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = DashboardTokens.accentInk)
            }
            Spacer(Modifier.width(Dimens.s12))
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium, color = DashboardTokens.inkStrong, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(strings.pdfDocument, style = MaterialTheme.typography.bodySmall, color = DashboardTokens.inkSoft)
            }
            Spacer(Modifier.width(Dimens.s12))
            DashboardButton(
                text = if (state == Opening.WORKING) strings.documentOpening else strings.openDocument,
                onClick = {
                    state = Opening.WORKING
                    scope.launch {
                        val bytes = files.load(attachment)
                        state = if (bytes != null && DocumentViewer.open(name, bytes, "application/pdf")) Opening.IDLE else Opening.FAILED
                    }
                },
                variant = DashboardButtonVariant.PRIMARY,
                enabled = state != Opening.WORKING,
                modifier = Modifier.width(104.dp),
                height = 40.dp,
            )
        }
        if (state == Opening.FAILED) {
            Text(strings.documentFailed, style = MaterialTheme.typography.bodySmall, color = DashboardTokens.inkSoft, modifier = Modifier.padding(top = Dimens.s4))
        }
    }
}
