package quest.feature.journey.presentation

import kotlinx.coroutines.delay
import quest.api.dto.StopCategory
import quest.api.dto.StopScoring
import quest.api.map.MapAssembler
import quest.core.mvi.MviViewModel
import quest.feature.children.domain.ChildrenRepository
import quest.feature.content.domain.JourneyRepository
import quest.feature.content.domain.LessonRepository
import quest.feature.journey.presentation.JourneyContract.Effect
import quest.feature.journey.presentation.JourneyContract.Intent
import quest.feature.journey.presentation.JourneyContract.State
import quest.ui.design.Timing

/** The lesson journey screen: path of stops, the pot, the level selector. */
class JourneyViewModel(
    private val lessonId: String, initialLevel: Int, private val initialVariant: Int,
    private val lessons: LessonRepository, private val journey: JourneyRepository, private val children: ChildrenRepository,
) : MviViewModel<State, Intent, Effect>(State(level = initialLevel, variant = initialVariant)) {

    init { dispatch(Intent.Load) }

    override suspend fun handle(intent: Intent) {
        when (intent) {
            Intent.Load -> load(current.level, current.variant)
            is Intent.SelectLevel -> if (intent.level in current.levelsUnlocked) load(intent.level, 0)
            is Intent.TapStop -> { val s = current.stops.getOrNull(intent.index) ?: return; if (current.nodeStates[intent.index] != quest.ui.journey.NodeState.LOCKED) effect(Effect.OpenStop(lessonId, current.level, current.variant, intent.index)) }
            Intent.ReadAloud -> effect(Effect.Speak(readAloud()))
            Intent.Serve -> effect(Effect.OpenComplete(lessonId, current.level, current.variant))
        }
    }

    private suspend fun load(level: Int, variant: Int) {
        val child = children.currentChild.value ?: run { reduce { copy(loading = false, error = "No child selected.") }; return }
        val lesson = runCatching { lessons.lesson(lessonId) }.getOrElse { reduce { copy(loading = false, error = "This quest is not on the phone yet. Try again when online.") }; return }
        val play = lesson.play(level, variant) ?: lesson.plays.first()
        val completions = journey.completions(child.id)
        val unlocks = journey.parentUnlocks(child.id)
        val progress = journey.progress(child.id, lessonId, play.level, play.variant)
        reduce {
            copy(loading = false, lesson = lesson, play = play, level = play.level, variant = play.variant,
                levelsUnlocked = MapAssembler.unlockedLevels(completions.filter { it.lessonId == lessonId }, unlocks[lessonId].orEmpty()),
                completedLevels = completions.filter { it.lessonId == lessonId }.map { it.level }.distinct().sorted(),
                stopStars = progress.stops, childName = child.name)
        }
    }

    private fun readAloud(): String {
        val s = current
        return when {
            s.complete -> "The ${s.play?.theme?.potName ?: "pot"} is full! Tap it to serve."
            s.collected.isEmpty() -> "Tap the first stop to begin the journey!"
            else -> "Great! ${s.collected.size} ingredients in the pot. Tap the next stop."
        }
    }
}

