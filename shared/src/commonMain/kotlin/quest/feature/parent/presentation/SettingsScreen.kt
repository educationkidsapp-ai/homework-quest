package quest.feature.parent.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import quest.core.mvi.MviEffect
import quest.core.mvi.MviIntent
import quest.core.mvi.MviState
import quest.core.mvi.MviViewModel
import quest.feature.children.domain.ChildrenRepository
import quest.feature.parent.domain.ParentRepository
import quest.feature.parent.domain.ParentSettings
import quest.ui.design.Dimens
import quest.ui.design.Palette

object SettingsContract {
    data class State(val loading: Boolean = true, val settings: ParentSettings = ParentSettings("en"), val childName: String = "", val childId: String? = null) : MviState
    sealed interface Intent : MviIntent { data object Load : Intent; data class Language(val code: String) : Intent }
    sealed interface Effect : MviEffect
}

class SettingsViewModel(private val parent: ParentRepository, private val children: ChildrenRepository) : MviViewModel<SettingsContract.State, SettingsContract.Intent, SettingsContract.Effect>(SettingsContract.State()) {
    override suspend fun handle(intent: SettingsContract.Intent) {
        when (intent) {
            SettingsContract.Intent.Load -> { val s = parent.settings(); val c = children.currentChild.value; reduce { copy(loading = false, settings = s, childName = c?.name ?: "", childId = c?.id) } }
            is SettingsContract.Intent.Language -> { parent.setLanguage(intent.code); reduce { copy(settings = settings.copy(language = intent.code)) } }
        }
    }
}

@Composable
fun SettingsRoute(onChangePin: () -> Unit, onEditChild: (String) -> Unit, onBack: () -> Unit) {
    val vm: SettingsViewModel = koinViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.dispatch(SettingsContract.Intent.Load) }
    ParentShell(title = { it.settings }, onBack = onBack) { s -> SettingsScreen(state, s, vm::dispatch, onChangePin, onEditChild) }
}

/** Screens 22–23: language (EN / AR with RTL), child profile, change PIN, privacy. */
@Composable
fun SettingsScreen(state: SettingsContract.State, s: Strings, dispatch: (SettingsContract.Intent) -> Unit, onChangePin: () -> Unit, onEditChild: (String) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Dimens.s16)) {
        SectionTitle(s.childProfile)
        ParentButton(state.childName.ifBlank { s.childProfile }, { state.childId?.let(onEditChild) }, primary = false, icon = "🧒", enabled = state.childId != null)
        SectionTitle(s.language)
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s8)) {
            Chip("English", Palette.parentAccentSoft, selected = state.settings.language == "en") { dispatch(SettingsContract.Intent.Language("en")) }
            Chip("العربية", Palette.parentAccentSoft, selected = state.settings.language == "ar") { dispatch(SettingsContract.Intent.Language("ar")) }
        }
        SectionTitle(s.changePin)
        ParentButton(s.changePin, onChangePin, primary = false, icon = "🔒")
        SectionTitle(s.privacy)
        ParentCard { Text(s.privacyBody, style = MaterialTheme.typography.bodyMedium, color = Palette.parentInkSoft) }
        Text("${s.version} 0.2.0", style = MaterialTheme.typography.bodySmall, color = Palette.parentInkSoft, modifier = Modifier.padding(vertical = Dimens.s16))
    }
}
