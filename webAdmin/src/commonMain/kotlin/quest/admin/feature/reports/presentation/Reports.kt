package quest.admin.feature.reports.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import quest.admin.core.design.Card
import quest.admin.core.design.Cell
import quest.admin.core.design.Col
import quest.admin.core.design.EmptyState
import quest.admin.core.design.ErrorBanner
import quest.admin.core.design.Gap
import quest.admin.core.design.LazyPage
import quest.admin.core.design.LinkButton
import quest.admin.core.design.Loading
import quest.admin.core.design.SectionLabel
import quest.admin.core.design.TableHeader
import quest.admin.core.design.TableRow
import quest.admin.core.design.Tag
import quest.admin.core.design.tokens
import quest.ui.design.AdminTokens
import quest.ui.design.Palette

import quest.admin.core.mvi.MviEffect
import quest.admin.core.mvi.MviIntent
import quest.admin.core.mvi.MviState
import quest.admin.core.mvi.MviViewModel
import quest.api.AdminApi
import quest.api.ApiException
import quest.api.CacheEntry
import quest.api.UsageResponse

object ReportsContract {
    data class State(val loading: Boolean = true, val cache: List<CacheEntry> = emptyList(), val usage: UsageResponse? = null, val error: String? = null) : MviState
    sealed interface Intent : MviIntent { data object LoadCache : Intent; data object LoadUsage : Intent }
    sealed interface Effect : MviEffect
}

class ReportsViewModel(private val api: AdminApi) : MviViewModel<ReportsContract.State, ReportsContract.Intent, ReportsContract.Effect>(ReportsContract.State()) {
    override suspend fun handle(intent: ReportsContract.Intent) {
        reduce { copy(loading = true, error = null) }
        try {
            when (intent) {
                ReportsContract.Intent.LoadCache -> { val c = api.cache(); reduce { copy(loading = false, cache = c) } }
                ReportsContract.Intent.LoadUsage -> { val u = api.usage(); reduce { copy(loading = false, usage = u) } }
            }
        } catch (e: ApiException) { reduce { copy(loading = false, error = e.error.message) } }
    }
}

private val cacheCols = listOf(Col("Source hash", AdminTokens.courseCard), Col("Course", AdminTokens.gradeCard), Col("Subject", AdminTokens.gradeCard), Col("Prompt", AdminTokens.gradeCard - AdminTokens.gutter), Col("Lessons"), Col("Tokens", AdminTokens.gradeCard, numeric = true), Col("Hits", AdminTokens.gradeCard - AdminTokens.gutter, numeric = true))

@Composable
fun CacheScreen(vm: ReportsViewModel, onOpenLesson: (String) -> Unit) {
    val s by vm.state.collectAsState()
    LaunchedEffect(Unit) { vm.dispatch(ReportsContract.Intent.LoadCache) }
    val saved = s.cache.sumOf { it.tokenUsage * it.hits }
    LazyPage("AI cache", description = "Every analysed file set and generated play is kept forever: the same content for the same course never costs a second model call.") {
        item {
            ErrorBanner(s.error)
            Row(horizontalArrangement = Arrangement.spacedBy(AdminTokens.gutter / 2)) { Tag("${s.cache.size} entries", Palette.parentSurface); Tag("${saved.tokens()} tokens saved by hits", Palette.parentSurface) }
            Gap(2)
        }
        when {
            s.loading -> item { Loading("Loading the cache…") }
            s.cache.isEmpty() -> item { EmptyState("The cache is empty", "It fills up as lessons are read and written.") }
            else -> {
                item { Column(Modifier.background(Palette.parentSurface).border(AdminTokens.rule, Palette.parentInk)) { TableHeader(cacheCols) } }
                items(s.cache.size, key = { s.cache[it].fileHash + s.cache[it].promptVersion + s.cache[it].grade }) { i ->
                    val c = s.cache[i]
                    Box(Modifier.background(Palette.parentSurface).padding(horizontal = AdminTokens.rule)) {
                        TableRow {
                            Cell(c.fileHash.take(12), cacheCols[0])
                            Cell("${c.curriculum.name.lowercase().replaceFirstChar { it.uppercase() }} · G${c.grade}", cacheCols[1])
                            Cell(c.subject.name.lowercase().replaceFirstChar { it.uppercase() }, cacheCols[2])
                            Cell(c.promptVersion, cacheCols[3], color = Palette.parentInkSoft)
                            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(AdminTokens.gutter / 3)) { c.lessonIds.take(4).forEach { id -> LinkButton(id.take(8), { onOpenLesson(id) }) } }
                            Cell(c.tokenUsage.tokens(), cacheCols[5])
                            Cell(c.hits.toString(), cacheCols[6])
                        }
                    }
                }
                item { Box(Modifier.fillMaxWidth().height(AdminTokens.rule).background(Palette.parentInk)) }
            }
        }
    }
}

