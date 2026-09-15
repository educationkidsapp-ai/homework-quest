package quest.admin.core.course

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import quest.admin.core.design.SectionLabel
import quest.admin.core.design.SelectCard
import quest.admin.core.platform.Browser
import quest.api.dto.Curriculum
import quest.ui.design.AdminTokens
import quest.ui.design.Palette

/** The (curriculum, grade) pair every screen starts from. Grade is only offered once a curriculum is chosen. */
data class CourseChoice(val curriculum: Curriculum? = null, val grade: Int? = null) {
    val complete: Boolean get() = curriculum != null && grade != null
    val label: String get() = listOfNotNull(curriculum?.let { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } }, grade?.let { "Grade $it" }).joinToString(" · ")
    fun choose(c: Curriculum): CourseChoice = if (c == curriculum) this else CourseChoice(c, null) // changing the curriculum resets the grade
    fun choose(g: Int): CourseChoice = copy(grade = g)
}

/** Last pair the signed-in admin used, per browser (localStorage), so the next New lesson opens pre-selected. */
object CourseMemory {
    private fun key(email: String?) = "course:" + (email ?: "anonymous")
    fun load(email: String?): CourseChoice {
        val v = Browser.get(key(email)) ?: return CourseChoice()
        val (c, g) = v.split('/').let { it.getOrNull(0) to it.getOrNull(1) }
        return CourseChoice(c?.let { runCatching { Curriculum.valueOf(it.uppercase()) }.getOrNull() }, g?.toIntOrNull())
    }
    fun save(email: String?, choice: CourseChoice) { if (choice.complete) Browser.set(key(email), "${choice.curriculum!!.name.lowercase()}/${choice.grade}") }
}

/**
 * Two steps: American | British as large square cards, then Grade 1 | 2 | 3 beneath. Both steps stay visible after
 * a choice (the selection shows as the 4px accent border); [onChange] fires on every tap.
 */
@Composable
fun CourseChooser(choice: CourseChoice, onChange: (CourseChoice) -> Unit, enabled: Boolean = true, compact: Boolean = false) {
    val cardSize = if (compact) AdminTokens.gradeCard else AdminTokens.courseCard
    Column {
        SectionLabel("Curriculum")
        Row(horizontalArrangement = Arrangement.spacedBy(AdminTokens.gutter / 2)) {
            SelectCard("American", if (compact) null else "US common core", choice.curriculum == Curriculum.AMERICAN, { onChange(choice.choose(Curriculum.AMERICAN)) }, size = cardSize, enabled = enabled)
            SelectCard("British", if (compact) null else "UK national curriculum", choice.curriculum == Curriculum.BRITISH, { onChange(choice.choose(Curriculum.BRITISH)) }, size = cardSize, enabled = enabled)
        }
        if (choice.curriculum != null) {
            Spacer(Modifier.height(AdminTokens.gutter / 2))
            SectionLabel("Grade")
            Row(horizontalArrangement = Arrangement.spacedBy(AdminTokens.gutter / 2)) {
                (1..3).forEach { g -> SelectCard("Grade $g", null, choice.grade == g, { onChange(choice.choose(g)) }, size = AdminTokens.gradeCard, enabled = enabled) }
            }
        } else {
            Spacer(Modifier.height(AdminTokens.gutter / 2))
            Text("Pick a curriculum to see the grades.", style = MaterialTheme.typography.bodyMedium, color = Palette.parentInkSoft)
        }
    }
}
