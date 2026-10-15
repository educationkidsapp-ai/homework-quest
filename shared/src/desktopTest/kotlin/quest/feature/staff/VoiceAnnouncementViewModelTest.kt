package quest.feature.staff

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.TimeZone
import quest.api.ApiException
import quest.api.DEFAULT_FLAGS
import quest.api.dashboard.CoordinatorClass
import quest.api.dashboard.ManagementGradeGroup
import quest.api.dto.ApiError
import quest.api.dto.BroadcastAudience
import quest.api.dto.BroadcastKind
import quest.api.dto.BroadcastView
import quest.api.dto.CreateBroadcastRequest
import quest.api.dto.CreateVoiceDraftRequest
import quest.api.dto.Curriculum
import quest.api.dto.Subject
import quest.api.dto.VoiceDraft
import quest.api.dto.VoiceDraftAudience
import quest.core.mvi.MviState
import quest.feature.school.domain.Flags
import quest.feature.staff.domain.StaffProfile
import quest.feature.staff.domain.StaffRole
import quest.feature.staff.domain.VoiceAnnouncementGateway
import quest.feature.staff.presentation.VoiceAnnouncementContract
import quest.feature.staff.presentation.VoiceAnnouncementViewModel
import quest.feature.staff.presentation.VoiceMessage
import quest.feature.staff.presentation.StaffStrings
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Feature 22 on the phone: a leader's voice note, turned into an announcement she reviews.
 *
 * The tests that matter are the ones the spec's acceptance criteria name. **Nothing is sent until she presses Send**,
 * so the gateway's `posted` list has to stay empty through recording, preparing and every edit. The recording stops
 * itself at two minutes. The suggestion the server made has to arrive in the preview — including the audience and the
 * expiry — because that is the whole point of the preview. And a teacher cannot reach any of it.
 */
class VoiceAnnouncementViewModelTest {

    /** The one cell-shaped answer `GET /management/classes` gives, with real section ids on its cards. */
    private val cells = listOf(Curriculum.BRITISH, Curriculum.AMERICAN).flatMap { track ->
        (1..3).map { grade ->
            ManagementGradeGroup(
                curriculum = track, grade = grade, sections = 1, children = 20,
                classes = listOf(
                    CoordinatorClass(
                        classId = "sec-${track.name.lowercase()}-$grade", className = "${grade}A",
                        curriculum = track, grade = grade, subject = Subject.MATH,
                    ),
                ),
            )
        }
    }

    /** Only feature 22's three routes; every other port is somebody else's test. */
    private class FakeGateway(
        var draftFailure: Throwable? = null,
        var sendFailure: Throwable? = null,
        var draft: VoiceDraft = DRAFT,
        private val cells: List<ManagementGradeGroup> = emptyList(),
    ) : VoiceAnnouncementGateway {
        val asked = mutableListOf<CreateVoiceDraftRequest>()
        val posted = mutableListOf<CreateBroadcastRequest>()

        override suspend fun managementClasses(): List<ManagementGradeGroup> = cells

        override suspend fun voiceDraft(request: CreateVoiceDraftRequest): VoiceDraft {
            draftFailure?.let { throw it }
            asked += request
            return draft.copy(transcript = request.transcript)
        }

        override suspend fun createBroadcast(request: CreateBroadcastRequest): BroadcastView {
            sendFailure?.let { throw it }
            posted += request
            return BroadcastView(
                id = "b-${posted.size}", kind = request.kind, authorId = "u-1", authorName = "Ms Noor",
                title = request.title, bodyEn = request.bodyEn.orEmpty(), bodyAr = request.bodyAr,
                grade = request.grade, sectionIds = request.sectionIds, audience = request.audience,
                expiresAt = request.expiresAt, createdAt = 0L,
            )
        }

        companion object {
            /** Epoch milliseconds inside 2026-11-30 in UTC, which is the zone the view model is given below. */
            const val EXPIRES_AT = 1_796_000_000_000L

            val DRAFT = VoiceDraft(
                id = "draft-1",
                title = "رحلة الصف الثالث",
                bodyAr = "نحيط أولياء أمور طلاب الصف الثالث علمًا بأن الرحلة غدًا.",
                bodyEn = "Dear parents of Grade 3, the trip is tomorrow.",
                audience = VoiceDraftAudience(curriculum = Curriculum.BRITISH, grade = 3),
                expiresAt = EXPIRES_AT,
                transcript = "",
            )
        }
    }

    private val built = mutableListOf<ViewModel>()

    @BeforeTest fun setUp() = Dispatchers.setMain(Dispatchers.Default)

    @AfterTest fun tearDown() {
        built.forEach { it.viewModelScope.cancel() }
        built.clear()
        Dispatchers.resetMain()
    }

