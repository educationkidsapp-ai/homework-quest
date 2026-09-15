package quest.admin.feature.lessons.presentation

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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import io.github.vinceglb.filekit.core.FileKit
import io.github.vinceglb.filekit.core.PickerMode
import io.github.vinceglb.filekit.core.PickerType
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import quest.admin.core.course.CourseChoice
import quest.admin.core.course.CourseChooser
import quest.admin.core.course.CourseMemory
import quest.admin.core.design.AdminButton
import quest.admin.core.design.Card
import quest.admin.core.design.Choice
import quest.admin.core.design.ErrorBanner
import quest.admin.core.design.Field
import quest.admin.core.design.Gap
import quest.admin.core.design.LinkButton
import quest.admin.core.design.Page
import quest.admin.core.design.ProgressBar
import quest.admin.core.design.SecondaryButton
import quest.admin.core.design.SectionLabel
import quest.admin.core.design.SelectCard
import quest.admin.core.mvi.MviEffect
import quest.admin.core.mvi.MviIntent
import quest.admin.core.mvi.MviState
import quest.admin.core.mvi.MviViewModel
import quest.admin.core.platform.Browser
import quest.api.AdminApi
import quest.api.ApiException
import quest.api.CreateLessonRequest
import quest.api.LessonSource
import quest.api.UploadFile
import quest.api.dto.Subject
import quest.ui.design.AdminTokens
import quest.ui.design.Palette

object NewLessonContract {
    /** What the admin is making the lesson from. */
    enum class Source { DOCUMENT, IMAGES, MANUAL }

    data class State(
        val course: CourseChoice = CourseChoice(), val subject: Subject = Subject.ENGLISH, val date: String = "", val title: String = "", val notes: String = "", val practiceLength: Int = 7,
        val source: Source? = null, val files: List<UploadFile> = emptyList(), val dragging: Boolean = false,
        val busy: String? = null, val error: String? = null,
    ) : MviState {
        val dateError: String? get() = if (date.isBlank() || runCatching { LocalDate.parse(date) }.isSuccess) null else "Use YYYY-MM-DD"
        val ready: Boolean get() = course.complete && dateError == null && date.isNotBlank() && busy == null && source != null && (source == Source.MANUAL || files.isNotEmpty())
    }
    sealed interface Intent : MviIntent {
        data class Course(val v: CourseChoice) : Intent
        data class SubjectPick(val v: Subject) : Intent
        data class Date(val v: String) : Intent; data class Title(val v: String) : Intent; data class Notes(val v: String) : Intent; data class Length(val v: Int) : Intent
        data class SourcePick(val v: Source) : Intent
        data class AddFiles(val v: List<UploadFile>) : Intent; data class RemoveFile(val index: Int) : Intent
        data class Dragging(val v: Boolean) : Intent
        data object Create : Intent
        data object DismissError : Intent
    }
    sealed interface Effect : MviEffect { data class Created(val lessonId: String) : Effect }
}

class NewLessonViewModel(private val api: AdminApi, today: LocalDate, private val email: String?) : MviViewModel<NewLessonContract.State, NewLessonContract.Intent, NewLessonContract.Effect>(NewLessonContract.State(course = CourseMemory.load(email), date = today.toString())) {
    override suspend fun handle(intent: NewLessonContract.Intent) = when (intent) {
        is NewLessonContract.Intent.Course -> { CourseMemory.save(email, intent.v); reduce { copy(course = intent.v) } }
        is NewLessonContract.Intent.SubjectPick -> reduce { copy(subject = intent.v) }
        is NewLessonContract.Intent.Date -> reduce { copy(date = intent.v.trim()) }
        is NewLessonContract.Intent.Title -> reduce { copy(title = intent.v) }
        is NewLessonContract.Intent.Notes -> reduce { copy(notes = intent.v) }
        is NewLessonContract.Intent.Length -> reduce { copy(practiceLength = intent.v) }
        is NewLessonContract.Intent.SourcePick -> reduce { copy(source = intent.v, files = if (intent.v == NewLessonContract.Source.MANUAL) emptyList() else files.filter { accepts(intent.v, it.fileName) }, error = null) }
        is NewLessonContract.Intent.AddFiles -> {
            val src = current.source ?: NewLessonContract.Source.DOCUMENT
            val (ok, bad) = intent.v.partition { accepts(src, it.fileName) }
            reduce { copy(files = (files + ok).take(10), error = if (bad.isEmpty()) null else "Skipped ${bad.joinToString { it.fileName }} — ${if (src == NewLessonContract.Source.IMAGES) "only PNG or JPG photos here" else "only PDF or PPTX here"}.") }
        }
        is NewLessonContract.Intent.RemoveFile -> reduce { copy(files = files.filterIndexed { i, _ -> i != intent.index }) }
        is NewLessonContract.Intent.Dragging -> reduce { copy(dragging = intent.v) }
        NewLessonContract.Intent.DismissError -> reduce { copy(error = null) }
        NewLessonContract.Intent.Create -> create()
    }

