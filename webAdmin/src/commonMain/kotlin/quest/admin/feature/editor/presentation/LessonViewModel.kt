package quest.admin.feature.editor.presentation

import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import quest.admin.core.mvi.MviViewModel
import quest.admin.feature.editor.presentation.LessonContract.Effect
import quest.admin.feature.editor.presentation.LessonContract.Intent
import quest.admin.feature.editor.presentation.LessonContract.SkillRow
import quest.admin.feature.editor.presentation.LessonContract.State
import quest.admin.feature.editor.presentation.LessonContract.from
import quest.api.AdminApi
import quest.api.AdminLesson
import quest.api.ApiException
import quest.api.ConfirmedSkill
import quest.api.dto.Play
import quest.api.dto.Stop
import quest.api.dto.Subject
import quest.api.validation.SchemaValidator

/** One lesson through its whole life: files → analysis → skills → levels → panel → publish (or written by hand). */
class LessonViewModel(lessonId: String, private val api: AdminApi) : MviViewModel<State, Intent, Effect>(State(lessonId)) {
    private val pretty = Json(SchemaValidator.json) { prettyPrint = true }

    init { dispatch(Intent.Load) }

    override suspend fun handle(intent: Intent) {
        when (intent) {
            Intent.Load -> { reduce { copy(loading = true) }; refresh(); reduce { copy(loading = false) }; if (current.isJobRunning) launch { delay(2500); dispatch(Intent.Poll) } }
            Intent.Poll -> { if (current.isJobRunning) { refresh(); launch { delay(2500); dispatch(Intent.Poll) } } }
            is Intent.GoTo -> reduce { copy(step = intent.step) }
            is Intent.Upload -> run("Uploading…") { api.uploadFiles(current.lessonId, intent.files); refresh() }
            Intent.Analyze -> run("Reading the pages…") { api.analyze(current.lessonId); refresh(); reduce { copy(step = null) }; dispatch(Intent.Poll) }
            Intent.DeleteFiles -> run("Deleting…") { api.deleteFiles(current.lessonId); refresh() }
            is Intent.EditSkill -> reduce { copy(skills = skills.mapIndexed { i, r -> if (i != intent.index) r else r.copy(name = intent.name ?: r.name, method = intent.method ?: r.method, subject = intent.subject ?: r.subject, keep = intent.keep ?: r.keep) }) }
            Intent.AddSkill -> reduce { copy(skills = skills + SkillRow(null, "", lesson?.subject ?: Subject.ENGLISH, "", 1.0, emptyList(), null)) }
            Intent.ConfirmSkills -> {
                val kept = current.skills.filter { it.keep && it.name.isNotBlank() }
                if (kept.isEmpty()) reduce { copy(error = "Keep at least one skill.") }
                else run("Writing the three levels…") { api.confirmSkills(current.lessonId, kept.map { ConfirmedSkill.from(it) }); refresh(); reduce { copy(step = null) }; dispatch(Intent.Poll) }
            }
            is Intent.Tab -> reduce { copy(tab = intent.tab, selectedStopId = null, stopDraft = "", stopErrors = emptyList(), addMenu = false) }
            is Intent.SelectStop -> select(intent.stopId)
            is Intent.EditStopJson -> reduce { copy(stopDraft = intent.json, stopErrors = validateStop(intent.json)) }
            is Intent.PatchStop -> patch(intent.field, if (intent.value.isEmpty() && intent.field == "imageId") null else intent.value)
            Intent.SaveStop -> saveStop()
            Intent.RegenerateStop -> { val id = current.selectedStopId ?: return; run("Asking the model for a new version…") { val stop = api.regenerateStop(id); refresh(); select(stop.id) } }
            Intent.RegeneratePlay -> { val play = current.currentPlay ?: return; run("Rewriting this level…") { api.regeneratePlay(play.id); refresh(); reduce { copy(selectedStopId = null, stopDraft = "") } } }
            // ---- manual authoring
            is Intent.ShowAddMenu -> reduce { copy(addMenu = intent.show) }
            is Intent.AddStop -> {
                val play = current.currentPlay ?: return
                val tpl = StopTemplates.byType(intent.type) ?: return
                reduce { copy(addMenu = false) }
                run("Adding a ${tpl.label.lowercase()} stop…") {
                    val stop = api.addStop(play.id, tpl.make("", current.lesson?.subject == Subject.MATH))
                    refresh(); select(stop.id)
                }
            }
            Intent.DeleteStop -> { val id = current.selectedStopId ?: return; run("Removing the stop…") { api.deleteStop(id); refresh(); reduce { copy(selectedStopId = null, stopDraft = "", stopErrors = emptyList()) } } }
            is Intent.MoveStop -> {
                val play = current.currentPlay ?: return
                val ids = play.play.stops.map { it.id }.toMutableList()
                val i = ids.indexOf(intent.stopId); val j = i + intent.delta
                if (i < 0 || j < 0 || j >= ids.size) return
                ids[i] = ids[j].also { ids[j] = ids[i] }
                run("Reordering…") { api.reorderStops(play.id, ids); refresh() }
            }
            is Intent.CreateLevel -> run("Creating the level…") { api.createPlay(current.lessonId, intent.level, intent.variant); refresh() }
            is Intent.AttachImage -> {
                if (current.selectedStopId == null) return
                run("Uploading the picture…") {
                    val img = api.uploadImage(current.lessonId, intent.file)
                    refresh()
                    patch("imageId", img.id)
                    saveStop()
                }
            }
            Intent.DetachImage -> { patch("imageId", null); saveStop() }
            is Intent.LessonText -> reduce { copy(lessonText = intent.v) }
            Intent.GenerateOthers -> run("Reading your text and writing the other levels…") { api.generateFromText(current.lessonId, current.lessonText); refresh(); dispatch(Intent.Poll) }
            is Intent.Dragging -> reduce { copy(dragging = intent.v) }
            // ---- panel + publish
            is Intent.EditPanel -> reduce { copy(panelDraft = intent.panel) }
            Intent.SavePanel -> { val p = current.panelDraft ?: return; run("Saving…") { api.updateParentPanel(current.lessonId, p); refresh(); reduce { copy(notice = "Parent panel saved.") } } }
            is Intent.AskPublish -> reduce { copy(confirmPublish = intent.show) }
            Intent.Publish -> run("Publishing…") { apply(api.publish(current.lessonId)); reduce { copy(confirmPublish = false, notice = "Published — every child on the course sees the island on its day.") } }
            Intent.Unpublish -> run("Unpublishing…") { apply(api.unpublish(current.lessonId)) }
            Intent.DismissError -> reduce { copy(error = null) }
            Intent.DismissNotice -> reduce { copy(notice = null) }
        }
    }

