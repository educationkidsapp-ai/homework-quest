package quest.feature.lesson.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
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
import kotlinx.coroutines.flow.catch
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import quest.api.LessonApiException
import quest.api.dto.ApiError
import quest.api.dto.LessonStatus
import quest.core.design.Dimens
import quest.core.design.Palette
import quest.core.design.Pip
import quest.core.design.PipPose
import quest.core.mvi.MviEffect
import quest.core.mvi.MviIntent
import quest.core.mvi.MviState
import quest.core.mvi.MviViewModel
import quest.feature.lesson.domain.LessonRepository
import quest.feature.lesson.domain.ObserveLessonUseCase
import quest.feature.parent.presentation.LocalStrings
import quest.feature.parent.presentation.ParentButton
import quest.feature.parent.presentation.ParentShell
import quest.feature.parent.presentation.Strings

object ReadingContract {
    data class State(val status: LessonStatus = LessonStatus.UPLOADING, val error: ApiError? = null, val skillCount: Int = 0) : MviState
    sealed interface Intent : MviIntent { data object Observe : Intent }
    sealed interface Effect : MviEffect { data class NeedsConfirmation(val lessonId: String) : Effect }
}

/** Drives the `uploading` / `reading` / `generating` / `error` screen from the job status (design screen 20). */
class ReadingViewModel(private val lessonId: String, private val observe: ObserveLessonUseCase, private val lessons: LessonRepository) :
    MviViewModel<ReadingContract.State, ReadingContract.Intent, ReadingContract.Effect>(ReadingContract.State()) {
    init { dispatch(ReadingContract.Intent.Observe) }

    override suspend fun handle(intent: ReadingContract.Intent) {
        lessons.lesson(lessonId)?.let { l -> reduce { copy(status = l.status, error = l.error) } }
        launch {
            observe(lessonId)
                .catch { e -> reduce { copy(status = LessonStatus.ERROR, error = (e as? LessonApiException)?.error ?: ApiError(ApiError.NETWORK, e.message ?: "")) } }
                .collect { job ->
                    reduce { copy(status = job.status, error = job.error, skillCount = job.skills.size) }
                    if (job.status == LessonStatus.NEEDS_CONFIRMATION) effect(ReadingContract.Effect.NeedsConfirmation(lessonId))
                }
        }
    }
}

@Composable
fun ReadingRoute(lessonId: String, onConfirm: (String) -> Unit, onDone: () -> Unit, onTryAgain: () -> Unit, onBack: () -> Unit) {
    val vm: ReadingViewModel = koinViewModel(key = "reading-$lessonId") { parametersOf(lessonId) }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.effects.collect { if (it is ReadingContract.Effect.NeedsConfirmation) onConfirm(it.lessonId) } }
    ParentShell(title = { it.addLesson }, onBack = onBack) { s -> ReadingScreen(state, s, onDone, onTryAgain, onBack) }
}

@Composable
fun ReadingScreen(state: ReadingContract.State, s: Strings, onDone: () -> Unit, onTryAgain: () -> Unit, onChooseAnother: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(Dimens.s24), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        when (state.status) {
            LessonStatus.UPLOADING -> {
                Pip(PipPose.IDLE, Dimens.pipMedium, animated = false)
                Spacer(Modifier.height(Dimens.s24))
                LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = "uploading" }, color = Palette.parentAccent, trackColor = Palette.parentAccentSoft)
                Spacer(Modifier.height(Dimens.s16))
                Text(s.uploading, style = MaterialTheme.typography.titleLarge, color = Palette.parentInk)
            }
            LessonStatus.READING, LessonStatus.NEEDS_CONFIRMATION, LessonStatus.GENERATING -> {
                Pip(PipPose.THINKING, Dimens.pipMedium)
                Spacer(Modifier.height(Dimens.s24))
                CircularProgressIndicator(color = Palette.parentAccent)
                Spacer(Modifier.height(Dimens.s16))
                Text(if (state.status == LessonStatus.GENERATING) s.generating else s.reading, style = MaterialTheme.typography.titleLarge, color = Palette.parentInk, textAlign = TextAlign.Center)
            }
            LessonStatus.READY -> {
                Pip(PipPose.CELEBRATING, Dimens.pipMedium)
                Spacer(Modifier.height(Dimens.s24))
                Text(s.ready, style = MaterialTheme.typography.headlineMedium, color = Palette.parentInk, textAlign = TextAlign.Center)
                Spacer(Modifier.height(Dimens.s8))
                Text(s.readyBody, style = MaterialTheme.typography.bodyLarge, color = Palette.parentInkSoft, textAlign = TextAlign.Center)
                Spacer(Modifier.height(Dimens.s24))
                ParentButton(s.parentHome, onDone)
            }
            LessonStatus.ERROR -> {
                Pip(PipPose.SLEEPING, Dimens.pipMedium)
                Spacer(Modifier.height(Dimens.s24))
                Text(s.somethingWrong, style = MaterialTheme.typography.headlineMedium, color = Palette.parentInk, textAlign = TextAlign.Center)
                Spacer(Modifier.height(Dimens.s8))
                Text(state.error?.message ?: "", style = MaterialTheme.typography.bodyLarge, color = Palette.parentInkSoft, textAlign = TextAlign.Center)
                Spacer(Modifier.height(Dimens.s24))
                ParentButton(s.tryAgain, onTryAgain)
                Spacer(Modifier.height(Dimens.s12))
                ParentButton(s.chooseAnother, onChooseAnother, primary = false)
            }
        }
    }
}
