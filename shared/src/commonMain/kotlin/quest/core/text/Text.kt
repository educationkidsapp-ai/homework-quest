package quest.core.text

import kotlinx.datetime.LocalDate

/**
 * M4 (D12): text somebody typed — a teacher's comment, a chat message, an announcement — wrapped in a Unicode
 * first-strong isolate (U+2068 … U+2069). Its direction is then taken from its own first strong letter, whatever the
 * screen's: an English comment in the Arabic app keeps its full stop after "4", not at the start of the line, and an
 * Arabic message in the English app reads right to left. The two marks are invisible format characters.
 */
fun isolate(text: String): String = if (text.isEmpty()) text else "$FSI$text$PDI"

private const val FSI = '⁨'
private const val PDI = '⁩'

/**
 * M4 (D10): "3 October" — day and month name in the reader's language, with the year only when it is not
 * [currentYear]. Never "3/10", which beside a score reads like a mark.
 */
fun longDate(date: LocalDate, months: List<String>, currentYear: Int): String {
    val month = months.getOrNull(date.monthNumber - 1) ?: date.month.name.lowercase().replaceFirstChar { it.uppercase() }
    return if (date.year == currentYear) "${date.dayOfMonth} $month" else "${date.dayOfMonth} $month ${date.year}"
}
