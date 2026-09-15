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
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import quest.admin.core.course.CourseChoice
import quest.admin.core.course.CourseChooser
import quest.admin.core.course.CourseMemory
import quest.admin.core.design.AdminButton
import quest.admin.core.design.BusyBar
import quest.admin.core.design.ConfirmBand
import quest.admin.core.design.DestructiveButton
import quest.admin.core.design.LinkButton
import quest.admin.core.design.MenuDots
import quest.admin.core.design.NoticeBand
import quest.admin.core.design.Card
import quest.admin.core.design.Cell
import quest.admin.core.design.Choice
import quest.admin.core.design.Col
import quest.admin.core.design.EmptyState
import quest.admin.core.design.ErrorBanner
import quest.admin.core.design.Gap
import quest.admin.core.design.LazyPage
import quest.admin.core.design.Loading
import quest.admin.core.design.Page
import quest.admin.core.design.SecondaryButton
import quest.admin.core.design.SectionLabel
import quest.admin.core.design.TableHeader
import quest.admin.core.design.TableRow
import quest.admin.core.design.Tag
import quest.admin.core.design.statusWord
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
import quest.api.dto.Subject
import quest.ui.design.AdminTokens
import quest.ui.design.Palette

object LessonsContract {
    data class State(
        val loading: Boolean = true, val lessons: List<AdminLesson> = emptyList(), val error: String? = null,
        val course: CourseChoice = CourseChoice(), val subject: Subject? = null,
        val calendar: CalendarResponse? = null, val calYear: Int = 2026, val calMonth: Int = 9,
        val menuFor: String? = null,                 // lesson id whose row menu is open
        val confirmDelete: String? = null,           // lesson id awaiting delete confirmation; "*" = all failed
        val busy: String? = null, val notice: String? = null,
    ) : MviState {
        val failed: List<AdminLesson> get() = lessons.filter { it.status == quest.api.dto.LessonStatus.ERROR }
    }
    sealed interface Intent : MviIntent {
        data object Load : Intent
        data class Course(val v: CourseChoice) : Intent
        data class SubjectFilter(val v: Subject?) : Intent
        data class Month(val year: Int, val month: Int) : Intent
        data class Menu(val lessonId: String?) : Intent
        data class AskDelete(val lessonId: String?) : Intent       // null closes; "*" = every failed lesson
        data object ConfirmDelete : Intent
        data class Retry(val lessonId: String) : Intent
        data object DismissNotice : Intent
    }
    sealed interface Effect : MviEffect
}

/** Lessons list and calendar share one model: the course pair (remembered per admin) drives both. */
class LessonsViewModel(private val api: AdminApi, today: LocalDate, private val email: String?) : MviViewModel<LessonsContract.State, LessonsContract.Intent, LessonsContract.Effect>(LessonsContract.State(course = CourseMemory.load(email), calYear = today.year, calMonth = today.monthNumber)) {
    override suspend fun handle(intent: LessonsContract.Intent) {
        when (intent) {
            LessonsContract.Intent.Load -> { load(); calendar() }
            is LessonsContract.Intent.Course -> { CourseMemory.save(email, intent.v); reduce { copy(course = intent.v) }; load(); calendar() }
            is LessonsContract.Intent.SubjectFilter -> { reduce { copy(subject = intent.v) }; load() }
            is LessonsContract.Intent.Month -> { reduce { copy(calYear = intent.year, calMonth = intent.month) }; calendar() }
            is LessonsContract.Intent.Menu -> reduce { copy(menuFor = intent.lessonId) }
            is LessonsContract.Intent.AskDelete -> reduce { copy(confirmDelete = intent.lessonId, menuFor = null) }
            LessonsContract.Intent.ConfirmDelete -> {
                val target = current.confirmDelete ?: return
                reduce { copy(busy = "Deleting…", confirmDelete = null, notice = null) }
                try {
                    if (target == "*") { val n = api.deleteFailedLessons(); reduce { copy(notice = "Deleted $n failed lesson${if (n == 1) "" else "s"}.") } }
                    else { api.deleteLesson(target); reduce { copy(notice = "Lesson deleted. Its analysed pages stay in the cache, so the same file costs nothing next time.") } }
                } catch (e: ApiException) { reduce { copy(error = e.error.message) } }
                reduce { copy(busy = null) }; load()
            }
            is LessonsContract.Intent.Retry -> {
                reduce { copy(busy = "Retrying…", notice = null, menuFor = null) }
                try { api.retry(intent.lessonId); reduce { copy(notice = "Retrying from the failed step — the row updates as it runs.") } } catch (e: ApiException) { reduce { copy(error = e.error.message) } }
                reduce { copy(busy = null) }; load()
            }
            LessonsContract.Intent.DismissNotice -> reduce { copy(notice = null, error = null) }
        }
    }