    /** Her profile as `GET /staff/me` answered it: the role and her school's effective flags. */
    private fun profile(
        role: StaffRole,
        announcements: Boolean = true,
        voice: Boolean = true,
    ): StateFlow<StaffProfile?> = MutableStateFlow(
        StaffProfile(
            userId = "u-1", displayName = "Ms Noor", role = role, roleWire = role.wire, phone = null,
            schoolId = "school-1", schoolName = "Learning International School",
            flags = DEFAULT_FLAGS + mapOf(Flags.ANNOUNCEMENTS to announcements, Flags.VOICE_ANNOUNCEMENT to voice),
        ),
    )

    /** UTC and a one-millisecond tick, so two minutes of recording take 120 ms and an expiry date is predictable. */
    private fun viewModel(gateway: FakeGateway, who: StateFlow<StaffProfile?>) =
        VoiceAnnouncementViewModel(gateway, who, zone = TimeZone.UTC, tickMillis = 1, now = { 1_700_000_000_000L })
            .also { built += it }

    private suspend fun <S : MviState> settle(state: StateFlow<S>, predicate: (S) -> Boolean) {
        repeat(600) {
            if (predicate(state.value)) return
            delay(5)
        }
        error("state never satisfied the predicate; last was ${state.value}")
    }

    /** Record, say something, stop, and wait for the preview the server's draft makes. */
    private suspend fun toPreview(vm: VoiceAnnouncementViewModel, said: String = "بكرة رحلة لطلاب ثالث") {
        vm.dispatch(VoiceAnnouncementContract.Intent.Open)
        settle(vm.state) { it.allowed }
        vm.dispatch(VoiceAnnouncementContract.Intent.StartRecording)
        settle(vm.state) { it.recording }
        vm.dispatch(VoiceAnnouncementContract.Intent.Transcribed(said, false))
        vm.dispatch(VoiceAnnouncementContract.Intent.StopRecording)
        settle(vm.state) { it.step == VoiceAnnouncementContract.Step.PREVIEW }
    }

    // ---- 1. nothing is sent until she presses Send

    @Test fun `preparing a draft posts nothing, and only Send does`() = runBlocking {
        val gateway = FakeGateway(cells = cells)
        val vm = viewModel(gateway, profile(StaffRole.MANAGERIAL))
        toPreview(vm)

        assertEquals(1, gateway.asked.size, "the transcript went up once, to be prepared")
        assertTrue(gateway.posted.isEmpty(), "a prepared draft is not an announcement: nothing was posted")

        // Editing every field still posts nothing.
        vm.dispatch(VoiceAnnouncementContract.Intent.SetTitle("رحلة الصف الثالث غدًا"))
        vm.dispatch(VoiceAnnouncementContract.Intent.SetGrade(2))
        vm.dispatch(VoiceAnnouncementContract.Intent.SetExpiry(""))
        settle(vm.state) { it.form.grade == 2 }
        assertTrue(gateway.posted.isEmpty(), "editing the preview sends nothing")

        vm.dispatch(VoiceAnnouncementContract.Intent.Send)
        settle(vm.state) { it.step == VoiceAnnouncementContract.Step.SENT }

        val sent = gateway.posted.single()
        assertEquals(BroadcastKind.ANNOUNCEMENT, sent.kind)
        assertEquals(listOf(BroadcastAudience.PARENTS), sent.audience, "an announcement from a voice note is for parents")
        assertEquals("رحلة الصف الثالث غدًا", sent.title)
        assertEquals(FakeGateway.DRAFT.bodyAr, sent.bodyAr)
        assertEquals(FakeGateway.DRAFT.bodyEn, sent.bodyEn)
        assertEquals("draft-1", sent.voiceDraftId, "the transcript stays with what went out")
        // She kept the British academy and moved to grade 2, so the row names that cell's real sections — and no
        // grade beside them, which the server refuses.
        assertEquals(listOf("sec-british-2"), sent.sectionIds)
        assertNull(sent.grade)
        assertNull(sent.expiresAt, "she cleared the expiry")
        assertEquals(VoiceMessage.Sent, vm.state.value.message)
    }

    @Test fun `a grade with no academy chosen goes as a grade of her whole department`() = runBlocking {
        val gateway = FakeGateway(cells = cells)
        val vm = viewModel(gateway, profile(StaffRole.ADMIN))
        toPreview(vm)
        vm.dispatch(VoiceAnnouncementContract.Intent.SetCurriculum(null))
        settle(vm.state) { it.form.curriculum == null }
        vm.dispatch(VoiceAnnouncementContract.Intent.Send)
        settle(vm.state) { it.step == VoiceAnnouncementContract.Step.SENT }

        val sent = gateway.posted.single()
        assertEquals(3, sent.grade, "the grade the assistant suggested survived")
        assertTrue(sent.sectionIds.isEmpty(), "no academy chosen means her whole scope, which is an empty list")
    }

