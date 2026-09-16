package quest.di

import quest.core.db.SettingsStore
import quest.feature.auth.data.SessionRestorer
import quest.feature.school.domain.SchoolSession

/**
 * Loads settings and restores the session before the first screen.
 *
 * The school's cached theme and flags are restored here too, off the device and without a network call, so the first
 * frame is already in the school's colours: waiting for `GET /schools/{id}/theme` would show the default palette and
 * then repaint, which is exactly the flash the 300 ms transition exists to avoid.
 */
class AppInitializer(private val settings: SettingsStore, private val auth: SessionRestorer, private val school: SchoolSession) {
    suspend fun initialise() {
        settings.load()
        auth.restore()
        runCatching { school.restore() }
    }
}
