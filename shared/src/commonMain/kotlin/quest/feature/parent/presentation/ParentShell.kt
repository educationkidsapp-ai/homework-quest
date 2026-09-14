package quest.feature.parent.presentation

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import quest.feature.parent.domain.ParentRepository
import quest.ui.design.Dimens
import quest.ui.design.Palette
import quest.ui.design.ParentTheme

/** Wraps every parent route: applies the parent theme, RTL when Arabic, and the header with the language toggle. */
@Composable
fun ParentShell(title: (Strings) -> String, onBack: (() -> Unit)?, content: @Composable (Strings) -> Unit) {
    val parent: ParentRepository = koinInject()
    val language by parent.language.collectAsStateWithLifecycle()
    val strings = Strings.forLanguage(language)
    val scope = rememberCoroutineScope()
    ParentTheme(rtl = strings.isRtl) {
        CompositionLocalProvider(LocalStrings provides strings) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = Dimens.s8, vertical = Dimens.s8), verticalAlignment = Alignment.CenterVertically) {
                    if (onBack != null) {
                        IconButton(onClick = onBack, modifier = Modifier.size(48.dp).semantics { contentDescription = "Back" }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = Palette.parentInk)
                        }
                    } else Spacer(Modifier.width(Dimens.s8))
                    Text(title(strings), style = MaterialTheme.typography.headlineMedium, color = Palette.parentInk, modifier = Modifier.weight(1f).padding(start = Dimens.s8))
                    LanguageToggle(language) { code -> scope.launch { parent.setLanguage(code) } }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) { content(strings) }
            }
        }
    }
}

@Composable
private fun LanguageToggle(current: String, onChange: (String) -> Unit) {
    Row(Modifier.border(1.dp, Palette.parentLine, RectangleShape).padding(3.dp).semantics { contentDescription = "Language" }) {
        listOf("en" to "EN", "ar" to "ع").forEach { (code, label) ->
            val on = code == current
            Box(
                Modifier.background(if (on) Palette.parentAccent else Color.Transparent, RectangleShape)
                    .clickable(role = Role.Button) { onChange(code) }.padding(horizontal = 12.dp, vertical = 6.dp),
            ) { Text(label, style = MaterialTheme.typography.labelLarge, color = if (on) Color.White else Palette.parentInkSoft) }
        }
    }
}

@Composable
fun ParentCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Column(
        modifier.fillMaxWidth().background(Palette.parentSurface, RectangleShape).border(1.dp, Palette.parentLine, RectangleShape)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(Dimens.s16),
    ) { content() }
}

@Composable
fun ParentButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = true, enabled: Boolean = true, icon: String? = null) {
    Box(
        modifier.fillMaxWidth().heightIn(min = 52.dp).alpha(if (enabled) 1f else 0.5f)
            .background(if (primary) Palette.parentAccent else Palette.parentAccentSoft, RectangleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = Dimens.s16, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            if (icon != null) { Text(icon, style = MaterialTheme.typography.titleLarge); Spacer(Modifier.width(Dimens.s8)) }
            Text(text, style = MaterialTheme.typography.labelLarge, color = if (primary) Color.White else Palette.parentInk)
        }
    }
}

@Composable
fun Chip(text: String, color: Color, modifier: Modifier = Modifier, selected: Boolean = true, onClick: (() -> Unit)? = null) {
    Box(
        modifier.background(if (selected) color else Color.Transparent, RectangleShape).border(1.dp, color, RectangleShape)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) { Text(text, style = MaterialTheme.typography.labelLarge, color = Palette.parentInk) }
}

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = Palette.parentInkSoft, modifier = Modifier.padding(top = Dimens.s16, bottom = Dimens.s8))
    Spacer(Modifier.height(0.dp))
}
