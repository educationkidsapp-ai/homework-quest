package quest.core.db

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Small typed key-value store over the Setting table, with an in-memory mirror for Compose. */
class SettingsStore(private val db: Db) {
    private val cache = mutableMapOf<String, String>()
    private val _language = MutableStateFlow("en")
    val language: StateFlow<String> = _language
    private val _practiceLength = MutableStateFlow(7)
    val practiceLength: StateFlow<Int> = _practiceLength

    suspend fun load() {
        _language.value = get(KEY_LANGUAGE) ?: "en"
        _practiceLength.value = get(KEY_PRACTICE_LENGTH)?.toIntOrNull() ?: 7
    }

    suspend fun get(key: String): String? = cache[key] ?: db.read { selectSetting(key).executeAsOneOrNull() }?.also { cache[key] = it }

    suspend fun set(key: String, value: String) {
        cache[key] = value
        db.write { upsertSetting(key, value) }
        when (key) {
            KEY_LANGUAGE -> _language.value = value
            KEY_PRACTICE_LENGTH -> _practiceLength.value = value.toIntOrNull() ?: 7
        }
    }

    suspend fun practiceLength(): Int = get(KEY_PRACTICE_LENGTH)?.toIntOrNull() ?: 7
    suspend fun setPracticeLength(n: Int) = set(KEY_PRACTICE_LENGTH, n.toString())
    suspend fun language(): String = get(KEY_LANGUAGE) ?: "en"
    suspend fun setLanguage(code: String) = set(KEY_LANGUAGE, code)

    companion object {
        const val KEY_LANGUAGE = "language"
        const val KEY_PRACTICE_LENGTH = "practiceLength"
        const val KEY_ONBOARDED = "onboarded"
    }
}
