package quest.feature.staff.data

import quest.core.db.SettingsStore
import quest.core.db.installId
import quest.feature.staff.domain.CheckInPrefs

/** [CheckInPrefs] over the device's own settings table, which is where all three of these already belong. */
class SettingsCheckInPrefs(private val settings: SettingsStore) : CheckInPrefs {
    override suspend fun installId(): String = settings.installId()
    override suspend fun rationaleSeen(): Boolean = settings.get(SettingsStore.KEY_LOCATION_RATIONALE_SEEN) != null
    override suspend fun markRationaleSeen() = settings.set(SettingsStore.KEY_LOCATION_RATIONALE_SEEN, "1")
    override fun language(): String = settings.language.value
}
