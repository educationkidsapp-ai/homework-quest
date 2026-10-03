package quest.core.platform

import androidx.compose.runtime.Composable
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

actual fun photoAsJpeg(bytes: ByteArray, maxDimensionPx: Int, quality: Int): ByteArray? {
    val image = runCatching { Image.makeFromEncoded(bytes) }.getOrNull() ?: return null
    val longest = maxOf(image.width, image.height)
    val scaled = if (longest <= maxDimensionPx) image else {
        val scale = maxDimensionPx.toFloat() / longest
        val surface = org.jetbrains.skia.Surface.makeRasterN32Premul((image.width * scale).toInt().coerceAtLeast(1), (image.height * scale).toInt().coerceAtLeast(1))
        surface.canvas.scale(scale, scale)
        surface.canvas.drawImage(image, 0f, 0f)
        surface.makeImageSnapshot()
    }
    return scaled.encodeToData(EncodedImageFormat.JPEG, quality)?.bytes
}

/** A desktop has no camera to offer: the menu shows the gallery and PDF items only. */
@Composable
actual fun rememberCameraCapture(onResult: (ByteArray?) -> Unit): (() -> Unit)? = null
