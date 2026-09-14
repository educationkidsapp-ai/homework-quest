package quest.feature.children.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import quest.api.dto.Child
import quest.api.dto.CreateChildRequest
import quest.api.dto.Curriculum
import quest.api.dto.UpdateChildRequest
import quest.core.mvi.MviEffect
import quest.core.mvi.MviIntent
import quest.core.mvi.MviState
import quest.core.mvi.MviViewModel
import quest.feature.children.domain.AddChildUseCase
import quest.feature.children.domain.ChildrenRepository
import quest.feature.parent.presentation.Chip
import quest.feature.parent.presentation.ParentButton
import quest.feature.parent.presentation.ParentCard
import quest.feature.parent.presentation.ParentShell
import quest.feature.parent.presentation.SectionTitle
import quest.feature.parent.presentation.Strings
import quest.ui.design.AvatarColors
import quest.ui.design.Dimens
import quest.ui.design.Palette
import quest.ui.design.Pip
import quest.ui.design.PipPose

object AddChildContract {
    data class State(
        val editingId: String? = null, val name: String = "", val avatar: String = "sky", val curriculum: Curriculum = Curriculum.BRITISH, val grade: Int = 1,
        val languages: List<String> = listOf("en"), val busy: Boolean = false, val error: String? = null, val loaded: Boolean = false,
    ) : MviState
    sealed interface Intent : MviIntent {
        data object Load : Intent; data class Name(val v: String) : Intent; data class Avatar(val v: String) : Intent
        data class SetCurriculum(val v: Curriculum) : Intent; data class Grade(val v: Int) : Intent; data class ToggleLanguage(val code: String) : Intent; data object Save : Intent
    }
    sealed interface Effect : MviEffect { data class Saved(val child: Child) : Effect }
}

class AddChildViewModel(private val editingId: String?, private val children: ChildrenRepository, private val addChild: AddChildUseCase) :
    MviViewModel<AddChildContract.State, AddChildContract.Intent, AddChildContract.Effect>(AddChildContract.State(editingId = editingId)) {
    init { dispatch(AddChildContract.Intent.Load) }
    override suspend fun handle(intent: AddChildContract.Intent) {
        when (intent) {
            AddChildContract.Intent.Load -> {
                val c = editingId?.let { id -> children.children().firstOrNull { it.id == id } }
                reduce { if (c == null) copy(loaded = true) else copy(loaded = true, name = c.name, avatar = c.avatarColor, curriculum = c.curriculum, grade = c.grade, languages = c.languages) }
            }
            is AddChildContract.Intent.Name -> reduce { copy(name = intent.v, error = null) }
            is AddChildContract.Intent.Avatar -> reduce { copy(avatar = intent.v) }
            is AddChildContract.Intent.SetCurriculum -> reduce { copy(curriculum = intent.v) }
            is AddChildContract.Intent.Grade -> reduce { copy(grade = intent.v) }
            is AddChildContract.Intent.ToggleLanguage -> reduce { copy(languages = if (intent.code in languages) (languages - intent.code).ifEmpty { listOf("en") } else languages + intent.code) }
            AddChildContract.Intent.Save -> {
                reduce { copy(busy = true, error = null) }
                runCatching {
                    if (editingId == null) addChild(CreateChildRequest(current.name.trim(), current.avatar, current.curriculum, current.grade, current.languages))
                    else children.update(editingId, UpdateChildRequest(current.name.trim(), current.avatar, current.curriculum, current.grade, current.languages))
                }.onSuccess { reduce { copy(busy = false) }; effect(AddChildContract.Effect.Saved(it)) }
                    .onFailure { e -> reduce { copy(busy = false, error = e.message) } }
            }
        }
    }
}

