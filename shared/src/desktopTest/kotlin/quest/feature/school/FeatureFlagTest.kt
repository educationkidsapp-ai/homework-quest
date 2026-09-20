package quest.feature.school

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import quest.api.AuthProvider
import quest.api.ContentApi
import quest.api.DEFAULT_FLAGS
import quest.api.dto.SchoolTheme
import quest.core.db.Db
import quest.core.db.SettingsStore
import quest.core.platform.DriverFactory
import quest.feature.auth.data.FakeAuth
import quest.feature.content.data.FakeContentApi
import quest.feature.content.domain.SchoolApi
import quest.feature.content.domain.ThemeFetch
import quest.feature.school.data.SchoolSessionImpl
import quest.feature.school.domain.FlagStore
import quest.feature.school.domain.Flags
import quest.feature.school.presentation.FeatureGate
import quest.feature.school.presentation.LocalFlags
import quest.feature.school.presentation.PlatformDefaultFlags
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** §4: the flag store, and the gate that decides whether a screen is composed at all. */
class FeatureFlagTest {
    private val db = Db(DriverFactory(null))
    private val settings = SettingsStore(db)
    private val auth: AuthProvider = FakeAuth(settings)
    private val fake = FakeContentApi(auth, delayMillis = 0)

    /** A backend whose flags we control, so the test can turn one off and sync again. */
    private class Flagged(private val delegate: FakeContentApi, var flags: Map<String, Boolean>) : ContentApi by delegate, SchoolApi {
        var flagCalls = 0
        var themeCalls = 0
        override suspend fun schoolFlags(schoolId: String): Map<String, Boolean> { flagCalls++; return flags }
        override suspend fun schoolByCode(code: String) = delegate.schoolByCode(code)
        override suspend fun classByJoinCode(code: String) = delegate.classByJoinCode(code)
        override suspend fun schoolTheme(schoolId: String) = delegate.schoolTheme(schoolId)
        override suspend fun schoolTheme(schoolId: String, ifNoneMatch: String?): ThemeFetch { themeCalls++; return delegate.schoolTheme(schoolId, ifNoneMatch) }
        override suspend fun platformSettings() = delegate.platformSettings()
    }

    // ---- the store ------------------------------------------------------------------------------------------------

    @Test fun beforeAnySyncEveryShippedFeatureIsOn() {
        val session = SchoolSessionImpl(fake, fake, settings)
        assertEquals(DEFAULT_FLAGS, session.flags.value)
        assertTrue(session.isEnabled(Flags.CERTIFICATES))
        assertFalse(session.isEnabled("complaints"), "a feature that is not built yet is off")
        assertFalse(session.isEnabled("a.key.nobody.defined"), "an unknown key is off, never a crash")
    }

    @Test fun syncBringsTheSchoolsFlagsAndAKeyTheServerOmitsKeepsItsDefault() = runBlocking {
        val api = Flagged(fake, mapOf(Flags.TREASURE_CHEST to false, Flags.LEVEL_THREE to false))
        val session = SchoolSessionImpl(api, api, settings)
        session.use(FakeContentApi.AL_NOOR_ID)

        assertFalse(session.isEnabled(Flags.TREASURE_CHEST))
        assertFalse(session.isEnabled(Flags.LEVEL_THREE))
        assertTrue(session.isEnabled(Flags.CERTIFICATES), "not in the server's answer, so the platform default stands")
        assertEquals(1, api.flagCalls)

        // The next sync brings the chest back; the store follows without another `use`.
        api.flags = mapOf(Flags.TREASURE_CHEST to true)
        session.sync()
        assertTrue(session.isEnabled(Flags.TREASURE_CHEST))
        assertEquals(2, api.flagCalls)
    }

    @Test fun flagsAreCachedOnTheDeviceSoAnOfflineLaunchIsStillCorrect() = runBlocking {
        val api = Flagged(fake, mapOf(Flags.CERTIFICATES to false))
        SchoolSessionImpl(api, api, settings).use(FakeContentApi.AL_NOOR_ID)

        // A second session with a backend that answers nothing at all must still know certificates are off here.
        val offline = SchoolSessionImpl(Offline, Offline, settings)
        offline.restore()
        assertEquals(FakeContentApi.AL_NOOR_ID, offline.schoolId.value)
        assertFalse(offline.isEnabled(Flags.CERTIFICATES))
        assertTrue(offline.isEnabled(Flags.TREASURE_CHEST))
    }

