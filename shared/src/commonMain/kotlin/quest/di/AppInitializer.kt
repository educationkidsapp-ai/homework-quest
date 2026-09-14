package quest.di

import quest.core.db.Db
import quest.core.db.SettingsStore
import quest.feature.auth.data.FakeAuth

/** Loads settings and restores the session before the first screen. */
class AppInitializer(private val settings: SettingsStore, private val auth: FakeAuth) {
    suspend fun initialise() {
        settings.load()
        auth.restore()
    }
}
