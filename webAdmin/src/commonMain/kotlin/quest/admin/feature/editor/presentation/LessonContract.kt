package quest.admin.feature.editor.presentation

import quest.admin.core.mvi.MviEffect
import quest.admin.core.mvi.MviIntent
import quest.admin.core.mvi.MviState
import quest.api.AdminLesson
import quest.api.ConfirmedSkill
import quest.api.LessonSource
import quest.api.UploadFile
import quest.api.dto.ParentPanel
import quest.api.dto.Stop

object LessonContract {
    /** One editable skill row on the confirm screen. */
    data class SkillRow(val id: String?, val name: String, val subject: quest.api.dto.Subject, val method: String, val confidence: Double, val candidates: List<String>, val question: String?, val keep: Boolean = true)

    /** Which step of the lesson the admin is looking at. */
    enum class Step { FILES, SKILLS, PLAYS, PANEL }

    data class State(
        val lessonId: String,
        val lesson: AdminLesson? = null,
        val loading: Boolean = true,
        val busy: String? = null,             // what the panel is waiting on ("Uploading…", "Saving…")
        val error: String? = null,
        val notice: String? = null,           // quiet confirmation band
        val step: Step? = null,               // null = follow the lesson's status
        val skills: List<SkillRow> = emptyList(),
        val tab: Int = 0,                     // 0..2 = levels, 3 = Again variant
        val selectedStopId: String? = null,
        val stopDraft: String = "",           // JSON of the selected stop being edited
        val stopErrors: List<String> = emptyList(),
        val addMenu: Boolean = false,
        val panelDraft: ParentPanel? = null,
        val confirmPublish: Boolean = false,
        val lessonText: String = "",          // "Generate the other levels" input (manual lessons)
        val dragging: Boolean = false,        // files hovering the window (image attach)
    ) : MviState {
        val status: String get() = lesson?.status?.name?.lowercase() ?: ""
        val isJobRunning: Boolean get() = status == "analyzing" || status == "generating" || status == "uploading"
        val manual: Boolean get() = lesson?.source == LessonSource.MANUAL
        val currentPlay get() = lesson?.plays?.firstOrNull { if (tab == 3) it.level == 1 && it.variant == 1 else it.level == tab + 1 && it.variant == 0 }
        val selectedStop: Stop? get() = currentPlay?.play?.stops?.firstOrNull { it.id == selectedStopId }
        val editable: Boolean get() = !isJobRunning && busy == null && (status == "review" || status == "published" || status == "error" || status == "paused")
        val failedStep: quest.api.LessonStepInfo? get() = lesson?.steps?.firstOrNull { it.status == quest.api.StepStatus.ERROR }
        val hasPipeline: Boolean get() = lesson?.steps?.isNotEmpty() == true
        /** The step shown: the admin's choice, else the furthest the lesson has reached. */
        val shownStep: Step get() = step ?: when {
            lesson == null -> Step.FILES
            lesson.plays.isNotEmpty() -> Step.PLAYS
            status == "needs_review" || skills.isNotEmpty() -> Step.SKILLS
            else -> Step.FILES
        }
        val publishReady: Boolean get() = lesson != null && (if (manual) lesson.plays.any { it.level == 1 && it.variant == 0 && it.play.stops.isNotEmpty() } else lesson.plays.count { it.variant == 0 } == 3 && lesson.plays.any { it.variant == 1 } && lesson.parentPanel != null)
    }

    sealed interface Intent : MviIntent {
        data object Load : Intent
        data object Poll : Intent
        data class GoTo(val step: Step) : Intent
        data class Upload(val files: List<UploadFile>) : Intent
        data object Analyze : Intent
        data object DeleteFiles : Intent
        data class EditSkill(val index: Int, val name: String? = null, val method: String? = null, val subject: quest.api.dto.Subject? = null, val keep: Boolean? = null) : Intent
        data object AddSkill : Intent
        data object ConfirmSkills : Intent
        data class Tab(val tab: Int) : Intent
        data class SelectStop(val stopId: String?) : Intent
        data class EditStopJson(val json: String) : Intent
        data class PatchStop(val field: String, val value: String) : Intent
        data object SaveStop : Intent
        data object RegenerateStop : Intent
        data object RegeneratePlay : Intent
        // manual authoring
        data class ShowAddMenu(val show: Boolean) : Intent
        data class AddStop(val type: String) : Intent
        data object DeleteStop : Intent
        data class MoveStop(val stopId: String, val delta: Int) : Intent
        data class CreateLevel(val level: Int, val variant: Int = 0) : Intent
        data class AttachImage(val file: UploadFile) : Intent
        data object DetachImage : Intent
        data class LessonText(val v: String) : Intent
        data object GenerateOthers : Intent
        data class Dragging(val v: Boolean) : Intent
        // panel + publish
        data class EditPanel(val panel: ParentPanel) : Intent
        data object SavePanel : Intent
        data class AskPublish(val show: Boolean) : Intent
        data object Publish : Intent
        data object Unpublish : Intent
        data object DismissError : Intent
        data object DismissNotice : Intent
        data object RetryContinue : Intent
        data class RetryStep(val step: quest.api.PipelineStep) : Intent
        data object ReplaceFile : Intent
    }
    sealed interface Effect : MviEffect

    fun ConfirmedSkill.Companion.from(r: SkillRow) = ConfirmedSkill(r.id, r.name, r.subject, r.method.ifBlank { null })
}
