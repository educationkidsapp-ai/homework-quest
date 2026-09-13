package quest.feature.practice.presentation

import kotlinx.coroutines.delay
import quest.api.dto.GenerateMode
import quest.api.dto.Question
import quest.core.design.Timing
import quest.core.mvi.MviViewModel
import quest.core.platform.Today
import quest.feature.lesson.domain.GenerateQuestionSetUseCase
import quest.feature.lesson.domain.LessonRepository
import quest.feature.practice.domain.CompleteSetUseCase
import quest.feature.practice.domain.PracticeRepository
import quest.feature.practice.domain.PracticeSession
import quest.feature.practice.domain.RecordAttemptUseCase
import quest.feature.practice.domain.TraceScorer
import quest.feature.practice.presentation.PracticeContract.Effect
import quest.feature.practice.presentation.PracticeContract.Intent
import quest.feature.practice.presentation.PracticeContract.Phase
import quest.feature.practice.presentation.PracticeContract.State
import quest.feature.rewards.domain.AwardStickerUseCase
import quest.feature.rewards.domain.UpdateStreakUseCase

class PracticeViewModel(
    private val setId: String,
    private val practice: PracticeRepository,
    private val lessons: LessonRepository,
    private val generate: GenerateQuestionSetUseCase,
    private val recordAttempt: RecordAttemptUseCase,
    private val completeSet: CompleteSetUseCase,
    private val awardSticker: AwardStickerUseCase,
    private val updateStreak: UpdateStreakUseCase,
) : MviViewModel<State, Intent, Effect>(State(setId = setId)) {

    private var session: PracticeSession? = null

    init { dispatch(Intent.Load) }

    override suspend fun handle(intent: Intent) {
        when (intent) {
            Intent.Load -> load()
            is Intent.Answer -> answer(intent.optionId)
            is Intent.TraceFinished -> traceFinished(intent.coverage)
            Intent.TryAgain -> { reduce { copy(phase = Phase.QUESTION) }; effect(Effect.Speak(current.prompt)) }
            Intent.Advance -> advance()
            Intent.ReadAloud -> readAloud()
            is Intent.SpeakWord -> effect(Effect.Speak(intent.text))
            is Intent.More -> more(intent.mode)
        }
    }

    private suspend fun load() {
        val set = practice.loadSet(setId)
        if (set == null) { reduce { copy(phase = Phase.ERROR, errorMessage = "This quest is missing.") }; return }
        val skill = lessons.skill(set.skillId)
        session = PracticeSession(set)
        reduce { copy(skillId = set.skillId, skillName = skill?.name ?: "", total = set.questions.size) }
        showCurrent()
    }

    private suspend fun showCurrent() {
        val s = session ?: return
        val q = s.question
        if (q == null) { complete(); return }
        val prompt = promptFor(q)
        reduce { copy(phase = Phase.QUESTION, index = s.index, stars = s.stars, question = q, dimmed = s.dimmed, prompt = prompt, traceStars = 0) }
        effect(Effect.Speak(prompt))
    }

    private suspend fun answer(optionId: String) {
        val s = session ?: return
        if (current.phase != Phase.QUESTION || optionId in s.dimmed) return
        when (val outcome = s.answer(optionId)) {
            is PracticeSession.Outcome.Correct -> onCorrect(outcome.session)
            is PracticeSession.Outcome.Wrong -> onWrong(outcome)
        }
    }

    private suspend fun traceFinished(coverage: Float) {
        val s = session ?: return
        if (current.phase != Phase.QUESTION) return
        val stars = TraceScorer.stars(coverage)
        when (val outcome = s.trace(stars)) {
            is PracticeSession.Outcome.Correct -> { reduce { copy(traceStars = stars) }; onCorrect(outcome.session) }
            is PracticeSession.Outcome.Wrong -> onWrong(outcome)
        }
    }

    private suspend fun onCorrect(next: PracticeSession) {
        session = next
        recordAttempt(next.results.last(), current.skillId)
        val praise = praises[next.completed % praises.size]
        reduce { copy(phase = Phase.CORRECT, stars = next.stars, praise = praise) }
        effect(Effect.Speak(praise))
        launch { delay(Timing.correctOverlayMillis); dispatch(Intent.Advance) }
    }

    private suspend fun onWrong(outcome: PracticeSession.Outcome.Wrong) {
        session = outcome.session
        recordAttempt(outcome.session.results.last(), current.skillId)
        reduce { copy(phase = Phase.HINT, hint = outcome.hint, numberLine = outcome.numberLine, dimmed = outcome.session.dimmed) }
        effect(Effect.Speak(outcome.hint))
    }

    private suspend fun advance() {
        if (current.phase != Phase.CORRECT) return
        session = session?.next()
        showCurrent()
    }

    private suspend fun complete() {
        val s = session ?: return
        completeSet(setId, s.stars)
        val sticker = awardSticker()
        updateStreak(Today.date())
        reduce { copy(phase = Phase.COMPLETE, stars = s.stars, stickerKey = sticker.key, firstTryCorrect = s.firstTryCorrect) }
        effect(Effect.Speak("You did it! You earned a new sticker."))
    }

    private suspend fun more(mode: GenerateMode) {
        if (current.phase != Phase.COMPLETE) return
        reduce { copy(phase = Phase.GENERATING) }
        effect(Effect.Speak(if (mode == GenerateMode.HARDER) "Making it a bit harder!" else "Here come some new ones!"))
        runCatching { generate(current.skillId, mode) }
            .onSuccess { set -> effect(Effect.OpenSet(set.id!!)) }
            .onFailure { reduce { copy(phase = Phase.COMPLETE) }; effect(Effect.Speak("Hmm, Pip could not fetch new questions. Try again soon.")) }
    }

    private suspend fun readAloud() {
        val text = when (current.phase) {
            Phase.HINT -> current.hint
            Phase.COMPLETE -> "You did it! You earned a new sticker."
            else -> current.prompt
        }
        effect(Effect.Speak(text))
    }

    private fun promptFor(q: Question): String = when (q) {
        is Question.Sequence -> "What number is missing?"
        is Question.Count -> "How many ${plural(q.objectKey)} are there?"
        is Question.Compare -> "Which sign goes in the middle?"
        is Question.Sound -> "What sound does this start with?"
        is Question.Word -> "Tap the word you hear."
        is Question.Trace -> "Trace the letter ${q.letter}."
        is Question.ReadTap -> "Tap the picture for ${q.word}."
    }

    private fun plural(key: String) = when (key) {
        "fish", "sheep" -> key
        "shoe" -> "shoes"; "sock" -> "socks"; "apple" -> "apples"; "star" -> "stars"; "ball" -> "balls"; "bee" -> "bees"
        else -> key + "s"
    }

    companion object {
        val praises = listOf("Yes!", "Great!", "You got it!")
    }
}
