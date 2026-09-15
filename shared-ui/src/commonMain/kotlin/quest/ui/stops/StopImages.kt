package quest.ui.stops

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.decodeToImageBitmap
import quest.ui.design.Dimens
import quest.ui.design.Palette

/**
 * Pictures inside stops (`Stop.imageId`, `Tile.pageImageId`…): the host resolves an image id to its bytes — the app
 * from `PublishedLesson.pageImages` + HTTP, the admin preview from `/media/pages/{id}` with its token. Tests and
 * screenshots get [NoStopImages] and draw the emoji glyph instead.
 */
fun interface StopImageLoader { suspend fun load(imageId: String): ByteArray? }

object NoStopImages : StopImageLoader { override suspend fun load(imageId: String): ByteArray? = null }

val LocalStopImageLoader = staticCompositionLocalOf<StopImageLoader> { NoStopImages }

private val decoded = HashMap<String, ImageBitmap>()

/** Loads and decodes once per id; null while loading or when the host has no picture for the id. */
@Composable
fun rememberStopImage(imageId: String?): State<ImageBitmap?> {
    val loader = LocalStopImageLoader.current
    return produceState<ImageBitmap?>(initialValue = imageId?.let { decoded[it] }, imageId, loader) {
        if (imageId == null) { value = null; return@produceState }
        decoded[imageId]?.let { value = it; return@produceState }
        val bytes = runCatching { loader.load(imageId) }.getOrNull()
        value = bytes?.let { runCatching { it.decodeToImageBitmap() }.getOrNull() }?.also { decoded[imageId] = it }
    }
}

/** The picture a stop carries, above its content: rounded card, 3:2, letterboxed on the cream ground. */
@Composable
fun StopPicture(imageId: String, modifier: Modifier = Modifier, description: String? = null) {
    val image by rememberStopImage(imageId)
    Box(modifier.fillMaxWidth().padding(horizontal = Dimens.s16).aspectRatio(1.5f).clip(RoundedCornerShape(Dimens.radiusCard)).background(Palette.cream), contentAlignment = Alignment.Center) {
        val bmp = image
        if (bmp != null) Image(bmp, description, Modifier.fillMaxWidth().aspectRatio(1.5f), contentScale = ContentScale.Fit)
        else Text("🖼️", fontSize = 48.sp)
    }
}
