package quest.feature.translate.presentation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import quest.api.dto.ReadingLanguage
import quest.api.dto.TranslationSource
import quest.core.text.isolate
import quest.feature.school.domain.Flags
import quest.feature.school.presentation.featureEnabled
import quest.feature.translate.domain.ReadingLanguages
import quest.feature.translate.domain.TranslateKey
import quest.feature.translate.domain.TranslationStore
import quest.ui.design.DashboardTokens
import quest.ui.design.Dimens

/**
 * Feature 29: the store the screens reach for, through a composition local rather than Koin, so a screenshot test, a
 * preview or the admin's phone preview can draw a message without a container. Null is "this build has no bridge" and
 * draws nothing at all — the same as the flag being off, which is what a school that has not bought it must see.
 */
val LocalTranslations = staticCompositionLocalOf<TranslationStore?> { null }

/**
 * The Translate action under one **staff-authored** item the parent reads — a message from a teacher, a broadcast, an
 * announcement, a reply in a complaint — and, once she has asked, the translation under the original.
 *
 * The original is the caller's to draw and this composable never touches it: it only adds. A translation always
 * carries the "Machine-translated" label and a "Show original" that puts it away without losing it.
 *
 * Nothing is drawn when the school has no `languageBridge`, when she has chosen no reading language, or when the text
 * is already in the language she reads — the three cases [ReadingLanguages.offered] decides.
 *
 * @param sourceLang which language the text in front of her is. Defaults to the script it is written in, which is the
 *   right answer for a message somebody typed; a broadcast knows better (it holds both bodies) and says so.
 * @param textColor the bubble's own ink — a translation inside a parent's own colour has to stay legible.
 */
@Composable
fun TranslateBlock(
    childId: String,
    kind: TranslationSource,
    sourceId: String,
    original: String,
    strings: TranslateStrings,
    modifier: Modifier = Modifier,
    sourceLang: ReadingLanguage = ReadingLanguages.scriptLanguage(original),
    textColor: Color = DashboardTokens.ink,
) {
    val store = LocalTranslations.current ?: return
    if (!featureEnabled(Flags.LANGUAGE_BRIDGE)) return
    // Behind the flag, and only then: her language is one `GET /parent/me` for the whole session.
    LaunchedEffect(store) { store.ensureReadingLanguage() }
    val reading by store.readingLanguage.collectAsState()
    val lang = reading ?: return
    if (!ReadingLanguages.offered(flagOn = true, reading = lang, source = sourceLang)) return
    if (original.isBlank()) return

    val key = TranslateKey(kind, sourceId, lang)
    val item by store.items.collectAsState()
    val state = item[key]
    val scope = rememberCoroutineScope()
    val soft = textColor.copy(alpha = 0.75f)

    // Not `fillMaxWidth` on the column itself: a chat bubble is as wide as its message, and an action that stretched
    // it to the maximum would widen every staff bubble the moment the bridge is switched on.
    Column(modifier) {
        if (state?.showing == true && state.text != null) {
            Spacer(Modifier.height(Dimens.s4))
            // The label first: she is told what she is about to read before she reads it, never afterwards.
            Text(
                text = "${ReadingLanguages.name(lang)} · ${strings.machine}",
                style = MaterialTheme.typography.labelSmall,
                color = soft,
                fontWeight = FontWeight.Bold,
            )
            // The translation reads in **its own** direction, not the screen's: Urdu and Arabic right to left, Hindi,
            // Bengali, Filipino, French and Indonesian left to right, whichever language the app itself is in.
            val rtl = ReadingLanguages.isRtl(lang)
            CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                Text(
                    text = isolate(state.text),
                    style = MaterialTheme.typography.bodyLarge,
                    color = textColor,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            TranslateAction(strings.showOriginal, soft) { store.showOriginal(key) }
        } else {
            if (state?.failure != null) Text(
                text = strings.failure(state.failure),
                style = MaterialTheme.typography.labelSmall,
                color = soft,
            )
            val label = if (state?.loading == true) strings.translating else strings.translate
            TranslateAction(label, soft, enabled = state?.loading != true) {
                scope.launch { store.translate(childId, key) }
            }
        }
    }
}

/** §7's 48 dp target, in the quiet weight a line under a message should have. */
@Composable
private fun TranslateAction(label: String, color: Color, enabled: Boolean = true, onClick: () -> Unit) {
    Row {
        TextButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
            colors = ButtonDefaults.textButtonColors(contentColor = color),
            contentPadding = ButtonDefaults.TextButtonContentPadding,
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        }
    }
}