    private suspend fun load() {
        reduce { copy(loading = true, error = null) }
        try { val list = api.lessons(LessonFilter(current.course.curriculum, current.course.grade, current.subject)); reduce { copy(loading = false, lessons = list) } }
        catch (e: ApiException) { reduce { copy(loading = false, error = e.error.message) } }
    }

    private suspend fun calendar() {
        val c = current.course
        if (!c.complete) { reduce { copy(calendar = null) }; return }
        val cal = runCatching { api.calendar(c.curriculum!!, c.grade!!, current.calYear, current.calMonth) }.getOrNull()
        reduce { copy(calendar = cal) }
    }
}

private val cols = listOf(Col("Date", AdminTokens.courseCard - AdminTokens.gutter), Col("Subject", AdminTokens.gradeCard - AdminTokens.gutter / 2), Col("Title"), Col("Source", AdminTokens.gradeCard - AdminTokens.gutter / 2), Col("Tokens", AdminTokens.gradeCard - AdminTokens.gutter / 2, numeric = true), Col("Status", AdminTokens.courseCard * 2 - AdminTokens.gutter, numeric = true), Col("", AdminTokens.gutter * 2))

private fun courseLabel(l: AdminLesson) = "${l.course.curriculum.name.lowercase().replaceFirstChar { it.uppercase() }} · Grade ${l.course.grade} · ${l.subject.name.lowercase().replaceFirstChar { it.uppercase() }} · ${l.date.dayOfMonth} ${l.date.month.name.lowercase().replaceFirstChar { it.uppercase() }.take(3)}"
private fun statusText(l: AdminLesson): String {
    if (l.status == quest.api.dto.LessonStatus.ERROR) { val at = l.steps.firstOrNull { it.status == quest.api.StepStatus.ERROR }?.step; return if (at != null) "Error at: ${at.short}" else "Error" }
    val running = l.currentStep; if (running != null && (l.status == quest.api.dto.LessonStatus.ANALYZING || l.status == quest.api.dto.LessonStatus.GENERATING)) return "${statusWord(l.status.name.lowercase())} ${running.short}"
    return statusWord(l.status.name.lowercase())
}
private fun deletable(l: AdminLesson) = l.status != quest.api.dto.LessonStatus.PUBLISHED && l.status != quest.api.dto.LessonStatus.ANALYZING && l.status != quest.api.dto.LessonStatus.GENERATING && l.status != quest.api.dto.LessonStatus.UPLOADING