    // ---- 2. the two-minute cap

    @Test fun `recording stops itself at two minutes`() = runBlocking {
        val gateway = FakeGateway(cells = cells)
        val vm = viewModel(gateway, profile(StaffRole.MANAGERIAL))
        vm.dispatch(VoiceAnnouncementContract.Intent.Open)
        settle(vm.state) { it.allowed }
        vm.dispatch(VoiceAnnouncementContract.Intent.StartRecording)
        settle(vm.state) { it.recording }
        vm.dispatch(VoiceAnnouncementContract.Intent.Transcribed("كلام طويل", false))

        settle(vm.state) { !it.recording }
        assertEquals(VoiceAnnouncementContract.CAP_SECONDS, vm.state.value.elapsedSeconds, "two minutes, as the spec words it")
        assertEquals(0, vm.state.value.remainingSeconds)
        // The cap is a stop, not a send: the transcript goes to be prepared and nothing is posted.
        settle(vm.state) { it.step == VoiceAnnouncementContract.Step.PREVIEW }
        assertTrue(gateway.posted.isEmpty())
    }

    @Test fun `a recording nobody said anything into prepares nothing`() = runBlocking {
        val gateway = FakeGateway(cells = cells)
        val vm = viewModel(gateway, profile(StaffRole.MANAGERIAL))
        vm.dispatch(VoiceAnnouncementContract.Intent.Open)
        settle(vm.state) { it.allowed }
        vm.dispatch(VoiceAnnouncementContract.Intent.StartRecording)
        settle(vm.state) { it.recording }
        vm.dispatch(VoiceAnnouncementContract.Intent.RecordingEnded)
        settle(vm.state) { it.message != null }

        assertEquals(VoiceMessage.NothingHeard, vm.state.value.message)
        assertEquals(VoiceAnnouncementContract.Step.RECORD, vm.state.value.step)
        assertTrue(gateway.asked.isEmpty(), "an empty transcript is never sent up")
    }

    // ---- 3. the draft becomes the preview

    @Test fun `the draft arrives in the preview with its audience and its expiry`() = runBlocking {
        val gateway = FakeGateway(cells = cells)
        val vm = viewModel(gateway, profile(StaffRole.MANAGERIAL))
        toPreview(vm, said = "بكرة رحلة لطلاب ثالث بريطاني")

        val state = vm.state.value
        assertEquals("draft-1", state.draftId)
        assertEquals(FakeGateway.DRAFT.title, state.form.title)
        assertEquals(FakeGateway.DRAFT.bodyAr, state.form.bodyAr)
        assertEquals(FakeGateway.DRAFT.bodyEn, state.form.bodyEn)
        assertEquals(Curriculum.BRITISH, state.form.curriculum, "the audience the server kept")
        assertEquals(3, state.form.grade)
        assertEquals("2026-11-30", state.form.expiresOn, "the expiry as a date she can edit")
        assertEquals("بكرة رحلة لطلاب ثالث بريطاني", state.transcript, "what she actually said, under the preview")
        // The pickers are built from her real cells, never from the suggestion alone.
        assertEquals(listOf(Curriculum.BRITISH, Curriculum.AMERICAN), state.curricula)
        assertEquals(listOf(1, 2, 3), state.grades(Curriculum.BRITISH))
        assertTrue(state.canSend)
    }

    @Test fun `an expiry that is not a date is refused before anything is posted`() = runBlocking {
        val gateway = FakeGateway(cells = cells)
        val vm = viewModel(gateway, profile(StaffRole.MANAGERIAL))
        toPreview(vm)
        vm.dispatch(VoiceAnnouncementContract.Intent.SetExpiry("next week"))
        vm.dispatch(VoiceAnnouncementContract.Intent.Send)
        settle(vm.state) { it.message == VoiceMessage.ExpiryInvalid }

        assertTrue(gateway.posted.isEmpty())
        assertEquals(VoiceAnnouncementContract.Step.PREVIEW, vm.state.value.step, "she stays on her preview")
    }

    // ---- 4. the role gate

    @Test fun `a teacher is refused every intent`() = runBlocking {
        val gateway = FakeGateway(cells = cells)
        val vm = viewModel(gateway, profile(StaffRole.TEACHER))
        vm.dispatch(VoiceAnnouncementContract.Intent.Open)
        delay(60)

        assertFalse(vm.state.value.allowed, "the recorder is for the Admin and a department manager")
        vm.dispatch(VoiceAnnouncementContract.Intent.StartRecording)
        vm.dispatch(VoiceAnnouncementContract.Intent.Transcribed("كلام", true))
        vm.dispatch(VoiceAnnouncementContract.Intent.Send)
        delay(60)

        assertFalse(vm.state.value.recording, "nothing was recorded")
        assertEquals("", vm.state.value.transcript)
        assertTrue(gateway.asked.isEmpty())
        assertTrue(gateway.posted.isEmpty())
    }

