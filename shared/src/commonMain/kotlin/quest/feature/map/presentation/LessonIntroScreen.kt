package quest.feature.map.presentation

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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import quest.api.dto.GenerateMode
import quest.api.dto.WorkedExample
import quest.core.design.BackButton
import quest.core.design.BigButton
import quest.core.design.ChildCard
import quest.core.design.Dimens
import quest.core.design.Palette
import quest.core.design.Pip
import quest.core.design.PipPose
import quest.core.design.ReadAloudButton
import quest.core.design.SpeechBubble
import quest.core.mvi.MviEffect
import quest.core.mvi.MviIntent
import quest.core.mvi.MviState
import quest.core.mvi.MviViewModel
import quest.core.platform.Speaker
import quest.feature.lesson.domain.GenerateQuestionSetUseCase
import quest.feature.lesson.domain.LessonRepository
import quest.feature.practice.domain.PracticeRepository
import quest.feature.practice.presentation.LoadingView

object IntroContract {
    data class State(
        val loading: Boolean = true,
        val generating: Boolean = false,
        val skillName: String = "",
        val explanation: String = "",
        val examples: List<WorkedExample> = emptyList(),
        val setId: String? = null,
        val completed: Boolean = false,
    ) : MviState

    sealed interface Intent : MviIntent {
        data object Load : Intent
        data object Start : Intent
        data object ReadAloud : Intent
        data class SpeakExample(val index: Int) : Intent
    }

    sealed interface Effect : MviEffect {
        data class Speak(val text: String) : Effect
        data class OpenSet(val setId: String) : Effect
    }
}

class IntroViewModel(
    private val skillId: String,
    private val lessons: LessonRepository,
    private val practice: PracticeRepository,
    private val generate: GenerateQuestionSetUseCase,
) : MviViewModel<IntroContract.State, IntroContract.Intent, IntroContract.Effect>(IntroContract.State()) {
    init { dispatch(IntroContract.Intent.Load) }

    override suspend fun handle(intent: IntroContract.Intent) {
        when (intent) {
            IntroContract.Intent.Load -> load()
            IntroContract.Intent.Start -> start()
            IntroContract.Intent.ReadAloud -> effect(IntroContract.Effect.Speak(current.explanation))
            is IntroContract.Intent.SpeakExample -> current.examples.getOrNull(intent.index)?.let { ex ->
                effect(IntroContract.Effect.Speak("${ex.prompt}. ${ex.steps.joinToString(". ")}. ${ex.answer}."))
            }
        }
    }

    private suspend fun load() {
        val skill = lessons.skill(skillId)
        val set = lessons.latestSet(skillId)
        val done = set?.id?.let { practice.completionStars(it) } != null
        reduce { copy(loading = false, skillName = skill?.name ?: "", explanation = set?.explanation ?: "", examples = set?.workedExamples ?: emptyList(), setId = set?.id, completed = done) }
        if (set != null) effect(IntroContract.Effect.Speak(set.explanation))
    }

    private suspend fun start() {
        val setId = current.setId ?: return
        if (!current.completed) { effect(IntroContract.Effect.OpenSet(setId)); return }
        reduce { copy(generating = true) }
        runCatching { generate(skillId, GenerateMode.AGAIN) }
            .onSuccess { effect(IntroContract.Effect.OpenSet(it.id!!)) }
            .onFailure { reduce { copy(generating = false) }; effect(IntroContract.Effect.OpenSet(setId)) }
    }
}

@Composable
fun LessonIntroRoute(skillId: String, onOpenSet: (String) -> Unit, onBack: () -> Unit) {
    val vm: IntroViewModel = koinViewModel(key = "intro-$skillId") { parametersOf(skillId) }
    val speaker: Speaker = koinInject()
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) {
        vm.effects.collect { e ->
            when (e) {
                is IntroContract.Effect.Speak -> speaker.speak(e.text)
                is IntroContract.Effect.OpenSet -> onOpenSet(e.setId)
            }
        }
    }
    LessonIntroScreen(state, vm::dispatch, onBack)
}

@Composable
fun LessonIntroScreen(state: IntroContract.State, dispatch: (IntroContract.Intent) -> Unit, onBack: () -> Unit) {
    if (state.loading || state.generating) { LoadingView(if (state.generating) "Pip is making new questions…" else "Getting ready…"); return }
    Column(Modifier.fillMaxSize().background(Palette.sky).safeDrawingPadding().padding(horizontal = Dimens.s16)) {
        Row(Modifier.fillMaxWidth().padding(top = Dimens.s8), verticalAlignment = Alignment.CenterVertically) {
            BackButton(onBack)
            Spacer(Modifier.weight(1f))
            ReadAloudButton({ dispatch(IntroContract.Intent.ReadAloud) })
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(Dimens.s8))
            Text(state.skillName, style = MaterialTheme.typography.headlineMedium, color = Palette.ink, textAlign = TextAlign.Center)
            Spacer(Modifier.height(Dimens.s12))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Pip(PipPose.IDLE, Dimens.pipMedium)
                SpeechBubble(state.explanation, Modifier.weight(1f))
            }
            Spacer(Modifier.height(Dimens.s16))
            state.examples.forEachIndexed { i, ex ->
                ChildCard(Modifier.fillMaxWidth().padding(bottom = Dimens.s12).semantics { contentDescription = "Example ${i + 1}: ${ex.prompt}" }) {
                    Text(ex.prompt, style = MaterialTheme.typography.headlineMedium, color = Palette.ink)
                    Spacer(Modifier.height(Dimens.s4))
                    ex.steps.forEach { step -> Text("• $step", style = MaterialTheme.typography.bodyLarge, color = Palette.inkSoft) }
                    Spacer(Modifier.height(Dimens.s4))
                    Text("= ${ex.answer}", style = MaterialTheme.typography.titleLarge, color = Palette.ink)
                    Spacer(Modifier.height(Dimens.s8))
                    BigButton("Hear it", onClick = { dispatch(IntroContract.Intent.SpeakExample(i)) }, color = Palette.lavender, emoji = "🔊")
                }
            }
        }
        BigButton(if (state.completed) "New questions" else "Let's go", onClick = { dispatch(IntroContract.Intent.Start) }, modifier = Modifier.padding(vertical = Dimens.s16), emoji = "🚀", enabled = state.setId != null)
    }
}
