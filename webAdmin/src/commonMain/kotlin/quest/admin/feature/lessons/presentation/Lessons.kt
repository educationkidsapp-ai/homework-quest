package quest.admin.feature.lessons.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import quest.admin.core.design.AdminButton
import quest.admin.core.design.AdminOutlinedButton
import quest.admin.core.design.Card
import quest.admin.core.design.Choice
import quest.admin.core.design.ErrorBanner
import quest.admin.core.design.Loading
import quest.admin.core.design.Page
import quest.admin.core.design.StatusTag
import quest.admin.core.design.Tag
import quest.admin.core.design.tokens
import quest.admin.core.mvi.MviEffect
import quest.admin.core.mvi.MviIntent
import quest.admin.core.mvi.MviState
import quest.admin.core.mvi.MviViewModel
import quest.api.AdminApi
import quest.api.AdminLesson
import quest.api.ApiException
import quest.api.CalendarResponse
import quest.api.LessonFilter
import quest.api.dto.Curriculum
import quest.api.dto.LessonStatus
import quest.api.dto.Subject
import quest.ui.design.Palette

object LessonsContract {
    data class State(
        val loading: Boolean = true, val lessons: List<AdminLesson> = emptyList(), val error: String? = null,
        val curriculum: Curriculum? = null, val grade: Int? = null, val subject: Subject? = null, val status: LessonStatus? = null,
        val calendar: CalendarResponse? = null, val calCurriculum: Curriculum = Curriculum.BRITISH, val calGrade: Int = 1, val calYear: Int = 2026, val calMonth: Int = 9,
    ) : MviState
    sealed interface Intent : MviIntent {
        data object Load : Intent
        data class Filter(val curriculum: Curriculum? = null, val grade: Int? = null, val subject: Subject? = null, val status: LessonStatus? = null, val clear: Boolean = false) : Intent
        data class Calendar(val curriculum: Curriculum, val grade: Int, val year: Int, val month: Int) : Intent
    }
    sealed interface Effect : MviEffect
}

class LessonsViewModel(private val api: AdminApi, today: LocalDate) : MviViewModel<LessonsContract.State, LessonsContract.Intent, LessonsContract.Effect>(LessonsContract.State(calYear = today.year, calMonth = today.monthNumber)) {
    override suspend fun handle(intent: LessonsContract.Intent) {
        when (intent) {
            LessonsContract.Intent.Load -> load()
            is LessonsContract.Intent.Filter -> {
                reduce { if (intent.clear) copy(curriculum = null, grade = null, subject = null, status = null) else copy(curriculum = intent.curriculum ?: curriculum, grade = intent.grade ?: grade, subject = intent.subject ?: subject, status = intent.status ?: status) }
                load()
            }
            is LessonsContract.Intent.Calendar -> {
                reduce { copy(calCurriculum = intent.curriculum, calGrade = intent.grade, calYear = intent.year, calMonth = intent.month) }
                val cal = runCatching { api.calendar(intent.curriculum, intent.grade, intent.year, intent.month) }.getOrNull()
                reduce { copy(calendar = cal) }
            }
        }
    }

    private suspend fun load() {
        reduce { copy(loading = true, error = null) }
        try { val list = api.lessons(LessonFilter(current.curriculum, current.grade, current.subject, status = current.status)); reduce { copy(loading = false, lessons = list) } }
        catch (e: ApiException) { reduce { copy(loading = false, error = e.error.message) } }
    }
}

