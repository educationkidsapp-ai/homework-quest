package quest.core.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.UIKit.UIGraphicsImageRenderer
import platform.UIKit.UIGraphicsImageRendererFormat
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.UIImagePickerController
import platform.UIKit.UIImagePickerControllerDelegateProtocol
import platform.UIKit.UIImagePickerControllerOriginalImage
import platform.UIKit.UIImagePickerControllerSourceType
import platform.UIKit.UINavigationControllerDelegateProtocol
import platform.darwin.NSObject

/** `UIImage` reads HEIC, which skia cannot — the reason this is UIKit's and not the shared skiko decoder's. */
actual fun photoAsJpeg(bytes: ByteArray, maxDimensionPx: Int, quality: Int): ByteArray? {
    val image = UIImage.imageWithData(bytes.toNSData()) ?: return null
    return jpegOf(image, maxDimensionPx, quality)
}

/**
 * Redrawn rather than re-encoded as it is: drawing applies the photo's orientation to the pixels, so the JPEG is the
 * right way up whatever reads it — the dashboard's browser and the other parent's phone alike.
 */
@OptIn(ExperimentalForeignApi::class)
private fun jpegOf(image: UIImage, maxDimensionPx: Int, quality: Int): ByteArray? {
    val (width, height) = image.size.useContents { width to height }
    if (width <= 0.0 || height <= 0.0) return null
    val scale = minOf(1.0, maxDimensionPx / maxOf(width, height))
    val w = width * scale
    val h = height * scale
    val format = UIGraphicsImageRendererFormat.defaultFormat().apply { setScale(1.0) }
    val drawn = UIGraphicsImageRenderer(size = CGSizeMake(w, h), format = format)
        .imageWithActions { image.drawInRect(CGRectMake(0.0, 0.0, w, h)) }
    return UIImageJPEGRepresentation(drawn, quality / 100.0)?.toByteArray()
}

/** The system camera sheet; null on a simulator or a device with no camera, so the menu leaves the item out. */
@Composable
actual fun rememberCameraCapture(onResult: (ByteArray?) -> Unit): (() -> Unit)? {
    if (!UIImagePickerController.isSourceTypeAvailable(UIImagePickerControllerSourceType.UIImagePickerControllerSourceTypeCamera)) return null
    val latest = rememberUpdatedState(onResult)
    val delegate = remember { CameraDelegate { latest.value(it) } }
    return remember(delegate) {
        {
            val root = activeKeyWindow()?.rootViewController
            if (root == null) {
                latest.value(null)
            } else {
                // Presented from whatever is on top — the Compose host may itself be presenting a sheet.
                val top = generateSequence(root) { it.presentedViewController }.last()
                val picker = UIImagePickerController()
                picker.sourceType = UIImagePickerControllerSourceType.UIImagePickerControllerSourceTypeCamera
                picker.delegate = delegate
                top.presentViewController(picker, animated = true, completion = null)
            }
        }
    }
}

private class CameraDelegate(private val onResult: (ByteArray?) -> Unit) :
    NSObject(), UIImagePickerControllerDelegateProtocol, UINavigationControllerDelegateProtocol {

    override fun imagePickerController(picker: UIImagePickerController, didFinishPickingMediaWithInfo: Map<Any?, *>) {
        val image = didFinishPickingMediaWithInfo[UIImagePickerControllerOriginalImage] as? UIImage
        picker.dismissViewControllerAnimated(true, completion = null)
        onResult(image?.let { jpegOf(it, CAMERA_MAX_PX, CAMERA_QUALITY) })
    }

    override fun imagePickerControllerDidCancel(picker: UIImagePickerController) {
        picker.dismissViewControllerAnimated(true, completion = null)
        onResult(null)
    }
}

/** A phone camera's 12 MP is more than a message needs and over the 5 MB cap; this keeps a capture well inside it. */
private const val CAMERA_MAX_PX = 2560
private const val CAMERA_QUALITY = 85
