package quest.core.platform

import androidx.compose.runtime.Composable

/**
 * M7: [bytes] re-encoded as a JPEG no larger than [maxDimensionPx] on its longer edge, the right way up. Used only for
 * a photo the server would refuse as it is — an iPhone's HEIC, or a camera photo over the 5 MB cap — so a parent can
 * still send it. Null when the bytes are not an image this platform can read.
 */
expect fun photoAsJpeg(bytes: ByteArray, maxDimensionPx: Int, quality: Int): ByteArray?

/**
 * M7: the system camera, for a photo to attach to a message. Answers the launcher, or null where there is no camera
 * to offer (desktop, a simulator), and the menu then leaves the item out. [onResult] gets the JPEG bytes, or null when
 * she cancelled.
 */
@Composable
expect fun rememberCameraCapture(onResult: (ByteArray?) -> Unit): (() -> Unit)?
