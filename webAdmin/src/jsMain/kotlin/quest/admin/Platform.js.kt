package quest.admin.core.platform

import kotlinx.browser.document
import kotlinx.browser.localStorage
import kotlinx.browser.window
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.w3c.dom.DragEvent
import org.w3c.dom.events.Event
import org.w3c.files.File
import org.w3c.files.FileReader
import org.w3c.files.get

actual object Browser {
    actual fun get(key: String): String? = localStorage.getItem(key)
    actual fun set(key: String, value: String?) { if (value == null) localStorage.removeItem(key) else localStorage.setItem(key, value) }
    actual fun open(url: String) { window.open(url, "_blank") }
    actual fun reload() { window.location.reload() }
    actual fun origin(): String = window.location.origin

    actual fun onFilesDropped(callback: (List<DroppedFile>) -> Unit): () -> Unit {
        val over: (Event) -> Unit = { it.preventDefault() }
        val drop: (Event) -> Unit = { e ->
            e.preventDefault()
            val files = (e as? DragEvent)?.dataTransfer?.files
            if (files != null && files.length > 0) {
                val out = ArrayList<DroppedFile>(); var pending = files.length
                for (i in 0 until files.length) {
                    val f: File = files[i]!!
                    val reader = FileReader()
                    reader.onload = { _ ->
                        out += DroppedFile(f.name, f.type, Int8Array(reader.result as ArrayBuffer).unsafeCast<ByteArray>())
                        if (--pending == 0) callback(out)
                        null
                    }
                    reader.readAsArrayBuffer(f)
                }
            }
        }
        document.addEventListener("dragover", over); document.addEventListener("drop", drop)
        return { document.removeEventListener("dragover", over); document.removeEventListener("drop", drop) }
    }

    actual fun onDragState(callback: (Boolean) -> Unit): () -> Unit {
        var depth = 0
        val enter: (Event) -> Unit = { if (depth++ == 0) callback(true) }
        val leave: (Event) -> Unit = { if (--depth <= 0) { depth = 0; callback(false) } }
        val drop: (Event) -> Unit = { depth = 0; callback(false) }
        document.addEventListener("dragenter", enter); document.addEventListener("dragleave", leave); document.addEventListener("drop", drop)
        return { document.removeEventListener("dragenter", enter); document.removeEventListener("dragleave", leave); document.removeEventListener("drop", drop) }
    }
}
