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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import quest.api.dto.Subject
import quest.core.design.Dimens
import quest.core.design.Palette
import quest.core.mvi.MviEffect
import quest.core.mvi.MviIntent
import quest.core.mvi.MviState
import quest.core.mvi.MviViewModel
import quest.feature.lesson.domain.ConfirmSkillsUseCase
import quest.feature.lesson.domain.LessonRepository
import quest.feature.lesson.domain.Skill
import quest.feature.lesson.domain.SkillDecision
import quest.feature.parent.presentation.Chip
import quest.feature.parent.presentation.LocalStrings
import quest.feature.parent.presentation.ParentButton
import quest.feature.parent.presentation.ParentCard
import quest.feature.parent.presentation.ParentShell
import quest.feature.parent.presentation.Strings

object ConfirmContract {
    data class Row(val skill: Skill, val keep: Boolean = true, val chosenName: String = skill.name) {
        val resolved: Boolean get() = skill.unsure == null || chosenName != skill.name || skill.unsure.candidates.contains(chosenName)
    }
    data class State(
        val loading: Boolean = true, val subject: Subject = Subject.MATH, val rows: List<Row> = emptyList(),
        val manual: List<String> = emptyList(), val newSkill: String = "", val submitting: Boolean = false, val error: String? = null,
    ) : MviState {
        val canSubmit: Boolean get() = !submitting && (rows.any { it.keep } || manual.isNotEmpty())
    }
    sealed interface Intent : MviIntent {
        data object Load : Intent
        data class Toggle(val skillId: String) : Intent
        data class Resolve(val skillId: String, val name: String) : Intent
        data class EditNew(val text: String) : Intent
        data object AddManual : Intent
        data class RemoveManual(val index: Int) : Intent
        data object Submit : Intent
    }
    sealed interface Effect : MviEffect { data class Confirmed(val lessonId: String) : Effect }
}

class ConfirmSkillsViewModel(private val lessonId: String, private val lessons: LessonRepository, private val confirm: ConfirmSkillsUseCase) :
    MviViewModel<ConfirmContract.State, ConfirmContract.Intent, ConfirmContract.Effect>(ConfirmContract.State()) {
    init { dispatch(ConfirmContract.Intent.Load) }

    override suspend fun handle(intent: ConfirmContract.Intent) {
        when (intent) {
            ConfirmContract.Intent.Load -> {
                val lesson = lessons.lesson(lessonId)
                val skills = lessons.skillsForLesson(lessonId)
                reduce { copy(loading = false, subject = lesson?.subject ?: Subject.MATH, rows = skills.map { s -> ConfirmContract.Row(s, chosenName = s.unsure?.candidates?.first() ?: s.name) }) }
            }
            is ConfirmContract.Intent.Toggle -> reduce { copy(rows = rows.map { if (it.skill.id == intent.skillId) it.copy(keep = !it.keep) else it }) }
            is ConfirmContract.Intent.Resolve -> reduce { copy(rows = rows.map { if (it.skill.id == intent.skillId) it.copy(chosenName = intent.name, keep = true) else it }) }
            is ConfirmContract.Intent.EditNew -> reduce { copy(newSkill = intent.text) }
            ConfirmContract.Intent.AddManual -> if (current.newSkill.isNotBlank()) reduce { copy(manual = manual + newSkill.trim(), newSkill = "") }
            is ConfirmContract.Intent.RemoveManual -> reduce { copy(manual = manual.filterIndexed { i, _ -> i != intent.index }) }
            ConfirmContract.Intent.Submit -> submit()
        }
    }

    private suspend fun submit() {
        if (!current.canSubmit) return
        reduce { copy(submitting = true, error = null) }
        val decisions = current.rows.map { SkillDecision(it.skill.id, it.chosenName, it.skill.subject, it.skill.method, it.keep) } +
            current.manual.map { SkillDecision(null, it, current.subject, null, true) }
        runCatching { confirm(lessonId, decisions) }
            .onSuccess { effect(ConfirmContract.Effect.Confirmed(lessonId)) }
            .onFailure { e -> reduce { copy(submitting = false, error = e.message) } }
    }
}