    @Test fun `a worker is refused, and so is a manager whose school has a switch off`() = runBlocking {
        val worker = viewModel(FakeGateway(cells = cells), profile(StaffRole.WORKER))
        worker.dispatch(VoiceAnnouncementContract.Intent.Open)
        delay(60)
        assertFalse(worker.state.value.allowed)

        // `voiceAnnouncement` rides beside `announcements`: either one off closes the door.
        val noVoice = viewModel(FakeGateway(cells = cells), profile(StaffRole.MANAGERIAL, voice = false))
        noVoice.dispatch(VoiceAnnouncementContract.Intent.Open)
        delay(60)
        assertFalse(noVoice.state.value.allowed)

        val noAnnouncements = viewModel(FakeGateway(cells = cells), profile(StaffRole.MANAGERIAL, announcements = false))
        noAnnouncements.dispatch(VoiceAnnouncementContract.Intent.Open)
        delay(60)
        assertFalse(noAnnouncements.state.value.allowed)
    }

    // ---- 5. what the server says when it cannot write the announcement

    @Test fun `a 422 asks her to say it again more clearly`() = runBlocking {
        val gateway = FakeGateway(cells = cells).also {
            it.draftFailure = ApiException(ApiError(VoiceAnnouncementContract.REPHRASE, "Couldn't save, please rephrase."))
        }
        val vm = viewModel(gateway, profile(StaffRole.MANAGERIAL))
        vm.dispatch(VoiceAnnouncementContract.Intent.Open)
        settle(vm.state) { it.allowed }
        vm.dispatch(VoiceAnnouncementContract.Intent.StartRecording)
        settle(vm.state) { it.recording }
        vm.dispatch(VoiceAnnouncementContract.Intent.Transcribed("مممم", false))
        vm.dispatch(VoiceAnnouncementContract.Intent.StopRecording)
        settle(vm.state) { it.message != null }

        assertEquals(VoiceMessage.Rephrase, vm.state.value.message)
        assertEquals(VoiceAnnouncementContract.Step.RECORD, vm.state.value.step, "she is back at the microphone")
        assertTrue(gateway.posted.isEmpty())
        assertEquals("قوليها مرة أخرى بشكل أوضح.", StaffStrings.ar.voiceMessage(VoiceMessage.Rephrase))
        assertEquals("Please say it again more clearly.", StaffStrings.en.voiceMessage(VoiceMessage.Rephrase))
    }

    @Test fun `any other failure is one generic sentence`() = runBlocking {
        val gateway = FakeGateway(cells = cells).also {
            it.draftFailure = ApiException(ApiError(ApiError.MODEL_FAILED, "provider"))
        }
        val vm = viewModel(gateway, profile(StaffRole.ADMIN))
        vm.dispatch(VoiceAnnouncementContract.Intent.Open)
        settle(vm.state) { it.allowed }
        vm.dispatch(VoiceAnnouncementContract.Intent.StartRecording)
        settle(vm.state) { it.recording }
        vm.dispatch(VoiceAnnouncementContract.Intent.Transcribed("كلام", false))
        vm.dispatch(VoiceAnnouncementContract.Intent.StopRecording)
        settle(vm.state) { it.message != null }

        assertEquals(VoiceMessage.Failed, vm.state.value.message, "a provider's failure is never a status code to her")
        assertEquals("لم نتمكّن من تحضيره. حاولي مرة أخرى.", StaffStrings.ar.voiceMessage(VoiceMessage.Failed))
    }

    @Test fun `a failed Send leaves her preview exactly where it was`() = runBlocking {
        val gateway = FakeGateway(cells = cells).also {
            it.sendFailure = ApiException(ApiError(ApiError.NETWORK, "offline"))
        }
        val vm = viewModel(gateway, profile(StaffRole.MANAGERIAL))
        toPreview(vm)
        vm.dispatch(VoiceAnnouncementContract.Intent.Send)
        settle(vm.state) { it.message != null }

        assertEquals(VoiceMessage.Offline, vm.state.value.message)
        assertEquals(VoiceAnnouncementContract.Step.PREVIEW, vm.state.value.step)
        assertFalse(vm.state.value.sending, "Send is offered again rather than left spinning")
        assertEquals(FakeGateway.DRAFT.title, vm.state.value.form.title)
    }
}