@Composable
fun LessonsScreen(vm: LessonsViewModel, onOpen: (String) -> Unit, onNew: () -> Unit) {
    val s by vm.state.collectAsState()
    LaunchedEffect(Unit) { vm.dispatch(LessonsContract.Intent.Load) }
    LazyPage("Lessons", description = "One island per published lesson, per course and day.", breadcrumb = s.course.label.ifBlank { null }, actions = {
        if (s.failed.isNotEmpty()) DestructiveButton("Delete all failed (${s.failed.size})", { vm.dispatch(LessonsContract.Intent.AskDelete("*")) }, enabled = s.busy == null)
        AdminButton("+ New lesson", onNew)
    }) {
        item {
            Card {
                CourseChooser(s.course, { vm.dispatch(LessonsContract.Intent.Course(it)) }, compact = true)
                Gap(2)
                SectionLabel("Subject")
                Choice(listOf("all" to "All", "math" to "Math", "english" to "English"), s.subject?.name?.lowercase() ?: "all") { v -> vm.dispatch(LessonsContract.Intent.SubjectFilter(if (v == "all") null else Subject.valueOf(v.uppercase()))) }
            }
            Gap(2)
            ErrorBanner(s.error) { vm.dispatch(LessonsContract.Intent.DismissNotice) }
            if (s.notice != null) NoticeBand(s.notice!!) { vm.dispatch(LessonsContract.Intent.DismissNotice) }
            if (s.busy != null) { BusyBar(s.busy) }
            val target = s.confirmDelete
            if (target != null) {
                val text = if (target == "*") "Delete ${s.failed.size} failed lesson${if (s.failed.size == 1) "" else "s"}? This removes their uploaded files and any generated plays. Cached analysis is kept, so re-uploading the same files will cost nothing."
                    else s.lessons.firstOrNull { it.id == target }?.let { "Delete ${courseLabel(it)}? This removes the uploaded file and any generated plays. Cached analysis is kept, so re-uploading the same file will cost nothing." } ?: "Delete this lesson?"
                ConfirmBand(text, "Delete", { vm.dispatch(LessonsContract.Intent.ConfirmDelete) }, { vm.dispatch(LessonsContract.Intent.AskDelete(null)) })
                Gap(2)
            }
        }
        when {
            s.loading -> item { Loading("Loading lessons…") }
            s.lessons.isEmpty() -> item { EmptyState("No lessons for ${s.course.label.ifBlank { "any course" }} yet", "Create one from a PDF, slides, photos of a worksheet — or write the questions yourself.", "+ New lesson", onNew) }
            else -> {
                item { Column(Modifier.background(Palette.parentSurface).border(AdminTokens.rule, Palette.parentInk)) { TableHeader(cols) } }
                items(s.lessons.size, key = { s.lessons[it].id }) { i ->
                    val l = s.lessons[i]
                    val failedRow = l.status == quest.api.dto.LessonStatus.ERROR
                    Column(Modifier.background(Palette.parentSurface).padding(horizontal = AdminTokens.rule)) {
                        TableRow(onClick = { onOpen(l.id) }) {
                            Cell(l.date.toString(), cols[0])
                            Cell(l.subject.name.lowercase().replaceFirstChar { it.uppercase() }, cols[1])
                            Cell(l.title ?: "(untitled)", cols[2], weight = FontWeight.SemiBold)
                            Cell(l.source.name.lowercase(), cols[3], color = Palette.parentInkSoft)
                            Cell(l.tokenUsage.tokens(), cols[4])
                            Row(Modifier.width(cols[5].width!!), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                                Text(statusText(l), style = MaterialTheme.typography.bodyMedium, color = if (failedRow) Palette.parentAccent else if (l.status.name == "PUBLISHED") Palette.parentInk else Palette.parentInkSoft, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                if (failedRow) { Spacer(Modifier.width(AdminTokens.gutter / 3)); LinkButton("Retry", { vm.dispatch(LessonsContract.Intent.Retry(l.id)) }, enabled = s.busy == null) }
                            }
                            Box(Modifier.width(cols[6].width!!), contentAlignment = Alignment.CenterEnd) { MenuDots({ vm.dispatch(LessonsContract.Intent.Menu(if (s.menuFor == l.id) null else l.id)) }) }
                        }
                        if (s.menuFor == l.id) Row(Modifier.fillMaxWidth().background(Palette.parentBg).padding(horizontal = AdminTokens.gutter / 2, vertical = AdminTokens.gutter / 4), horizontalArrangement = Arrangement.spacedBy(AdminTokens.gutter), verticalAlignment = Alignment.CenterVertically) {
                            LinkButton("Open", { onOpen(l.id) }, color = Palette.parentInk)
                            if (failedRow) LinkButton("Retry and continue", { vm.dispatch(LessonsContract.Intent.Retry(l.id)) })
                            if (deletable(l)) LinkButton("Delete", { vm.dispatch(LessonsContract.Intent.AskDelete(l.id)) })
                            else Text(if (l.status == quest.api.dto.LessonStatus.PUBLISHED) "Unpublish it first to delete it." else "Wait for the job to finish.", style = MaterialTheme.typography.bodySmall, color = Palette.parentInkSoft)
                            Spacer(Modifier.weight(1f)); LinkButton("Close", { vm.dispatch(LessonsContract.Intent.Menu(null)) }, color = Palette.parentInkSoft)
                        }
                    }
                }
                item { Box(Modifier.fillMaxWidth().height(AdminTokens.rule).background(Palette.parentInk)) }
            }
        }
    }
}

@Composable
fun CalendarScreen(vm: LessonsViewModel, onOpenDay: (LocalDate) -> Unit) {
    val s by vm.state.collectAsState()
    LaunchedEffect(Unit) { vm.dispatch(LessonsContract.Intent.Load) }
    Page("Calendar", description = "Which days have a published lesson for this course. Children see one island per day.", breadcrumb = s.course.label.ifBlank { null }) {
        Card {
            CourseChooser(s.course, { vm.dispatch(LessonsContract.Intent.Course(it)) }, compact = true)
        }
        Gap(2)
        if (!s.course.complete) { EmptyState("Choose a course", "Pick a curriculum and a grade to see its calendar."); return@Page }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AdminTokens.gutter / 2)) {
            SecondaryButton("‹ Previous", { val d = LocalDate(s.calYear, s.calMonth, 1).plus(-1, DateTimeUnit.MONTH); vm.dispatch(LessonsContract.Intent.Month(d.year, d.monthNumber)) })
            Text("${monthName(s.calMonth)} ${s.calYear}", style = MaterialTheme.typography.titleLarge, color = Palette.parentInk, modifier = Modifier.width(AdminTokens.courseCard * 2))
            SecondaryButton("Next ›", { val d = LocalDate(s.calYear, s.calMonth, 1).plus(1, DateTimeUnit.MONTH); vm.dispatch(LessonsContract.Intent.Month(d.year, d.monthNumber)) })
        }
        Gap(2)
        val first = LocalDate(s.calYear, s.calMonth, 1)
        val days = generateSequence(first) { it.plus(1, DateTimeUnit.DAY) }.takeWhile { it.monthNumber == s.calMonth }.toList()
        val published = s.calendar?.days?.associateBy { it.date }.orEmpty()
        Card(padding = AdminTokens.gutter / 2) {
            Row { listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun").forEach { Text(it.uppercase(), Modifier.weight(1f).padding(AdminTokens.gutter / 4), style = MaterialTheme.typography.bodySmall, color = Palette.parentInkSoft) } }
            val offset = first.dayOfWeek.ordinal
            val cells: List<LocalDate?> = List(offset) { null } + days
            cells.chunked(7).forEach { week ->
                Row(Modifier.fillMaxWidth()) {
                    week.forEach { day ->
                        val info = day?.let { published[it] }
                        Box(Modifier.weight(1f).height(AdminTokens.gradeCard - AdminTokens.gutter / 2).border(AdminTokens.ruleThin, Palette.parentRule).background(if (info != null && (info.math || info.english)) Palette.parentAccentSoft else Palette.parentSurface).clickable(enabled = day != null) { onOpenDay(day!!) }.padding(AdminTokens.gutter / 4)) {
                            if (day != null) Column {
                                Text(day.dayOfMonth.toString(), style = MaterialTheme.typography.labelLarge, color = Palette.parentInk)
                                Spacer(Modifier.height(AdminTokens.gutter / 6))
                                if (info?.math == true) Tag("Math", Palette.parentSurface)
                                if (info?.english == true) Tag("English", Palette.parentSurface)
                            }
                        }
                    }
                    repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        if (published.values.none { it.math || it.english }) { Gap(2); EmptyState("Nothing published in ${monthName(s.calMonth)} for ${s.course.label}", "Publish a lesson and its day lights up here.") }
    }
}

private fun monthName(m: Int) = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")[m - 1]