@Composable
fun AddChildRoute(editingId: String?, onSaved: () -> Unit, onBack: (() -> Unit)?) {
    val vm: AddChildViewModel = koinViewModel(key = "child-$editingId") { parametersOf(editingId) }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.effects.collect { if (it is AddChildContract.Effect.Saved) onSaved() } }
    ParentShell(title = { if (editingId == null) it.addChild else it.childProfile }, onBack = onBack) { s -> AddChildScreen(state, s, vm::dispatch) }
}

/** Screen 13: name, Pip in four colours, curriculum (American / British), grade (1 / 2 / 3), subject languages. */
@Composable
fun AddChildScreen(state: AddChildContract.State, s: Strings, dispatch: (AddChildContract.Intent) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Dimens.s16)) {
        SectionTitle(s.childName)
        OutlinedTextField(state.name, { dispatch(AddChildContract.Intent.Name(it)) }, Modifier.fillMaxWidth(), placeholder = { Text(s.childName) }, singleLine = true)
        SectionTitle(s.avatar)
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s12)) {
            AvatarColors.keys.forEach { key ->
                Box(
                    Modifier.size(76.dp).background(Palette.parentSurface).border(if (state.avatar == key) 3.dp else 1.dp, if (state.avatar == key) Palette.parentAccent else Palette.parentRule)
                        .clickable(role = Role.Button) { dispatch(AddChildContract.Intent.Avatar(key)) }.semantics { contentDescription = "avatar $key" + if (state.avatar == key) ", selected" else "" },
                    contentAlignment = Alignment.Center,
                ) { Pip(PipPose.IDLE, 60.dp, animated = false, color = key) }
            }
        }
        SectionTitle(s.curriculum)
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s8)) {
            Chip(s.american, Palette.parentAccentSoft, selected = state.curriculum == Curriculum.AMERICAN) { dispatch(AddChildContract.Intent.SetCurriculum(Curriculum.AMERICAN)) }
            Chip(s.british, Palette.parentAccentSoft, selected = state.curriculum == Curriculum.BRITISH) { dispatch(AddChildContract.Intent.SetCurriculum(Curriculum.BRITISH)) }
        }
        SectionTitle(s.grade)
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s8)) { (1..3).forEach { g -> Chip("$g", Palette.parentAccentSoft, selected = state.grade == g) { dispatch(AddChildContract.Intent.Grade(g)) } } }
        SectionTitle(s.languages)
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s8)) {
            Chip("English", Palette.parentAccentSoft, selected = "en" in state.languages) { dispatch(AddChildContract.Intent.ToggleLanguage("en")) }
            Chip("العربية", Palette.parentAccentSoft, selected = "ar" in state.languages) { dispatch(AddChildContract.Intent.ToggleLanguage("ar")) }
        }
        state.error?.let { Text(it, color = Palette.parentAccent, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = Dimens.s8)) }
        Spacer(Modifier.height(Dimens.s24))
        ParentButton(s.save, { dispatch(AddChildContract.Intent.Save) }, enabled = state.name.isNotBlank() && !state.busy)
        Spacer(Modifier.height(Dimens.s24))
    }
}

/** Pick which child is playing (only shown when the parent has more than one). */
@Composable
fun ChildPickerScreen(children: List<Child>, s: Strings, onPick: (Child) -> Unit, onAdd: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Dimens.s16)) {
        SectionTitle(s.whoIsPlaying)
        children.forEach { c ->
            ParentCard(Modifier.padding(bottom = Dimens.s12), onClick = { onPick(c) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Pip(PipPose.IDLE, 56.dp, animated = false, color = c.avatarColor)
                    Spacer(Modifier.size(Dimens.s12))
                    Column { Text(c.name, style = MaterialTheme.typography.titleLarge, color = Palette.parentInk); Text("${if (c.curriculum == Curriculum.BRITISH) s.british else s.american} · ${s.grade} ${c.grade}", style = MaterialTheme.typography.bodyMedium, color = Palette.parentInkSoft) }
                }
            }
        }
        ParentButton(s.addChild, onAdd, primary = false, icon = "＋")
    }
}
