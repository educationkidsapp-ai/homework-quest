package quest.feature.journey.presentation

import quest.feature.today.domain.NoExamSittingPresenter
import quest.feature.today.domain.ExamWindows
import quest.feature.today.domain.ExamSittingPresenter
import quest.feature.today.domain.ExamSitting
import kotlinx.coroutines.delay
import quest.api.ContentApi
import quest.api.UploadFile
import quest.api.dto.MediaKind
import quest.api.dto.StopCategory
import quest.api.dto.StopScoring
import quest.api.map.MapAssembler
import quest.core.mvi.MviViewModel
import quest.feature.children.domain.ChildrenRepository
import quest.feature.content.domain.JourneyRepository
import quest.feature.content.domain.LessonRepository
import quest.feature.content.domain.SubmitOutcome
import quest.feature.journey.presentation.JourneyContract.Effect
import quest.feature.journey.presentation.JourneyContract.Intent
import quest.feature.journey.presentation.JourneyContract.State
import quest.ui.design.Timing

/** The lesson overview: the list of steps and, for a homework, the level selector. */
class JourneyViewModel(
    private val lessonId: String, initialLevel: Int, private val initialVariant: Int,
    private val lessons: LessonRepository, private val journey: JourneyRepository, private val children: ChildrenRepository, private val copy: LessonCopy,
) : MviViewModel<State, Intent, Effect>(State(level = initialLevel, variant = initialVariant)) {

    init { dispatch(Intent.Load) }

    override suspend fun handle(intent: Intent) {
        when (intent) {
            Intent.Load -> load(current.level, current.variant)
            is Intent.SelectLevel -> if (!current.exam && intent.level in current.levelsUnlocked) load(intent.level, 0)
            is Intent.TapStop -> {
                val state = current.nodeStates.getOrNull(intent.index) ?: return
                // §8: an answered exam question is not reopened — only the next unanswered one opens.
                val open = if (current.exam) state == quest.ui.journey.NodeState.CURRENT else state != quest.ui.journey.NodeState.LOCKED
                if (open) effect(Effect.OpenStop(lessonId, current.level, current.variant, intent.index))
            }
            Intent.ReadAloud -> effect(Effect.Speak(readAloud()))
            Intent.Finish -> effect(Effect.OpenComplete(lessonId, current.level, current.variant))
        }
    }

    private suspend fun load(level: Int, variant: Int) {
        val child = children.currentChild.value ?: run { reduce { copy(loading = false, error = this@JourneyViewModel.copy.strings().noStudent) }; return }
        val lesson = runCatching { lessons.lesson(lessonId) }.getOrElse { reduce { copy(loading = false, error = this@JourneyViewModel.copy.strings().lessonUnavailable) }; return }
        val play = lesson.playFor(level, variant)
        val completions = journey.completions(child.id)
        val unlocks = journey.parentUnlocks(child.id)
        val progress = journey.progress(child.id, lessonId, play.level, play.variant)
        reduce {
            // An exam keeps the route's level and variant: its one play is found by [playFor] whatever they say, and the
            // back stack is popped by the same route that was pushed.
            copy(loading = false, lesson = lesson, play = play, level = if (lesson.isExam) level else play.level, variant = if (lesson.isExam) variant else play.variant,
                levelsUnlocked = MapAssembler.unlockedLevels(completions.filter { it.lessonId == lessonId }, unlocks[lessonId].orEmpty()),
                completedLevels = completions.filter { it.lessonId == lessonId }.map { it.level }.distinct().sorted(),
                stopStars = progress.stops, childName = child.name, exam = lesson.isExam)
        }
    }

    private fun readAloud(): String {
        val s = current
        val t = copy.strings()
        return when {
            s.exam && s.complete -> t.speakExamSubmitted
            s.exam && s.doneCount == 0 -> t.speakExamStart
            s.exam -> t.speakExamProgress.replace("{n}", "${s.doneCount}")
            s.complete -> t.speakAllDone
            s.doneCount == 0 -> t.speakStart
            else -> t.speakProgress.replace("{n}", "${s.doneCount}")
        }
    }
}

