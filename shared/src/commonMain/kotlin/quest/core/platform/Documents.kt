package quest.core.platform

/**
 * Cached documents and the system's own viewer for them (Android `ACTION_VIEW` through the app's `FileProvider`, iOS a
 * document preview, desktop the default application). The app downloads the bytes itself because the route is
 * authenticated; the viewer only ever sees a file in the app's cache.
 */
expect object DocumentViewer {
    /** The one directory cached documents live in, created if missing. Nothing else is ever handed to a viewer. */
    fun directory(): String

    /**
     * Opens the cached document called [name]. The name is reduced to a plain file name here ([safeFileName]) — it can
     * never point outside [directory], whatever the caller passed. False when there is no such file or nothing can
     * open it; never throws.
     */
    fun open(name: String, mimeType: String): Boolean
}

/** [name] as one path segment every platform accepts: no directories, no dots-only names, at most 120 characters. */
fun safeFileName(name: String): String {
    val leaf = name.substringAfterLast('/').substringAfterLast('\\')
    val safe = leaf.map { if (it.isLetterOrDigit() || it == '-' || it == '_' || it == ' ' || it == '.') it else '_' }.joinToString("").trim().trim('.').take(120)
    return safe.ifEmpty { "document" }
}

/** A display and file name that always ends in [extension], so the viewer knows the type. */
fun safeDocumentName(name: String?, extension: String): String {
    val base = safeFileName(name.orEmpty()).removeSuffix(".$extension").removeSuffix(".${extension.uppercase()}").trim().trim('.')
    return "${base.ifEmpty { "document" }}.$extension"
}