/** Plays the stops of one level in order; owns hint sheet, correct overlay and ingredient drop. */
class StopPlayerViewModel(
    private val lessonId: String, private val level: Int, private val variant: Int, private val startIndex: Int,
    private val lessons: LessonRepository, private val journey: JourneyRepository, private val children: ChildrenRepository,
) : MviViewModel<PlayerContract.State, PlayerContract.Intent, PlayerContract.Effect>(PlayerContract.State(index = startIndex)) {

    private var childId = ""
    init { dispatch(PlayerContract.Intent.Load) }

    override suspend fun handle(intent: PlayerContract.Intent) {
        when (intent) {
            PlayerContract.Intent.Load -> load()
            is PlayerContract.Intent.Correct -> onCorrect(intent.attempt, intent.answer)
            is PlayerContract.Intent.Wrong -> onWrong(intent)
            is PlayerContract.Intent.Completed -> onCompleted(intent.stars, intent.answer, intent.mistakes)
            PlayerContract.Intent.TryAgain -> { reduce { copy(phase = PlayerContract.Phase.STOP) }; current.stop?.let { effect(PlayerContract.Effect.Speak(it.speak)) } }
            PlayerContract.Intent.Advance -> advance()
            PlayerContract.Intent.ReadAloud -> effect(PlayerContract.Effect.Speak(if (current.phase == PlayerContract.Phase.HINT) current.hint else current.stop?.speak ?: ""))
            is PlayerContract.Intent.Speak -> effect(PlayerContract.Effect.Speak(intent.text))
        }
    }

    private suspend fun load() {
        val child = children.currentChild.value ?: run { reduce { copy(phase = PlayerContract.Phase.ERROR, error = "No child selected.") }; return }
        childId = child.id
        val lesson = runCatching { lessons.lesson(lessonId) }.getOrElse { reduce { copy(phase = PlayerContract.Phase.ERROR, error = "This quest is missing.") }; return }
        val play = lesson.play(level, variant) ?: lesson.plays.first()
        val progress = journey.progress(child.id, lessonId, play.level, play.variant)
        reduce { copy(phase = PlayerContract.Phase.STOP, lesson = lesson, play = play, stopStars = progress.stops, childName = child.name) }
        current.stop?.let { effect(PlayerContract.Effect.Speak(it.speak)) }
    }

    private suspend fun onCorrect(attempt: Int, answer: String) {
        val stop = current.stop ?: return
        if (stop.category != StopCategory.SINGLE && stop.category != StopCategory.EXIT) return
        if (stop.category == StopCategory.EXIT) { // sub-question inside an exit ticket: brief praise, the ticket continues
            reduce { copy(phase = PlayerContract.Phase.CORRECT, praise = praises[attempt % praises.size]) }
            effect(PlayerContract.Effect.Speak(current.praise)); launch { delay(900); reduce { copy(phase = PlayerContract.Phase.STOP) } }
            return
        }
        val stars = StopScoring.singleAnswer(attempt)
        record(stop.id, stars, answer, true, attempt, 0)
        reduce { copy(phase = PlayerContract.Phase.CORRECT, praise = praises[(stopStars.size) % praises.size]) }
        effect(PlayerContract.Effect.Speak(current.praise))
        launch { delay(Timing.correctOverlayMillis); dispatch(PlayerContract.Intent.Advance) }
    }

    private suspend fun onWrong(i: PlayerContract.Intent.Wrong) {
        val stop = current.stop ?: return
        val lesson = current.lesson ?: return; val play = current.play ?: return
        journey.recordWrongAttempt(childId, lesson, play, stop.id, i.answer, i.attempt)
        reduce { copy(phase = PlayerContract.Phase.HINT, hint = i.hint, numberLine = i.numberLine) }
        effect(PlayerContract.Effect.Speak(i.hint))
    }

    private suspend fun onCompleted(stars: Int, answer: String, mistakes: Int) {
        val stop = current.stop ?: return
        record(stop.id, stars, answer, true, 1, mistakes)
        reduce { copy(phase = PlayerContract.Phase.INGREDIENT, lastIngredient = stop.ingredient) }
        effect(PlayerContract.Effect.Speak("${stop.ingredient.name} goes in the pot!"))
        launch { delay(1400); dispatch(PlayerContract.Intent.Advance) }
    }

    private suspend fun record(stopId: String, stars: Int, answer: String, correct: Boolean, attempt: Int, mistakes: Int) {
        val lesson = current.lesson ?: return; val play = current.play ?: return
        journey.recordStop(childId, lesson, play, stopId, stars, answer, correct, attempt, mistakes)
        reduce { copy(stopStars = stopStars + (stopId to stars)) }
    }

    private suspend fun advance() {
        val play = current.play ?: return
        if (current.phase == PlayerContract.Phase.CORRECT && current.stop?.category == StopCategory.SINGLE) {
            // single-answer stop finished: drop the ingredient before moving on
            val stop = current.stop!!
            reduce { copy(phase = PlayerContract.Phase.INGREDIENT, lastIngredient = stop.ingredient) }
            launch { delay(1200); dispatch(PlayerContract.Intent.Advance) }
            return
        }
        val next = play.stops.indices.firstOrNull { it > current.index && play.stops[it].id !in current.stopStars }
            ?: play.stops.indices.firstOrNull { play.stops[it].id !in current.stopStars }
        if (next == null) {
            journey.completeLevel(childId, current.lesson!!, play)
            runCatching { journey.flushAttempts(childId) }
            reduce { copy(phase = PlayerContract.Phase.DONE) }
            effect(PlayerContract.Effect.Finished(lessonId, play.level, play.variant))
        } else {
            reduce { copy(phase = PlayerContract.Phase.STOP, index = next) }
            effect(PlayerContract.Effect.Speak(play.stops[next].speak))
        }
    }

    companion object { val praises = listOf("Yes!", "Great!", "You got it!") }
}