/** Plays the steps of one level in order; owns the hint sheet and the two confirmation overlays. */
class StopPlayerViewModel(
    private val lessonId: String, private val level: Int, private val variant: Int, private val startIndex: Int,
    private val lessons: LessonRepository, private val journey: JourneyRepository, private val children: ChildrenRepository, private val media: ContentApi, private val copy: LessonCopy,
    /** M3: the sitting as the system shows it outside the app (Live Activity / ongoing notification). */
    private val sitting: ExamSittingPresenter = NoExamSittingPresenter, private val windows: ExamWindows = ExamWindows(), private val now: () -> Long = { 0L },
) : MviViewModel<PlayerContract.State, PlayerContract.Intent, PlayerContract.Effect>(PlayerContract.State(index = startIndex)) {

    private var childId = ""
    private var knownEnd: Long? = null
    init { dispatch(PlayerContract.Intent.Load) }

    companion object { /** How long "Answer saved" stays up — one value for every answer. */ const val EXAM_ACKNOWLEDGE_MILLIS = 1_200L }

    override suspend fun handle(intent: PlayerContract.Intent) {
        when (intent) {
            PlayerContract.Intent.Load -> load()
            is PlayerContract.Intent.Correct -> onCorrect(intent.attempt, intent.answer)
            is PlayerContract.Intent.Wrong -> onWrong(intent)
            is PlayerContract.Intent.Completed -> onCompleted(intent.stars, intent.answer, intent.mistakes, intent.recording, intent.drawing, intent.correct)
            PlayerContract.Intent.TryAgain -> if (!current.exam) { reduce { copy(phase = PlayerContract.Phase.STOP) }; current.stop?.let { effect(PlayerContract.Effect.Speak(it.speak)) } }
            PlayerContract.Intent.Advance -> advance()
            PlayerContract.Intent.SendAgain -> if (current.phase == PlayerContract.Phase.SENDING) finish()
            PlayerContract.Intent.ReadAloud -> effect(PlayerContract.Effect.Speak(if (current.phase == PlayerContract.Phase.HINT) current.hint else current.stop?.speak ?: ""))
            is PlayerContract.Intent.Speak -> effect(PlayerContract.Effect.Speak(intent.text))
        }
    }

    private suspend fun load() {
        val child = children.currentChild.value ?: run { reduce { copy(phase = PlayerContract.Phase.ERROR, error = this@StopPlayerViewModel.copy.strings().noStudent) }; return }
        childId = child.id
        val lesson = runCatching { lessons.lesson(lessonId) }.getOrElse { reduce { copy(phase = PlayerContract.Phase.ERROR, error = this@StopPlayerViewModel.copy.strings().lessonMissing) }; return }
        val play = lesson.playFor(level, variant)
        val exam = lesson.isExam
        // §8: answers left on the device by an earlier sitting — given offline, or refused when the window shut and now
        // deliverable because the teacher re-opened it — go up before anything else, so the server's paper and this
        // device's agree before the next question is shown.
        if (exam) journey.submit(child.id, lessonId)
        val progress = journey.progress(child.id, lessonId, play.level, play.variant)
        // §8: a sitting resumes at the first unanswered question, wherever the route pointed — an answered one is never reopened.
        val index = if (exam) play.stops.indexOfFirst { it.id !in progress.stops } else startIndex
        reduce { copy(phase = PlayerContract.Phase.STOP, lesson = lesson, play = play, index = index.coerceAtLeast(0), stopStars = progress.stops, childName = child.name, exam = exam) }
        if (exam && index < 0) { finish(); return }
        if (exam) showSitting()
        current.stop?.let { effect(PlayerContract.Effect.Speak(it.speak)) }
    }

    private suspend fun onCorrect(attempt: Int, answer: String) {
        val stop = current.stop ?: return
        if (stop.category != StopCategory.SINGLE && stop.category != StopCategory.EXIT) return
        if (current.exam) { if (stop.category == StopCategory.SINGLE) examAnswer(stop.id, StopScoring.singleAnswer(1), answer, correct = true); return }
        if (stop.category == StopCategory.EXIT) { // sub-question inside an exit ticket: brief praise, the ticket continues
            reduce { copy(phase = PlayerContract.Phase.CORRECT, praise = praise(attempt)) }
            effect(PlayerContract.Effect.Speak(current.praise)); launch { delay(900); reduce { copy(phase = PlayerContract.Phase.STOP) } }
            return
        }
        val stars = StopScoring.singleAnswer(attempt)
        record(stop.id, stars, answer, true, attempt, 0)
        reduce { copy(phase = PlayerContract.Phase.CORRECT, praise = praise(stopStars.size)) }
        effect(PlayerContract.Effect.Speak(current.praise))
        launch { delay(Timing.correctOverlayMillis); dispatch(PlayerContract.Intent.Advance) }
    }

    private suspend fun onWrong(i: PlayerContract.Intent.Wrong) {
        val stop = current.stop ?: return
        val lesson = current.lesson ?: return; val play = current.play ?: return
        // §8: in an exam the first answer is the answer. It is stored as the question's result — no stars — and
        // acknowledged exactly as a right one is; there is no hint and no second go.
        if (current.exam) { if (stop.category == StopCategory.SINGLE) examAnswer(stop.id, 0, i.answer, correct = false); return }
        journey.recordWrongAttempt(childId, lesson, play, stop.id, i.answer, i.attempt)
        reduce { copy(phase = PlayerContract.Phase.HINT, hint = i.hint, numberLine = i.numberLine) }
        effect(PlayerContract.Effect.Speak(i.hint))
    }

    private suspend fun onCompleted(stars: Int, answer: String, mistakes: Int, recording: ByteArray?, drawing: String?, correct: Boolean) {
        val stop = current.stop ?: return
        if (current.exam && (current.phase != PlayerContract.Phase.STOP || stop.id in current.stopStars)) return
        record(stop.id, stars, answer, correct || !current.exam, 1, mistakes, recording, drawing)
        if (recording != null) launch { runCatching { media.uploadStopMedia(childId, stop.id, UploadFile("${stop.id}.m4a", "audio/mp4", recording), MediaKind.RECORDING) } }
        if (drawing != null) launch { runCatching { media.uploadStopMedia(childId, stop.id, UploadFile("${stop.id}.json", "application/json", drawing.encodeToByteArray()), MediaKind.DRAWING) } }
        if (current.exam) { acknowledge(); return }
        reduce { copy(phase = PlayerContract.Phase.STEP_DONE) }
        effect(PlayerContract.Effect.Speak(doneText()))
        launch { delay(1400); dispatch(PlayerContract.Intent.Advance) }
    }

    /** One answer of a single-answer exam question; a second tap on the same question is ignored. */
    private suspend fun examAnswer(stopId: String, stars: Int, answer: String, correct: Boolean) {
        if (current.phase != PlayerContract.Phase.STOP || stopId in current.stopStars) return
        record(stopId, stars, answer, correct, 1, if (correct) 0 else 1)
        acknowledge()
    }

    /**
     * §8: every exam answer gets the same acknowledgement — [LessonStrings.answerSaved], the same overlay, the same
     * pause — so nothing on screen or in the timing says whether it was right. The answer goes to the server at
     * once; a `409` ends the sitting with [PlayerContract.Phase.REFUSED], and an unreachable server leaves it queued.
     */
    private suspend fun acknowledge() {
        reduce { copy(phase = PlayerContract.Phase.STEP_DONE) }
        showSitting()
        effect(PlayerContract.Effect.Speak(copy.strings().answerSaved))
        when (val outcome = journey.submit(childId, lessonId)) {
            SubmitOutcome.ALREADY_TAKEN -> { sitting.end(); reduce { copy(phase = PlayerContract.Phase.REFUSED, refusal = outcome) } }
            // The window shut during the sitting: it ends here, on the submitted screen, which says the exam closed
            // and how many answers (this one at least) did not reach the teacher. They stay queued for a re-opening.
            SubmitOutcome.CLOSED -> { sitting.end(); reduce { copy(phase = PlayerContract.Phase.DONE, refusal = outcome) }; effect(PlayerContract.Effect.Finished(lessonId, level, variant)) }
            SubmitOutcome.SENT, SubmitOutcome.QUEUED -> launch { delay(EXAM_ACKNOWLEDGE_MILLIS); dispatch(PlayerContract.Intent.Advance) }
        }
    }

    /** Title, student, how many are answered — never how — and, when the window's end is known, the time it closes. */
    private fun showSitting() {
        val lesson = current.lesson ?: return
        knownEnd = windows.closesAt(lessonId, now())
        sitting.show(ExamSitting(lessonId, lesson.title, current.childName, knownEnd, current.doneCount, current.total))
    }

    /** Leaving the exam screen: a sitting with a known end stays up until then; one without is taken down now. */
    override fun onCleared() {
        if (current.exam && knownEnd == null) sitting.end()
        super.onCleared()
    }

    /**
     * Every step is answered. A homework is complete at once — its answers upload whenever they can. An exam is
     * **submitted only when the server has every answer**: until then the screen says the answers are still being
     * sent and offers to try again, and nothing is marked handed in. If the window has shut meanwhile, the sitting
     * ends on the submitted screen with what was not delivered, and stays re-openable.
     */
    private suspend fun finish() {
        val play = current.play ?: return
        if (current.exam) {
            reduce { copy(phase = PlayerContract.Phase.DONE) }
            when (val outcome = journey.submit(childId, lessonId)) {
                SubmitOutcome.QUEUED -> { reduce { copy(phase = PlayerContract.Phase.SENDING) }; return }
                SubmitOutcome.CLOSED -> { sitting.end(); reduce { copy(refusal = outcome) }; effect(PlayerContract.Effect.Finished(lessonId, level, variant)); return }
                SubmitOutcome.SENT, SubmitOutcome.ALREADY_TAKEN -> sitting.end()
            }
        }
        journey.completeLevel(childId, current.lesson!!, play)
        if (!current.exam) runCatching { journey.flushAttempts(childId) }
        reduce { copy(phase = PlayerContract.Phase.DONE) }
        effect(PlayerContract.Effect.Finished(lessonId, if (current.exam) level else play.level, if (current.exam) variant else play.variant))
    }

    private suspend fun record(stopId: String, stars: Int, answer: String, correct: Boolean, attempt: Int, mistakes: Int, recording: ByteArray? = null, drawing: String? = null) {
        val lesson = current.lesson ?: return; val play = current.play ?: return
        journey.recordStop(childId, lesson, play, stopId, stars, answer, correct, attempt, mistakes, recording, drawing)
        reduce { copy(stopStars = stopStars + (stopId to stars)) }
    }

    private suspend fun advance() {
        val play = current.play ?: return
        if (current.phase == PlayerContract.Phase.CORRECT && current.stop?.category == StopCategory.SINGLE) {
            // single-answer step finished: confirm it before moving on
            reduce { copy(phase = PlayerContract.Phase.STEP_DONE) }
            launch { delay(1200); dispatch(PlayerContract.Intent.Advance) }
            return
        }
        val next = play.stops.indices.firstOrNull { it > current.index && play.stops[it].id !in current.stopStars }
            ?: play.stops.indices.firstOrNull { play.stops[it].id !in current.stopStars }
        if (current.phase == PlayerContract.Phase.REFUSED) return
        if (next == null) finish() else {
            reduce { copy(phase = PlayerContract.Phase.STOP, index = next) }
            effect(PlayerContract.Effect.Speak(play.stops[next].speak))
        }
    }

    private fun praise(n: Int): String = copy.strings().praises.let { it[n % it.size] }

    private fun doneText(): String = copy.strings().stepComplete
}
