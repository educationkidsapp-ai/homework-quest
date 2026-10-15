package quest.api.dto

import kotlinx.serialization.Serializable

/**
 * Feature 26 — **document reader** (`docs/features/26-document-reader.md`). A leader uploads a circular — a PDF, a
 * Word file, or a photograph of a paper letter — and gets back what it says: a title, a summary in Arabic and in
 * English, the dates it names, what the school has to do, and optionally an announcement she may send to parents.
 *
 * **The uploaded file is never stored.** `POST /management/documents/read` reads it from a temporary file that is
 * deleted in a `finally` and never writes it to the bucket, so there is no original to download, to attach or to
 * leak; only the summary below is kept, and it is swept after thirty days.
 *
 * **"Draft announcement" sends nothing.** The dashboard opens the compose sheet it already has, pre-filled from
 * [announcement]; the announcement goes out when the leader presses Send, which is the unchanged
 * `POST /management/broadcasts`. Behind the `documentReader` flag and `document.read` (ADMIN, MANAGERIAL): 404 while
 * the flag is off, and a summary is its author's own — another author's and another school's are both 404.
 */

/**
 * One date the circular names. [date] is an ISO day (`yyyy-MM-dd`) that really parses — the server asks the model
 * again when it does not — and the two labels are what the day is for, in the reader's own language.
 */
@Serializable
data class DocumentKeyDate(val date: String, val labelAr: String, val labelEn: String)

/**
 * The announcement the assistant suggests when the circular is clearly something parents must be told. Absent when
 * it is not. It is a *draft for the compose sheet*: the audience and the expiry are the leader's to choose there, and
 * nothing is delivered until she presses Send.
 */
@Serializable
data class DocumentAnnouncementDraft(val title: String, val bodyAr: String, val bodyEn: String)

/**
 * One circular, read. [fileName] and [pages] are what was uploaded — kept so the list reads like her own filing
 * cabinet — and everything else is the assistant's answer, validated before it was stored: both summaries at most
 * 1,200 characters, at most ten dates and at most ten actions per language.
 *
 * Answered by `POST /management/documents/read` (201), `GET /management/documents` (her last twenty, newest first)
 * and `GET /management/documents/{id}`.
 */
@Serializable
data class DocumentSummary(
    val id: String,
    val fileName: String,
    val pages: Int,
    val title: String,
    val summaryAr: String,
    val summaryEn: String,
    val keyDates: List<DocumentKeyDate> = emptyList(),
    val actionsAr: List<String> = emptyList(),
    val actionsEn: List<String> = emptyList(),
    val announcement: DocumentAnnouncementDraft? = null,
    val createdAt: Long,
)
