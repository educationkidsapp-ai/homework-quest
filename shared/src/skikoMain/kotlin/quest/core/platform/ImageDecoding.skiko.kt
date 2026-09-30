package quest.core.platform

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.SamplingMode

/**
 * Desktop and iOS both draw through skiko, so they share one actual. Skia has no "decode at a fraction" entry point the way Android's
 * `inSampleSize` does, so the image is decoded and then scaled into a smaller [Bitmap]; the full-size one is dropped
 * as soon as this returns, which is the allocation that mattered.
 */
actual fun decodeBoundedImage(bytes: ByteArray, maxDimensionPx: Int): ImageBitmap? =
    decodeBoundedSkia(bytes, maxDimensionPx)?.toComposeImageBitmap()

private fun decodeBoundedSkia(bytes: ByteArray, maxDimensionPx: Int): Image? {
    val image = runCatching { Image.makeFromEncoded(bytes) }.getOrNull() ?: return null
    val longest = maxOf(image.width, image.height)
    if (maxDimensionPx <= 0 || longest <= maxDimensionPx) return image
    val scale = maxDimensionPx.toDouble() / longest
    val width = (image.width * scale).toInt().coerceAtLeast(1)
    val height = (image.height * scale).toInt().coerceAtLeast(1)
    val target = Bitmap()
    target.allocPixels(ImageInfo.makeN32Premul(width, height))
    if (!image.scalePixels(target.peekPixels() ?: return image, SamplingMode.LINEAR, false)) return image
    return Image.makeFromBitmap(target)
}
