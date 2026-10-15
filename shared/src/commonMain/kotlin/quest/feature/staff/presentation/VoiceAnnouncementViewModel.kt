package quest.feature.staff.presentation

import kotlinx.coroutines.delay
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.coroutines.flow.StateFlow
import quest.api.ApiException
import quest.api.dashboard.ManagementGradeGroup
import quest.api.dto.ApiError
import quest.api.dto.BroadcastAudience
import quest.api.dto.BroadcastKind
import quest.api.dto.CreateBroadcastRequest
import quest.api.dto.CreateVoiceDraftRequest
import quest.api.dto.Curriculum
import quest.api.dto.VoiceDraft
import quest.core.mvi.MviEffect
import quest.core.mvi.MviIntent
import quest.core.mvi.MviState
import quest.core.mvi.MviViewModel
import quest.feature.staff.domain.StaffProfile
import quest.feature.staff.domain.VoiceAnnouncementGateway

/**
 * Feature 22, what the screen says in one line — as data rather than as a sentence, exactly as [RequestMessage] and
 * [CheckInMessage] are, so a test asserts the outcome without reading Arabic or English.
 */
sealed interface VoiceMessage {
    /** The recogniser heard nothing worth sending: the microphone was refused, or she said nothing. */
    data object NothingHeard : VoiceMessage

    /** This phone has no Arabic recogniser, so there is nothing to record with. */
    data object NoRecogniser : VoiceMessage

    /** 422 `rephrase`: the assistant could not make an announcement out of it, twice. The owner's own words. */
    data object Rephrase : VoiceMessage

    /** Anything else the server or the provider did — one generic sentence, never a status code. */
    data object Failed : VoiceMessage
    data object Offline : VoiceMessage

    /** The expiry she typed is not a date, or it has already passed. */
    data object ExpiryInvalid : VoiceMessage

    /** The announcement went out. */
    data object Sent : VoiceMessage
}

/**
 * Feature 22 on the phone: a leader speaks a short note and gets a formal announcement in Arabic and English, ready
 * to review. **Nothing is sent until she presses Send** — [Intent.Send] is the only intent that posts anything, and
 * `POST /management/broadcasts/voice-drafts` deliberately stores the draft unsent.
 *
 * Speech to text happens on the device and **outside this class**: the screen drives `SpeechInput` from
 * [State.recording] and feeds what it hears back as [Intent.Transcribed], so the two-minute cap, the role gate and
 * the preview are all testable with no platform under them.
 */
object VoiceAnnouncementContract {

    /** Where she is in the flow. There is no step between [PREVIEW] and sending that posts anything. */
    enum class Step { RECORD, PREPARING, PREVIEW, SENT }

    /**
     * The editable preview. [curriculum] and [grade] start as the server's suggestion — which it already narrowed to
     * her real cells — and [expiresOn] is an ISO date or blank, because the app has no date picker yet.
     */
    data class Form(
        val title: String = "",
        val bodyAr: String = "",
        val bodyEn: String = "",
        val curriculum: Curriculum? = null,
        val grade: Int? = null,
        val expiresOn: String = "",
    )

    data class State(
        /** False until `GET /staff/me` is known, and false for anyone who is not a leader with both flags on. */
        val allowed: Boolean = false,
        val step: Step = Step.RECORD,
        val recording: Boolean = false,
        val elapsedSeconds: Int = 0,
        /** What the recogniser has heard so far; read-only under the preview, as the spec asks. */
        val transcript: String = "",
        val draftId: String? = null,
        val form: Form = Form(),
        /** The (academy, grade) cells of `GET /management/classes`, which the two pickers are built from. */
        val cells: List<ManagementGradeGroup> = emptyList(),
        val sending: Boolean = false,
        val message: VoiceMessage? = null,
    ) : MviState {
        val remainingSeconds: Int get() = (CAP_SECONDS - elapsedSeconds).coerceAtLeast(0)

        /** Send is offered only on a preview that has something to send, and never twice. */
        val canSend: Boolean get() = allowed && step == Step.PREVIEW && !sending &&
            form.title.isNotBlank() && form.bodyAr.isNotBlank() && form.bodyEn.isNotBlank()

        /** The academies she may address, in the order the server listed them. */
        val curricula: List<Curriculum> get() = cells.map { it.curriculum }.distinct()

        /** The grades of the chosen academy — every grade she reaches when she has chosen none. */
        fun grades(of: Curriculum?): List<Int> =
            cells.filter { of == null || it.curriculum == of }.map { it.grade }.distinct().sorted()
    }

    sealed interface Intent : MviIntent {
        /** On open: who she is, and the cells she may address. */
        data object Open : Intent
        data object StartRecording : Intent
        data object StopRecording : Intent
        /** The whole transcript so far, as `SpeechInput` hands it over. */
        data class Transcribed(val text: String, val final: Boolean) : Intent
        /** The recogniser closed the microphone by itself (a refused permission, no engine). */
        data object RecordingEnded : Intent
        data class SetTitle(val text: String) : Intent
        data class SetBodyAr(val text: String) : Intent
        data class SetBodyEn(val text: String) : Intent
        data class SetCurriculum(val curriculum: Curriculum?) : Intent
        data class SetGrade(val grade: Int?) : Intent
        data class SetExpiry(val text: String) : Intent
        /** The one intent that posts an announcement. */
        data object Send : Intent
        /** Start over from the recorder, keeping nothing. */
        data object Again : Intent
        data object DismissMessage : Intent
    }

