package quest.feature.journey.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import quest.api.dto.PublishedLesson
import quest.api.map.MapAssembler
import quest.core.mvi.MviEffect
import quest.core.mvi.MviIntent
import quest.core.mvi.MviState
import quest.core.mvi.MviViewModel
import quest.core.platform.Speaker
import quest.core.platform.Today
import quest.feature.children.domain.ChildrenRepository
import quest.feature.content.domain.JourneyRepository
import quest.feature.content.domain.LessonRepository
import quest.feature.rewards.domain.AwardStickerUseCase
import quest.feature.rewards.domain.UpdateStreakUseCase
import quest.feature.school.domain.Flags
import quest.feature.school.presentation.FeatureGate
import quest.ui.design.BigButton
import quest.ui.design.Dimens
import quest.ui.design.Palette
import quest.ui.design.Pip
import quest.ui.design.PipPose
import quest.ui.design.ReadAloudButton
import quest.ui.design.StickerKeys
import quest.ui.journey.Certificate

object CompleteContract {
    data class State(val loading: Boolean = true, val lesson: PublishedLesson? = null, val level: Int = 1, val variant: Int = 0, val stars: Int = 0, val starsTotal: Int = 0, val stickerKey: String? = null, val childName: String = "", val served: Boolean = false, val nextLevelUnlocked: Boolean = false) : MviState
    sealed interface Intent : MviIntent { data object Load : Intent; data object Serve : Intent; data object ReadAloud : Intent }
    sealed interface Effect : MviEffect { data class Speak(val text: String) : Effect }
}

class LessonCompleteViewModel(
    private val lessonId: String, private val level: Int, private val variant: Int,
    private val lessons: LessonRepository, private val journey: JourneyRepository, private val children: ChildrenRepository,
    private val awardSticker: AwardStickerUseCase, private val updateStreak: UpdateStreakUseCase,
) : MviViewModel<CompleteContract.State, CompleteContract.Intent, CompleteContract.Effect>(CompleteContract.State(level = level, variant = variant)) {
    init { dispatch(CompleteContract.Intent.Load) }
    override suspend fun handle(intent: CompleteContract.Intent) {
        when (intent) {
            CompleteContract.Intent.Load -> {
                val child = children.currentChild.value ?: return
                val lesson = lessons.lesson(lessonId)
                val play = lesson.play(level, variant) ?: lesson.plays.first()
                val progress = journey.progress(child.id, lessonId, play.level, play.variant)
                val sticker = awardSticker()
                updateStreak(Today.date())
                val unlocked = MapAssembler.unlockedLevels(journey.completions(child.id).filter { it.lessonId == lessonId }, journey.parentUnlocks(child.id)[lessonId].orEmpty())
                reduce { copy(loading = false, lesson = lesson, stars = progress.starsFor(play), starsTotal = play.stops.size * 3, stickerKey = sticker.key, childName = child.name, nextLevelUnlocked = (level + 1) in unlocked) }
                effect(CompleteContract.Effect.Speak("The ${play.theme.potName} is full! Tap to serve the ${play.theme.dishName}."))
            }
            CompleteContract.Intent.Serve -> { reduce { copy(served = true) }; effect(CompleteContract.Effect.Speak("${current.lesson?.theme?.servedText} You earned a certificate, ${current.childName}!")) }
            CompleteContract.Intent.ReadAloud -> effect(CompleteContract.Effect.Speak(if (current.served) "Well done ${current.childName}! You earned a certificate and a new sticker." else "Tap to serve the ${current.lesson?.theme?.dishName}."))
        }
    }
}

@Composable
fun LessonCompleteRoute(lessonId: String, level: Int, variant: Int, onAgain: (String, Int, Int) -> Unit, onNextLevel: (String, Int) -> Unit, onStickers: () -> Unit, onMap: () -> Unit) {
    val vm: LessonCompleteViewModel = koinViewModel(key = "complete-$lessonId-$level-$variant") { parametersOf(lessonId, level, variant) }
    val speaker: Speaker = koinInject()
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.effects.collect { if (it is CompleteContract.Effect.Speak) speaker.speak(it.text) } }
    LessonCompleteScreen(state, vm::dispatch, onAgain = { onAgain(lessonId, 1, 1) }, onNextLevel = { onNextLevel(lessonId, level + 1) }, onStickers = onStickers, onMap = onMap)
}

@Composable
fun LessonCompleteScreen(state: CompleteContract.State, dispatch: (CompleteContract.Intent) -> Unit, onAgain: () -> Unit, onNextLevel: () -> Unit, onStickers: () -> Unit, onMap: () -> Unit) {
    val lesson = state.lesson ?: run { LoadingView("Serving…"); return }
    Column(Modifier.fillMaxSize().background(Palette.sky).safeDrawingPadding().padding(Dimens.s16).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth()) { Spacer(Modifier.weight(1f)); ReadAloudButton({ dispatch(CompleteContract.Intent.ReadAloud) }) }
        if (!state.served) {
            Text(lesson.theme.potEmoji, fontSize = 110.sp)
            Text("The ${lesson.theme.potName} is full!", style = MaterialTheme.typography.displayLarge, color = Palette.ink, textAlign = TextAlign.Center)
            Spacer(Modifier.height(Dimens.s24))
            BigButton("Serve the ${lesson.theme.dishName}", onClick = { dispatch(CompleteContract.Intent.Serve) }, emoji = "🥣")
        } else {
            Pip(PipPose.CELEBRATING, Dimens.pipMedium)
            Text(lesson.theme.servedText, style = MaterialTheme.typography.bodyLarge, color = Palette.ink, textAlign = TextAlign.Center)
            Spacer(Modifier.height(Dimens.s12))
            // §4 `certificates`: off, the pot is still served and the sticker still arrives — only the certificate
            // is absent, because a school that does not issue them must never show one.
            FeatureGate(Flags.CERTIFICATES) {
                Certificate(state.childName, lesson.title, state.level, state.stars, state.starsTotal, "${Today.date()}")
                Spacer(Modifier.height(Dimens.s16))
            }
            state.stickerKey?.let { key ->
                Column(Modifier.background(Palette.cream, MaterialTheme.shapes.extraLarge).padding(Dimens.s16), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("New sticker!", style = MaterialTheme.typography.titleLarge, color = Palette.ink)
                    Text(StickerKeys.emoji(key), fontSize = 64.sp)
                }
            }
            Spacer(Modifier.height(Dimens.s16))
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s12)) {
                BigButton("Again", onClick = onAgain, modifier = Modifier.weight(1f), emoji = "🔁", compact = true)
                BigButton(if (state.level < 3) "Next level" else "Stickers", onClick = if (state.level < 3) onNextLevel else onStickers, modifier = Modifier.weight(1f), emoji = "🚀", color = Palette.lavender, compact = true, enabled = state.level >= 3 || state.nextLevelUnlocked)
            }
            Spacer(Modifier.height(Dimens.s12))
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s12)) {
                BigButton("Stickers", onClick = onStickers, modifier = Modifier.weight(1f), emoji = "🌟", color = Palette.cream, compact = true)
                BigButton("Map", onClick = onMap, modifier = Modifier.weight(1f), emoji = "🗺️", color = Palette.cream, compact = true)
            }
        }
        Spacer(Modifier.height(Dimens.s24))
    }
}
