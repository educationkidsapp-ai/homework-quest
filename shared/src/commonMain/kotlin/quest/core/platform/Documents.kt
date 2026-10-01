package quest.core.platform

/**
 * Hands a downloaded document to the system's own viewer (Android `ACTION_VIEW` through the app's `FileProvider`, iOS
 * a document preview, desktop the default application). The app downloads the bytes itself because the route is
 * authenticated; the viewer only ever sees a file in the app's cache.
 */
expect object DocumentViewer {
    /** Writes [bytes] to the cache as [name] and opens it. False when it could not be written or nothing can open it. */
    fun open(name: String, bytes: ByteArray, mimeType: String): Boolean
}

/** A file name every platform's file system accepts, always ending in [extension] so the viewer knows the type. */
fun safeDocumentName(name: String?, extension: String): String {
    val base = name.orEmpty().substringAfterLast('/').substringAfterLast('\\').removeSuffix(".$extension").removeSuffix(".${extension.uppercase()}")
    val safe = base.map { if (it.isLetterOrDigit() || it == '-' || it == '_' || it == ' ') it else '_' }.joinToString("").trim().take(80)
    return "${safe.ifEmpty { "document" }}.$extension"
}