    sealed interface Effect : MviEffect {
        /** The announcement went out; the route closes itself. */
        data object Sent : Effect
    }

    /** Two minutes, as the spec words it. */
    const val CAP_SECONDS = 120

    /** The server's 422 code (`VoiceDraftService.REPHRASE`); not in the contract, which carries codes as strings. */
    const val REPHRASE = "rephrase"

    /** The cap on a transcript the server takes (`CreateVoiceDraftRequest`), applied here so a 400 is impossible. */
    const val TRANSCRIPT_MAX = 4_000
}

class VoiceAnnouncementViewModel(
    private val api: VoiceAnnouncementGateway,
    private val profile: StateFlow<StaffProfile?>,
    /** The zone the expiry date is read in — the device's, which is the school's on a school phone. */
    private val zone: TimeZone = TimeZone.currentSystemDefault(),
    /** One second of her two minutes. A test shortens it rather than waiting two real minutes. */
    private val tickMillis: Long = 1_000,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : MviViewModel<VoiceAnnouncementContract.State, VoiceAnnouncementContract.Intent, VoiceAnnouncementContract.Effect>(
    VoiceAnnouncementContract.State(),
) {

    override suspend fun handle(intent: VoiceAnnouncementContract.Intent) {
        // The role gate, in front of everything: Open is the only intent anyone may send, and it is what decides.
        if (intent !is VoiceAnnouncementContract.Intent.Open && !current.allowed) return
        when (intent) {
            VoiceAnnouncementContract.Intent.Open -> open()
            VoiceAnnouncementContract.Intent.StartRecording -> startRecording()
            VoiceAnnouncementContract.Intent.StopRecording -> stopRecording()
            is VoiceAnnouncementContract.Intent.Transcribed -> reduce {
                copy(transcript = intent.text.take(VoiceAnnouncementContract.TRANSCRIPT_MAX))
            }
            VoiceAnnouncementContract.Intent.RecordingEnded -> recordingEnded()
            is VoiceAnnouncementContract.Intent.SetTitle -> form { copy(title = intent.text.take(TITLE_MAX)) }
            is VoiceAnnouncementContract.Intent.SetBodyAr -> form { copy(bodyAr = intent.text.take(BODY_MAX)) }
            is VoiceAnnouncementContract.Intent.SetBodyEn -> form { copy(bodyEn = intent.text.take(BODY_MAX)) }
            is VoiceAnnouncementContract.Intent.SetCurriculum -> form {
                // A grade the new academy does not teach would address nobody, so it goes with it.
                val keep = intent.curriculum == null || grade == null ||
                    current.cells.any { it.curriculum == intent.curriculum && it.grade == grade }
                copy(curriculum = intent.curriculum, grade = if (keep) grade else null)
            }
            is VoiceAnnouncementContract.Intent.SetGrade -> form { copy(grade = intent.grade) }
            is VoiceAnnouncementContract.Intent.SetExpiry -> form { copy(expiresOn = intent.text.trim()) }
            VoiceAnnouncementContract.Intent.Send -> send()
            VoiceAnnouncementContract.Intent.Again -> reduce {
                copy(
                    step = VoiceAnnouncementContract.Step.RECORD, recording = false, elapsedSeconds = 0,
                    transcript = "", draftId = null, form = VoiceAnnouncementContract.Form(), message = null,
                )
            }
            VoiceAnnouncementContract.Intent.DismissMessage -> reduce { copy(message = null) }
        }
    }

    private suspend fun open() {
        val me = profile.value
        val allowed = me?.canVoiceAnnounce == true
        reduce { copy(allowed = allowed) }
        if (!allowed) return
        // The cells are hers by construction: `GET /management/classes` is already narrowed to her department.
        val cells = runCatching { api.managementClasses() }.getOrDefault(emptyList())
        reduce { copy(cells = cells) }
    }

    private suspend fun startRecording() {
        if (current.recording) return
        reduce {
            copy(
                step = VoiceAnnouncementContract.Step.RECORD, recording = true, elapsedSeconds = 0,
                transcript = "", draftId = null, form = VoiceAnnouncementContract.Form(), message = null,
            )
        }
        launch {
            // Her two minutes, counted here so the cap holds whatever the recogniser does — and stopping is a
            // state change, which is what closes the microphone: the screen follows `recording`.
            while (current.recording && current.elapsedSeconds < VoiceAnnouncementContract.CAP_SECONDS) {
                delay(tickMillis)
                if (!current.recording) return@launch
                reduce { copy(elapsedSeconds = elapsedSeconds + 1) }
            }
            if (current.recording) dispatch(VoiceAnnouncementContract.Intent.StopRecording)
        }
    }

    private suspend fun stopRecording() {
        if (!current.recording) return
        reduce { copy(recording = false) }
        // The recogniser is closed by the screen following `recording`, and it answers the close with its last
        // words. A short grace lets them land, so the draft is made from everything she actually said.
        delay(minOf(SETTLE_MILLIS, tickMillis))
        prepare()
    }

    /** The microphone closed without being asked to. Whatever was heard is still worth preparing. */
    private suspend fun recordingEnded() {
        if (!current.recording) return
        reduce { copy(recording = false) }
        prepare()
    }

    private suspend fun prepare() {
        val said = current.transcript.trim()
        if (said.isBlank()) {
            reduce { copy(step = VoiceAnnouncementContract.Step.RECORD, message = VoiceMessage.NothingHeard) }
            return
        }
        reduce { copy(step = VoiceAnnouncementContract.Step.PREPARING, message = null) }
        try {
            fill(api.voiceDraft(CreateVoiceDraftRequest(said)))
        } catch (e: ApiException) {
            reduce { copy(step = VoiceAnnouncementContract.Step.RECORD, message = e.asMessage()) }
        } catch (_: Throwable) {
            reduce { copy(step = VoiceAnnouncementContract.Step.RECORD, message = VoiceMessage.Offline) }
        }
    }

    /** The draft, as the preview: her text, her audience and her expiry, all editable from here on. */
    private suspend fun fill(draft: VoiceDraft) = reduce {
        copy(
            step = VoiceAnnouncementContract.Step.PREVIEW,
            draftId = draft.id,
            transcript = draft.transcript,
            form = VoiceAnnouncementContract.Form(
                title = draft.title,
                bodyAr = draft.bodyAr,
                bodyEn = draft.bodyEn,
                curriculum = draft.audience?.curriculum,
                grade = draft.audience?.grade,
                expiresOn = draft.expiresAt?.let { Instant.fromEpochMilliseconds(it).toLocalDateTime(zone).date.toString() }.orEmpty(),
            ),
            message = null,
        )
    }

    /**
     * **Send.** The existing announcement flow, carrying `voiceDraftId` so the transcript stays with what went out.
     *
     * The academy is expressed as the real sections of the chosen cell, because `CreateBroadcastRequest` has no
     * curriculum field: an empty `sectionIds` means her whole department, and `grade` alone narrows that to one grade
     * of it. The two may not be combined, so a chosen academy names sections and leaves `grade` off.
     */
    private suspend fun send() {
        if (!current.canSend) return
        val form = current.form
        val expiresAt = when {
            form.expiresOn.isBlank() -> null
            else -> endOfDay(form.expiresOn) ?: run {
                reduce { copy(message = VoiceMessage.ExpiryInvalid) }
                return
            }
        }
        if (expiresAt != null && expiresAt <= now()) {
            reduce { copy(message = VoiceMessage.ExpiryInvalid) }
            return
        }
        val sections = sectionsOf(form)
        reduce { copy(sending = true, message = null) }
        try {
            api.createBroadcast(
                CreateBroadcastRequest(
                    kind = BroadcastKind.ANNOUNCEMENT,
                    title = form.title.trim(),
                    bodyEn = form.bodyEn.trim(),
                    bodyAr = form.bodyAr.trim(),
                    grade = if (sections.isEmpty()) form.grade else null,
                    audience = listOf(BroadcastAudience.PARENTS),
                    sectionIds = sections,
                    expiresAt = expiresAt,
                    voiceDraftId = current.draftId,
                ),
            )
            reduce { copy(sending = false, step = VoiceAnnouncementContract.Step.SENT, message = VoiceMessage.Sent) }
            effect(VoiceAnnouncementContract.Effect.Sent)
        } catch (e: ApiException) {
            reduce { copy(sending = false, message = e.asMessage()) }
        } catch (_: Throwable) {
            reduce { copy(sending = false, message = VoiceMessage.Offline) }
        }
    }

    /** The sections of the chosen cell, or none at all when she named no academy — her whole department. */
    private fun sectionsOf(form: VoiceAnnouncementContract.Form): List<String> {
        val track = form.curriculum ?: return emptyList()
        return current.cells
            .filter { it.curriculum == track && (form.grade == null || it.grade == form.grade) }
            .flatMap { group -> group.classes.map { it.classId } }
            .distinct()
    }

    /** The last millisecond of the day she typed, or null when that is not a date. */
    private fun endOfDay(text: String): Long? {
        val date = runCatching { LocalDate.parse(text) }.getOrNull() ?: return null
        return date.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone).toEpochMilliseconds() - 1
    }

    private suspend fun form(change: VoiceAnnouncementContract.Form.() -> VoiceAnnouncementContract.Form) =
        reduce { copy(form = form.change(), message = null) }

    private fun ApiException.asMessage(): VoiceMessage = when (error.code) {
        VoiceAnnouncementContract.REPHRASE -> VoiceMessage.Rephrase
        ApiError.NETWORK -> VoiceMessage.Offline
        else -> VoiceMessage.Failed
    }

    private companion object {
        const val TITLE_MAX = 200
        const val BODY_MAX = 4_000

        /** How long the last utterance is given to arrive after the microphone closes. */
        const val SETTLE_MILLIS = 400L
    }
}