    private suspend fun create() {
        val s = current
        val date = runCatching { LocalDate.parse(s.date) }.getOrNull()
        if (!s.course.complete) { reduce { copy(error = "Choose the curriculum and the grade first.") }; return }
        if (date == null) { reduce { copy(error = "Pick a valid date.") }; return }
        val manual = s.source == NewLessonContract.Source.MANUAL
        reduce { copy(busy = if (manual) "Creating the lesson…" else "Creating the lesson and uploading ${s.files.size} file${if (s.files.size == 1) "" else "s"}…", error = null) }
        try {
            val lesson = api.createLesson(CreateLessonRequest(s.course.curriculum!!, s.course.grade!!, s.subject, date, s.notes.ifBlank { null }, s.practiceLength, if (manual) LessonSource.MANUAL else null, s.title.ifBlank { null }))
            if (!manual) {
                api.uploadFiles(lesson.id, s.files)
                reduce { copy(busy = "Starting to read the ${if (s.source == NewLessonContract.Source.IMAGES) "photos" else "slides"}…") }
                api.analyze(lesson.id)
            }
            reduce { copy(busy = null) }
            effect(NewLessonContract.Effect.Created(lesson.id))
        } catch (e: ApiException) { reduce { copy(busy = null, error = e.error.message) } }
    }

    private fun accepts(source: NewLessonContract.Source, name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (source) { NewLessonContract.Source.DOCUMENT -> ext in setOf("pdf", "pptx"); NewLessonContract.Source.IMAGES -> ext in setOf("png", "jpg", "jpeg"); NewLessonContract.Source.MANUAL -> false }
    }
}

