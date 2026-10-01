package quest.feature.broadcasts.presentation

import quest.ui.design.DashboardTokens
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import quest.api.dto.BroadcastAttachment
import quest.core.LruCache
import quest.core.platform.decodeBoundedImage
import quest.feature.broadcasts.domain.AttachmentImages
import quest.feature.broadcasts.domain.NoAttachmentImages
import quest.feature.parent.presentation.ParentButton
import quest.feature.parent.presentation.Strings
import quest.ui.design.Dimens

/** Provided by the app root; [NoAttachmentImages] under tests, screenshots and previews, so nothing is fetched. */
val LocalAttachmentImages = staticCompositionLocalOf<AttachmentImages> { NoAttachmentImages }

/**
 * Decoded bitmaps, **at most three**: the pinned plan, the one earlier week a parent has open, and whichever of them
 * the full-screen viewer is showing at full size. A card is redrawn on every recomposition, so some cache is needed;
 * holding every week she has ever opened is not, and a weekly plan is a 5 MB photograph rather than a small asset.
 *
 * The key carries the bound, because the card and the viewer want different sizes of the same image.
 */
private val decoded = LruCache<String, ImageBitmap>(MAX_DECODED)

internal const val MAX_DECODED = 3

/**
 * The longest edge the **card** decodes to. The card is capped at 420 dp and the widest phone the app ships on is
 * about 430 dp at 3.5×, so this is already more pixels than it can draw; a plan straight off a camera is several times
 * larger again. [FULL_SIZE] is the viewer's bound — none, because zooming in is the whole reason to open it.
 */
internal const val CARD_MAX_PX = 1440
internal const val FULL_SIZE = 0

private sealed interface Load {
    data object Loading : Load
    data object Failed : Load
    data class Ready(val image: ImageBitmap) : Load
}

/** The cache key for one attachment at one decode bound. */
internal fun decodeKey(attachment: BroadcastAttachment, maxDimensionPx: Int) =
    "${attachment.id ?: attachment.url}@$maxDimensionPx"

@Composable
private fun rememberAttachment(attachment: BroadcastAttachment, attempt: Int, maxDimensionPx: Int): State<Load> {
    val images = LocalAttachmentImages.current
    val key = decodeKey(attachment, maxDimensionPx)
    return produceState<Load>(decoded[key]?.let { Load.Ready(it) } ?: Load.Loading, key, attempt, images) {
        decoded[key]?.let { value = Load.Ready(it); return@produceState }
        value = Load.Loading
        // The bytes come off the disk cache after the first fetch, so a second decode at the viewer's size is a read
        // and a decode rather than a second download.
        val bytes = images.load(attachment)
        val image = bytes?.let { runCatching { decodeBoundedImage(it, maxDimensionPx) }.getOrNull() }
        value = if (image == null) Load.Failed else Load.Ready(image).also { decoded[key] = image }
    }
}

/**
 * MH3: an image attachment as the parent sees it — a tinted placeholder with a spinner while it downloads, the image
 * itself once it is here, and a **Try again** button when it is not. There is no error colour and no red X (§7): a
 * school's image that will not load is a card that says so, on the parent palette.
 *
 * The image fills the card's width and is capped so a tall plan cannot push the earlier weeks off the screen; tapping
 * it opens the full-screen viewer, where it pinches and pans.
 */
@Composable
fun AttachmentImage(attachment: BroadcastAttachment, description: String, strings: Strings, modifier: Modifier = Modifier) {
    var attempt by remember { mutableIntStateOf(0) }
    val load by rememberAttachment(attachment, attempt, CARD_MAX_PX)
    var full by remember { mutableStateOf(false) }

    Box(
        modifier.fillMaxWidth().heightIn(max = 420.dp).background(MaterialTheme.colorScheme.primaryContainer)
            .then(if (load is Load.Ready) Modifier.clickable { full = true } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        when (val state = load) {
            Load.Loading -> Box(Modifier.fillMaxWidth().aspectRatio(1.4f), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
            Load.Failed -> Box(Modifier.fillMaxWidth().aspectRatio(1.4f).padding(Dimens.s16), contentAlignment = Alignment.Center) {
                ParentButton(strings.tryAgain, { attempt++ }, primary = false, icon = "↻")
            }
            is Load.Ready -> Image(
                bitmap = state.image,
                contentDescription = description,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = description },
                contentScale = ContentScale.FillWidth,
            )
        }
    }
    if (load is Load.Failed) {
        Text(strings.imageFailed, style = MaterialTheme.typography.bodySmall, color = DashboardTokens.inkSoft)
    }
    // The viewer decodes the same bytes again at full size, so pinching to 6× shows detail the card never held.
    if (full && load is Load.Ready) FullScreenImage(attachment, description, strings) { full = false }
}

/**
 * The plan at full size: pinch to zoom, drag to pan, tap anywhere to close. The scale is clamped so the image cannot be
 * flicked off its own dialog and lost — the way back is always the same tap.
 */
@Composable
private fun FullScreenImage(attachment: BroadcastAttachment, description: String, strings: Strings, onClose: () -> Unit) {
    val load by rememberAttachment(attachment, attempt = 0, maxDimensionPx = FULL_SIZE)
    // `usePlatformDefaultWidth = false`, or the dialog keeps a phone-dialog inset and the plan is a stamp in the middle
    // of a black sheet — the whole point of the full-screen view is that the week is legible.
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        var scale by remember { mutableFloatStateOf(1f) }
        var offsetX by remember { mutableFloatStateOf(0f) }
        var offsetY by remember { mutableFloatStateOf(0f) }
        Box(
            Modifier.fillMaxSize().background(Color.Black)
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 6f)
                        if (scale <= 1f) { offsetX = 0f; offsetY = 0f } else { offsetX += pan.x; offsetY += pan.y }
                    }
                }
                .clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            val image = (load as? Load.Ready)?.image
            if (image == null) {
                CircularProgressIndicator(color = Color.White)
                return@Box
            }
            Image(
                bitmap = image,
                contentDescription = description,
                modifier = Modifier.fillMaxSize()
                    .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offsetX, translationY = offsetY),
                contentScale = ContentScale.Fit,
            )
            Text(
                strings.tapToClose,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White,
                modifier = Modifier.align(Alignment.BottomCenter).padding(Dimens.s24),
            )
        }
    }
}
