package quest.core.platform

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.File

private const val PDF = "application/pdf"
private const val PPTX = "application/vnd.openxmlformats-officedocument.presentationml.presentation"

@Composable
actual fun rememberFilePicker(onPicked: (List<PickedFile>) -> Unit): FilePickerLauncher {
    val context = LocalContext.current
    val callback = rememberUpdatedState(onPicked)
    var cameraUri: Uri? = null

    val documents = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        callback.value(uris.mapNotNull { context.read(it) })
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
        callback.value(uris.mapNotNull { context.read(it) })
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = cameraUri
        if (ok && uri != null) callback.value(listOfNotNull(context.read(uri, forcedName = "camera-${System.currentTimeMillis()}.jpg")))
    }

    return remember {
        object : FilePickerLauncher {
            override fun launch(kind: PickKind) {
                when (kind) {
                    PickKind.PDF -> documents.launch(arrayOf(PDF))
                    PickKind.PPTX -> documents.launch(arrayOf(PPTX, "application/vnd.ms-powerpoint"))
                    PickKind.GALLERY -> gallery.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    PickKind.CAMERA -> {
                        val dir = File(context.cacheDir, "camera").apply { mkdirs() }
                        val file = File(dir, "shot-${System.currentTimeMillis()}.jpg")
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.quest.fileprovider", file)
                        cameraUri = uri
                        camera.launch(uri)
                    }
                }
            }
        }
    }
}

private fun Context.read(uri: Uri, forcedName: String? = null): PickedFile? = runCatching {
    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    val name = forcedName ?: displayName(uri) ?: "file"
    val mime = contentResolver.getType(uri) ?: when {
        name.endsWith(".pdf", true) -> PDF
        name.endsWith(".pptx", true) -> PPTX
        name.endsWith(".png", true) -> "image/png"
        else -> "image/jpeg"
    }
    PickedFile(name, mime, bytes)
}.getOrNull()

private fun Context.displayName(uri: Uri): String? =
    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