    private fun select(stopId: String?) = reduce { val stop = currentPlay?.play?.stops?.firstOrNull { it.id == stopId }; copy(selectedStopId = stopId, stopDraft = stop?.let { pretty.encodeToString(Stop.serializer(), it) } ?: "", stopErrors = emptyList(), addMenu = false) }

    private fun patch(field: String, value: String?) {
        val patched = runCatching {
            val obj = SchemaValidator.json.parseToJsonElement(current.stopDraft).jsonObject
            val updated: JsonObject = when (field) {
                "parentTip.en", "parentTip.ar" -> buildJsonObject { obj.forEach { (k, v) -> put(k, v) }; put("parentTip", buildJsonObject { (obj["parentTip"]?.jsonObject ?: JsonObject(emptyMap())).forEach { (k, v) -> put(k, v) }; put(field.substringAfter('.'), JsonPrimitive(value ?: "")) }) }
                else -> buildJsonObject { obj.forEach { (k, v) -> if (k != field) put(k, v) }; if (value != null) put(field, JsonPrimitive(value)) }
            }
            pretty.encodeToString(JsonObject.serializer(), updated)
        }.getOrNull() ?: return
        reduce { copy(stopDraft = patched, stopErrors = validateStop(patched)) }
    }

    private suspend fun saveStop() {
        val errors = validateStop(current.stopDraft)
        if (errors.isNotEmpty()) { reduce { copy(stopErrors = errors) }; return }
        run("Saving…") { val stop = SchemaValidator.json.decodeFromString(Stop.serializer(), current.stopDraft); api.updateStop(stop.id, stop); refresh(); reduce { copy(notice = "Stop saved.") } }
    }

    private suspend fun run(label: String, block: suspend () -> Unit) {
        reduce { copy(busy = label, error = null, notice = null) }
        try { block() } catch (e: ApiException) { reduce { copy(error = e.error.message) } } catch (e: Exception) { reduce { copy(error = e.message ?: "Something went wrong") } }
        reduce { copy(busy = null) }
    }

    private suspend fun refresh() {
        try { apply(api.lesson(current.lessonId)) } catch (e: ApiException) { reduce { copy(error = e.error.message) } }
    }

    private fun apply(lesson: AdminLesson) = reduce {
        val rows = if (skills.isNotEmpty() && lesson.status == this.lesson?.status) skills else lesson.skills.map { s -> SkillRow(s.id, s.name, s.subject, s.method, s.confidence, s.unsure?.candidates.orEmpty(), s.unsure?.question) }
        copy(lesson = lesson, skills = rows, panelDraft = panelDraft ?: lesson.parentPanel, error = lesson.error?.message ?: error)
    }

    private fun validateStop(json: String): List<String> = runCatching {
        val stop = SchemaValidator.json.decodeFromString(Stop.serializer(), json)
        val play = current.currentPlay?.play ?: return listOf("no play")
        val trial = Play(play.level, play.variant, play.kind, play.theme, play.stops.map { if (it.id == current.selectedStopId) stop else it }, play.id)
        if (stop.id != current.selectedStopId) listOf("the stop id can't change") else SchemaValidator.validate(trial, play.level, lenient = current.manual).errors
    }.getOrElse { listOf("not valid JSON: ${it.message?.lineSequence()?.firstOrNull()}") }
}
