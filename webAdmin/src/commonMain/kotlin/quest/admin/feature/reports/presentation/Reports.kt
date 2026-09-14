package quest.admin.feature.reports.presentation

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import quest.admin.core.design.Card
import quest.admin.core.design.ErrorBanner
import quest.admin.core.design.Loading
import quest.admin.core.design.Page
import quest.admin.core.design.SectionTitle
import quest.admin.core.design.Tag
import quest.admin.core.design.tokens
import quest.admin.core.mvi.MviEffect
import quest.admin.core.mvi.MviIntent
import quest.admin.core.mvi.MviState
import quest.admin.core.mvi.MviViewModel
import quest.api.AdminApi
import quest.api.ApiException
import quest.api.CacheEntry
import quest.api.UsageResponse
import quest.ui.design.Palette

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

@Composable
fun CacheScreen(vm: ReportsViewModel, onOpenLesson: (String) -> Unit) {
    val s by vm.state.collectAsState()
    LaunchedEffect(Unit) { vm.dispatch(ReportsContract.Intent.LoadCache) }
    Page("AI cache") {
        Text("One entry per (slides, course, prompt version). A hit means a lesson reused it with zero model calls. Entries are permanent; bumping a prompt version starts a new one.", color = Palette.parentInkSoft)
        Spacer(Modifier.height(16.dp))
        ErrorBanner(s.error)
        if (s.loading) Loading() else Card {
            val total = s.cache.sumOf { it.tokenUsage }; val saved = s.cache.sumOf { it.tokenUsage * it.hits }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { Tag("${s.cache.size} entries"); Tag("spent ${total.tokens()} tokens"); Tag("saved ≈ ${saved.tokens()} tokens", Palette.mint) }
            Spacer(Modifier.height(12.dp))
            s.cache.forEach { c ->
                Row(Modifier.fillMaxWidth().border(1.dp, Palette.parentRule).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(c.fileHash.take(12), Modifier.width(120.dp), style = MaterialTheme.typography.bodySmall)
                    Text("${c.curriculum.name.lowercase()} G${c.grade} ${c.subject.name.lowercase()}", Modifier.width(180.dp))
                    Text("prompt ${c.promptVersion}", Modifier.width(90.dp), style = MaterialTheme.typography.bodySmall)
                    Text(c.tokenUsage.tokens() + " tokens", Modifier.width(110.dp))
                    Tag(if (c.hits > 0) "♻ ${c.hits} hit${if (c.hits > 1) "s" else ""}" else "no hits yet", if (c.hits > 0) Palette.mint else Palette.parentRule)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { c.lessonIds.forEach { id -> Text(id.take(8), Modifier.clickable { onOpenLesson(id) }, color = Palette.parentAccent, fontWeight = FontWeight.SemiBold) } }
                }
            }
        }
    }
}

@Composable
fun UsageScreen(vm: ReportsViewModel, onOpenLesson: (String) -> Unit) {
    val s by vm.state.collectAsState()
    LaunchedEffect(Unit) { vm.dispatch(ReportsContract.Intent.LoadUsage) }
    Page("Usage") {
        ErrorBanner(s.error)
        val u = s.usage
        if (s.loading || u == null) Loading() else {
            Card {
                SectionTitle("Children per course")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { u.courses.forEach { Tag("${it.course.curriculum.name.lowercase()} G${it.course.grade}: ${it.children}") } }
            }
            Spacer(Modifier.height(16.dp))
            Card {
                SectionTitle("Lessons played / completed")
                u.lessons.forEach { l ->
                    Row(Modifier.fillMaxWidth().border(1.dp, Palette.parentRule).clickable { onOpenLesson(l.lessonId) }.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(l.date.toString(), Modifier.width(110.dp)); Text("${l.course.curriculum.name.lowercase()} G${l.course.grade}", Modifier.width(120.dp))
                        Text(l.title, Modifier.weight(1f), fontWeight = FontWeight.SemiBold); Text("played ${l.played}", Modifier.width(100.dp)); Text("completed ${l.completed}", Modifier.width(120.dp))
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Card {
                SectionTitle("Stops children find hardest (first-try accuracy)")
                u.stops.take(30).forEach { st ->
                    val pct = if (st.attempts == 0) 0 else st.firstTryCorrect * 100 / st.attempts
                    Row(Modifier.fillMaxWidth().border(1.dp, Palette.parentRule).clickable { onOpenLesson(st.lessonId) }.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(st.title, Modifier.weight(1f)); Text(st.type, Modifier.width(110.dp), style = MaterialTheme.typography.bodySmall)
                        Text("${st.firstTryCorrect}/${st.attempts}", Modifier.width(80.dp)); Tag("$pct%", if (pct >= 85) Palette.mint else if (pct >= 60) Palette.sun else Palette.coral)
                    }
                }
            }
        }
    }
}
