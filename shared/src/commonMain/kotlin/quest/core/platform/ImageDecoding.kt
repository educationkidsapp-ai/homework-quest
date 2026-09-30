package quest.core.platform

import androidx.compose.ui.graphics.ImageBitmap

/**
 * MH3: decode encoded image bytes, **no larger than [maxDimensionPx] on the longer edge**.
 *
 * `decodeToImageBitmap()` always decodes at full size, and a weekly plan is a manager's photograph: the server takes
 * JPEG, PNG and WebP up to 5 MB, which is tens of megabytes of pixels once decoded. Drawing that into a 420 dp card is
 * paying for detail nobody can see, so the card asks for a bounded copy and only the full-screen viewer asks for the
 * whole thing.
 *
 * Downsampling only: an image already inside the bound is decoded as it is rather than scaled up. Returns null when the
 * bytes are not an image this platform can read.
 */
expect fun decodeBoundedImage(bytes: ByteArray, maxDimensionPx: Int): ImageBitmap?
