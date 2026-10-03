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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.sp
import io.github.vinceglb.filekit.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.core.PickerMode
import io.github.vinceglb.filekit.core.PickerType
import io.github.vinceglb.filekit.core.PlatformFile
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import quest.api.UploadFile
import quest.core.platform.Today
import quest.core.platform.photoAsJpeg
import quest.core.platform.rememberCameraCapture
import quest.feature.chat.domain.MAX_ATTACHMENTS
import quest.feature.chat.domain.PHOTO_MAX_PX
import quest.feature.chat.domain.PHOTO_QUALITY
import quest.feature.chat.domain.PickedFile
import quest.feature.chat.domain.contentTypeOf
import quest.feature.chat.domain.preparePhoto
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
    AttachmentRefusal.PHOTO_TOO_MANY_PIXELS -> strings.chatFiles.attachTooManyPixels
    AttachmentRefusal.ALREADY_SENT -> strings.chatFiles.attachAlreadySent
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
    drafts: List<AttachmentDraft>,
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
    draft: AttachmentDraft,
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
            // A 48 dp target, as the remove button beside it.
            Box(
                Modifier.padding(top = Dimens.s4).heightIn(min = 48.dp).clip(shape)
                    .clickable(role = Role.Button) { onRetry(draft.localId) }.padding(horizontal = Dimens.s8),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text("↻ ${strings.retry}", style = MaterialTheme.typography.labelLarge, color = DashboardTokens.accentInk)
            }
        }
    }
}

/**
 * M7: the paper-clip and its menu — the camera (where there is one), the gallery (several at once) and a PDF. Each
 * pick is handed over as [PickedFile]s for the view model's [AttachmentDrafts] to check, re-encode, stage and upload.
 * Full once five files are on the message ([already] counts them). M8: the Complaints page uses the same button.
 */
@Composable
fun AttachMenuButton(already: Int, strings: Strings, onPick: (List<PickedFile>) -> Unit, modifier: Modifier = Modifier.size(48.dp)) {
    var showAttachMenu by remember { mutableStateOf(false) }
    // The room left on the message caps what a picker lets her choose.
    val room = (MAX_ATTACHMENTS - already).coerceAtLeast(1)
    val galleryPicker = rememberFilePickerLauncher(type = PickerType.Image, mode = PickerMode.Multiple(maxItems = room)) { files ->
        if (!files.isNullOrEmpty()) onPick(files.map { it.picked(photo = true) })
    }
    val pdfPicker = rememberFilePickerLauncher(type = PickerType.File(listOf("pdf")), mode = PickerMode.Multiple(maxItems = room)) { files ->
        if (!files.isNullOrEmpty()) onPick(files.map { it.picked(photo = false) })
    }
    val camera = rememberCameraCapture { path -> if (path != null) onPick(listOf(capturedPhoto(path))) }
    Box {
        IconButton(
            onClick = { showAttachMenu = true },
            enabled = already < MAX_ATTACHMENTS,
            modifier = modifier.semantics { contentDescription = strings.chatFiles.attach },
        ) {
            Text("📎", fontSize = 20.sp)
        }
        DropdownMenu(expanded = showAttachMenu, onDismissRequest = { showAttachMenu = false }) {
            if (camera != null) DropdownMenuItem(
                text = { Text(strings.chatFiles.attachCamera) },
                leadingIcon = { Text("📷") },
                onClick = { showAttachMenu = false; camera() },
            )
            DropdownMenuItem(
                text = { Text(strings.chatFiles.attachGallery) },
                leadingIcon = { Text("🖼️") },
                onClick = { showAttachMenu = false; galleryPicker.launch() },
            )
            DropdownMenuItem(
                text = { Text(strings.chatFiles.attachPdf) },
                leadingIcon = { Text("📄") },
                onClick = { showAttachMenu = false; pdfPicker.launch() },
            )
        }
    }
}

/**
 * A file from FileKit's picker as the view model takes it: sized up front (an unknown size is refused rather than
 * read to find out), read only after [refusalFor] has passed it, and — a photo — re-encoded by [preparePhoto].
 */
private fun PlatformFile.picked(photo: Boolean) = PickedFile(name, getSize() ?: Long.MAX_VALUE, photo) {
    val bytes = readBytes()
    if (photo) photoToUpload(name, bytes) else contentTypeOf(name)?.let { UploadFile(name, it, bytes) }
}

/** The camera's capture: sized from the disk, read and re-encoded off the main thread, and deleted once read. */
private fun capturedPhoto(path: String): PickedFile {
    val file = Path(path)
    val size = runCatching { SystemFileSystem.metadataOrNull(file)?.size }.getOrNull() ?: Long.MAX_VALUE
    return PickedFile("photo-${Today.epochMillis()}.jpg", size, photo = true) {
        try {
            photoToUpload("photo.jpg", SystemFileSystem.source(file).buffered().use { it.readByteArray() })
        } finally {
            runCatching { SystemFileSystem.delete(file, mustExist = false) }
        }
    }
}

private fun reencode(bytes: ByteArray): ByteArray? = photoAsJpeg(bytes, PHOTO_MAX_PX, PHOTO_QUALITY)

/** A gallery or camera photo as every picker in the app uploads it — re-encoded, downscaled, without metadata. */
internal fun photoToUpload(name: String, bytes: ByteArray): UploadFile? = preparePhoto(name, bytes, ::reencode)
