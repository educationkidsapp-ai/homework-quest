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

/** The width a bubble asks the server to downscale a photo to (`?w=` takes 320, 640 or 1280), and decodes it to. */
const val THUMBNAIL_PX = 640

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
enum class AttachmentRefusal { TOO_MANY, PHOTO_TOO_LARGE, PDF_TOO_LARGE, WRONG_TYPE, UNREADABLE, PHOTO_TOO_MANY_PIXELS, ALREADY_SENT }

/**
 * What the server's refusal of an upload means to the parent (B5): `too_large` over the byte caps, `image_too_large`
 * over 8192 px a side or 40 MP, `bad_request` for a file it could not read or does not take. Null for anything a
 * retry may cure (the network, a 5xx), which keeps the draft in the tray with its retry.
 */
fun refusalForUpload(code: String, contentType: String): AttachmentRefusal? = when (code) {
    "too_large" -> if (isPdf(contentType)) AttachmentRefusal.PDF_TOO_LARGE else AttachmentRefusal.PHOTO_TOO_LARGE
    "image_too_large" -> AttachmentRefusal.PHOTO_TOO_MANY_PIXELS
    "bad_request" -> AttachmentRefusal.UNREADABLE
    else -> null
}

/**
 * The largest photo the app will read at all (M7 review). Every photo is re-encoded before it goes up, so its own
 * size is not the 5 MB cap — but nothing past this is ever loaded into memory to find that out.
 */
const val MAX_PHOTO_SOURCE_BYTES: Long = 25L * 1024 * 1024

/**
 * The check made before a byte is read: room on the message ([already] files picked), the type, and the size. A
 * [photo] from the gallery or the camera is held only to [MAX_PHOTO_SOURCE_BYTES], because [preparePhoto] re-encodes
 * every one; the JPEG it produces is checked again with `photo = false`.
 */
fun refusalFor(fileName: String, size: Long, already: Int, photo: Boolean = false): AttachmentRefusal? {
    if (already >= MAX_ATTACHMENTS) return AttachmentRefusal.TOO_MANY
    if (photo) return if (size > MAX_PHOTO_SOURCE_BYTES) AttachmentRefusal.PHOTO_TOO_LARGE else null
    val type = contentTypeOf(fileName) ?: return AttachmentRefusal.WRONG_TYPE
    return when {
        isPdf(type) && size > MAX_PDF_BYTES -> AttachmentRefusal.PDF_TOO_LARGE
        !isPdf(type) && size > MAX_PHOTO_BYTES -> AttachmentRefusal.PHOTO_TOO_LARGE
        else -> null
    }
}

/** The longest edge and the JPEG quality every photo is re-encoded to before it goes up. */
const val PHOTO_MAX_PX = 2560
const val PHOTO_QUALITY = 85

/**
 * A gallery or camera photo as it will be uploaded: **always** re-encoded by [toJpeg] — at most [PHOTO_MAX_PX] on its
 * longer edge, the orientation applied to the pixels, and **no metadata at all**. Sending the original bytes would
 * send its EXIF with it, and a phone's photo carries where it was taken; the server does not strip it either. It also
 * turns an iPhone's HEIC into a JPEG the server takes. Null when the bytes cannot be read as an image.
 */
fun preparePhoto(name: String, bytes: ByteArray, toJpeg: (ByteArray) -> ByteArray?): UploadFile? {
    val jpeg = toJpeg(bytes)?.takeIf { it.isNotEmpty() } ?: return null
    return UploadFile(name.substringBeforeLast('.') + ".jpg", "image/jpeg", jpeg)
}

/** A file the parent picked; [read] answers it as it will be uploaded, and runs only once it has passed [refusalFor]. */
class PickedFile(val name: String, val size: Long, val photo: Boolean, val read: suspend () -> UploadFile?)

/** A file on its way up, kept in the app's cache rather than in memory until the server has it. */
data class StagedUpload(val path: String, val name: String, val contentType: String, val size: Long)

/**
 * Where picked files wait between the picker and the server (M7 review): a file in the cache, written off the main
 * thread, deleted once uploaded or removed — and with the rest of the cache on sign-out.
 */
interface UploadStaging {
    /** Writes [file] to the cache; null when it could not be written. */
    suspend fun stage(file: UploadFile): StagedUpload?
    fun discard(staged: StagedUpload)
}

/**
 * B5's upload for a chat message (`POST /children/{id}/chat/attachments`), streamed from the staged file with its
 * length, and reporting progress in 0..1. Two implementations: the server's (Ktor's upload progress) and the fake's,
 * which answers in one step — the screen reads both the same way.
 */
fun interface AttachmentUploader {
    suspend fun upload(childId: String, file: StagedUpload, onProgress: (Float) -> Unit): AttachmentRef
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