    @Test fun theSecondThemeFetchSendsTheEtagAndKeepsTheCachedTheme() = runBlocking {
        val api = Flagged(fake, DEFAULT_FLAGS)
        val session = SchoolSessionImpl(api, api, settings)
        session.use(FakeContentApi.AL_NOOR_ID)
        val first = session.theme.value
        assertEquals(FakeContentApi.alNoorTheme, first)

        session.sync()   // the fake answers 304 for the ETag it handed out
        assertEquals(2, api.themeCalls)
        assertEquals(first, session.theme.value, "a 304 leaves the cached theme exactly where it was")
    }

    @Test fun everyKeyTheAppGatesOnIsAKeyThePlatformDefines() {
        Flags.used.forEach { key -> assertTrue(Flags.isKnown(key), "$key is not one of the ${DEFAULT_FLAGS.size} seeded flags") }
    }

    // ---- the gate -------------------------------------------------------------------------------------------------

    @Test fun anOffFlagMeansTheContentIsNeverComposed() {
        var composed = 0
        composeOnce(store(mapOf(Flags.TREASURE_CHEST to false))) {
            FeatureGate(Flags.TREASURE_CHEST) { composed++ }
        }
        assertEquals(0, composed, "off means absent — not hidden, not disabled, not an error")
    }

    @Test fun anOnFlagComposesTheContent() {
        var composed = 0
        composeOnce(store(mapOf(Flags.TREASURE_CHEST to true))) {
            FeatureGate(Flags.TREASURE_CHEST) { composed++ }
        }
        assertEquals(1, composed)
    }

    @Test fun withoutAHostTheGateFallsBackToThePlatformDefaults() {
        var chest = 0
        var complaints = 0
        composeOnce(PlatformDefaultFlags) {
            FeatureGate(Flags.TREASURE_CHEST) { chest++ }
            FeatureGate("complaints") { complaints++ }
        }
        assertEquals(1, chest, "shipped features render in a screenshot test or preview with no container")
        assertEquals(0, complaints, "and an unshipped one still does not")
    }

    @Test fun aSyncThatFlipsAFlagReachesTheGate() {
        val flags = MutableStateFlow(DEFAULT_FLAGS)
        val store = object : FlagStore { override val flags: StateFlow<Map<String, Boolean>> = flags.asStateFlow() }
        var composed = 0
        @OptIn(ExperimentalComposeUiApi::class)
        val scene = ImageComposeScene(64, 64, Density(1f))
        try {
            scene.setContent { CompositionLocalProvider(LocalFlags provides store) { FeatureGate(Flags.CERTIFICATES) { composed++ } } }
            scene.render(0L)
            assertEquals(1, composed)

            flags.value = DEFAULT_FLAGS + (Flags.CERTIFICATES to false)
            scene.render(16_000_000L)
            assertEquals(1, composed, "recomposition removed the content rather than composing it again")
        } finally {
            scene.close()
        }
    }

    private fun store(overrides: Map<String, Boolean>) = object : FlagStore {
        override val flags: StateFlow<Map<String, Boolean>> = MutableStateFlow(DEFAULT_FLAGS + overrides).asStateFlow()
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun composeOnce(flags: FlagStore, content: @Composable () -> Unit) {
        val scene = ImageComposeScene(64, 64, Density(1f))
        try {
            scene.setContent { CompositionLocalProvider(LocalFlags provides flags) { content() } }
            scene.render(0L)
        } finally {
            scene.close()
        }
    }

    /** A backend that is simply not there: every call fails, so the store has only its cache to go on. */
    private object Offline : ContentApi, SchoolApi {
        override suspend fun listChildren() = error("offline")
        override suspend fun createChild(request: quest.api.dto.CreateChildRequest) = error("offline")
        override suspend fun updateChild(id: String, request: quest.api.dto.UpdateChildRequest) = error("offline")
        override suspend fun deleteChild(id: String) = error("offline")
        override suspend fun map(childId: String, from: kotlinx.datetime.LocalDate, to: kotlinx.datetime.LocalDate) = error("offline")
        override suspend fun lesson(id: String, version: Int?) = error("offline")
        override suspend fun uploadAttempts(childId: String, attempts: List<quest.api.dto.AttemptUpload>) = error("offline")
        override suspend fun uploadStopMedia(childId: String, stopId: String, media: quest.api.UploadFile, kind: quest.api.dto.MediaKind) = error("offline")
        override suspend fun progress(childId: String) = error("offline")
        override suspend fun classByJoinCode(code: String) = error("offline")
        override suspend fun schoolFlags(schoolId: String): Map<String, Boolean> = error("offline")
        override suspend fun schoolTheme(schoolId: String): SchoolTheme = error("offline")
        override suspend fun schoolByCode(code: String) = error("offline")
        override suspend fun schoolTheme(schoolId: String, ifNoneMatch: String?): ThemeFetch = error("offline")
        override suspend fun platformSettings() = error("offline")
    }
}
