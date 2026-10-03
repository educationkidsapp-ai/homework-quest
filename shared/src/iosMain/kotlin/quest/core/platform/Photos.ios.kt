package quest.core.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.useContents
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFNumberCreate
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFNumberIntType
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.CoreGraphics.CGImageRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.writeToFile
import platform.ImageIO.CGImageSourceCreateThumbnailAtIndex
import platform.ImageIO.CGImageSourceCreateWithData
import platform.ImageIO.kCGImageSourceCreateThumbnailFromImageAlways
import platform.ImageIO.kCGImageSourceCreateThumbnailWithTransform
import platform.ImageIO.kCGImageSourceThumbnailMaxPixelSize
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

/**
 * ImageIO decodes straight to a thumbnail no larger than [maxDimensionPx] — bounded, so a 48 MP photo is never decoded
 * whole — with the orientation applied to the pixels (`…WithTransform`), and reads HEIC, which skia cannot. The JPEG
 * is written from those pixels alone, so none of the original's metadata (its GPS location among it) comes along.
 */
@OptIn(ExperimentalForeignApi::class)
actual fun photoAsJpeg(bytes: ByteArray, maxDimensionPx: Int, quality: Int): ByteArray? = memScoped {
    val data = CFBridgingRetain(bytes.toNSData()) as CFDataRef?
    val source = CGImageSourceCreateWithData(data, null)
    val size = alloc<IntVar>().apply { value = maxDimensionPx }
    val max = CFNumberCreate(null, kCFNumberIntType, size.ptr)
    val options = CFDictionaryCreateMutable(null, 3, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
    CFDictionarySetValue(options, kCGImageSourceCreateThumbnailFromImageAlways, kCFBooleanTrue)
    CFDictionarySetValue(options, kCGImageSourceCreateThumbnailWithTransform, kCFBooleanTrue)
    CFDictionarySetValue(options, kCGImageSourceThumbnailMaxPixelSize, max)
    val thumbnail = source?.let { CGImageSourceCreateThumbnailAtIndex(it, 0u, options) }
    val jpeg = thumbnail?.let { UIImageJPEGRepresentation(UIImage.imageWithCGImage(it), quality / 100.0)?.toByteArray() }
    thumbnail?.let { CGImageRelease(it) }
    CFRelease(options); CFRelease(max)
    source?.let { CFRelease(it) }
    data?.let { CFRelease(it) }
    jpeg
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
actual fun rememberCameraCapture(onResult: (String?) -> Unit): (() -> Unit)? {
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

private class CameraDelegate(private val onResult: (String?) -> Unit) :
    NSObject(), UIImagePickerControllerDelegateProtocol, UINavigationControllerDelegateProtocol {
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * The capture is drawn and written to the temporary directory off the main thread (UIKit's renderer may run on
     * any thread), and only its path comes back; the caller re-encodes it and deletes it, as Android's.
     */
    override fun imagePickerController(picker: UIImagePickerController, didFinishPickingMediaWithInfo: Map<Any?, *>) {
        val image = didFinishPickingMediaWithInfo[UIImagePickerControllerOriginalImage] as? UIImage
        picker.dismissViewControllerAnimated(true, completion = null)
        if (image == null) { onResult(null); return }
        background.launch {
            val path = NSTemporaryDirectory() + "camera-capture.jpg"
            val written = jpegOf(image, CAMERA_MAX_PX, CAMERA_QUALITY)?.toNSData()?.writeToFile(path, true) == true
            withContext(Dispatchers.Main) { onResult(path.takeIf { written }) }
        }
    }

    override fun imagePickerControllerDidCancel(picker: UIImagePickerController) {
        picker.dismissViewControllerAnimated(true, completion = null)
        onResult(null)
    }
}

/** The capture is written at the size and near the quality it will be sent at; [photoAsJpeg] then re-encodes it once more. */
private const val CAMERA_MAX_PX = 2560
private const val CAMERA_QUALITY = 92
