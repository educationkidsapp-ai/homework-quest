package quest.admin.core.platform

/** Browser bits that differ between Kotlin/Wasm and Kotlin/JS: local storage and opening a link in a new tab. */
expect object Browser {
    fun get(key: String): String?
    fun set(key: String, value: String?)
    fun open(url: String)
    fun reload()
}