@Composable
fun LessonsScreen(vm: LessonsViewModel, onOpen: (String) -> Unit, onNew: () -> Unit) {
    val s by vm.state.collectAsState()
    LaunchedEffect(Unit) { vm.dispatch(LessonsContract.Intent.Load) }
    Page("Lessons", actions = { AdminButton("+ New lesson", onNew) }) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Choice(listOf("all" to "All", "british" to "British", "american" to "American"), s.curriculum?.name?.lowercase() ?: "all") { v -> vm.dispatch(if (v == "all") LessonsContract.Intent.Filter(clear = true) else LessonsContract.Intent.Filter(curriculum = Curriculum.valueOf(v.uppercase()))) }
            Choice(listOf("1" to "Grade 1", "2" to "Grade 2", "3" to "Grade 3"), s.grade?.toString()) { v -> vm.dispatch(LessonsContract.Intent.Filter(grade = v.toInt())) }
            Choice(listOf("math" to "Math", "english" to "English"), s.subject?.name?.lowercase()) { v -> vm.dispatch(LessonsContract.Intent.Filter(subject = Subject.valueOf(v.uppercase()))) }
            Choice(listOf("published" to "Published", "review" to "In review", "needs_review" to "Needs skills", "error" to "Errors"), s.status?.name?.lowercase()) { v -> vm.dispatch(LessonsContract.Intent.Filter(status = LessonStatus.valueOf(v.uppercase()))) }
        }
        Spacer(Modifier.height(16.dp))
        ErrorBanner(s.error)
        if (s.loading) Loading()
        else if (s.lessons.isEmpty()) Card { Text("No lessons yet. Create one and upload the slides.", color = Palette.parentInkSoft) }
        else Card {
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                listOf("Date" to 110, "Course" to 120, "Subject" to 90, "Title" to 0, "Status" to 130, "Tokens" to 110, "Saved" to 90).forEach { (h, w) ->
                    Text(h, if (w == 0) Modifier.weight(1f) else Modifier.width(w.dp), style = MaterialTheme.typography.bodySmall, color = Palette.parentInkSoft, fontWeight = FontWeight.Bold)
                }
            }
            s.lessons.forEach { l ->
                Row(Modifier.fillMaxWidth().border(1.dp, Palette.parentRule).clickable { onOpen(l.id) }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(l.date.toString(), Modifier.width(110.dp))
                    Text("${l.course.curriculum.name.lowercase().replaceFirstChar { it.uppercase() }} · G${l.course.grade}", Modifier.width(120.dp))
                    Text(l.subject.name.lowercase().replaceFirstChar { it.uppercase() }, Modifier.width(90.dp))
                    Text(l.title ?: "(untitled)", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    Box(Modifier.width(130.dp)) { StatusTag(l.status.name.lowercase()) }
                    Text(l.tokenUsage.tokens(), Modifier.width(110.dp))
                    Text(if (l.tokensSaved > 0) "♻ ${l.tokensSaved.tokens()}" else "", Modifier.width(90.dp), color = Palette.mint.copy(alpha = 1f))
                }
            }
        }
    }
}

@Composable
fun CalendarScreen(vm: LessonsViewModel, onOpenDay: (LocalDate) -> Unit) {
    val s by vm.state.collectAsState()
    LaunchedEffect(s.calCurriculum, s.calGrade, s.calYear, s.calMonth) { vm.dispatch(LessonsContract.Intent.Calendar(s.calCurriculum, s.calGrade, s.calYear, s.calMonth)) }
    Page("Calendar") {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Choice(listOf("british" to "British", "american" to "American"), s.calCurriculum.name.lowercase()) { v -> vm.dispatch(LessonsContract.Intent.Calendar(Curriculum.valueOf(v.uppercase()), s.calGrade, s.calYear, s.calMonth)) }
            Choice(listOf("1" to "Grade 1", "2" to "Grade 2", "3" to "Grade 3"), s.calGrade.toString()) { v -> vm.dispatch(LessonsContract.Intent.Calendar(s.calCurriculum, v.toInt(), s.calYear, s.calMonth)) }
            AdminOutlinedButton("‹", { val d = LocalDate(s.calYear, s.calMonth, 1).plus(-1, DateTimeUnit.MONTH); vm.dispatch(LessonsContract.Intent.Calendar(s.calCurriculum, s.calGrade, d.year, d.monthNumber)) })
            Text("${s.calYear}-${s.calMonth.toString().padStart(2, '0')}", fontWeight = FontWeight.Bold)
            AdminOutlinedButton("›", { val d = LocalDate(s.calYear, s.calMonth, 1).plus(1, DateTimeUnit.MONTH); vm.dispatch(LessonsContract.Intent.Calendar(s.calCurriculum, s.calGrade, d.year, d.monthNumber)) })
        }
        Spacer(Modifier.height(16.dp))
        val first = LocalDate(s.calYear, s.calMonth, 1)
        val days = generateSequence(first) { it.plus(1, DateTimeUnit.DAY) }.takeWhile { it.monthNumber == s.calMonth }.toList()
        val published = s.calendar?.days?.associateBy { it.date }.orEmpty()
        Card {
            Row { listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun").forEach { Text(it, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = Palette.parentInkSoft) } }
            val offset = first.dayOfWeek.ordinal
            val cells: List<LocalDate?> = List(offset) { null } + days
            cells.chunked(7).forEach { week ->
                Row(Modifier.fillMaxWidth()) {
                    week.forEach { day ->
                        Box(Modifier.weight(1f).height(72.dp).border(1.dp, Palette.parentRule).clickable(enabled = day != null) { onOpenDay(day!!) }.padding(6.dp)) {
                            if (day != null) Column {
                                Text(day.dayOfMonth.toString(), style = MaterialTheme.typography.bodySmall)
                                val info = published[day]
                                if (info?.math == true) Tag("Math", Palette.sea)
                                if (info?.english == true) Tag("English", Palette.lavender)
                            }
                        }
                    }
                    repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("Every child on this course sees one island per published lesson. Days with nothing published show nothing on the map.", style = MaterialTheme.typography.bodySmall, color = Palette.parentInkSoft)
    }
}
