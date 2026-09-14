package quest.admin.feature.editor.presentation

import quest.admin.core.mvi.MviEffect
import quest.admin.core.mvi.MviIntent
import quest.admin.core.mvi.MviState
import quest.api.AdminLesson
import quest.api.ConfirmedSkill
import quest.api.UploadFile
import quest.api.dto.ParentPanel
import quest.api.dto.Stop

object LessonContract {
    /** One editable skill row on the confirm screen. */
    data class SkillRow(val id: String?, val name: String, val subject: quest.api.dto.Subject, val method: String, val confidence: Double, val candidates: List<String>, val question: String?, val keep: Boolean = true)

    data class State(
        val lessonId: String,
        val lesson: AdminLesson? = null,
        val loading: Boolean = true,
        val busy: String? = null,             // what the panel is waiting on ("Uploading…", "Saving…")
        val error: String? = null,
        val skills: List<SkillRow> = emptyList(),
        val tab: Int = 0,                     // 0..2 = levels, 3 = Again variant
        val selectedStopId: String? = null,
        val stopDraft: String = "",           // JSON of the selected stop being edited
        val stopErrors: List<String> = emptyList(),
        val panelDraft: ParentPanel? = null,
        val confirmPublish: Boolean = false,
    ) : MviState {
        val status: String get() = lesson?.status?.name?.lowercase() ?: ""
        val isJobRunning: Boolean get() = status == "analyzing" || status == "generating" || status == "uploading"
        val currentPlay get() = lesson?.plays?.firstOrNull { if (tab == 3) it.level == 1 && it.variant == 1 else it.level == tab + 1 && it.variant == 0 }
        val selectedStop: Stop? get() = currentPlay?.play?.stops?.firstOrNull { it.id == selectedStopId }
    }

    sealed interface Intent : MviIntent {
        data object Load : Intent
        data object Poll : Intent
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
        data class EditPanel(val panel: ParentPanel) : Intent
        data object SavePanel : Intent
        data class AskPublish(val show: Boolean) : Intent
        data object Publish : Intent
        data object Unpublish : Intent
        data object DismissError : Intent
    }
    sealed interface Effect : MviEffect { data class Toast(val text: String) : Effect }

    fun ConfirmedSkill.Companion.from(r: SkillRow) = ConfirmedSkill(r.id, r.name, r.subject, r.method.ifBlank { null })
}
