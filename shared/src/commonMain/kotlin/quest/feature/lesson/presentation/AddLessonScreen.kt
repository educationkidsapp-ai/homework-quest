package quest.feature.lesson.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import quest.api.dto.Subject
import quest.api.dto.UploadFile
import quest.core.design.Dimens
import quest.core.design.Palette
import quest.core.mvi.MviEffect
import quest.core.mvi.MviIntent
import quest.core.mvi.MviState
import quest.core.mvi.MviViewModel
import quest.core.platform.PickKind
import quest.core.platform.PickedFile
import quest.core.platform.Today
import quest.core.platform.rememberFilePicker
import quest.feature.lesson.domain.AddLessonUseCase
import quest.feature.lesson.domain.NewLesson
import quest.feature.parent.presentation.Chip
import quest.feature.parent.presentation.LocalStrings
import quest.feature.parent.presentation.ParentButton
import quest.feature.parent.presentation.ParentCard
import quest.feature.parent.presentation.ParentShell
import quest.feature.parent.presentation.SectionTitle
import quest.feature.parent.presentation.Strings

object AddLessonContract {
    data class State(val subject: Subject = Subject.MATH, val files: List<PickedFile> = emptyList(), val typedTask: String = "", val submitting: Boolean = false, val error: String? = null) : MviState {
        val canSubmit: Boolean get() = !submitting && (files.isNotEmpty() || typedTask.isNotBlank())
    }
    sealed interface Intent : MviIntent {
        data class SetSubject(val subject: Subject) : Intent
        data class FilesPicked(val files: List<PickedFile>) : Intent
        data class RemoveFile(val index: Int) : Intent
        data class TypedTask(val text: String) : Intent
        data object Submit : Intent
    }
    sealed interface Effect : MviEffect { data class OpenReading(val lessonId: String) : Effect }
}

class AddLessonViewModel(subject: Subject, private val addLesson: AddLessonUseCase) :
    MviViewModel<AddLessonContract.State, AddLessonContract.Intent, AddLessonContract.Effect>(AddLessonContract.State(subject = subject)) {
    override suspend fun handle(intent: AddLessonContract.Intent) {
        when (intent) {
            is AddLessonContract.Intent.SetSubject -> reduce { copy(subject = intent.subject) }
            is AddLessonContract.Intent.FilesPicked -> reduce { copy(files = files + intent.files, error = null) }
            is AddLessonContract.Intent.RemoveFile -> reduce { copy(files = files.filterIndexed { i, _ -> i != intent.index }) }
            is AddLessonContract.Intent.TypedTask -> reduce { copy(typedTask = intent.text) }
            AddLessonContract.Intent.Submit -> submit()
        }
    }

    private suspend fun submit() {
        if (!current.canSubmit) return
        reduce { copy(submitting = true, error = null) }
        val uploads = current.files.map { UploadFile(it.name, it.mimeType, it.bytes) }
        runCatching { addLesson(NewLesson(current.subject, Today.date(), uploads, current.typedTask.ifBlank { null })) }
            .onSuccess { job -> reduce { copy(submitting = false) }; effect(AddLessonContract.Effect.OpenReading(job.id)) }
            .onFailure { e -> reduce { copy(submitting = false, error = e.message ?: "error") } }
    }
}

@Composable
fun AddLessonRoute(subject: String, typedMode: Boolean, onOpenReading: (String) -> Unit, onTypeTask: (String) -> Unit, onBack: () -> Unit) {
    val initial = if (subject == "english") Subject.ENGLISH else Subject.MATH
    val vm: AddLessonViewModel = koinViewModel(key = "add-$subject-$typedMode") { parametersOf(initial) }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.effects.collect { if (it is AddLessonContract.Effect.OpenReading) onOpenReading(it.lessonId) } }
    val picker = rememberFilePicker { files -> vm.dispatch(AddLessonContract.Intent.FilesPicked(files)) }
    val s = LocalStrings.current
    ParentShell(title = { if (typedMode) it.typedTaskTitle else it.addLesson }, onBack = onBack) { strings ->
        if (typedMode) TypedTaskScreen(state, strings, vm::dispatch)
        else AddLessonScreen(state, strings, vm::dispatch, onPick = { picker.launch(it) }, onTypeTask = { onTypeTask(state.subject.name.lowercase()) })
    }
}

