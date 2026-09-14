package quest.core.db

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Small typed key-value store over the Setting table, with an in-memory mirror for Compose. */
class SettingsStore(private val db: Db) {
    private val cache = mutableMapOf<String, String?>()
    private val _language = MutableStateFlow("en")
    val language: StateFlow<String> = _language
    private val _currentChildId = MutableStateFlow<String?>(null)
    val currentChildId: StateFlow<String?> = _currentChildId

    suspend fun load() {
        _language.value = get(KEY_LANGUAGE) ?: "en"
        _currentChildId.value = get(KEY_CURRENT_CHILD)
    }

    suspend fun get(key: String): String? = if (cache.containsKey(key)) cache[key] else db.read { selectSetting(key).executeAsOneOrNull() }.also { cache[key] = it }

    suspend fun set(key: String, value: String?) {
        cache[key] = value
        db.write { if (value == null) deleteSetting(key) else upsertSetting(key, value) }
        when (key) {
            KEY_LANGUAGE -> _language.value = value ?: "en"
            KEY_CURRENT_CHILD -> _currentChildId.value = value
        }
    }

    suspend fun language(): String = get(KEY_LANGUAGE) ?: "en"
    suspend fun setLanguage(code: String) = set(KEY_LANGUAGE, code)
    suspend fun setCurrentChild(id: String?) = set(KEY_CURRENT_CHILD, id)

    companion object {
        const val KEY_LANGUAGE = "language"
        const val KEY_CURRENT_CHILD = "currentChild"
        const val KEY_PIN_HASH = "pinHash"
        const val KEY_FAKE_UID = "fakeAuthUid"
    }
}
