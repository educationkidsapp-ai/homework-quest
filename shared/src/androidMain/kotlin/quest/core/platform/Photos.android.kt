package quest.core.platform

import android.content.ActivityNotFoundException
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

actual fun photoAsJpeg(bytes: ByteArray, maxDimensionPx: Int, quality: Int): ByteArray? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val longest = maxOf(bounds.outWidth, bounds.outHeight)
    if (longest <= 0) return null
    // Sampled while decoding: a 50 MP photo is read at a quarter or less of its pixels, never whole.
    var sample = 1
    while (longest / (sample * 2) >= maxDimensionPx) sample *= 2
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
    // BitmapFactory ignores EXIF, and a camera writes "rotate 90°" there rather than into the pixels: the rotation is
    // applied here, because the JPEG written below carries no EXIF at all — not the orientation, not the location.
    val matrix = Matrix()
    val scale = (maxDimensionPx.toFloat() / maxOf(decoded.width, decoded.height)).coerceAtMost(1f)
    matrix.postScale(scale, scale)
    when (runCatching { ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }.getOrNull()) {
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
    }
    val upright = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
    if (upright !== decoded) decoded.recycle()
    return try {
        // `Bitmap.compress` writes pixels and a JFIF header only — no EXIF block.
        ByteArrayOutputStream().use { out -> if (upright.compress(Bitmap.CompressFormat.JPEG, quality, out)) out.toByteArray() else null }
    } finally {
        upright.recycle()
    }
}

/**
 * `ACTION_IMAGE_CAPTURE` into `cache/camera/` through the app's `FileProvider` (`quest_file_paths.xml`). The app holds
 * no `CAMERA` permission, so the system camera app takes the picture and none is asked for. Only the path comes back:
 * the caller reads, re-encodes and deletes the file off the main thread, so nothing of the photo stays in the cache.
 */
@Composable
actual fun rememberCameraCapture(onResult: (String?) -> Unit): (() -> Unit)? {
    val context = LocalContext.current
    val target = remember { File(File(context.cacheDir, "camera").apply { mkdirs() }, "capture.jpg") }
    val uri: Uri = remember { FileProvider.getUriForFile(context, "${context.packageName}.quest.fileprovider", target) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        if (saved) onResult(target.absolutePath) else { target.delete(); onResult(null) }
    }
    return {
        try {
            launcher.launch(uri)
        } catch (_: ActivityNotFoundException) {
            onResult(null)
        }
    }
}
