package quest.feature.translate

import kotlinx.coroutines.runBlocking
import quest.api.ApiException
import quest.api.ContentApi
import quest.api.dashboard.ParentProfile
import quest.api.dto.ApiError
import quest.api.dto.ReadingLanguage
import quest.api.dto.TranslateRequest
import quest.api.dto.Translation
import quest.api.dto.TranslationSource
import quest.core.db.Db
import quest.core.db.SettingsStore
import quest.core.platform.DriverFactory
import quest.feature.auth.data.FakeAuth
import quest.feature.content.data.FakeContentApi
import quest.feature.translate.domain.TranslateFailure
import quest.feature.translate.domain.TranslateKey
import quest.feature.translate.domain.TranslationStore
import quest.feature.translate.presentation.TranslateStrings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Feature 29, the owner's rule: **the original is the record and is never replaced.** These are the app's half of it —
 * a translation is kept beside the source text, it is always machine-translated, asking twice costs one call, and a
 * refusal is a sentence the parent can act on rather than a code.
 */
class TranslationStoreTest {
    private val settings = SettingsStore(Db(DriverFactory(null)))

    /** The source row the server holds. Nothing here may write to [body] — that is the whole point of the feature. */
    private class Api(
        base: ContentApi,
        var body: String = "Please send a snack tomorrow.",
        var reading: ReadingLanguage? = ReadingLanguage.UR,
        var failWith: ApiError? = null,
    ) : ContentApi by base {
        var calls = 0
        var profileCalls = 0
        val asked = mutableListOf<TranslateRequest>()

        override suspend fun parentProfile(): ParentProfile {
            profileCalls++
            return ParentProfile("p1", "parent@example.com", null, reading)
        }

        override suspend fun setReadingLanguage(lang: ReadingLanguage): ParentProfile {
            failWith?.let { throw ApiException(it) }
            reading = lang
            return ParentProfile("p1", "parent@example.com", null, lang)
        }

        override suspend fun translate(childId: String, request: TranslateRequest): Translation {
            calls++
            asked += request
            failWith?.let { throw ApiException(it) }
            return Translation(
                text = "اردو ترجمہ: ${request.id}",
                lang = request.lang,
                original = body,
                sourceKind = request.kind,
                sourceId = request.id,
            )
        }
    }

    private fun api(block: Api.() -> Unit = {}) =
        Api(FakeContentApi(FakeAuth(settings), delayMillis = 0)).also(block)

    private val key = TranslateKey(TranslationSource.CHAT, "m-1", ReadingLanguage.UR)

    @Test fun aTranslationIsAnAdditionAndIsAlwaysMachineMade() = runBlocking {
        val api = api()
        val store = TranslationStore(api)
        store.translate("c-1", key)

        val item = store.items.value.getValue(key)
        assertEquals("اردو ترجمہ: m-1", item.text)
        assertTrue(item.showing)
        assertNull(item.failure)
        // The source is untouched, and the label the screen draws is not a server field it could forget to send.
        assertEquals("Please send a snack tomorrow.", api.body)
        assertEquals("Machine-translated", TranslateStrings.en.machine)
        assertEquals("ترجمة آلية", TranslateStrings.ar.machine)
    }

    /** "Show original" puts the translation away; it does not throw it away, so showing it again asks nothing. */
    @Test fun askingTwiceCostsOneCall() = runBlocking {
        val api = api()
        val store = TranslationStore(api)
        store.translate("c-1", key)
        store.showOriginal(key)
        assertEquals(false, store.items.value.getValue(key).showing)
        assertEquals("اردو ترجمہ: m-1", store.items.value.getValue(key).text, "the text survives Show original")

        store.translate("c-1", key)
        assertTrue(store.items.value.getValue(key).showing)
        assertEquals(1, api.calls, "the second ask is served from the cache")
    }

    /** Each language is its own row: Urdu and Hindi never share one, so the second language is a second call. */
    @Test fun eachLanguageIsCachedOnItsOwn() = runBlocking {
        val api = api()
        val store = TranslationStore(api)
        store.translate("c-1", key)
        store.translate("c-1", key.copy(lang = ReadingLanguage.HI))
        assertEquals(2, api.calls)
        assertEquals(listOf(ReadingLanguage.UR, ReadingLanguage.HI), api.asked.map { it.lang })
    }

    /** 429: one reader working through her inbox is told to come back, not shown a code — and the original stands. */
    @Test fun theHourlyLimitIsOwnWords() = runBlocking {
        val store = TranslationStore(api { failWith = ApiError(ApiError.RATE_LIMITED, "slow down") })
        store.translate("c-1", key)

        val item = store.items.value.getValue(key)
        assertEquals(TranslateFailure.RATE_LIMITED, item.failure)
        assertNull(item.text)
        assertEquals(false, item.showing)
        assertEquals(TranslateStrings.en.rateLimited, TranslateStrings.en.failure(TranslateFailure.RATE_LIMITED))
        assertTrue(TranslateStrings.ar.rateLimited.isNotBlank())
    }

    /** 422 `rephrase` and a provider failure are different waits, and a dead connection is a third. */
    @Test fun theOtherRefusalsEachKeepTheirOwnWords() = runBlocking {
        suspend fun failureFor(code: String): TranslateFailure {
            val store = TranslationStore(api { failWith = ApiError(code, code) })
            store.translate("c-1", key)
            return store.items.value.getValue(key).failure!!
        }
        assertEquals(TranslateFailure.REPHRASE, failureFor("rephrase"))
        assertEquals(TranslateFailure.MODEL_FAILED, failureFor(ApiError.MODEL_FAILED))
        assertEquals(TranslateFailure.OFFLINE, failureFor(ApiError.NETWORK))
    }

    /** Her language is one `GET /parent/me` for the session, however many messages ask for it. */
    @Test fun herReadingLanguageIsReadOnceAndSavedBack() = runBlocking {
        val api = api()
        val store = TranslationStore(api)
        store.ensureReadingLanguage()
        store.ensureReadingLanguage()
        assertEquals(ReadingLanguage.UR, store.readingLanguage.value)
        assertEquals(1, api.profileCalls)

        assertTrue(store.setReadingLanguage(ReadingLanguage.BN))
        assertEquals(ReadingLanguage.BN, store.readingLanguage.value)
    }

    /** A save the server refused leaves her old language in place rather than one nothing holds. */
    @Test fun aRefusedSaveChangesNothing() = runBlocking {
        val api = api { failWith = ApiError(ApiError.BAD_REQUEST, "lang") }
        val store = TranslationStore(api)
        store.ensureReadingLanguage()
        assertEquals(false, store.setReadingLanguage(ReadingLanguage.FR))
        assertEquals(ReadingLanguage.UR, store.readingLanguage.value)
    }
}
