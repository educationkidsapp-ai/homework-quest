package quest.feature.chat.presentation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import quest.api.ApiException
import quest.api.dto.AttachmentRef
import quest.core.platform.Ids
import quest.feature.chat.domain.AttachmentRefusal
import quest.feature.chat.domain.PickedFile
import quest.feature.chat.domain.StagedUpload
import quest.feature.chat.domain.UploadStaging
import quest.feature.chat.domain.refusalFor
import quest.feature.chat.domain.refusalForUpload

/**
 * M7: one file in a composer's tray. It uploads the moment it is picked, so by the time she has written her sentence
 * the photo is usually up; [ref] is the server's answer, and the message can only go once every draft has one. A
 * failed upload stays in the tray with its own retry rather than disappearing. M8: the same tray serves a complaint.
 */
data class AttachmentDraft(
    val localId: String,
    val name: String,
    val contentType: String,
    val size: Long,
    val progress: Float = 0f,
    val ref: AttachmentRef? = null,
    val failed: Boolean = false,
) {
    val uploading: Boolean get() = ref == null && !failed
}

/** The tray as a screen holds it: the drafts and why the last picked file was not added. */
data class DraftsState(val drafts: List<AttachmentDraft> = emptyList(), val refusal: AttachmentRefusal? = null)

/**
 * M7's attachment pipeline, shared by Messages and (M8) Complaints so there is one of it: each picked file is checked
 * before a byte of it is read (type, size, room on the message), then read off the main thread — every photo is
 * re-encoded there, at most 2560 px and **without its metadata** ([quest.feature.chat.domain.preparePhoto]) — checked
 * again as it will go up, written to the cache folder ([UploadStaging]) and streamed up with its length. The staged
 * copy is deleted as soon as the server has it, the draft is removed, the message goes, or the screen goes away.
 *
 * The owning view model gives it its scope and a [read] / [write] pair over its own state's tray.
 */
class AttachmentDrafts(
    private val scope: CoroutineScope,
    private val staging: UploadStaging,
    private val upload: suspend (StagedUpload, (Float) -> Unit) -> AttachmentRef,
    private val read: () -> DraftsState,
    private val write: (DraftsState.() -> DraftsState) -> Unit,
) {
    private val pendingFiles = mutableMapOf<String, StagedUpload>()
    private val uploadJobs = mutableMapOf<String, Job>()

    /** The last refusal is the one sentence the tray shows; the files that passed are added either way. */
    suspend fun add(files: List<PickedFile>) {
        var refusal: AttachmentRefusal? = null
        for (file in files) {
            val early = refusalFor(file.name, file.size, read().drafts.size, file.photo)
            if (early != null) { refusal = early; continue }
            val prepared = withContext(Dispatchers.Default) { runCatching { file.read() }.getOrNull() }
            if (prepared == null || prepared.bytes.isEmpty()) { refusal = AttachmentRefusal.UNREADABLE; continue }
            val late = refusalFor(prepared.fileName, prepared.bytes.size.toLong(), read().drafts.size)
            if (late != null) { refusal = late; continue }
            val staged = staging.stage(prepared)
            if (staged == null) { refusal = AttachmentRefusal.UNREADABLE; continue }
            val localId = Ids.random()
            pendingFiles[localId] = staged
            write { copy(drafts = drafts + AttachmentDraft(localId, staged.name, staged.contentType, staged.size)) }
            retry(localId)
        }
        write { copy(refusal = refusal) }
    }

    /** Starts (or restarts) one draft's upload from its staged file. */
    fun retry(localId: String) {
        val file = pendingFiles[localId] ?: return
        update(localId) { it.copy(progress = 0f, failed = false) }
        uploadJobs[localId]?.cancel()
        uploadJobs[localId] = scope.launch {
            try {
                val ref = upload(file) { sent -> update(localId) { it.copy(progress = sent) } }
                update(localId) { it.copy(ref = ref, progress = 1f) }
                // The server has it; the message names it by id from here on, so the staged copy goes now.
                pendingFiles.remove(localId)?.let(staging::discard)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                // A refusal no retry can cure takes the file out of the tray and says why; anything else stays to retry.
                val refusal = refusalForUpload(e.error.code, file.contentType)
                if (refusal == null) update(localId) { it.copy(failed = true) }
                else { drop(localId); write { copy(refusal = refusal) } }
            } catch (_: Throwable) {
                update(localId) { it.copy(failed = true) }
            }
        }
    }

    fun remove(localId: String) {
        uploadJobs.remove(localId)?.cancel()
        drop(localId)
        write { copy(refusal = null) }
    }

    /** The message that names these drafts is on its way: their staged copies go, and the tray empties. */
    fun sent() {
        read().drafts.forEach { draft -> pendingFiles.remove(draft.localId)?.let(staging::discard); uploadJobs.remove(draft.localId) }
        write { DraftsState() }
    }

    /** The screen went away: nothing keeps uploading, nothing stays in the cache. */
    fun clear() {
        uploadJobs.values.forEach { it.cancel() }
        uploadJobs.clear()
        pendingFiles.values.forEach(staging::discard)
        pendingFiles.clear()
    }

    private fun update(localId: String, change: (AttachmentDraft) -> AttachmentDraft) =
        write { copy(drafts = drafts.map { if (it.localId == localId) change(it) else it }) }

    private fun drop(localId: String) {
        pendingFiles.remove(localId)?.let(staging::discard)
        uploadJobs.remove(localId)
        write { copy(drafts = drafts.filterNot { it.localId == localId }) }
    }
}
