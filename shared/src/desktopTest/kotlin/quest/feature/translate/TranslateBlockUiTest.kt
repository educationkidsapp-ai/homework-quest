package quest.feature.translate

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import quest.api.ContentApi
import quest.api.DEFAULT_FLAGS
import quest.api.dashboard.ParentProfile
import quest.api.dto.ReadingLanguage
import quest.api.dto.TranslateRequest
import quest.api.dto.Translation
import quest.api.dto.TranslationSource
import quest.core.db.Db
import quest.core.db.SettingsStore
import quest.core.platform.DriverFactory
import quest.feature.auth.data.FakeAuth
import quest.feature.content.data.FakeContentApi
import quest.feature.parent.presentation.Strings
import quest.feature.school.domain.FlagStore
import quest.feature.school.domain.Flags
import quest.feature.school.presentation.LocalFlags
import quest.feature.translate.domain.TranslateKey
import quest.feature.translate.domain.TranslationStore
import quest.feature.translate.presentation.LocalTranslations
import quest.feature.translate.presentation.TranslateBlock
import quest.ui.design.ParentTheme
import kotlin.test.Test

/**
 * Feature 29 on the screen: the original is never replaced, the translation is always labelled, and a school without
 * the switch — or a parent already reading the message's own language — is offered nothing at all.
 */
@OptIn(ExperimentalTestApi::class)
class TranslateBlockUiTest {
    private val settings = SettingsStore(Db(DriverFactory(null)))
    private val original = "Please send a snack tomorrow."
    private val translated = "براہِ کرم کل ناشتہ بھیجیں۔"
    private val key = TranslateKey(TranslationSource.CHAT, "m-1", ReadingLanguage.UR)

    private inner class Api(base: ContentApi, val reading: ReadingLanguage?) : ContentApi by base {
        override suspend fun parentProfile() = ParentProfile("p1", "parent@example.com", null, reading)
        override suspend fun translate(childId: String, request: TranslateRequest) = Translation(
            text = translated, lang = request.lang, original = original,
            sourceKind = request.kind, sourceId = request.id,
        )
    }

    private fun store(reading: ReadingLanguage?) =
        TranslationStore(Api(FakeContentApi(FakeAuth(settings), delayMillis = 0), reading))

    private fun flags(on: Boolean) = object : FlagStore {
        override val flags: StateFlow<Map<String, Boolean>> =
            MutableStateFlow(DEFAULT_FLAGS + (Flags.LANGUAGE_BRIDGE to on)).asStateFlow()
    }

    /** The bubble, as the three screens draw it: the message itself, and the block underneath it. */
    private fun bubble(store: TranslationStore, on: Boolean, source: ReadingLanguage = ReadingLanguage.EN): @Composable () -> Unit = {
        CompositionLocalProvider(LocalFlags provides flags(on), LocalTranslations provides store) {
            ParentTheme(rtl = false) {
                Column {
                    Text(original)
                    TranslateBlock(
                        childId = "c-1",
                        kind = TranslationSource.CHAT,
                        sourceId = "m-1",
                        original = original,
                        strings = Strings.en.translate,
                        sourceLang = source,
                    )
                }
            }
        }
    }

    /** Tapping Translate adds the text under the original, labelled, with a way back — and the original stays. */
    @Test fun theTranslationIsAddedUnderTheOriginalAndLabelled() = runComposeUiTest {
        val store = store(ReadingLanguage.UR)
        runBlocking { store.ensureReadingLanguage() }
        setContent(bubble(store, on = true))

        onNodeWithText(Strings.en.translate.translate).assertHeightIsAtLeast(48.dp).performClick()
        waitUntil(timeoutMillis = 5_000) { store.items.value[key]?.text != null }
        waitForIdle()

        onNodeWithText(original).assertExists()
        onNodeWithText(translated, substring = true).assertExists()
        onNodeWithText("اردو · ${Strings.en.translate.machine}", substring = true).assertExists()

        // "Show original" puts the translation away. The original was never touched to begin with.
        onNodeWithText(Strings.en.translate.showOriginal).performClick()
        waitForIdle()
        onNodeWithText(translated, substring = true).assertDoesNotExist()
        onNodeWithText(original).assertExists()
        onNodeWithText(Strings.en.translate.translate).assertExists()
    }

    /** §4: a school that has not bought the bridge never learns it exists — no action, and no request. */
    @Test fun nothingIsDrawnWithoutTheFlag() = runComposeUiTest {
        val store = store(ReadingLanguage.UR)
        runBlocking { store.ensureReadingLanguage() }
        setContent(bubble(store, on = false))
        onNodeWithText(original).assertExists()
        onNodeWithText(Strings.en.translate.translate).assertDoesNotExist()
    }

    /** A parent who reads the language the message is in is offered nothing: there is nothing to translate. */
    @Test fun nothingIsDrawnWhenSheAlreadyReadsTheMessagesLanguage() = runComposeUiTest {
        val store = store(ReadingLanguage.EN)
        runBlocking { store.ensureReadingLanguage() }
        setContent(bubble(store, on = true, source = ReadingLanguage.EN))
        onNodeWithText(original).assertExists()
        onNodeWithText(Strings.en.translate.translate).assertDoesNotExist()
    }

    /** No reading language chosen is the state every family was in before this feature: nothing changes for them. */
    @Test fun nothingIsDrawnBeforeShePicksALanguage() = runComposeUiTest {
        val store = store(null)
        runBlocking { store.ensureReadingLanguage() }
        setContent(bubble(store, on = true))
        onNodeWithText(original).assertExists()
        onNodeWithText(Strings.en.translate.translate).assertDoesNotExist()
    }
}
