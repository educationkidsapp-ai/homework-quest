package quest.admin.feature.editor.presentation

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.vinceglb.filekit.core.FileKit
import io.github.vinceglb.filekit.core.PickerMode
import io.github.vinceglb.filekit.core.PickerType
import kotlinx.coroutines.launch
import quest.admin.core.design.AdminButton
import quest.admin.core.design.AdminOutlinedButton
import quest.admin.core.design.Card
import quest.admin.core.design.Choice
import quest.admin.core.design.ErrorBanner
import quest.admin.core.design.Field
import quest.admin.core.design.Loading
import quest.admin.core.design.Page
import quest.admin.core.design.SectionTitle
import quest.admin.core.design.StatusTag
import quest.admin.core.design.Tag
import quest.admin.core.design.tokens
import quest.admin.feature.editor.presentation.LessonContract.Intent
import quest.api.UploadFile
import quest.api.dto.Bilingual
import quest.api.dto.ModelAnswer
import quest.api.dto.ParentPanel
import quest.api.dto.Stop
import quest.api.dto.StopTip
import quest.api.dto.Subject
import quest.ui.design.ChildTheme
import quest.ui.design.Palette
import quest.ui.stops.StopContent

@Composable
fun LessonScreen(vm: LessonViewModel, onBack: () -> Unit) {
    val s by vm.state.collectAsState()
    var toast by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { vm.effects.collect { if (it is LessonContract.Effect.Toast) toast = it.text } }
    val lesson = s.lesson
    Page(lesson?.title ?: "Lesson", actions = {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (lesson != null) StatusTag(s.status)
            AdminOutlinedButton("← Lessons", onBack)
        }
    }) {
        if (lesson == null) { if (s.loading) Loading() else ErrorBanner(s.error); return@Page }
        ErrorBanner(s.error) { vm.dispatch(Intent.DismissError) }
        if (toast != null) { ErrorBanner(toast) { toast = null } }
        if (s.busy != null) Row(Modifier.fillMaxWidth().background(Palette.sand).padding(10.dp)) { Text("⏳ ${s.busy}", fontWeight = FontWeight.SemiBold) }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Tag("${lesson.course.curriculum.name.lowercase()} · grade ${lesson.course.grade}"); Tag(lesson.subject.name.lowercase()); Tag(lesson.date.toString())
            Tag("tokens ${lesson.tokenUsage.tokens()}"); if (lesson.tokensSaved > 0) Tag("♻ saved ${lesson.tokensSaved.tokens()}", Palette.mint)
            if (lesson.version > 0) Tag("v${lesson.version}")
        }
        Spacer(Modifier.height(20.dp))
        FilesSection(s, vm)
        if (s.status == "needs_review" || (s.skills.isNotEmpty() && lesson.plays.isEmpty() && !s.isJobRunning && s.status != "draft")) { Spacer(Modifier.height(20.dp)); SkillsSection(s, vm) }
        if (lesson.plays.isNotEmpty()) { Spacer(Modifier.height(20.dp)); ReviewSection(s, vm) }
        if (lesson.plays.isNotEmpty() && s.panelDraft != null) { Spacer(Modifier.height(20.dp)); PanelSection(s, vm) }
        if (lesson.plays.isNotEmpty()) { Spacer(Modifier.height(20.dp)); PublishSection(s, vm) }
    }
}

