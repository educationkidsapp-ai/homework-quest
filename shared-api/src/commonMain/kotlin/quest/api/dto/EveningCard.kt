package quest.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Feature 28 — **the parent evening card** (`docs/features/28-parent-evening-card.md`). From 16:00 school time, one
 * card per child about today: what she learned and how it went, in two or three sentences, in Arabic and in English.
 *
 * **Only the teacher's lesson text and the child's own results.** The facts behind the card are the day's homework
 * lessons (never a sealed or unreleased exam), their title and the parent panel's objectives, the `parentTip` of the
 * one stop worth practising, and — in words, never as a number — whether she finished and how it went. **No score and
 * no percentage** is ever in it, which the server's own validator enforces rather than trusts.
 *
 * **The child is a placeholder.** [textAr] and [textEn] carry `{child}` where her name belongs, and the app fills it
 * in: her name, her age, her class and everything else about her stay on the server, and the model is told the facts
 * above and nothing more.
 *
 * Behind the `parentEveningCard` flag and `child.eveningCard.read` (PARENT): 404 while the flag is off, and 404 for
 * another family's child.
 */

/** Who wrote the sentences: the assistant, or the fixed bilingual template used when its answer broke a rule. */
@Serializable
enum class EveningCardSource { @SerialName("model") MODEL, @SerialName("template") TEMPLATE }

/**
 * `GET /children/{id}/evening-card` — the card, or **204** before 16:00 school time, on a day the school does not
 * teach, and when nothing happened today.
 *
 * [day] is the school's own date (`yyyy-MM-dd`), [lessonIds] the homework the card is about (so the app can open the
 * lesson panel), and the words themselves hold `{child}`: replace it with the child's name before showing them.
 */
@Serializable
data class EveningCard(
    val day: String,
    val textAr: String,
    val textEn: String,
    val lessonIds: List<String> = emptyList(),
    val source: EveningCardSource = EveningCardSource.MODEL,
) {
    /** The words with [name] in place of every `{child}` — the one thing every client does with them. */
    fun forChild(name: String, arabic: Boolean): String =
        (if (arabic) textAr else textEn).replace(CHILD, name)

    companion object {
        /** The placeholder the model is made to write instead of the child's name. */
        const val CHILD: String = "{child}"
    }
}
