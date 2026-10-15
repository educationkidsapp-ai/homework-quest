package quest.api.dto

import kotlinx.serialization.Serializable

/**
 * Feature 22 — **voice note to announcement** (`docs/features/22-voice-to-announcement.md`). A leader speaks a short
 * note in Saudi dialect and gets a formal announcement in Modern Standard Arabic and in English, addressed to real
 * parents, ready to review. **Nothing is sent until she presses Send**, which is the existing
 * `POST /management/broadcasts` carrying [CreateBroadcastRequest.voiceDraftId].
 *
 * **Speech to text runs on the device** — the browser's Web Speech API on the dashboard, `SpeechRecognizer` and
 * `SFSpeechRecognizer` in the app, both with `ar-SA`. The server receives *text only*: there is no audio endpoint and
 * no audio storage, so the spec's "the audio file is gone after transcription" holds by construction. Nothing a child
 * or a parent is named in ever reaches the model: the prompt carries the transcript, today's date in the school's zone
 * and the caller's own (academy, grade) cells, and nothing else.
 *
 * Behind the `voiceAnnouncement` flag and `broadcast.voice` (ADMIN, MANAGERIAL): 404 while the flag is off.
 */

/**
 * `POST /management/broadcasts/voice-drafts` — the transcript the device produced. At most 4,000 characters and not
 * blank, otherwise 400; two minutes of speech is well inside that.
 */
@Serializable
data class CreateVoiceDraftRequest(val transcript: String)

/**
 * The audience the assistant suggests, which the leader may change before she sends. Both fields are optional and the
 * whole object is absent when nothing could be suggested — meaning the author's whole scope, exactly as an empty
 * [CreateBroadcastRequest.sectionIds] does.
 *
 * **The server keeps a suggestion only when it matches one of the author's real cells** (a `classes` row of her own
 * department in her own school). A grade the school does not teach, the other academy, and another school's academy are
 * each dropped rather than corrected, so the preview can never address a class that does not exist.
 */
@Serializable
data class VoiceDraftAudience(val curriculum: Curriculum? = null, val grade: Int? = null)

/**
 * One prepared announcement, stored unsent. [transcript] is kept beside the text so the preview can show what she
 * actually said underneath it, and an unsent draft is deleted after seven days.
 *
 * [expiresAt] is epoch milliseconds, the end of the day the note mentioned in the school's zone, and absent when she
 * named no date or named one that is in the past or more than 400 days out — the broadcast rule.
 */
@Serializable
data class VoiceDraft(
    val id: String,
    val title: String,
    val bodyAr: String,
    val bodyEn: String,
    val audience: VoiceDraftAudience? = null,
    val expiresAt: Long? = null,
    val transcript: String,
)
