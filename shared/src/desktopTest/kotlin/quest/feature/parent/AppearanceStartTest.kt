package quest.feature.parent

import kotlinx.coroutines.test.runTest
import quest.core.db.Db
import quest.core.db.SettingsStore
import quest.core.platform.DriverFactory
import quest.feature.parent.data.ParentRepositoryImpl
import quest.feature.parent.domain.Appearance
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The theme choice at start-up. The first frame is drawn from `ParentRepository.appearance.value`, so that value has
 * to be the stored choice the moment the repository exists — not the default until `SettingsStore.load()` has run,
 * which is what made a Dark app flash the device's light palette on every launch.
 */
class AppearanceStartTest {
    private val db = Db(DriverFactory(null))

    @Test fun theStoredChoiceIsThereBeforeAnythingIsLoaded() = runTest {
        SettingsStore(db).set(SettingsStore.KEY_APPEARANCE, Appearance.DARK.key)
        // A new process: a fresh store over the same database, and nobody has called load().
        val restarted = SettingsStore(db)
        assertEquals(Appearance.DARK, ParentRepositoryImpl(restarted).appearance.value)
        assertEquals(Appearance.DARK.key, restarted.appearance.value)
    }

    @Test fun nothingStoredFollowsTheDevice() {
        assertEquals(Appearance.SYSTEM, ParentRepositoryImpl(SettingsStore(db)).appearance.value)
    }

    @Test fun aChangeIsSeenAtOnceAndSurvivesARestart() = runTest {
        val parent = ParentRepositoryImpl(SettingsStore(db))
        val flow = parent.appearance                     // the same flow before and after: reading it changes nothing
        parent.setAppearance(Appearance.LIGHT)
        assertEquals(Appearance.LIGHT, flow.value)
        parent.setAppearance(Appearance.SYSTEM)
        assertEquals(Appearance.SYSTEM, flow.value)
        assertEquals(Appearance.SYSTEM, ParentRepositoryImpl(SettingsStore(db)).appearance.value)
    }

    @Test fun anUnknownStoredValueFallsBackToSystem() = runTest {
        SettingsStore(db).set(SettingsStore.KEY_APPEARANCE, "sepia")
        assertEquals(Appearance.SYSTEM, ParentRepositoryImpl(SettingsStore(db)).appearance.value)
    }
}
