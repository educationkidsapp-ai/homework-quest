package quest.feature.parent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import quest.api.ApiException
import quest.api.ContentApi
import quest.api.dashboard.ParentProfile
import quest.api.dto.ApiError
import quest.api.dto.ReadingLanguage
import quest.core.db.Db
import quest.core.db.SettingsStore
import quest.core.platform.DriverFactory
import quest.feature.auth.data.FakeAuth
import quest.feature.content.data.FakeContentApi
import quest.feature.lock.data.BiometricPreferencesImpl
import quest.feature.lock.domain.AppLock
import quest.feature.parent.data.ParentRepositoryImpl
import quest.feature.parent.presentation.SettingsContract
import quest.feature.parent.presentation.SettingsViewModel
import quest.feature.translate.domain.TranslationStore
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Feature 29: the Reading language setting. It is read back from `GET /parent/me`, so it survives a sign-out, and a
 * choice the server refused leaves the old one selected rather than one nothing holds.
 */
class SettingsReadingLanguageTest {
    private val settings = SettingsStore(Db(DriverFactory(null)))
    private val built = mutableListOf<ViewModel>()

    @BeforeTest fun setUp() = Dispatchers.setMain(Dispatchers.Default)

    @AfterTest fun tearDown() {
        built.forEach { it.viewModelScope.cancel() }
        built.clear()
        Dispatchers.resetMain()
    }

    private class Api(base: ContentApi, var stored: ReadingLanguage? = null, val refuse: Boolean = false) : ContentApi by base {
        var sent: ReadingLanguage? = null
        override suspend fun parentProfile() = ParentProfile("p1", "parent@example.com", null, stored)
        override suspend fun setReadingLanguage(lang: ReadingLanguage): ParentProfile {
            sent = lang
            if (refuse) throw ApiException(ApiError(ApiError.BAD_REQUEST, "lang"))
            stored = lang
            return ParentProfile("p1", "parent@example.com", null, lang)
        }
    }

    private fun api(stored: ReadingLanguage? = null, refuse: Boolean = false) =
        Api(FakeContentApi(FakeAuth(settings), delayMillis = 0), stored, refuse)

    private fun viewModel(api: ContentApi) = SettingsViewModel(
        ParentRepositoryImpl(settings), api,
        AppLock(FakeAuth(settings), BiometricPreferencesImpl(settings), quest.core.platform.platformBiometricAuthenticator(), signOut = {}, elapsed = { 0L }),
        TranslationStore(api),
    ).also { built.add(it) }

    @Test fun herChoiceComesBackFromTheServer() = runBlocking {
        val vm = viewModel(api(stored = ReadingLanguage.UR))
        vm.dispatch(SettingsContract.Intent.Load)
        settle(vm.state) { it.readingLanguage == ReadingLanguage.UR }
        assertFalse(vm.state.value.readingLanguageFailed)
    }

    @Test fun pickingALanguageSavesIt() = runBlocking {
        val api = api()
        val vm = viewModel(api)
        vm.dispatch(SettingsContract.Intent.Load)
        settle(vm.state) { !it.loading }
        vm.dispatch(SettingsContract.Intent.SetReadingLanguage(ReadingLanguage.BN))
        settle(vm.state) { it.readingLanguage == ReadingLanguage.BN }
        assertEquals(ReadingLanguage.BN, api.sent)
        assertFalse(vm.state.value.readingLanguageFailed)
    }

    /** A refused save says so and keeps the language she had: a picker showing a value the server never took is a lie. */
    @Test fun aRefusedSaveKeepsTheOldChoice() = runBlocking {
        val vm = viewModel(api(stored = ReadingLanguage.HI, refuse = true))
        vm.dispatch(SettingsContract.Intent.Load)
        settle(vm.state) { it.readingLanguage == ReadingLanguage.HI }
        vm.dispatch(SettingsContract.Intent.SetReadingLanguage(ReadingLanguage.FR))
        settle(vm.state) { it.readingLanguageFailed }
        assertEquals(ReadingLanguage.HI, vm.state.value.readingLanguage)
        assertTrue(vm.state.value.readingLanguageFailed)
    }

    private suspend fun <S> settle(state: StateFlow<S>, predicate: (S) -> Boolean) {
        repeat(400) {
            if (predicate(state.value)) return
            delay(5)
        }
        error("state never satisfied the predicate; last was ${state.value}")
    }
}
