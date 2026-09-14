package quest.admin.core.platform

import kotlinx.browser.localStorage
import kotlinx.browser.window

actual object Browser {
    actual fun get(key: String): String? = localStorage.getItem(key)
    actual fun set(key: String, value: String?) { if (value == null) localStorage.removeItem(key) else localStorage.setItem(key, value) }
    actual fun open(url: String) { window.open(url, "_blank") }
    actual fun reload() { window.location.reload() }
    actual fun origin(): String = window.location.origin
}
