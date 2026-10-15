package quest.feature.staff.domain

import quest.api.dashboard.ManagementGradeGroup
import quest.api.dto.BroadcastView
import quest.api.dto.CreateBroadcastRequest
import quest.api.dto.CreateVoiceDraftRequest
import quest.api.dto.VoiceDraft

/**
 * Feature 22: the three routes a leader's voice announcement needs, as their own port.
 *
 * It is separate from [StaffGateway] on purpose. These are the dashboard's `/management` routes taking a staff token,
 * they belong to one screen only, and a port a test has to answer in full is a port every other feature's fake has to
 * grow with. The contract's own `StaffApi` is untouched for the same reason: a method added there would break every
 * implementor outside this app.
 *
 * **Nothing here sends anything by itself.** [voiceDraft] prepares and stores an unsent draft; [createBroadcast] is
 * the announcement flow that already existed, reached only by the leader pressing Send.
 */
interface VoiceAnnouncementGateway {
    /** `GET /management/classes` — the (academy, grade) cells she may address, with their real sections. */
    suspend fun managementClasses(): List<ManagementGradeGroup>

    /**
     * `POST /management/broadcasts/voice-drafts` — the transcript the device produced, back as a formal announcement
     * in Arabic and English. `422 rephrase` when the assistant could not make one, `502 model_failed` when the
     * provider failed, and `404` while `voiceAnnouncement` is off.
     */
    suspend fun voiceDraft(request: CreateVoiceDraftRequest): VoiceDraft

    /** `POST /management/broadcasts` — Send, carrying [CreateBroadcastRequest.voiceDraftId] and whatever she edited. */
    suspend fun createBroadcast(request: CreateBroadcastRequest): BroadcastView
}