// ---------------------------------------------------------------- files
@Composable
private fun FilesSection(s: LessonContract.State, vm: LessonViewModel) {
    val scope = rememberCoroutineScope()
    val lesson = s.lesson!!
    Card {
        SectionTitle("1 · Slides")
        val active = lesson.files.filter { !it.deleted }
        if (lesson.files.isEmpty()) Text("No files yet. PDF, PPTX, PNG or JPEG — up to 10 files, 25 MB each.", color = Palette.parentInkSoft)
        lesson.files.forEach { f ->
            Row(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (f.deleted) "🗑 ${f.fileName}" else "📄 ${f.fileName}", color = if (f.deleted) Palette.parentInkSoft else Palette.parentInk)
                Text("${f.pageCount} pages", style = MaterialTheme.typography.bodySmall, color = Palette.parentInkSoft)
                if (f.cacheHit) Tag("♻ already analysed — no model call", Palette.mint)
                Text(f.fileHash.take(12), style = MaterialTheme.typography.bodySmall, color = Palette.parentInkSoft)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val canEdit = !s.isJobRunning && s.status != "published" && s.busy == null
            AdminOutlinedButton("Choose files…", {
                scope.launch {
                    val picked = FileKit.pickFile(type = PickerType.File(listOf("pdf", "pptx", "png", "jpg", "jpeg")), mode = PickerMode.Multiple()) ?: return@launch
                    val uploads = picked.map { f -> UploadFile(f.name, mime(f.name), f.readBytes()) }
                    if (uploads.isNotEmpty()) vm.dispatch(Intent.Upload(uploads))
                }
            }, enabled = canEdit)
            AdminButton(if (s.status == "analyzing") "Reading…" else "Read the slides (Prompt A)", { vm.dispatch(Intent.Analyze) }, enabled = canEdit && active.isNotEmpty())
            if (active.isNotEmpty()) AdminOutlinedButton("Delete uploaded files", { vm.dispatch(Intent.DeleteFiles) }, enabled = canEdit)
        }
        if (s.status == "analyzing" || s.status == "generating") { Spacer(Modifier.height(8.dp)); Text("This page refreshes itself every few seconds.", style = MaterialTheme.typography.bodySmall, color = Palette.parentInkSoft) }
        val a = lesson.analysis
        if (a != null) {
            Spacer(Modifier.height(16.dp))
            Text("What the model read", fontWeight = FontWeight.Bold)
            Text("${a.kind.name.lowercase()} · ${a.pages.size} pages · ${a.vocabulary.size} words · ${a.events.size} events · ${a.facts.size} facts", color = Palette.parentInkSoft)
            a.pages.take(6).forEach { p -> Text("p${p.number}: ${p.childText.joinToString(" ")}", style = MaterialTheme.typography.bodySmall, color = Palette.parentInkSoft) }
            Text("Objectives: " + a.objectives.en.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun mime(name: String) = when (name.substringAfterLast('.', "").lowercase()) { "pdf" -> "application/pdf"; "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"; "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; else -> "application/octet-stream" }

// ---------------------------------------------------------------- skills
@Composable
private fun SkillsSection(s: LessonContract.State, vm: LessonViewModel) {
    Card {
        SectionTitle("2 · Confirm the skills the levels will practise")
        Text("Edit names and methods, untick anything the slides don't really teach. Unsure skills show the model's question — tap a candidate to answer it.", color = Palette.parentInkSoft)
        Spacer(Modifier.height(12.dp))
        s.skills.forEachIndexed { i, r ->
            Column(Modifier.fillMaxWidth().border(1.dp, if (r.keep) Palette.parentLine else Palette.parentRule).padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Checkbox(r.keep, { vm.dispatch(Intent.EditSkill(i, keep = it)) })
                    OutlinedTextField(r.name, { vm.dispatch(Intent.EditSkill(i, name = it)) }, Modifier.weight(1f), label = { Text("Skill") }, singleLine = true)
                    Choice(listOf("math" to "Math", "english" to "English"), r.subject.name.lowercase()) { vm.dispatch(Intent.EditSkill(i, subject = Subject.valueOf(it.uppercase()))) }
                    if (r.confidence < 0.7) Tag("unsure", Palette.sun) else Tag("${(r.confidence * 100).toInt()}%", Palette.parentRule)
                }
                OutlinedTextField(r.method, { vm.dispatch(Intent.EditSkill(i, method = it)) }, Modifier.fillMaxWidth(), label = { Text("Method the slides use") }, singleLine = true)
                if (r.question != null) {
                    Spacer(Modifier.height(6.dp)); Text("❓ ${r.question}", fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { r.candidates.forEach { c -> AdminOutlinedButton(c, { vm.dispatch(Intent.EditSkill(i, name = c)) }) } }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AdminOutlinedButton("+ Add a skill", { vm.dispatch(Intent.AddSkill) })
            AdminButton(if (s.status == "generating") "Writing levels…" else "Confirm and write the three levels (Prompt B + C)", { vm.dispatch(Intent.ConfirmSkills) }, enabled = !s.isJobRunning && s.busy == null)
        }
    }
}

// ---------------------------------------------------------------- review
@Composable
private fun ReviewSection(s: LessonContract.State, vm: LessonViewModel) {
    val lesson = s.lesson!!
    Card {
        SectionTitle("3 · Review the levels")
        Choice(listOf("0" to "Level 1 · Same as the book", "1" to "Level 2 · Think", "2" to "Level 3 · Challenge", "3" to "Level 1 · Again"), s.tab.toString()) { vm.dispatch(Intent.Tab(it.toInt())) }
        Spacer(Modifier.height(12.dp))
        val play = s.currentPlay
        if (play == null) { Text("This level hasn't been written yet.", color = Palette.parentInkSoft); return@Card }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${play.play.theme.potEmoji} ${play.play.theme.dishName} · ${play.play.stops.size} stops · prompt ${play.promptVersion}", color = Palette.parentInkSoft)
            AdminOutlinedButton("Rewrite this whole level", { vm.dispatch(Intent.RegeneratePlay) }, enabled = s.busy == null && s.status != "published")
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            // stop list
            Column(Modifier.width(260.dp)) {
                play.play.stops.forEachIndexed { i, stop ->
                    val on = stop.id == s.selectedStopId
                    Column(Modifier.fillMaxWidth().background(if (on) Palette.parentAccentSoft else Color.Transparent).border(1.dp, Palette.parentRule).clickable { vm.dispatch(Intent.SelectStop(stop.id)) }.padding(8.dp)) {
                        Text("${i + 1}. ${stop.title}", fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text("${stop.type} · ${stop.ingredient.emoji} ${stop.ingredient.name}", style = MaterialTheme.typography.bodySmall, color = Palette.parentInkSoft)
                        if (stop is Stop.ExitTicket) Text(stop.questions.joinToString(", ") { it.type }, style = MaterialTheme.typography.bodySmall, color = Palette.parentInkSoft)
                    }
                }
            }
            // editor
            Column(Modifier.weight(1f)) {
                val stop = s.selectedStop
                if (stop == null) Text("Select a stop to edit it. Every child-facing string is spoken aloud, so keep them short.", color = Palette.parentInkSoft)
                else {
                    Field(stop.title, { vm.dispatch(Intent.PatchStop("title", it)) }, "Title (≤ 40)")
                    Field(stop.speak, { vm.dispatch(Intent.PatchStop("speak", it)) }, "What Pip says (≤ 90)")
                    if (stop is Stop.SingleAnswer) Field(stop.hint, { vm.dispatch(Intent.PatchStop("hint", it)) }, "Hint after a wrong answer (points to the method)")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Field(stop.parentTip.en, { vm.dispatch(Intent.PatchStop("parentTip.en", it)) }, "Parent tip (EN)", Modifier.weight(1f))
                        Field(stop.parentTip.ar, { vm.dispatch(Intent.PatchStop("parentTip.ar", it)) }, "Parent tip (AR)", Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Full stop JSON (options, answers, cues…) — validated against the shared schema as you type", style = MaterialTheme.typography.bodySmall, color = Palette.parentInkSoft)
                    OutlinedTextField(s.stopDraft, { vm.dispatch(Intent.EditStopJson(it)) }, Modifier.fillMaxWidth().height(320.dp), textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp))
                    s.stopErrors.forEach { Text("• $it", color = Palette.parentAccent, style = MaterialTheme.typography.bodySmall) }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        AdminButton("Save stop", { vm.dispatch(Intent.SaveStop) }, enabled = s.stopErrors.isEmpty() && s.busy == null)
                        AdminOutlinedButton("Regenerate this stop", { vm.dispatch(Intent.RegenerateStop) }, enabled = s.busy == null)
                    }
                }
            }
            // phone preview
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Phone preview", style = MaterialTheme.typography.bodySmall, color = Palette.parentInkSoft)
                Box(Modifier.size(360.dp, 720.dp).border(8.dp, Palette.night).clip(androidx.compose.foundation.shape.RoundedCornerShape(24.dp))) {
                    val preview = runCatching { quest.api.validation.SchemaValidator.json.decodeFromString(Stop.serializer(), s.stopDraft) }.getOrNull() ?: s.selectedStop
                    if (preview != null) ChildTheme { StopContent(preview, onEvent = {}, childName = "Pip") }
                    else Box(Modifier.background(Palette.sky).fillMaxWidth().height(720.dp), contentAlignment = Alignment.Center) { Text("Pick a stop", color = Palette.inkSoft) }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- parent panel
@Composable
private fun PanelSection(s: LessonContract.State, vm: LessonViewModel) {
    val p = s.panelDraft!!
    fun update(np: ParentPanel) = vm.dispatch(Intent.EditPanel(np))
    Card {
        SectionTitle("4 · Parent panel (EN + AR)")
        Text("Objectives", fontWeight = FontWeight.Bold)
        p.objectives.en.indices.forEach { i ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(p.objectives.en[i], { v -> update(p.copy(objectives = p.objectives.copy(en = p.objectives.en.toMutableList().also { it[i] = v }))) }, "EN ${i + 1}", Modifier.weight(1f))
                Field(p.objectives.ar.getOrElse(i) { "" }, { v -> update(p.copy(objectives = p.objectives.copy(ar = p.objectives.ar.toMutableList().also { while (it.size <= i) it += ""; it[i] = v }))) }, "AR ${i + 1}", Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(8.dp))
        BilingualList("How to help (supported)", p.supported) { update(p.copy(supported = it)) }
        BilingualList("Stretch ideas (challenge)", p.challenge) { update(p.copy(challenge = it)) }
        Text("Stop tips", fontWeight = FontWeight.Bold)
        p.stopTips.forEachIndexed { i, t ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(t.stopId.substringAfterLast(':'), Modifier.width(90.dp), style = MaterialTheme.typography.bodySmall)
                Field(t.en, { v -> update(p.copy(stopTips = p.stopTips.toMutableList().also { it[i] = StopTip(t.stopId, v, t.ar) })) }, "EN", Modifier.weight(1f))
                Field(t.ar, { v -> update(p.copy(stopTips = p.stopTips.toMutableList().also { it[i] = StopTip(t.stopId, t.en, v) })) }, "AR", Modifier.weight(1f))
            }
        }
        Text("Model answers (open stops)", fontWeight = FontWeight.Bold)
        p.modelAnswers.forEachIndexed { i, m ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(m.stopId.substringAfterLast(':'), Modifier.width(90.dp), style = MaterialTheme.typography.bodySmall)
                Field(m.en, { v -> update(p.copy(modelAnswers = p.modelAnswers.toMutableList().also { it[i] = ModelAnswer(m.stopId, v) })) }, "A good answer sounds like…", Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(10.dp))
        AdminButton("Save parent panel", { vm.dispatch(Intent.SavePanel) }, enabled = s.busy == null)
    }
}

@Composable
private fun BilingualList(title: String, items: List<Bilingual>, onChange: (List<Bilingual>) -> Unit) {
    Text(title, fontWeight = FontWeight.Bold)
    items.forEachIndexed { i, b ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(b.en, { v -> onChange(items.toMutableList().also { it[i] = Bilingual(v, b.ar) }) }, "EN", Modifier.weight(1f))
            Field(b.ar, { v -> onChange(items.toMutableList().also { it[i] = Bilingual(b.en, v) }) }, "AR", Modifier.weight(1f))
        }
    }
    Spacer(Modifier.height(8.dp))
}

// ---------------------------------------------------------------- publish
@Composable
private fun PublishSection(s: LessonContract.State, vm: LessonViewModel) {
    val lesson = s.lesson!!
    Card {
        SectionTitle("5 · Publish")
        val ready = lesson.plays.count { it.variant == 0 } == 3 && lesson.plays.any { it.variant == 1 } && lesson.parentPanel != null
        Text(if (ready) "Three levels, the Again variant and the parent panel are ready." else "Needs all three levels, the Again variant and the parent panel.", color = Palette.parentInkSoft)
        Spacer(Modifier.height(10.dp))
        if (s.status == "published") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Tag("Published v${lesson.version}", Palette.mint)
                AdminOutlinedButton("Unpublish", { vm.dispatch(Intent.Unpublish) }, enabled = s.busy == null)
            }
            Text("Edits after publishing put the lesson back in review; publishing again makes a new version (children re-download it).", style = MaterialTheme.typography.bodySmall, color = Palette.parentInkSoft)
        } else if (!s.confirmPublish) AdminButton("Publish to every child on ${lesson.course.curriculum.name.lowercase()} grade ${lesson.course.grade}", { vm.dispatch(Intent.AskPublish(true)) }, enabled = ready && s.status == "review" && s.busy == null)
        else Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Publish for ${lesson.date}? The island appears on every child's map that day.", fontWeight = FontWeight.SemiBold)
            AdminButton("Yes, publish", { vm.dispatch(Intent.Publish) }, enabled = s.busy == null)
            AdminOutlinedButton("Cancel", { vm.dispatch(Intent.AskPublish(false)) })
        }
    }
}
