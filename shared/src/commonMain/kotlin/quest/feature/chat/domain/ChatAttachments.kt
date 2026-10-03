package quest.feature.chat.domain

import quest.api.UploadFile
import quest.api.dto.AttachmentRef
import quest.api.dto.BroadcastAttachment
import quest.api.dto.ChatAttachment
import quest.feature.broadcasts.domain.MAX_DOCUMENT_BYTES

/** M7 (B5): at most this many files on one message — the server answers 400 past it. */
const val MAX_ATTACHMENTS = 5

/** B5: a chat photo is at most 5 MB, a PDF at most 10 MB (the weekly plan's cap, so one constant serves both). */
const val MAX_PHOTO_BYTES: Long = 5L * 1024 * 1024
const val MAX_PDF_BYTES: Long = MAX_DOCUMENT_BYTES

const val PDF_TYPE = "application/pdf"

/** The bound a bubble asks the server to downscale a photo to (`?w=`), and decodes it to. */
const val THUMBNAIL_PX = 720

/**
 * The media type a picked file is sent as, from its extension — or null when it is not one of the four the server
 * takes. The server sniffs the bytes anyway; this only decides whether the app offers to send the file at all.
 */
fun contentTypeOf(fileName: String): String? = when (fileName.substringAfterLast('.', "").lowercase()) {
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "webp" -> "image/webp"
    "pdf" -> PDF_TYPE
    else -> null
}

fun isPdf(contentType: String) = contentType == PDF_TYPE

/** Why a picked file is not added to the message. Each has its own sentence (EN/AR); none is an error colour (§7). */
enum class AttachmentRefusal { TOO_MANY, PHOTO_TOO_LARGE, PDF_TOO_LARGE, WRONG_TYPE, UNREADABLE }

/**
 * The check made before a byte is read: room on the message ([already] files picked), the type, and the size. A
 * [photo] from the gallery or the camera is let through on type and size alike, because [preparePhoto] re-encodes
 * what the server would refuse; the bytes it produces are checked again with `photo = false`.
 */
fun refusalFor(fileName: String, size: Long, already: Int, photo: Boolean = false): AttachmentRefusal? {
    if (already >= MAX_ATTACHMENTS) return AttachmentRefusal.TOO_MANY
    if (photo) return null
    val type = contentTypeOf(fileName) ?: return AttachmentRefusal.WRONG_TYPE
    return when {
        isPdf(type) && size > MAX_PDF_BYTES -> AttachmentRefusal.PDF_TOO_LARGE
        !isPdf(type) && size > MAX_PHOTO_BYTES -> AttachmentRefusal.PHOTO_TOO_LARGE
        else -> null
    }
}

/** The longest edge and the JPEG quality a photo is re-encoded to when the server would refuse it as it is. */
const val PHOTO_MAX_PX = 2560
const val PHOTO_QUALITY = 85

/**
 * A gallery or camera photo as it will be uploaded: unchanged when the server takes it (JPEG, PNG or WebP within
 * 5 MB), else re-encoded by [toJpeg] — an iPhone's HEIC, or a camera photo over the cap. Null when it cannot be read.
 */
fun preparePhoto(name: String, bytes: ByteArray, toJpeg: (ByteArray) -> ByteArray?): UploadFile? {
    val type = contentTypeOf(name)
    if (type != null && !isPdf(type) && bytes.size <= MAX_PHOTO_BYTES) return UploadFile(name, type, bytes)
    val jpeg = toJpeg(bytes)?.takeIf { it.isNotEmpty() } ?: return null
    return UploadFile(name.substringBeforeLast('.') + ".jpg", "image/jpeg", jpeg)
}

/** A file the parent picked; [read] answers it as it will be uploaded, and runs only once it has passed [refusalFor]. */
class PickedFile(val name: String, val size: Long, val photo: Boolean, val read: suspend () -> UploadFile?)

/**
 * `POST /media/attachments` with `purpose=chat`, reporting progress in 0..1. Two implementations: the server's (Ktor's
 * upload progress) and the fake's, which answers in one step — the screen reads both the same way.
 */
fun interface AttachmentUploader {
    suspend fun upload(childId: String, file: UploadFile, onProgress: (Float) -> Unit): AttachmentRef
}

/** What the server answered for an upload, as the message will carry it once sent. */
fun AttachmentRef.asChatAttachment() = ChatAttachment(id = id, contentType = type, name = name, size = sizeBytes, width = width, height = height)

/**
 * The weekly plan's loaders take a [BroadcastAttachment], keyed by its id and fetched from its url with the parent's
 * bearer — exactly what a chat file needs, so a chat file is handed to them in that shape rather than given a second
 * loader. The full-size copy is keyed by the attachment's own id.
 */
fun ChatAttachment.asDownload() = BroadcastAttachment(url = "/media/attachments/$id", name = name, id = id, type = contentType)

/**
 * The bubble's copy: the server's `?w=` thumbnail, cached under its own key so it never stands in for the full-size
 * bytes the viewer decodes when the photo is opened.
 */
fun ChatAttachment.asThumbnail() =
    BroadcastAttachment(url = "/media/attachments/$id?w=$THUMBNAIL_PX", name = name, id = "$id-w$THUMBNAIL_PX", type = contentType)

/** "820 KB", "1.4 MB" — the size a PDF card shows. */
fun formatBytes(size: Long): String = when {
    size >= 1024L * 1024 -> {
        val tenths = (size * 10 / (1024L * 1024)).toInt()
        "${tenths / 10}.${tenths % 10} MB"
    }
    else -> "${(size / 1024).coerceAtLeast(1)} KB"
}
