@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package quest.core.platform

import platform.darwin.NSObject
import platform.UIKit.UIViewController
import platform.UIKit.UIDocumentInteractionControllerDelegateProtocol
import platform.UIKit.UIDocumentInteractionController
import platform.UIKit.UIApplication
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AVFAudio.AVAudioPlayer
import platform.AVFAudio.AVAudioRecorder
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayAndRecord
import platform.AVFAudio.AVAudioSessionRecordPermissionGranted
import platform.AVFAudio.AVEncoderAudioQualityKey
import platform.AVFAudio.AVFormatIDKey
import platform.AVFAudio.AVNumberOfChannelsKey
import platform.AVFAudio.AVSampleRateKey
import platform.AVFAudio.setActive
import platform.CoreAudioTypes.kAudioFormatMPEG4AAC
import platform.Foundation.NSData
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile
import quest.ui.stops.StopMedia
import kotlin.coroutines.resume

@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.toNSData(): NSData = usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray { val n = length.toInt(); if (n == 0) return ByteArray(0); return ByteArray(n).apply { usePinned { platform.posix.memcpy(it.addressOf(0), bytes, length) } } }

actual object MediaFiles {
    private val dir: String get() {
        val docs = NSFileManager.defaultManager.URLsForDirectory(NSDocumentDirectory, NSUserDomainMask).first() as NSURL
        val path = docs.path + "/media"
        NSFileManager.defaultManager.createDirectoryAtPath(path, true, null, null)
        return path
    }
    actual fun save(name: String, bytes: ByteArray): String { val p = "$dir/$name"; bytes.toNSData().writeToFile(p, true); return p }
    actual fun read(path: String): ByteArray? = NSData.dataWithContentsOfFile(path)?.toByteArray()
    actual fun delete(path: String) { NSFileManager.defaultManager.removeItemAtPath(path, null) }
    actual fun pathOf(name: String): String = "$dir/$name"
}

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun rememberStopMedia(): StopMedia = remember {
    object : StopMedia {
        private var recorder: AVAudioRecorder? = null
        private var player: AVAudioPlayer? = null
        private var path: String = ""
        override val canRecord = true

        private suspend fun permission(): Boolean = suspendCancellableCoroutine { cont ->
            val session = AVAudioSession.sharedInstance()
            if (session.recordPermission == AVAudioSessionRecordPermissionGranted) cont.resume(true)
            else session.requestRecordPermission { granted -> cont.resume(granted) }
        }

        override suspend fun startRecording(): Boolean {
            if (!permission()) return false
            val session = AVAudioSession.sharedInstance()
            session.setCategory(AVAudioSessionCategoryPlayAndRecord, null); session.setActive(true, null)
            path = NSTemporaryDirectory() + "retell-${(platform.Foundation.NSDate().timeIntervalSince1970 * 1000).toLong()}.m4a"
            val settings = mapOf<Any?, Any?>(AVFormatIDKey to kAudioFormatMPEG4AAC, AVSampleRateKey to 44100.0, AVNumberOfChannelsKey to 1, AVEncoderAudioQualityKey to 64)
            val r = AVAudioRecorder(NSURL.fileURLWithPath(path), settings, null)
            recorder = r
            return r.record()
        }

        override suspend fun stopRecording(): ByteArray? { recorder?.stop(); recorder = null; return NSData.dataWithContentsOfFile(path)?.toByteArray() }

        override suspend fun play(bytes: ByteArray) {
            stopPlayback()
            player = AVAudioPlayer(bytes.toNSData(), null).also { it.prepareToPlay(); it.play() }
        }

        override fun stopPlayback() { player?.stop(); player = null }
    }
}

/** Tells the document preview which screen to present over; held by [DocumentViewer] because the controller keeps only a weak delegate. */
private class PreviewHost(private val root: UIViewController) : NSObject(), UIDocumentInteractionControllerDelegateProtocol {
    override fun documentInteractionControllerViewControllerForPreview(controller: UIDocumentInteractionController): UIViewController = root
}

actual object DocumentViewer {
    private var controller: UIDocumentInteractionController? = null
    private var host: PreviewHost? = null

    actual fun open(name: String, bytes: ByteArray, mimeType: String): Boolean {
        if (bytes.isEmpty()) return false
        val path = NSTemporaryDirectory() + name
        if (!bytes.toNSData().writeToFile(path, true)) return false
        val root = UIApplication.sharedApplication.keyWindow?.rootViewController ?: return false
        val preview = UIDocumentInteractionController.interactionControllerWithURL(NSURL.fileURLWithPath(path))
        val delegate = PreviewHost(root)
        preview.delegate = delegate
        controller = preview
        host = delegate
        return preview.presentPreviewAnimated(true)
    }
}
