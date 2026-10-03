package quest.core.platform

import androidx.compose.runtime.Composable

/**
 * M7: [bytes] re-encoded as a JPEG no larger than [maxDimensionPx] on its longer edge, the right way up, and with **no
 * metadata** — a phone photo's EXIF (where it was taken among it) is not carried over. Decoding is bounded (sampled
 * down while it is read, where the platform can), so a 50 MP photo never becomes 200 MB of pixels. Every photo goes
 * through here before it is uploaded. Null when the bytes are not an image this platform can read.
 */
expect fun photoAsJpeg(bytes: ByteArray, maxDimensionPx: Int, quality: Int): ByteArray?

/**
 * M7: the system camera, for a photo to attach to a message. Answers the launcher, or null where there is no camera
 * to offer (desktop, a simulator), and the menu then leaves the item out. [onResult] gets the path of the capture in
 * the app's cache — read, re-encoded and deleted off the main thread by the caller — or null when she cancelled.
 */
@Composable
expect fun rememberCameraCapture(onResult: (String?) -> Unit): (() -> Unit)?
