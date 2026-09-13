package quest.di

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import quest.core.db.Db
import quest.core.db.SettingsStore
import quest.core.platform.Ids

/** One device, one child (v1). Creates the local child row on first launch and loads settings. */
class AppInitializer(private val db: Db, private val settings: SettingsStore) {
    private val mutex = Mutex()
    private var cachedChildId: String? = null

    suspend fun initialise() {
        childId()
        settings.load()
    }

    suspend fun childId(): String = cachedChildId ?: mutex.withLock {
        cachedChildId ?: run {
            val existing = db.read { selectChild().executeAsOneOrNull() }
            val id = existing?.id ?: Ids.random().also { id ->
                db.write { upsertChild(id, "", "sun", 1, "international", "en", null) }
            }
            cachedChildId = id
            id
        }
    }
}
