package quest.feature.school.presentation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.decodeToImageBitmap
import quest.feature.school.domain.NoSchoolLogos
import quest.feature.school.domain.SchoolLogoLoader

/** Provided by the app root; [NoSchoolLogos] under tests and previews, so a logo is never fetched off-app. */
val LocalSchoolLogos = staticCompositionLocalOf<SchoolLogoLoader> { NoSchoolLogos }

private val decoded = HashMap<String, ImageBitmap>()

@Composable
private fun rememberSchoolLogo(url: String?): State<ImageBitmap?> {
    val loader = LocalSchoolLogos.current
    return produceState<ImageBitmap?>(initialValue = url?.let { decoded[it] }, url, loader) {
        if (url.isNullOrBlank()) { value = null; return@produceState }
        decoded[url]?.let { value = it; return@produceState }
        val bytes = runCatching { loader.load(url) }.getOrNull()
        value = bytes?.let { runCatching { it.decodeToImageBitmap() }.getOrNull() }?.also { decoded[url] = it }
    }
}

/**
 * The school's mark: its monogram on a tinted square straight away, the real logo fading in over it once it has been
 * fetched (§6 "the school logo appears with a fade-in"). There is no spinner and no broken-image state — a school with
 * no logo, or a logo that will not load, simply keeps its monogram.
 */
@Composable
fun SchoolLogo(
    logoUrl: String?,
    schoolName: String,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    background: Color = MaterialTheme.colorScheme.primaryContainer,
    ink: Color = MaterialTheme.colorScheme.onPrimaryContainer,
) {
    val logo by rememberSchoolLogo(logoUrl)
    val monogram = schoolName.trim().takeIf { it.isNotEmpty() }?.first()?.uppercase() ?: "?"
    Box(
        modifier.size(size).clip(RoundedCornerShape(size / 6)).background(background)
            .semantics { contentDescription = schoolName.ifBlank { "School logo" } },
        contentAlignment = Alignment.Center,
    ) {
        Text(monogram, style = MaterialTheme.typography.titleLarge, color = ink)
        AnimatedVisibility(logo != null, enter = fadeIn(tween(300))) {
            logo?.let { Image(it, contentDescription = null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
        }
    }
}