private val lessonCols = listOf(Col("Date", AdminTokens.gradeCard + AdminTokens.gutter / 2), Col("Course", AdminTokens.gradeCard), Col("Lesson"), Col("Played", AdminTokens.gradeCard + AdminTokens.gutter, numeric = true), Col("Completed", AdminTokens.courseCard, numeric = true))
private val stopCols = listOf(Col("Stop"), Col("Type", AdminTokens.gradeCard + AdminTokens.gutter / 2), Col("Attempts", AdminTokens.gradeCard + AdminTokens.gutter, numeric = true), Col("First try", AdminTokens.gradeCard + AdminTokens.gutter, numeric = true))

@Composable
fun UsageScreen(vm: ReportsViewModel, onOpenLesson: (String) -> Unit) {
    val s by vm.state.collectAsState()
    LaunchedEffect(Unit) { vm.dispatch(ReportsContract.Intent.LoadUsage) }
    val u = s.usage
    LazyPage("Usage", description = "Children per course, plays and completions per lesson, first-try accuracy per stop.") {
        item { ErrorBanner(s.error) }
        if (s.loading || u == null) { item { Loading("Loading usage…") }; return@LazyPage }
        item {
            Card {
                SectionLabel("Children per course")
                Row(horizontalArrangement = Arrangement.spacedBy(AdminTokens.gutter / 3)) { u.courses.forEach { Tag("${it.course.curriculum.name.lowercase().replaceFirstChar { c -> c.uppercase() }} G${it.course.grade}: ${it.children}", if (it.children > 0) Palette.parentAccentSoft else Palette.parentBg) } }
            }
            Gap(2)
            SectionLabel("Lessons")
        }
        if (u.lessons.isEmpty()) item { EmptyState("No plays yet", "Once a child opens a published lesson it shows up here.") }
        else {
            item { Column(Modifier.background(Palette.parentSurface).border(AdminTokens.rule, Palette.parentInk)) { TableHeader(lessonCols) } }
            items(u.lessons.size, key = { "l" + u.lessons[it].lessonId }) { i ->
                val l = u.lessons[i]
                Box(Modifier.background(Palette.parentSurface).padding(horizontal = AdminTokens.rule)) {
                    TableRow(onClick = { onOpenLesson(l.lessonId) }) {
                        Cell(l.date.toString(), lessonCols[0]); Cell("${l.course.curriculum.name.lowercase().replaceFirstChar { c -> c.uppercase() }} · G${l.course.grade}", lessonCols[1]); Cell(l.title, lessonCols[2], weight = FontWeight.SemiBold)
                        Cell(l.played.toString(), lessonCols[3]); Cell(l.completed.toString(), lessonCols[4])
                    }
                }
            }
            item { Box(Modifier.fillMaxWidth().height(AdminTokens.rule).background(Palette.parentInk)); Gap(2); SectionLabel("Stops — first-try accuracy") }
        }
        if (u.stops.isEmpty()) { if (u.lessons.isNotEmpty()) item { EmptyState("No answers yet", "Accuracy per stop appears after the first attempts.") } }
        else {
            item { Column(Modifier.background(Palette.parentSurface).border(AdminTokens.rule, Palette.parentInk)) { TableHeader(stopCols) } }
            items(u.stops.size, key = { "s" + u.stops[it].stopId }) { i ->
                val st = u.stops[i]
                Box(Modifier.background(Palette.parentSurface).padding(horizontal = AdminTokens.rule)) {
                    TableRow(onClick = { onOpenLesson(st.lessonId) }) {
                        Cell(st.title, stopCols[0], weight = FontWeight.SemiBold); Cell(st.type, stopCols[1], color = Palette.parentInkSoft)
                        Cell(st.attempts.toString(), stopCols[2]); Cell(if (st.attempts == 0) "–" else "${st.firstTryCorrect * 100 / st.attempts}%", stopCols[3])
                    }
                }
            }
            item { Box(Modifier.fillMaxWidth().height(AdminTokens.rule).background(Palette.parentInk)) }
        }
    }
}
