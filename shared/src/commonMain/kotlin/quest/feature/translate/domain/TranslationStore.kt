package quest.feature.translate.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import quest.api.ApiException
import quest.api.ContentApi
import quest.api.dto.ApiError
import quest.api.dto.ReadingLanguage
import quest.api.dto.TranslateRequest

/**
 * Feature 29: the one place the app keeps her reading language and the translations she has asked for. One object for
 * the whole signed-in session, so a translation she opened in Messages is still there when the same broadcast is
 * drawn again, and asking twice costs nothing.
 *
 * **The cache is per item *and* language** ([TranslateKey]): Urdu and Hindi never share a row, and a key that already
 * holds text never reaches the server again — which is the point. `POST /children/{id}/translate` is cached on the
 * server too, but a second AI call is not the only cost worth avoiding: a parent paging back through a thread should
 * not wait on a round trip for text her phone already has.
 *
 * Nothing here touches a source row: [ItemTranslation.text] sits beside the original, and the original is what the
 * screens draw whatever this store says.
 */
class TranslationStore(private val api: ContentApi) {
    private val _readingLanguage = MutableStateFlow<ReadingLanguage?>(null)

    /** Her choice, or null for "the app's own language", which is what every family had before this feature. */
    val readingLanguage: StateFlow<ReadingLanguage?> = _readingLanguage.asStateFlow()

    private val _items = MutableStateFlow<Map<TranslateKey, ItemTranslation>>(emptyMap())
    val items: StateFlow<Map<TranslateKey, ItemTranslation>> = _items.asStateFlow()

    private val mutex = Mutex()
    private var profileRead = false

    /**
     * Reads her reading language off `GET /parent/me` the first time something asks for it, and then never again. The
     * screens that offer Translate call this; a build against a server without the route, or a phone offline, simply
     * offers nothing rather than putting an error on a page about something else.
     */
    suspend fun ensureReadingLanguage() {
        mutex.withLock {
            if (profileRead) return@withLock
            // A call that did not answer is not a decision: the flag stays down, so the next screen asks again.
            val profile = runCatching { api.parentProfile() }.getOrNull() ?: return@withLock
            profileRead = true
            _readingLanguage.value = profile.readingLanguage
        }
    }

    /**
     * `PUT /parent/me/reading-language`. True when the server took it — the value shown is the one it echoed back, so
     * the setting never claims a save that did not happen.
     */
    suspend fun setReadingLanguage(lang: ReadingLanguage): Boolean {
        val saved = runCatching { api.setReadingLanguage(lang) }.getOrNull() ?: return false
        profileRead = true
        _readingLanguage.value = saved.readingLanguage
        return true
    }

    /**
     * Asks for one item in one language, unless this phone already has it — a cache hit only flips the translation
     * back into view, with no call at all.
     */
    suspend fun translate(childId: String, key: TranslateKey) {
        val held = _items.value[key]
        if (held?.text != null) {
            update(key) { it.copy(showing = true, loading = false, failure = null) }
            return
        }
        if (held?.loading == true) return
        update(key) { it.copy(loading = true, failure = null) }
        val result = runCatching { api.translate(childId, TranslateRequest(key.kind, key.id, key.lang)) }
        val translation = result.getOrNull()
        if (translation == null) {
            update(key) { it.copy(loading = false, showing = false, failure = failureOf(result.exceptionOrNull())) }
            return
        }
        update(key) { it.copy(loading = false, text = translation.text, showing = true, failure = null) }
    }

    /** "Show original": the translation is put away, not forgotten. */
    fun showOriginal(key: TranslateKey) = update(key) { it.copy(showing = false, failure = null) }

    /**
     * Signing out: everything here belongs to the parent who signed in, so none of it may outlive her session. One
     * object serves the whole process, so without this the next parent on the same phone inherited the last one's
     * reading language — [ensureReadingLanguage] had already read a profile and would never read hers — and was
     * offered Translate into a language she never chose. Called by `SignOutUseCase`.
     */
    suspend fun forget() {
        mutex.withLock { profileRead = false; _readingLanguage.value = null }
        _items.value = emptyMap()
    }

    private fun update(key: TranslateKey, block: (ItemTranslation) -> ItemTranslation) {
        _items.value = _items.value + (key to block(_items.value[key] ?: ItemTranslation()))
    }

    private fun failureOf(error: Throwable?): TranslateFailure = when ((error as? ApiException)?.error?.code) {
        ApiError.RATE_LIMITED -> TranslateFailure.RATE_LIMITED
        REPHRASE -> TranslateFailure.REPHRASE
        ApiError.MODEL_FAILED -> TranslateFailure.MODEL_FAILED
        ApiError.NETWORK, null -> TranslateFailure.OFFLINE
        else -> TranslateFailure.MODEL_FAILED
    }

    private companion object {
        /** 422 (`TranslationService.REPHRASE`): two answers the validator refused, so the words themselves are the problem. */
        const val REPHRASE = "rephrase"
    }
}
