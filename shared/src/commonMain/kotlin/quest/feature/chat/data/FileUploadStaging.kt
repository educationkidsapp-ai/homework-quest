package quest.feature.chat.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import quest.api.UploadFile
import quest.core.platform.safeFileName
import quest.feature.chat.domain.StagedUpload
import quest.feature.chat.domain.UploadStaging
import quest.core.platform.Ids

/**
 * M7 (review): picked files wait in `<documents cache>/chat-uploads/`, one file each, rather than as byte arrays in the
 * view model — five photos in a tray are five files, not 25 MB of heap. The folder sits inside the document cache the
 * sign-out already empties ([quest.feature.broadcasts.domain.AttachmentDocuments.clear]), so a half-sent tray leaves
 * nothing behind on the device either. Every write and delete runs on [io].
 */
class FileUploadStaging(
    private val directory: () -> String,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : UploadStaging {

    override suspend fun stage(file: UploadFile): StagedUpload? = withContext(io) {
        runCatching {
            val folder = Path(directory(), FOLDER).also { SystemFileSystem.createDirectories(it) }
            val path = Path(folder, "${Ids.random()}-${safeFileName(file.fileName)}")
            SystemFileSystem.sink(path).buffered().use { it.write(file.bytes) }
            StagedUpload(path.toString(), file.fileName, file.mimeType, file.bytes.size.toLong())
        }.getOrNull()
    }

    override fun discard(staged: StagedUpload) {
        runCatching { SystemFileSystem.delete(Path(staged.path), mustExist = false) }
    }

    private companion object {
        const val FOLDER = "chat-uploads"
    }
}

/** The staged bytes as a stream, for an upload that sends them with their length and never holds them whole. */
fun StagedUpload.source(): Source = SystemFileSystem.source(Path(path)).buffered()

/** The staged bytes whole — only for the fake API, whose contract takes a byte array. */
fun StagedUpload.readBytes(): ByteArray = source().use { it.readByteArray() }
