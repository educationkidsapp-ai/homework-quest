package quest.feature.parent.presentation

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import quest.core.design.Dimens
import quest.core.design.Palette
import quest.core.mvi.MviEffect
import quest.core.mvi.MviIntent
import quest.core.mvi.MviState
import quest.core.mvi.MviViewModel
import quest.feature.lesson.domain.DeleteUploadedFilesUseCase
import quest.feature.parent.domain.ChildProfile
import quest.feature.parent.domain.ParentRepository
import quest.feature.parent.domain.ParentSettings

object SettingsContract {
    data class State(
        val loading: Boolean = true, val profile: ChildProfile? = null, val settings: ParentSettings = ParentSettings(7, "en"),
        val deleting: Boolean = false, val deletedCount: Int? = null,
        // profile editing
        val name: String = "", val grade: Int = 1, val curriculum: String = "", val saved: Boolean = false,
    ) : MviState
    sealed interface Intent : MviIntent {
        data object Load : Intent
        data class PracticeLength(val n: Int) : Intent
        data class Language(val code: String) : Intent
        data object DeleteFiles : Intent
        data class EditName(val v: String) : Intent
        data class EditGrade(val v: Int) : Intent
        data class EditCurriculum(val v: String) : Intent
        data object SaveProfile : Intent
    }
    sealed interface Effect : MviEffect { data object ProfileSaved : Effect }
}

class SettingsViewModel(private val parent: ParentRepository, private val deleteFiles: DeleteUploadedFilesUseCase) :
    MviViewModel<SettingsContract.State, SettingsContract.Intent, SettingsContract.Effect>(SettingsContract.State()) {
    override suspend fun handle(intent: SettingsContract.Intent) {
        when (intent) {
            SettingsContract.Intent.Load -> {
                val p = parent.profile(); val s = parent.settings()
                reduce { copy(loading = false, profile = p, settings = s, name = p.name, grade = p.grade, curriculum = p.curriculum) }
            }
            is SettingsContract.Intent.PracticeLength -> { parent.setPracticeLength(intent.n); reduce { copy(settings = settings.copy(practiceLength = intent.n)) } }
            is SettingsContract.Intent.Language -> { parent.setLanguage(intent.code); reduce { copy(settings = settings.copy(language = intent.code)) } }
            SettingsContract.Intent.DeleteFiles -> {
                reduce { copy(deleting = true) }
                val n = runCatching { deleteFiles() }.getOrDefault(0)
                reduce { copy(deleting = false, deletedCount = n) }
            }
            is SettingsContract.Intent.EditName -> reduce { copy(name = intent.v, saved = false) }
            is SettingsContract.Intent.EditGrade -> reduce { copy(grade = intent.v, saved = false) }
            is SettingsContract.Intent.EditCurriculum -> reduce { copy(curriculum = intent.v, saved = false) }
            SettingsContract.Intent.SaveProfile -> {
                parent.saveProfile(current.name.trim(), current.grade, current.curriculum.trim().ifBlank { "international" }, current.profile?.avatarColor ?: "sun")
                val refreshed = parent.profile()
                reduce { copy(saved = true, profile = refreshed) }
                effect(SettingsContract.Effect.ProfileSaved)
            }
        }
    }
}

@Composable
fun SettingsRoute(onChangePin: () -> Unit, onBack: () -> Unit) {
    val vm: SettingsViewModel = koinViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.dispatch(SettingsContract.Intent.Load) }
    ParentShell(title = { it.settings }, onBack = onBack) { s -> SettingsScreen(state, s, vm::dispatch, onChangePin) }
}

@Composable
fun SettingsScreen(state: SettingsContract.State, s: Strings, dispatch: (SettingsContract.Intent) -> Unit, onChangePin: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Dimens.s16)) {
        SectionTitle(s.childProfile)
        ProfileEditor(state, s, dispatch)

        SectionTitle(s.practiceLength)
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s8)) {
            listOf(5, 7, 10).forEach { n -> Chip("$n ${s.questions}", Palette.parentAccentSoft, selected = state.settings.practiceLength == n) { dispatch(SettingsContract.Intent.PracticeLength(n)) } }
        }

        SectionTitle(s.language)
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s8)) {
            Chip("English", Palette.parentAccentSoft, selected = state.settings.language == "en") { dispatch(SettingsContract.Intent.Language("en")) }
            Chip("العربية", Palette.parentAccentSoft, selected = state.settings.language == "ar") { dispatch(SettingsContract.Intent.Language("ar")) }
        }

        SectionTitle(s.changePin)
        ParentButton(s.changePin, onChangePin, primary = false, icon = "🔒")

        SectionTitle(s.deleteFiles)
        ParentCard {
            Text(s.deleteFilesBody, style = MaterialTheme.typography.bodyMedium, color = Palette.parentInkSoft)
            Spacer(Modifier.height(Dimens.s12))
            ParentButton(if (state.deletedCount != null) "${s.deleted} (${state.deletedCount})" else s.deleteFiles, { dispatch(SettingsContract.Intent.DeleteFiles) }, enabled = !state.deleting, icon = "🗑️")
        }

        SectionTitle(s.privacy)
        ParentCard { Text(s.privacyBody, style = MaterialTheme.typography.bodyMedium, color = Palette.parentInkSoft) }
        Text("${s.version} 0.1.0", style = MaterialTheme.typography.bodySmall, color = Palette.parentInkSoft, modifier = Modifier.padding(vertical = Dimens.s16))
    }
}

@Composable
fun ProfileEditor(state: SettingsContract.State, s: Strings, dispatch: (SettingsContract.Intent) -> Unit) {
    ParentCard {
        OutlinedTextField(state.name, { dispatch(SettingsContract.Intent.EditName(it)) }, Modifier.fillMaxWidth(), label = { Text(s.childName) }, singleLine = true)
        Spacer(Modifier.height(Dimens.s8))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.s8)) {
            Text(s.grade, style = MaterialTheme.typography.bodyLarge, color = Palette.parentInk)
            listOf(1, 2, 3).forEach { g -> Chip("$g", Palette.parentAccentSoft, selected = state.grade == g) { dispatch(SettingsContract.Intent.EditGrade(g)) } }
        }
        Spacer(Modifier.height(Dimens.s8))
        OutlinedTextField(state.curriculum, { dispatch(SettingsContract.Intent.EditCurriculum(it)) }, Modifier.fillMaxWidth(), label = { Text(s.curriculum) }, singleLine = true)
        Spacer(Modifier.height(Dimens.s12))
        ParentButton(if (state.saved) "✓ ${s.save}" else s.save, { dispatch(SettingsContract.Intent.SaveProfile) }, enabled = state.name.isNotBlank())
    }
}

@Composable
fun ProfileRoute(onDone: () -> Unit, onBack: () -> Unit) {
    val vm: SettingsViewModel = koinViewModel(key = "profile")
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.dispatch(SettingsContract.Intent.Load); vm.effects.collect { if (it is SettingsContract.Effect.ProfileSaved) onDone() } }
    ParentShell(title = { it.childProfile }, onBack = onBack) { s ->
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Dimens.s16)) {
            Spacer(Modifier.height(Dimens.s8))
            ProfileEditor(state, s, vm::dispatch)
        }
    }
}
