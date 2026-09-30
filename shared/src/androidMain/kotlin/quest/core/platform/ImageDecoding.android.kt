package quest.core.platform

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * `inSampleSize` — the decoder's own downsampler, so the full-size bitmap is never allocated at all. It only takes
 * powers of two, which is why the result can be up to twice the bound on its longer edge; that is the point, the next
 * step down would lose detail the card can show.
 */
actual fun decodeBoundedImage(bytes: ByteArray, maxDimensionPx: Int): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val longest = maxOf(bounds.outWidth, bounds.outHeight)
    if (longest <= 0) return null
    val options = BitmapFactory.Options().apply {
        var sample = 1
        while (maxDimensionPx > 0 && longest / (sample * 2) >= maxDimensionPx) sample *= 2
        inSampleSize = sample
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
}
