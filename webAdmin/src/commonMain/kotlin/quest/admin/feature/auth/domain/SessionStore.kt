package quest.admin.feature.auth.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import quest.admin.core.platform.Browser
import quest.api.AdminSession

/** The signed-in admin, persisted in localStorage so a reload keeps the session until the JWT expires. */
class SessionStore {
    private val _session = MutableStateFlow(load())
    val session: StateFlow<AdminSession?> = _session

    fun token(): String? = _session.value?.token
    fun save(s: AdminSession) { Browser.set(KEY, "${s.token}|${s.email}|${s.expiresAt}"); _session.value = s }
    fun clear() { Browser.set(KEY, null); _session.value = null }

    private fun load(): AdminSession? {
        val raw = Browser.get(KEY) ?: return null
        val parts = raw.split('|'); if (parts.size != 3) return null
        val s = AdminSession(parts[0], parts[1], parts[2].toLongOrNull() ?: return null)
        return s
    }
    private companion object { const val KEY = "quest.admin.session" }
}
