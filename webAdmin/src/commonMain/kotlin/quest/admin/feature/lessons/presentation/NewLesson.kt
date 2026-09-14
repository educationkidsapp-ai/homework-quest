package quest.admin.feature.lessons.presentation

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.datetime.LocalDate
import quest.admin.core.design.AdminButton
import quest.admin.core.design.Card
import quest.admin.core.design.Choice
import quest.admin.core.design.ErrorBanner
import quest.admin.core.design.Field
import quest.admin.core.design.Page
import quest.admin.core.design.SectionTitle
import quest.admin.core.mvi.MviEffect
import quest.admin.core.mvi.MviIntent
import quest.admin.core.mvi.MviState
import quest.admin.core.mvi.MviViewModel
import quest.api.AdminApi
import quest.api.ApiException
import quest.api.CreateLessonRequest
import quest.api.dto.Curriculum
import quest.api.dto.Subject
import quest.ui.design.Palette

object NewLessonContract {
    data class State(val curriculum: Curriculum = Curriculum.BRITISH, val grade: Int = 1, val subject: Subject = Subject.ENGLISH, val date: String = "", val notes: String = "", val practiceLength: Int = 7, val busy: Boolean = false, val error: String? = null) : MviState {
        val dateError: String? get() = if (date.isBlank() || runCatching { LocalDate.parse(date) }.isSuccess) null else "Use YYYY-MM-DD"
    }
    sealed interface Intent : MviIntent {
        data class Curriculum(val v: quest.api.dto.Curriculum) : Intent; data class Grade(val v: Int) : Intent; data class Subject(val v: quest.api.dto.Subject) : Intent
        data class Date(val v: String) : Intent; data class Notes(val v: String) : Intent; data class Length(val v: Int) : Intent; data object Create : Intent
    }
    sealed interface Effect : MviEffect { data class Created(val lessonId: String) : Effect }
}

class NewLessonViewModel(private val api: AdminApi, today: LocalDate) : MviViewModel<NewLessonContract.State, NewLessonContract.Intent, NewLessonContract.Effect>(NewLessonContract.State(date = today.toString())) {
    override suspend fun handle(intent: NewLessonContract.Intent) = when (intent) {
        is NewLessonContract.Intent.Curriculum -> reduce { copy(curriculum = intent.v) }
        is NewLessonContract.Intent.Grade -> reduce { copy(grade = intent.v) }
        is NewLessonContract.Intent.Subject -> reduce { copy(subject = intent.v) }
        is NewLessonContract.Intent.Date -> reduce { copy(date = intent.v.trim()) }
        is NewLessonContract.Intent.Notes -> reduce { copy(notes = intent.v) }
        is NewLessonContract.Intent.Length -> reduce { copy(practiceLength = intent.v) }
        NewLessonContract.Intent.Create -> {
            val date = runCatching { LocalDate.parse(current.date) }.getOrNull()
            if (date == null) reduce { copy(error = "Pick a valid date.") } else {
                reduce { copy(busy = true, error = null) }
                try { val l = api.createLesson(CreateLessonRequest(current.curriculum, current.grade, current.subject, date, current.notes.ifBlank { null }, current.practiceLength)); reduce { copy(busy = false) }; effect(NewLessonContract.Effect.Created(l.id)) }
                catch (e: ApiException) { reduce { copy(busy = false, error = e.error.message) } }
            }
        }
    }
}

@Composable
fun NewLessonScreen(vm: NewLessonViewModel, onCreated: (String) -> Unit) {
    val s by vm.state.collectAsState()
    LaunchedEffect(Unit) { vm.effects.collect { if (it is NewLessonContract.Effect.Created) onCreated(it.lessonId) } }
    Page("New lesson") {
        Card(Modifier.width(720.dp)) {
            ErrorBanner(s.error)
            SectionTitle("Course")
            Row { Choice(listOf("british" to "British", "american" to "American"), s.curriculum.name.lowercase()) { vm.dispatch(NewLessonContract.Intent.Curriculum(Curriculum.valueOf(it.uppercase()))) }; Spacer(Modifier.width(16.dp))
                Choice(listOf("1" to "Grade 1", "2" to "Grade 2", "3" to "Grade 3"), s.grade.toString()) { vm.dispatch(NewLessonContract.Intent.Grade(it.toInt())) } }
            Spacer(Modifier.height(16.dp)); SectionTitle("Subject")
            Choice(listOf("math" to "Math", "english" to "English"), s.subject.name.lowercase()) { vm.dispatch(NewLessonContract.Intent.Subject(Subject.valueOf(it.uppercase()))) }
            Spacer(Modifier.height(16.dp))
            Field(s.date, { vm.dispatch(NewLessonContract.Intent.Date(it)) }, "Lesson date (YYYY-MM-DD) — the day its island appears", error = s.dateError)
            Spacer(Modifier.height(8.dp))
            Field(s.notes, { vm.dispatch(NewLessonContract.Intent.Notes(it)) }, "Notes for the model (optional): what to focus on, what to skip", singleLine = false, minLines = 3)
            Spacer(Modifier.height(16.dp)); SectionTitle("Practice length: ${s.practiceLength} stops per level")
            Choice((5..12).map { it.toString() to it.toString() }, s.practiceLength.toString()) { vm.dispatch(NewLessonContract.Intent.Length(it.toInt())) }
            Spacer(Modifier.height(24.dp))
            AdminButton(if (s.busy) "Creating…" else "Create and upload slides →", { vm.dispatch(NewLessonContract.Intent.Create) }, enabled = !s.busy && s.dateError == null)
            Spacer(Modifier.height(8.dp))
            Text("Slides are analysed once per file hash + course; the same slides never cost a second model call.", color = Palette.parentInkSoft)
        }
    }
}
