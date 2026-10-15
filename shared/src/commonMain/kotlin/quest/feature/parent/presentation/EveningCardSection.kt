package quest.feature.parent.presentation

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import quest.api.dto.EveningCard
import quest.api.dto.EveningCardSource
import quest.feature.school.domain.Flags
import quest.feature.school.presentation.FeatureGate
import quest.ui.design.DashboardTokens

/** The evening card's own test tag — one card, so one tag (`TestTags` is :shared-ui's, and this stays in :shared). */
const val EVENING_CARD_TAG: String = "parent.eveningCard"

/** `{name}` in [Strings.eveningCardTitle]; the sentences' own placeholder is [EveningCard.CHILD]. */
private const val NAME = "{name}"

/**
 * Feature 28 — the calm card at the top of the parent home: what the child learned today and how it went, in two or
 * three sentences the parent can read in the pick-up queue.
 *
 * The words come whole from the server, in both languages, with `{child}` where her name belongs; **this is where her
 * name is put in**, on her own parent's phone, because nothing about her ever reached the assistant that wrote them.
 * Her first name alone, the way a teacher says it.
 *
 * [card] is null for every answer that is not a card — the server's 204 (before 16:00 school time, a day the school
 * does not teach, nothing to say), its 404 (the flag off, or not her child) and a request that failed — so the home
 * simply has no card above the children, never an error in its place.
 */
@Composable
fun EveningCardSection(
    card: EveningCard?,
    childName: String?,
    s: Strings,
    onLessonPanel: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    card ?: return
    val name = firstName(childName) ?: return
    FeatureGate(Flags.PARENT_EVENING_CARD) {
        // Tapping opens the lesson the card is about; a card with no lesson on it is simply not a button.
        val lessonId = card.lessonIds.firstOrNull()
        ParentCard(
            modifier = modifier.testTag(EVENING_CARD_TAG),
            onClick = lessonId?.let { id -> { onLessonPanel(id) } },
        ) {
            Text(
                text = s.eveningCardTitle.replace(NAME, name),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = DashboardTokens.inkStrong,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = card.forChild(name, s.isRtl),
                style = MaterialTheme.typography.bodyMedium,
                color = DashboardTokens.ink,
            )
            // Said only where it is true: the template's words are the school's own, not the assistant's.
            if (card.source == EveningCardSource.MODEL) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = s.eveningCardByAssistant,
                    style = MaterialTheme.typography.bodySmall,
                    color = DashboardTokens.inkSoft,
                )
            }
        }
    }
}

/**
 * The child's first name, or null when the school recorded none — and then there is no card at all rather than a
 * "Today with {name}" with the placeholder showing.
 */
internal fun firstName(full: String?): String? =
    full?.trim()?.split(' ', ' ')?.firstOrNull { it.isNotBlank() }
