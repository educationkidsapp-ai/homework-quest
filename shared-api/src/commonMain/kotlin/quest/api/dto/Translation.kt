package quest.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Feature 29 — **parent language bridge** (`docs/features/29-parent-language-bridge.md`). A parent who reads neither
 * Arabic nor English well reads the school's messages in her own language, and a member of staff reads what she
 * writes.
 *
 * The owner's rule decides the whole shape: **the original text is always stored and shown as the record**. A
 * translation is an addition the reader asks for — [Translation.machine] is always true and [Translation.original]
 * travels with it, so a client can label it and offer "Show original" without a second request. No source row is
 * ever written to.
 *
 * Translations are cached per (source, language, source text): asking twice costs nothing, and an **edited** source
 * is translated again because its text hashes differently.
 *
 * Behind the `languageBridge` flag: 404 while it is off. The parent's route also needs `chat` (a message) or
 * `announcements` (a broadcast or an announcement), the same switches that let her read the original at all.
 */

/**
 * The eight languages the pilot school's families actually read. Anything else is a 400 — a language nobody has
 * reviewed the wording of is worse for a parent than the English she was already given.
 *
 * It is also what [ParentProfile.readingLanguage][quest.api.dashboard.ParentProfile] holds, and `null` there means
 * "the app's own language", which is what every family had before this feature.
 */
@Serializable
enum class ReadingLanguage {
    @SerialName("ar") AR,
    @SerialName("en") EN,
    @SerialName("ur") UR,
    @SerialName("hi") HI,
    @SerialName("bn") BN,
    @SerialName("fil") FIL,
    @SerialName("fr") FR,
    @SerialName("id") ID,
}

/**
 * What is being translated. [CHAT] and [COMPLAINT] are one `chat_messages` row each — a Messages message and a
 * complaint's message — and [BROADCAST] and [ANNOUNCEMENT] are one row of the child's feed. Staff only ever send
 * [CHAT] or [COMPLAINT]: a broadcast is the school's own words, already in Arabic and English.
 */
@Serializable
enum class TranslationSource {
    @SerialName("chat") CHAT,
    @SerialName("broadcast") BROADCAST,
    @SerialName("announcement") ANNOUNCEMENT,
    @SerialName("complaint") COMPLAINT,
}

/** `PUT /parent/me/reading-language` — her own choice; it comes back on `GET /parent/me`. */
@Serializable
data class UpdateReadingLanguageRequest(val lang: ReadingLanguage)

/**
 * `POST /children/{id}/translate` and `POST /staff/translate`. [id] is the source row's own id, and the source must
 * be something the caller may already read: another family's message, or a broadcast that is not in this child's
 * feed, is a 404 rather than a 403 — whether it exists is not hers to learn.
 */
@Serializable
data class TranslateRequest(val kind: TranslationSource, val id: String, val lang: ReadingLanguage)

/**
 * One translation. [machine] is always true, so a client that forgets to check it still labels the text honestly;
 * [original] is the record, unchanged, and is what "Show original" shows.
 */
@Serializable
data class Translation(
    val text: String,
    val lang: ReadingLanguage,
    val machine: Boolean = true,
    val original: String,
    val sourceKind: TranslationSource,
    val sourceId: String,
)
