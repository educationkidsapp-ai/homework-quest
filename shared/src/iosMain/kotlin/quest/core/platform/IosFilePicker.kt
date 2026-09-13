package quest.core.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSURL
import platform.Foundation.dataWithContentsOfURL
import platform.PhotosUI.PHPickerConfiguration
import platform.PhotosUI.PHPickerFilter
import platform.PhotosUI.PHPickerResult
import platform.PhotosUI.PHPickerViewController
import platform.PhotosUI.PHPickerViewControllerDelegateProtocol
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.UIImagePickerController
import platform.UIKit.UIImagePickerControllerDelegateProtocol
import platform.UIKit.UIImagePickerControllerOriginalImage
import platform.UIKit.UIImagePickerControllerSourceType
import platform.UIKit.UINavigationControllerDelegateProtocol
import platform.UIKit.UIViewController
import platform.UniformTypeIdentifiers.UTType
import platform.darwin.NSObject
import platform.posix.memcpy

/** iOS pickers: documents (PDF / PPTX), photo library (PHPicker) and camera (UIImagePickerController). */
@OptIn(ExperimentalForeignApi::class)
class IosFilePicker(private val onPicked: (List<PickedFile>) -> Unit) : FilePickerLauncher {
    // Delegates must be retained while the picker is shown.
    private var documentDelegate: NSObject? = null
    private var photoDelegate: NSObject? = null
    private var cameraDelegate: NSObject? = null

    override fun launch(kind: PickKind) {
        val root = rootController() ?: return
        when (kind) {
            PickKind.PDF, PickKind.PPTX -> {
                val types = if (kind == PickKind.PDF) listOf(UTType.typeWithIdentifier("com.adobe.pdf")!!)
                else listOfNotNull(UTType.typeWithIdentifier("org.openxmlformats.presentationml.presentation"))
                val picker = UIDocumentPickerViewController(forOpeningContentTypes = types, asCopy = true)
                val delegate = object : NSObject(), UIDocumentPickerDelegateProtocol {
                    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
                        val files = didPickDocumentsAtURLs.filterIsInstance<NSURL>().mapNotNull { url ->
                            val data = NSData.dataWithContentsOfURL(url) ?: return@mapNotNull null
                            val name = url.lastPathComponent ?: "file"
                            val mime = if (name.endsWith(".pdf", true)) "application/pdf"
                            else "application/vnd.openxmlformats-officedocument.presentationml.presentation"
                            PickedFile(name, mime, data.toByteArray())
                        }
                        onPicked(files)
                        documentDelegate = null
                    }
                    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) { onPicked(emptyList()); documentDelegate = null }
                }
                documentDelegate = delegate
                picker.delegate = delegate
                root.presentViewController(picker, true, null)
            }
            PickKind.GALLERY -> {
                val config = PHPickerConfiguration().apply { selectionLimit = 10; filter = PHPickerFilter.imagesFilter }
                val picker = PHPickerViewController(configuration = config)
                val delegate = object : NSObject(), PHPickerViewControllerDelegateProtocol {
                    override fun picker(picker: PHPickerViewController, didFinishPicking: List<*>) {
                        picker.dismissViewControllerAnimated(true, null)
                        val results = didFinishPicking.filterIsInstance<PHPickerResult>()
                        if (results.isEmpty()) { onPicked(emptyList()); photoDelegate = null; return }
                        val collected = mutableListOf<PickedFile>()
                        var remaining = results.size
                        results.forEachIndexed { index, result ->
                            result.itemProvider.loadDataRepresentationForTypeIdentifier("public.image") { data, _ ->
                                if (data != null) collected += PickedFile("photo-${index + 1}.jpg", "image/jpeg", data.toByteArray())
                                remaining -= 1
                                if (remaining == 0) {
                                    platform.darwin.dispatch_async(platform.darwin.dispatch_get_main_queue()) { onPicked(collected.toList()); photoDelegate = null }
                                }
                            }
                        }
                    }
                }
                photoDelegate = delegate
                picker.delegate = delegate
                root.presentViewController(picker, true, null)
            }
            PickKind.CAMERA -> {
                if (!UIImagePickerController.isSourceTypeAvailable(UIImagePickerControllerSourceType.UIImagePickerControllerSourceTypeCamera)) { onPicked(emptyList()); return }
                val picker = UIImagePickerController().apply { sourceType = UIImagePickerControllerSourceType.UIImagePickerControllerSourceTypeCamera }
                val delegate = object : NSObject(), UIImagePickerControllerDelegateProtocol, UINavigationControllerDelegateProtocol {
                    override fun imagePickerController(picker: UIImagePickerController, didFinishPickingMediaWithInfo: Map<Any?, *>) {
                        picker.dismissViewControllerAnimated(true, null)
                        val image = didFinishPickingMediaWithInfo[UIImagePickerControllerOriginalImage] as? UIImage
                        val data = image?.let { UIImageJPEGRepresentation(it, 0.85) }
                        onPicked(listOfNotNull(data?.let { PickedFile("camera.jpg", "image/jpeg", it.toByteArray()) }))
                        cameraDelegate = null
                    }
                    override fun imagePickerControllerDidCancel(picker: UIImagePickerController) {
                        picker.dismissViewControllerAnimated(true, null); onPicked(emptyList()); cameraDelegate = null
                    }
                }
                cameraDelegate = delegate
                picker.delegate = delegate
                root.presentViewController(picker, true, null)
            }
        }
    }

    private fun rootController(): UIViewController? {
        var vc = UIApplication.sharedApplication.keyWindow?.rootViewController
        while (vc?.presentedViewController != null) vc = vc.presentedViewController
        return vc
    }
}

@OptIn(ExperimentalForeignApi::class)
fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    return ByteArray(size).apply { usePinned { memcpy(it.addressOf(0), bytes, length) } }
}
