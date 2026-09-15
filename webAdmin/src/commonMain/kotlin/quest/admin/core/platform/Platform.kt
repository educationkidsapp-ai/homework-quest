package quest.admin.core.platform

/** Browser bits that differ between Kotlin/Wasm and Kotlin/JS: local storage, links, files dropped on the page. */
expect object Browser {
    fun get(key: String): String?
    fun set(key: String, value: String?)
    fun open(url: String)
    fun reload()
    /** scheme://host[:port] of the page — the API when the panel is served by the server itself (`/panel/`). */
    fun origin(): String
    /**
     * Files dragged from the desktop onto the page (the whole window: the Compose canvas cannot expose drop targets).
     * The callback gets (name, mime type, bytes) per file; returns a function that removes the listener.
     */
    fun onFilesDropped(callback: (List<DroppedFile>) -> Unit): () -> Unit
    /** True while a drag with files hovers the window (screens show the drop zone highlighted). */
    fun onDragState(callback: (Boolean) -> Unit): () -> Unit
}

class DroppedFile(val name: String, val mimeType: String, val bytes: ByteArray)
