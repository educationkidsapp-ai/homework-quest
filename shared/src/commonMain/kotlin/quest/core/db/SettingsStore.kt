package quest.core.db

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Small typed key-value store over the Setting table, with an in-memory mirror for Compose.
 *
 * The mirror is read and written from several coroutines at once — a screen reading a value while a view model writes
 * one — so it is guarded. The lock is not only for the map: a [get] that misses goes to the database, and a [set]
 * that lands while that read is in flight must win, or the read caches the old value (usually `null`) on top of the
 * new one and every later read is answered from that stale entry until the process restarts.
 */
class SettingsStore(private val db: Db) {
    private val lock = Mutex()
    private val cache = mutableMapOf<String, String?>()
    private val _language = MutableStateFlow("en")
    val language: StateFlow<String> = _language
    private val _currentChildId = MutableStateFlow<String?>(null)
    val currentChildId: StateFlow<String?> = _currentChildId

    suspend fun load() {
        _language.value = get(KEY_LANGUAGE) ?: "en"
        _currentChildId.value = get(KEY_CURRENT_CHILD)
    }

    suspend fun get(key: String): String? {
        lock.withLock { if (cache.containsKey(key)) return cache[key] }
        val fromDb = db.read { selectSetting(key).executeAsOneOrNull() }
        return lock.withLock {
            // A `set` that happened while the read was in flight is the newer answer, and keeps it.
            if (cache.containsKey(key)) cache[key] else fromDb.also { cache[key] = it }
        }
    }

    suspend fun set(key: String, value: String?) {
        lock.withLock { cache[key] = value }
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
        const val KEY_FIREBASE_SESSION = "firebaseSession" // refreshToken|uid|email
    }
}