@Composable
fun ConfirmSkillsRoute(lessonId: String, onConfirmed: (String) -> Unit, onBack: () -> Unit) {
    val vm: ConfirmSkillsViewModel = koinViewModel(key = "confirm-$lessonId") { parametersOf(lessonId) }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.effects.collect { if (it is ConfirmContract.Effect.Confirmed) onConfirmed(it.lessonId) } }
    ParentShell(title = { it.confirmTitle }, onBack = onBack) { s -> ConfirmSkillsScreen(state, s, vm::dispatch) }
}

@Composable
fun ConfirmSkillsScreen(state: ConfirmContract.State, s: Strings, dispatch: (ConfirmContract.Intent) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Dimens.s16)) {
        Text(s.confirmBody, style = MaterialTheme.typography.bodyLarge, color = Palette.parentInkSoft, modifier = Modifier.padding(vertical = Dimens.s8))
        state.rows.forEach { row ->
            ParentCard(Modifier.padding(bottom = Dimens.s12)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = row.keep, onCheckedChange = { dispatch(ConfirmContract.Intent.Toggle(row.skill.id)) },
                        colors = CheckboxDefaults.colors(checkedColor = Palette.parentAccent),
                        modifier = Modifier.semantics { contentDescription = "keep ${row.chosenName}" },
                    )
                    Column(Modifier.weight(1f)) {
                        Text(row.chosenName, style = MaterialTheme.typography.titleMedium, color = Palette.parentInk)
                        Text(row.skill.method, style = MaterialTheme.typography.bodySmall, color = Palette.parentInkSoft)
                        if (row.skill.examples.isNotEmpty()) Text(row.skill.examples.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Palette.parentInkSoft)
                    }
                }
                row.skill.unsure?.let { unsure ->
                    Spacer(Modifier.height(Dimens.s8))
                    Text("❓ ${s.unsureQuestion}", style = MaterialTheme.typography.labelLarge, color = Palette.sunDeep)
                    Text(unsure.question, style = MaterialTheme.typography.bodyMedium, color = Palette.parentInk)
                    Spacer(Modifier.height(Dimens.s8))
                    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s8)) {
                        unsure.candidates.forEach { c -> Chip(c, Palette.sun, selected = row.chosenName == c) { dispatch(ConfirmContract.Intent.Resolve(row.skill.id, c)) } }
                    }
                }
            }
        }
        state.manual.forEachIndexed { i, name ->
            ParentCard(Modifier.padding(bottom = Dimens.s12)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("✍️ $name", style = MaterialTheme.typography.titleMedium, color = Palette.parentInk, modifier = Modifier.weight(1f))
                    Chip(s.remove, Palette.parentLine) { dispatch(ConfirmContract.Intent.RemoveManual(i)) }
                }
            }
        }
        ParentCard {
            Text(s.addSkill, style = MaterialTheme.typography.titleMedium, color = Palette.parentInk)
            Spacer(Modifier.height(Dimens.s8))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.s8)) {
                OutlinedTextField(value = state.newSkill, onValueChange = { dispatch(ConfirmContract.Intent.EditNew(it)) }, modifier = Modifier.weight(1f), placeholder = { Text(s.skillName) }, singleLine = true)
                ParentButton("＋", { dispatch(ConfirmContract.Intent.AddManual) }, Modifier.weight(0.3f), primary = false, enabled = state.newSkill.isNotBlank())
            }
        }
        if (!state.canSubmit && !state.loading) Text(s.keepAtLeastOne, color = Palette.coral, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = Dimens.s8))
        state.error?.let { Text(it, color = Palette.coral, style = MaterialTheme.typography.bodyMedium) }
        Spacer(Modifier.height(Dimens.s16))
        ParentButton(s.makeQuest, { dispatch(ConfirmContract.Intent.Submit) }, enabled = state.canSubmit, icon = "✨")
        Spacer(Modifier.height(Dimens.s24))
    }
}