@Composable
fun AddLessonScreen(state: AddLessonContract.State, s: Strings, dispatch: (AddLessonContract.Intent) -> Unit, onPick: (PickKind) -> Unit, onTypeTask: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Dimens.s16)) {
        SectionTitle(s.subject)
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s8)) {
            Chip("🔢 ${s.math}", Palette.sand, selected = state.subject == Subject.MATH) { dispatch(AddLessonContract.Intent.SetSubject(Subject.MATH)) }
            Chip("🔤 ${s.english}", Palette.lavender, selected = state.subject == Subject.ENGLISH) { dispatch(AddLessonContract.Intent.SetSubject(Subject.ENGLISH)) }
        }
        SectionTitle(s.chooseSource)
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s12)) {
            ParentButton(s.pdf, { onPick(PickKind.PDF) }, Modifier.weight(1f), primary = false, icon = "📄")
            ParentButton(s.powerpoint, { onPick(PickKind.PPTX) }, Modifier.weight(1f), primary = false, icon = "📊")
        }
        Spacer(Modifier.height(Dimens.s12))
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s12)) {
            ParentButton(s.camera, { onPick(PickKind.CAMERA) }, Modifier.weight(1f), primary = false, icon = "📷")
            ParentButton(s.gallery, { onPick(PickKind.GALLERY) }, Modifier.weight(1f), primary = false, icon = "🖼️")
        }
        Spacer(Modifier.height(Dimens.s12))
        ParentButton(s.typeTask, onTypeTask, primary = false, icon = "⌨️")
        if (state.files.isNotEmpty()) {
            SectionTitle("${s.selectedFiles} (${state.files.size})")
            state.files.forEachIndexed { i, f ->
                ParentCard(Modifier.padding(bottom = Dimens.s8)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (f.mimeType.startsWith("image/")) "🖼️" else if (f.mimeType.contains("pdf")) "📄" else "📊", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.padding(Dimens.s4))
                        Column(Modifier.weight(1f)) {
                            Text(f.name, style = MaterialTheme.typography.bodyLarge, color = Palette.parentInk, maxLines = 1)
                            Text("${f.bytes.size / 1024} KB", style = MaterialTheme.typography.bodySmall, color = Palette.parentInkSoft)
                        }
                        Chip(s.remove, Palette.parentLine) { dispatch(AddLessonContract.Intent.RemoveFile(i)) }
                    }
                }
            }
        }
        state.error?.let { Text(it, color = Palette.coral, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = Dimens.s8)) }
        Spacer(Modifier.height(Dimens.s24))
        ParentButton(s.readSlides, { dispatch(AddLessonContract.Intent.Submit) }, enabled = state.canSubmit && state.files.isNotEmpty(), icon = "✨")
        Spacer(Modifier.height(Dimens.s24))
    }
}

@Composable
fun TypedTaskScreen(state: AddLessonContract.State, s: Strings, dispatch: (AddLessonContract.Intent) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Dimens.s16)) {
        SectionTitle(s.subject)
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s8)) {
            Chip("🔢 ${s.math}", Palette.sand, selected = state.subject == Subject.MATH) { dispatch(AddLessonContract.Intent.SetSubject(Subject.MATH)) }
            Chip("🔤 ${s.english}", Palette.lavender, selected = state.subject == Subject.ENGLISH) { dispatch(AddLessonContract.Intent.SetSubject(Subject.ENGLISH)) }
        }
        SectionTitle(s.typedTaskTitle)
        OutlinedTextField(
            value = state.typedTask, onValueChange = { dispatch(AddLessonContract.Intent.TypedTask(it)) },
            modifier = Modifier.fillMaxWidth().height(160.dp), placeholder = { Text(s.typedTaskHint) },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        )
        Spacer(Modifier.height(Dimens.s24))
        ParentButton(s.readIt, { dispatch(AddLessonContract.Intent.Submit) }, enabled = state.typedTask.isNotBlank() && !state.submitting, icon = "✨")
    }
}