@Composable
fun NewLessonScreen(vm: NewLessonViewModel, onCreated: (String) -> Unit) {
    val s by vm.state.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { vm.effects.collect { if (it is NewLessonContract.Effect.Created) onCreated(it.lessonId) } }
    DisposableEffect(Unit) {
        val stopDrop = Browser.onFilesDropped { dropped -> vm.dispatch(NewLessonContract.Intent.AddFiles(dropped.map { UploadFile(it.name, it.mimeType.ifBlank { mime(it.name) }, it.bytes) })) }
        val stopDrag = Browser.onDragState { vm.dispatch(NewLessonContract.Intent.Dragging(it)) }
        onDispose { stopDrop(); stopDrag() }
    }
    val step2 = s.course.complete
    Page("New lesson", description = "Pick the course, then the day and what the lesson is made from.", breadcrumb = s.course.label.ifBlank { null },
        footer = {
            Text(if (!step2) "Choose a curriculum and a grade to continue." else when (s.source) { null -> "Choose how to add the content."; NewLessonContract.Source.MANUAL -> "Opens an empty lesson you write yourself."; else -> if (s.files.isEmpty()) "Add at least one file." else "${s.files.size} file${if (s.files.size == 1) "" else "s"} ready." },
                style = MaterialTheme.typography.bodyMedium, color = Palette.parentInkSoft, modifier = Modifier.weight(1f))
            AdminButton(when (s.source) { NewLessonContract.Source.MANUAL -> "Create and write the questions"; NewLessonContract.Source.IMAGES -> "Create and read the photos"; else -> "Create and read the slides" }, { vm.dispatch(NewLessonContract.Intent.Create) }, enabled = s.ready)
        }) {
        ErrorBanner(s.error) { vm.dispatch(NewLessonContract.Intent.DismissError) }
        if (s.busy != null) { ProgressBar(s.busy!!); Gap(2) }
        Card { CourseChooser(s.course, { vm.dispatch(NewLessonContract.Intent.Course(it)) }, enabled = s.busy == null) }
        if (!step2) return@Page
        Gap(2)
        Card {
            SectionLabel("Lesson")
            Row(horizontalArrangement = Arrangement.spacedBy(AdminTokens.gutter)) {
                Column(Modifier.weight(1f)) {
                    Text("Subject", style = MaterialTheme.typography.labelLarge, color = Palette.parentInk, modifier = Modifier.padding(bottom = AdminTokens.gutter / 4))
                    Choice(listOf("english" to "English", "math" to "Math"), s.subject.name.lowercase(), enabled = s.busy == null) { vm.dispatch(NewLessonContract.Intent.SubjectPick(Subject.valueOf(it.uppercase()))) }
                }
                Field(s.date, { vm.dispatch(NewLessonContract.Intent.Date(it)) }, "Lesson day (YYYY-MM-DD) — when its island appears", Modifier.weight(1f), error = s.dateError, enabled = s.busy == null)
            }
            Gap(2)
            Field(s.title, { vm.dispatch(NewLessonContract.Intent.Title(it)) }, "Title (optional — the model names it otherwise)", placeholder = "Hot Soup for Mummy · Part 1", enabled = s.busy == null)
            Gap(2)
            Field(s.notes, { vm.dispatch(NewLessonContract.Intent.Notes(it)) }, "Notes for the model (optional): what to focus on, what to skip", singleLine = false, minLines = 3, enabled = s.busy == null)
            Gap(2)
            Text("Practice length — ${s.practiceLength} stops per level", style = MaterialTheme.typography.labelLarge, color = Palette.parentInk, modifier = Modifier.padding(bottom = AdminTokens.gutter / 4))
            Choice((5..12).map { it.toString() to it.toString() }, s.practiceLength.toString(), enabled = s.busy == null) { vm.dispatch(NewLessonContract.Intent.Length(it.toInt())) }
        }
        Gap(2)
        Card {
            SectionLabel("Content")
            Row(horizontalArrangement = Arrangement.spacedBy(AdminTokens.gutter / 2)) {
                SelectCard("Upload PDF or slides", "PDF · PPTX", s.source == NewLessonContract.Source.DOCUMENT, { vm.dispatch(NewLessonContract.Intent.SourcePick(NewLessonContract.Source.DOCUMENT)) }, size = AdminTokens.courseCard + AdminTokens.gutter, glyph = "📄", enabled = s.busy == null)
                SelectCard("Upload images", "Photos of a worksheet, a page, the board", s.source == NewLessonContract.Source.IMAGES, { vm.dispatch(NewLessonContract.Intent.SourcePick(NewLessonContract.Source.IMAGES)) }, size = AdminTokens.courseCard + AdminTokens.gutter, glyph = "🖼️", enabled = s.busy == null)
                SelectCard("Add questions manually", "Write the stops yourself", s.source == NewLessonContract.Source.MANUAL, { vm.dispatch(NewLessonContract.Intent.SourcePick(NewLessonContract.Source.MANUAL)) }, size = AdminTokens.courseCard + AdminTokens.gutter, glyph = "✏️", enabled = s.busy == null)
            }
            when (s.source) {
                null -> Unit
                NewLessonContract.Source.MANUAL -> { Gap(2); Text("You get an empty Level 1: add stops of any type, attach pictures, and either write Levels 2 and 3 too or type a short lesson text and let the model write them.", style = MaterialTheme.typography.bodyMedium, color = Palette.parentInkSoft) }
                else -> {
                    Gap(2)
                    val images = s.source == NewLessonContract.Source.IMAGES
                    DropZone(s.dragging, if (images) "Drop PNG or JPG photos here" else "Drop a PDF or PPTX here", "or", if (images) "Choose photos…" else "Choose a file…", enabled = s.busy == null) {
                        scope.launch {
                            val picked = FileKit.pickFile(type = PickerType.File(if (images) listOf("png", "jpg", "jpeg") else listOf("pdf", "pptx")), mode = PickerMode.Multiple()) ?: return@launch
                            vm.dispatch(NewLessonContract.Intent.AddFiles(picked.map { f -> UploadFile(f.name, mime(f.name), f.readBytes()) }))
                        }
                    }
                    if (s.files.isNotEmpty()) {
                        Gap(2)
                        s.files.forEachIndexed { i, f ->
                            Row(Modifier.fillMaxWidth().padding(vertical = AdminTokens.gutter / 6), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AdminTokens.gutter / 2)) {
                                Text(if (images) "🖼️" else "📄", style = MaterialTheme.typography.bodyLarge)
                                Text(f.fileName, style = MaterialTheme.typography.bodyMedium, color = Palette.parentInk, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                Text(size(f.bytes.size), style = MaterialTheme.typography.bodySmall, color = Palette.parentInkSoft)
                                LinkButton("Remove", { vm.dispatch(NewLessonContract.Intent.RemoveFile(i)) }, enabled = s.busy == null)
                            }
                        }
                        Text(if (images) "Each photo becomes one page. The same photos for the same course are never read twice." else "The same file for the same course is never read twice.", style = MaterialTheme.typography.bodySmall, color = Palette.parentInkSoft)
                    }
                }
            }
        }
    }
}

@Composable
fun DropZone(active: Boolean, title: String, or: String, pick: String, enabled: Boolean = true, onPick: () -> Unit) {
    Column(Modifier.fillMaxWidth().height(AdminTokens.dropZoneHeight).background(if (active) Palette.parentAccentSoft else Palette.parentBg).border(AdminTokens.rule, if (active) Palette.parentAccent else Palette.parentInk), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = Palette.parentInk, textAlign = TextAlign.Center)
        Text(or, style = MaterialTheme.typography.bodySmall, color = Palette.parentInkSoft, modifier = Modifier.padding(vertical = AdminTokens.gutter / 4))
        SecondaryButton(pick, onPick, enabled = enabled)
    }
}

fun mime(name: String) = when (name.substringAfterLast('.', "").lowercase()) { "pdf" -> "application/pdf"; "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"; "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; else -> "application/octet-stream" }
fun size(bytes: Int) = when { bytes >= 1_000_000 -> "${bytes / 100_000 / 10.0} MB"; bytes >= 1_000 -> "${bytes / 1000} KB"; else -> "$bytes B" }
