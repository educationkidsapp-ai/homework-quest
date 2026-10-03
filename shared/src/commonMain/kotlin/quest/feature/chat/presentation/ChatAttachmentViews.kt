package quest.feature.chat.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import quest.api.dto.ChatAttachment
import quest.api.dto.ChatMessage
import quest.feature.broadcasts.presentation.AttachmentDocument
import quest.feature.broadcasts.presentation.AttachmentImage
import quest.feature.chat.domain.AttachmentRefusal
import quest.feature.chat.domain.THUMBNAIL_PX
import quest.feature.chat.domain.asDownload
import quest.feature.chat.domain.asThumbnail
import quest.feature.chat.domain.formatBytes
import quest.feature.chat.domain.isPdf
import quest.feature.parent.presentation.Strings
import quest.ui.design.DashboardTokens
import quest.ui.design.Dimens

/**
 * M7: the one-line preview of a message — a thread row's last message. Files are named by kind (B5's contract: an
 * image is "📷 Photo", a PDF "📄 its name"), and words the parent wrote with them follow. A body carrying the old
 * client-side `[attachment:…]` tag is plain text like any other body.
 */
fun messagePreview(message: ChatMessage, strings: Strings): String {
    val files = message.attachments.orEmpty()
    val label = when {
        files.isEmpty() -> null
        files.all { !isPdf(it.contentType) } ->
            if (files.size == 1) strings.chatFiles.previewPhoto else strings.chatFiles.previewPhotos.replace("{n}", files.size.toString())
        else -> strings.chatFiles.previewPdf.replace("{name}", files.first { isPdf(it.contentType) }.name)
    }
    return listOfNotNull(label, message.body.trim().takeIf { it.isNotEmpty() }).joinToString(" · ")
}

/** The sentence for a file that was not added. Plain words on the parent palette — no error colour, no red X (§7). */
fun refusalText(refusal: AttachmentRefusal, strings: Strings): String = when (refusal) {
    AttachmentRefusal.TOO_MANY -> strings.chatFiles.attachTooMany
    AttachmentRefusal.PHOTO_TOO_LARGE -> strings.chatFiles.attachPhotoTooLarge
    AttachmentRefusal.PDF_TOO_LARGE -> strings.chatFiles.attachPdfTooLarge
    AttachmentRefusal.WRONG_TYPE -> strings.chatFiles.attachWrongType
    AttachmentRefusal.UNREADABLE -> strings.chatFiles.attachUnreadable
}

/**
 * The files on one message: each photo as the server's thumbnail, tapped open full screen to pinch and pan, and each
 * PDF as the weekly plan's card — name, size and **Open**, which streams it to the document cache with the parent's
 * token and hands it to the system viewer.
 */
@Composable
fun MessageAttachments(attachments: List<ChatAttachment>, strings: Strings, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Dimens.s8)) {
        attachments.forEach { file ->
            if (isPdf(file.contentType)) {
                AttachmentDocument(
                    attachment = file.asDownload(),
                    strings = strings,
                    detail = "${strings.pdfDocument} · ${formatBytes(file.size)}",
                    stacked = true,
                )
            } else {
                val ratio = if ((file.width ?: 0) > 0 && (file.height ?: 0) > 0) file.width!!.toFloat() / file.height!! else 1.4f
                AttachmentImage(
                    attachment = file.asThumbnail(),
                    full = file.asDownload(),
                    description = strings.chatFiles.photoDescription.replace("{name}", file.name),
                    strings = strings,
                    modifier = Modifier.clip(RoundedCornerShape(DashboardTokens.radiusSm)),
                    maxHeight = 260.dp,
                    maxDimensionPx = THUMBNAIL_PX,
                    placeholderRatio = ratio.coerceIn(0.5f, 2f),
                    failedText = strings.chatFiles.photoFailed,
                )
            }
        }
    }
}

/**
 * The composer's tray: one chip per file with its upload's progress, a retry on one that did not go up, and a remove
 * on each. The refusal sentence for the last file that could not be added sits under it.
 */
@Composable
fun DraftTray(
    drafts: List<ChatConversationContract.Draft>,
    refusal: AttachmentRefusal?,
    strings: Strings,
    onRemove: (String) -> Unit,
    onRetry: (String) -> Unit,
) {
    if (drafts.isEmpty() && refusal == null) return
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = Dimens.s12, vertical = Dimens.s8),
    ) {
        if (drafts.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(Dimens.s8)) {
                items(drafts, key = { it.localId }) { draft -> DraftChip(draft, strings, onRemove, onRetry) }
            }
        }
        if (refusal != null) {
            if (drafts.isNotEmpty()) Spacer(Modifier.height(Dimens.s8))
            Text(refusalText(refusal, strings), style = MaterialTheme.typography.bodySmall, color = DashboardTokens.ink)
        }
    }
}

@Composable
private fun DraftChip(
    draft: ChatConversationContract.Draft,
    strings: Strings,
    onRemove: (String) -> Unit,
    onRetry: (String) -> Unit,
) {
    val shape = RoundedCornerShape(DashboardTokens.radiusSm)
    Column(
        Modifier.width(208.dp).background(MaterialTheme.colorScheme.primaryContainer, shape)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape).padding(Dimens.s8),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (isPdf(draft.contentType)) "📄" else "📷", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(Dimens.s8))
            Column(Modifier.weight(1f)) {
                Text(
                    draft.name, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    color = DashboardTokens.inkStrong, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    when {
                        draft.failed -> strings.chatFiles.attachmentUploadFailed
                        draft.uploading -> strings.chatFiles.attachmentUploading
                        else -> formatBytes(draft.size)
                    },
                    style = MaterialTheme.typography.bodySmall, color = DashboardTokens.inkSoft,
                )
            }
            val removeLabel = strings.chatFiles.removeAttachment.replace("{name}", draft.name)
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(24.dp))
                    .clickable(role = Role.Button) { onRemove(draft.localId) }
                    .semantics { contentDescription = removeLabel },
                contentAlignment = Alignment.Center,
            ) {
                Text("✕", color = DashboardTokens.inkSoft, fontWeight = FontWeight.Bold)
            }
        }
        if (draft.uploading) {
            LinearProgressIndicator(
                progress = { draft.progress },
                modifier = Modifier.fillMaxWidth().padding(top = Dimens.s4),
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (draft.failed) {
            Text(
                "↻ ${strings.retry}",
                style = MaterialTheme.typography.labelLarge, color = DashboardTokens.accentInk,
                modifier = Modifier.padding(top = Dimens.s4).clip(shape).clickable(role = Role.Button) { onRetry(draft.localId) }
                    .padding(horizontal = Dimens.s8, vertical = Dimens.s8),
            )
        }
    }
}
