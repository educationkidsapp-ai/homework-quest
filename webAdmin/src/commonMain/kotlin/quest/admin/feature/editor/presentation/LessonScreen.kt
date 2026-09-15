package quest.admin.feature.editor.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import io.github.vinceglb.filekit.core.FileKit
import io.github.vinceglb.filekit.core.PickerMode
import io.github.vinceglb.filekit.core.PickerType
import kotlinx.coroutines.launch
import quest.admin.core.design.AdminButton
import quest.admin.core.design.Arrow
import quest.admin.core.design.BusyBar
import quest.admin.core.design.ButtonKind
import quest.admin.core.design.Card
import quest.admin.core.design.Choice
import quest.admin.core.design.DestructiveButton
import quest.admin.core.design.EmptyState
import quest.admin.core.design.ErrorBanner
import quest.admin.core.design.Field
import quest.admin.core.design.Gap
import quest.admin.core.design.LinkButton
import quest.admin.core.design.Loading
import quest.admin.core.design.NoticeBand
import quest.admin.core.design.Page
import quest.admin.core.design.Rule
import quest.admin.core.design.SecondaryButton
import quest.admin.core.design.SectionLabel
import quest.admin.core.design.Tag
import quest.admin.core.design.cardTitleStyle
import quest.admin.core.design.labelStyle
import quest.admin.core.design.statusWord
import quest.admin.core.design.tokens
import quest.admin.core.platform.Browser
import quest.admin.di.Graph
import quest.admin.feature.editor.presentation.LessonContract.Intent
import quest.admin.feature.editor.presentation.LessonContract.Step
import quest.admin.feature.lessons.presentation.DropZone
import quest.admin.feature.lessons.presentation.mime
import quest.api.LessonSource
import quest.api.UploadFile
import quest.api.dto.Bilingual
import quest.api.dto.ModelAnswer
import quest.api.dto.ParentPanel
import quest.api.dto.Stop
import quest.api.dto.StopTip
import quest.api.dto.Subject
import quest.ui.design.AdminTokens
import quest.ui.design.ChildTheme
import quest.ui.design.Palette
import quest.ui.stops.LocalStopImageLoader
import quest.ui.stops.StopContent
import quest.ui.stops.StopImageLoader

@Composable
fun LessonScreen(vm: LessonViewModel, onBack: () -> Unit) {
    val s by vm.state.collectAsState()
    val lesson = s.lesson
    val course = lesson?.let { "${it.course.curriculum.name.lowercase().replaceFirstChar { c -> c.uppercase() }} · Grade ${it.course.grade}" }
    val stepLabel = if (s.manual) when (s.shownStep) { Step.PLAYS -> "1 · Write the plays"; Step.PANEL -> "2 · Parent panel"; else -> "" }
        else when (s.shownStep) { Step.FILES -> "1 · Files"; Step.SKILLS -> "2 · Skills"; Step.PLAYS -> "3 · Review plays"; Step.PANEL -> "4 · Parent panel" }
    Page(lesson?.title ?: "Lesson", description = lesson?.let { "${it.subject.name.lowercase().replaceFirstChar { c -> c.uppercase() }} · ${it.date} · ${it.source.name.lowercase()} · ${statusWord(s.status)}${if (it.version > 0) " v${it.version}" else ""} · tokens ${it.tokenUsage.tokens()}${if (it.tokensSaved > 0) " (saved ${it.tokensSaved.tokens()})" else ""}" },
        breadcrumb = listOfNotNull(course, stepLabel.ifBlank { null }).joinToString("  ›  "),
        actions = { SecondaryButton("← Lessons", onBack) },
        footer = if (lesson == null) null else { { Footer(s, vm) } },
        scroll = lesson == null || s.shownStep != Step.PLAYS) {
        if (lesson == null) { if (s.loading) Loading("Loading the lesson…") else ErrorBanner(s.error); return@Page }
        ErrorBanner(s.error) { vm.dispatch(Intent.DismissError) }
        if (s.notice != null) NoticeBand(s.notice!!) { vm.dispatch(Intent.DismissNotice) }
        BusyBar(s.busy ?: if (s.isJobRunning) "${statusWord(s.status)} This page refreshes itself every few seconds." else null)
        Steps(s, vm)
        Gap(2)
        when (s.shownStep) {
            Step.FILES -> FilesStep(s, vm)
            Step.SKILLS -> SkillsStep(s, vm)
            Step.PLAYS -> PlaysStep(s, vm)   // fills the rest: list + editor scroll on the left, the phone is pinned on the right
            Step.PANEL -> PanelStep(s, vm)
        }
    }
}

@Composable
private fun Steps(s: LessonContract.State, vm: LessonViewModel) {
    val lesson = s.lesson!!
    val available = buildList {
        if (!s.manual) add(Step.FILES to "1 · Files")
        if (!s.manual && (s.skills.isNotEmpty() || s.status == "needs_review")) add(Step.SKILLS to "2 · Skills")
        if (lesson.plays.isNotEmpty()) add(Step.PLAYS to if (s.manual) "1 · Write the plays" else "3 · Review plays")
        if (lesson.plays.isNotEmpty() && (lesson.parentPanel != null || s.manual)) add(Step.PANEL to if (s.manual) "2 · Parent panel" else "4 · Parent panel")
    }
    Choice(available.map { it.first.name to it.second }, s.shownStep.name) { vm.dispatch(Intent.GoTo(Step.valueOf(it))) }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Footer(s: LessonContract.State, vm: LessonViewModel) {
    val lesson = s.lesson!!
    val course = "${lesson.course.curriculum.name.lowercase()} grade ${lesson.course.grade}"
    when {
        s.status == "published" -> {
            Text("Published v${lesson.version} — every child on $course sees it on ${lesson.date}. Editing puts it back in draft.", style = MaterialTheme.typography.bodyMedium, color = Palette.parentInkSoft, modifier = Modifier.weight(1f))
            DestructiveButton("Unpublish", { vm.dispatch(Intent.Unpublish) }, enabled = s.busy == null)
        }
        s.confirmPublish -> {
            Text("Publish for ${lesson.date}? The island appears on every child's map that day.", style = MaterialTheme.typography.labelLarge, color = Palette.parentInk, modifier = Modifier.weight(1f))
            SecondaryButton("Cancel", { vm.dispatch(Intent.AskPublish(false)) })
            AdminButton("Yes, publish", { vm.dispatch(Intent.Publish) }, enabled = s.busy == null, kind = ButtonKind.ACCENT)
        }
        else -> {
            val hint = when {
                s.isJobRunning -> "Working… the buttons return when the model is done."
                s.status == "draft" -> "Upload the files, then read them."
                s.status == "needs_review" -> "Confirm the skills to write the levels."
                s.manual && !s.publishReady -> "Add at least one stop to Level 1 to publish."
                s.manual -> "Levels you leave empty repeat Level 1; the parent panel is derived from your stops unless you write it."
                s.publishReady -> "Three levels, the Again variant and the parent panel are ready."
                else -> "Needs all three levels, the Again variant and the parent panel."
            }
            Text(hint, style = MaterialTheme.typography.bodyMedium, color = Palette.parentInkSoft, modifier = Modifier.weight(1f))
            when (s.shownStep) {
                Step.FILES -> AdminButton(if (s.status == "analyzing") "Reading…" else "Read the pages", { vm.dispatch(Intent.Analyze) }, enabled = s.busy == null && !s.isJobRunning && lesson.files.any { !it.deleted } && s.status != "published")
                Step.SKILLS -> AdminButton(if (s.status == "generating") "Writing levels…" else "Build practice (3 levels)", { vm.dispatch(Intent.ConfirmSkills) }, enabled = s.busy == null && !s.isJobRunning)
                Step.PLAYS, Step.PANEL -> {
                    if (s.shownStep == Step.PANEL) SecondaryButton("Save panel", { vm.dispatch(Intent.SavePanel) }, enabled = s.busy == null && s.panelDraft != null)
                    AdminButton("Publish to $course", { vm.dispatch(Intent.AskPublish(true)) }, enabled = s.publishReady && s.status == "review" && s.busy == null, kind = ButtonKind.ACCENT)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 1 · files
@Composable
private fun FilesStep(s: LessonContract.State, vm: LessonViewModel) {
    val scope = rememberCoroutineScope()
    val lesson = s.lesson!!
    DisposableEffect(Unit) {
        val stopDrop = Browser.onFilesDropped { dropped -> vm.dispatch(Intent.Upload(dropped.map { UploadFile(it.name, it.mimeType.ifBlank { mime(it.name) }, it.bytes) })) }
        val stopDrag = Browser.onDragState { vm.dispatch(Intent.Dragging(it)) }
        onDispose { stopDrop(); stopDrag() }
    }
    val canEdit = !s.isJobRunning && s.status != "published" && s.busy == null
    Card {
        SectionLabel("Files")
        if (lesson.files.isEmpty()) Text("No files yet. PDF, PPTX, PNG or JPEG — up to 10 files, 25 MB each.", style = MaterialTheme.typography.bodyMedium, color = Palette.parentInkSoft)
        lesson.files.forEach { f ->
            Row(Modifier.fillMaxWidth().height(AdminTokens.buttonHeight), horizontalArrangement = Arrangement.spacedBy(AdminTokens.gutter / 2), verticalAlignment = Alignment.CenterVertically) {
                Text(if (f.deleted) "🗑" else if (f.fileName.lowercase().endsWith(".pdf") || f.fileName.lowercase().endsWith(".pptx")) "📄" else "🖼️", style = MaterialTheme.typography.bodyLarge)
                Text(f.fileName, style = MaterialTheme.typography.bodyMedium, color = if (f.deleted) Palette.parentInkSoft else Palette.parentInk, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${f.pageCount} page${if (f.pageCount == 1) "" else "s"}", style = MaterialTheme.typography.bodySmall, color = Palette.parentInkSoft)
                if (f.cacheHit) Tag("already read — no model call")
                Text(f.fileHash.take(10), style = MaterialTheme.typography.bodySmall, color = Palette.parentInkSoft)
            }
            Rule(thin = true)
        }
        Gap(2)
        if (canEdit) DropZone(s.dragging, "Drop more files here", "or", "Choose files…") {
            scope.launch {
                val picked = FileKit.pickFile(type = PickerType.File(listOf("pdf", "pptx", "png", "jpg", "jpeg")), mode = PickerMode.Multiple()) ?: return@launch
                val uploads = picked.map { f -> UploadFile(f.name, mime(f.name), f.readBytes()) }
                if (uploads.isNotEmpty()) vm.dispatch(Intent.Upload(uploads))
            }
        }
        if (lesson.files.any { !it.deleted } && canEdit) { Gap(); Row { LinkButton("Delete uploaded files", { vm.dispatch(Intent.DeleteFiles) }) } }
    }
    val a = lesson.analysis
    if (a != null) {
        Gap(2)
        Card {
            SectionLabel("What the model read")
            Text("${a.kind.name.lowercase()} · ${a.pages.size} pages · ${a.vocabulary.size} words · ${a.events.size} events · ${a.facts.size} facts", style = MaterialTheme.typography.bodyMedium, color = Palette.parentInkSoft)
            Gap()
            a.pages.take(8).forEach { p -> Text("p${p.number}: ${p.childText.joinToString(" ")}", style = MaterialTheme.typography.bodySmall, color = Palette.parentInkSoft, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            Gap()
            Text("Objectives: " + a.objectives.en.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = Palette.parentInk)
        }
    }
}

// ---------------------------------------------------------------- 2 · skills
@Composable
private fun SkillsStep(s: LessonContract.State, vm: LessonViewModel) {
    Card {
        SectionLabel("Confirm the skills the levels will practise")
        Text("Edit names and methods, untick anything the pages don't really teach. Unsure skills show the model's question — tap a candidate to answer it.", style = MaterialTheme.typography.bodyMedium, color = Palette.parentInkSoft)
        Gap(2)
        s.skills.forEachIndexed { i, r ->
            Column(Modifier.fillMaxWidth().border(AdminTokens.rule, if (r.keep) Palette.parentInk else Palette.parentRule).padding(AdminTokens.gutter / 2)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AdminTokens.gutter / 2)) {
                    Choice(listOf("keep" to if (r.keep) "✓ Keep" else "Keep"), if (r.keep) "keep" else null) { vm.dispatch(Intent.EditSkill(i, keep = !r.keep)) }
                    Field(r.name, { vm.dispatch(Intent.EditSkill(i, name = it)) }, "Skill", Modifier.weight(1f))
                    Column { Text("Subject", style = MaterialTheme.typography.labelLarge, color = Palette.parentInk, modifier = Modifier.padding(bottom = AdminTokens.gutter / 4)); Choice(listOf("math" to "Math", "english" to "English"), r.subject.name.lowercase()) { vm.dispatch(Intent.EditSkill(i, subject = Subject.valueOf(it.uppercase()))) } }
                    if (r.confidence < 0.7) Tag("unsure") else Tag("${(r.confidence * 100).toInt()}%", Palette.parentBg)
                }
                Gap()
                Field(r.method, { vm.dispatch(Intent.EditSkill(i, method = it)) }, "Method the pages use")
                if (r.question != null) {
                    Gap(); Text("❓ ${r.question}", style = MaterialTheme.typography.labelLarge, color = Palette.parentInk)
                    Gap(); Row(horizontalArrangement = Arrangement.spacedBy(AdminTokens.gutter / 3)) { r.candidates.forEach { c -> SecondaryButton(c, { vm.dispatch(Intent.EditSkill(i, name = c)) }) } }
                }
            }
            Gap()
        }
        Row { LinkButton("+ Add a skill", { vm.dispatch(Intent.AddSkill) }) }
    }
}

// ---------------------------------------------------------------- 3 · plays: stop list · editor · phone
@Composable
private fun PlaysStep(s: LessonContract.State, vm: LessonViewModel) {
    val lesson = s.lesson!!
    val scope = rememberCoroutineScope()
    val loader = remember { StopImageLoader { id -> Graph.remote.imageBytes(id) } }
    // a picture dropped anywhere on the page attaches to the selected stop
    DisposableEffect(Unit) {
        val stopDrop = Browser.onFilesDropped { dropped -> dropped.firstOrNull { it.name.lowercase().substringAfterLast('.') in setOf("png", "jpg", "jpeg") }?.let { vm.dispatch(Intent.AttachImage(UploadFile(it.name, it.mimeType.ifBlank { mime(it.name) }, it.bytes))) } }
        val stopDrag = Browser.onDragState { vm.dispatch(Intent.Dragging(it)) }
        onDispose { stopDrop(); stopDrag() }
    }
    val levels = listOf(0 to "Level 1 · Same as the book", 1 to "Level 2 · Think", 2 to "Level 3 · Challenge", 3 to "Level 1 · Again")
    Choice(levels.map { it.first.toString() to it.second }, s.tab.toString()) { vm.dispatch(Intent.Tab(it.toInt())) }
    Gap(2)
    val play = s.currentPlay
    if (play == null) {
        val (level, variant) = if (s.tab == 3) 1 to 1 else (s.tab + 1) to 0
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            EmptyState("This level hasn't been written yet", if (s.manual) "Write it stop by stop, or type a short lesson text below and let the model write the levels you left empty." else "The model writes it after the skills are confirmed.",
                if (s.manual) "Create ${levels[s.tab].second}" else null, if (s.manual) ({ vm.dispatch(Intent.CreateLevel(level, variant)) }) else null)
            if (s.manual) { Gap(2); GenerateOthers(s, vm) }
            Gap(2)
        }
        return
    }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(AdminTokens.gutter / 2)) {
      Row(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(bottom = AdminTokens.gutter), horizontalArrangement = Arrangement.spacedBy(AdminTokens.gutter / 2)) {
        // stop list
        Column(Modifier.width(AdminTokens.stopListWidth)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${play.play.theme.potEmoji} ${play.play.theme.dishName} · ${play.play.stops.size} stop${if (play.play.stops.size == 1) "" else "s"}", style = MaterialTheme.typography.bodySmall, color = Palette.parentInkSoft, modifier = Modifier.weight(1f))
            }
            Gap()
            play.play.stops.forEachIndexed { i, stop ->
                val on = stop.id == s.selectedStopId
                Row(Modifier.fillMaxWidth().background(if (on) Palette.parentAccentSoft else Palette.parentSurface).border(if (on) AdminTokens.rule else AdminTokens.ruleThin, if (on) Palette.parentAccent else Palette.parentRule).clickable { vm.dispatch(Intent.SelectStop(stop.id)) }.padding(AdminTokens.gutter / 3), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${i + 1}. ${stop.title}", style = MaterialTheme.typography.labelLarge, color = Palette.parentInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${stop.type} · ${stop.ingredient.emoji} ${stop.ingredient.name}${if (stop.imageId != null) " · 🖼️" else ""}", style = MaterialTheme.typography.bodySmall, color = Palette.parentInkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (s.editable) Column {
                        Arrow(up = true, enabled = i > 0) { vm.dispatch(Intent.MoveStop(stop.id, -1)) }
                        Arrow(up = false, enabled = i < play.play.stops.size - 1) { vm.dispatch(Intent.MoveStop(stop.id, +1)) }
                    }
                }
                Spacer(Modifier.height(AdminTokens.gutter / 6))
            }
            if (s.editable) {
                Gap()
                if (!s.addMenu) SecondaryButton("+ Add stop", { vm.dispatch(Intent.ShowAddMenu(true)) }, Modifier.fillMaxWidth())
                else Card(padding = AdminTokens.gutter / 2) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Text("ADD A STOP", style = labelStyle(), color = Palette.parentInkSoft, modifier = Modifier.weight(1f)); LinkButton("Close", { vm.dispatch(Intent.ShowAddMenu(false)) }) }
                    StopTemplates.all.groupBy { it.group }.forEach { (group, entries) ->
                        Gap(); Text(group.uppercase(), style = labelStyle(), color = Palette.parentInkSoft)
                        entries.forEach { e -> Row { LinkButton(e.label, { vm.dispatch(Intent.AddStop(e.type)) }, color = Palette.parentInk) } }
                    }
                }
                if (!s.manual) { Gap(); Row { LinkButton("Rewrite this whole level", { vm.dispatch(Intent.RegeneratePlay) }, enabled = s.status != "published") } }
            }
        }
        // editor
        Column(Modifier.weight(1f)) {
            val stop = s.selectedStop
            if (stop == null) {
                EmptyState("Select a stop", if (s.editable && play.play.stops.isEmpty()) "Add the first stop with “+ Add stop”." else "Every child-facing string is spoken aloud, so keep them short.")
                if (s.manual) { Gap(2); GenerateOthers(s, vm) }
            } else Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stop.type, style = cardTitleStyle(), color = Palette.parentInk, modifier = Modifier.weight(1f))
                    if (s.editable) LinkButton("Delete stop", { vm.dispatch(Intent.DeleteStop) })
                }
                Gap()
                Field(stop.title, { vm.dispatch(Intent.PatchStop("title", it)) }, "Title (≤ 40)", enabled = s.editable)
                Gap()
                Field(stop.speak, { vm.dispatch(Intent.PatchStop("speak", it)) }, "What Pip says (≤ 90)", enabled = s.editable)
                if (stop is Stop.SingleAnswer) { Gap(); Field(stop.hint, { vm.dispatch(Intent.PatchStop("hint", it)) }, "Hint after a wrong answer (points to the method)", enabled = s.editable) }
                Gap()
                Row(horizontalArrangement = Arrangement.spacedBy(AdminTokens.gutter / 2)) {
                    Field(stop.parentTip.en, { vm.dispatch(Intent.PatchStop("parentTip.en", it)) }, "Parent tip (EN)", Modifier.weight(1f), enabled = s.editable)
                    Field(stop.parentTip.ar, { vm.dispatch(Intent.PatchStop("parentTip.ar", it)) }, "Parent tip (AR)", Modifier.weight(1f), enabled = s.editable)
                }
                Gap(2)
                SectionLabel("Picture")
                val draftImage = runCatching { SchemaValidator.json.decodeFromString(Stop.serializer(), s.stopDraft).imageId }.getOrNull()
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AdminTokens.gutter / 2)) {
                    Text(draftImage?.let { id -> lesson.images.firstOrNull { it.id == id }?.description?.ifBlank { id } ?: id } ?: if (s.dragging) "Drop the picture to attach it." else "No picture on this stop — attach one, or drop it on the page.", style = MaterialTheme.typography.bodyMedium, color = if (draftImage == null) Palette.parentInkSoft else Palette.parentInk, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    SecondaryButton(if (draftImage == null) "Attach a picture…" else "Replace…", {
                        scope.launch {
                            val picked = FileKit.pickFile(type = PickerType.File(listOf("png", "jpg", "jpeg")), mode = PickerMode.Single) ?: return@launch
                            vm.dispatch(Intent.AttachImage(UploadFile(picked.name, mime(picked.name), picked.readBytes())))
                        }
                    }, enabled = s.editable)
                    if (draftImage != null) LinkButton("Remove", { vm.dispatch(Intent.DetachImage) }, enabled = s.editable)
                }
                Gap(2)
                SectionLabel("Full stop JSON — options, answers, cues; validated against the shared schema as you type")
                Field(s.stopDraft, { vm.dispatch(Intent.EditStopJson(it)) }, "", singleLine = false, minLines = 14, mono = true, enabled = s.editable)
                s.stopErrors.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = Palette.parentAccent) }
                Gap(2)
                Row(horizontalArrangement = Arrangement.spacedBy(AdminTokens.gutter / 2)) {
                    AdminButton("Save stop", { vm.dispatch(Intent.SaveStop) }, enabled = s.stopErrors.isEmpty() && s.editable)
                    if (!s.manual) SecondaryButton("Regenerate this stop", { vm.dispatch(Intent.RegenerateStop) }, enabled = s.editable)
                }
            }
        }
      }
        // phone preview, pinned to the right, scrolls on its own
        Column(Modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(bottom = AdminTokens.gutter), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("PHONE PREVIEW", style = labelStyle(), color = Palette.parentInkSoft)
            Gap()
            Box(Modifier.size(AdminTokens.phoneWidth, AdminTokens.phoneHeight).clip(RoundedCornerShape(AdminTokens.phoneCorner)).background(Palette.night).padding(AdminTokens.phoneBezel)) {
                Box(Modifier.fillMaxSize().clip(RoundedCornerShape(AdminTokens.phoneCorner - AdminTokens.phoneBezel)).background(Palette.sky)) {
                    val preview = runCatching { SchemaValidator.json.decodeFromString(Stop.serializer(), s.stopDraft) }.getOrNull() ?: s.selectedStop
                    if (preview != null) CompositionLocalProvider(LocalStopImageLoader provides loader) {
                        ChildTheme { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = AdminTokens.gutter)) { StopContent(preview, onEvent = {}, childName = "Pip") } }
                    } else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Pick a stop", color = Palette.inkSoft) }
                }
            }
        }
    }
}

@Composable
private fun GenerateOthers(s: LessonContract.State, vm: LessonViewModel) {
    Card {
        SectionLabel("Generate the other levels")
        Text("Type a few sentences about the lesson — what the page teaches, the key words, what the children should be able to do. The model writes every level you left empty (and the parent panel) from that text and your Level 1.", style = MaterialTheme.typography.bodyMedium, color = Palette.parentInkSoft)
        Gap()
        Field(s.lessonText, { vm.dispatch(Intent.LessonText(it)) }, "Lesson text", singleLine = false, minLines = 4, placeholder = "Biscuit is our class hamster. He eats carrots and seeds and sleeps in the day. Children learn the words hamster, cage, nibble…", enabled = s.editable)
        Gap()
        Row { AdminButton("Generate the other levels", { vm.dispatch(Intent.GenerateOthers) }, enabled = s.editable && s.lessonText.trim().length >= 20) }
    }
}

// ---------------------------------------------------------------- 4 · parent panel
@Composable
private fun PanelStep(s: LessonContract.State, vm: LessonViewModel) {
    val p = s.panelDraft
    if (p == null) { EmptyState("No parent panel yet", if (s.manual) "Publishing derives one from your stops, or generate the levels from a lesson text to get a written panel." else "The model writes it with the levels."); return }
    fun update(np: ParentPanel) = vm.dispatch(Intent.EditPanel(np))
    Card {
        SectionLabel("Objectives (EN + AR)")
        p.objectives.en.indices.forEach { i ->
            Row(horizontalArrangement = Arrangement.spacedBy(AdminTokens.gutter / 2)) {
                Field(p.objectives.en[i], { v -> update(p.copy(objectives = p.objectives.copy(en = p.objectives.en.toMutableList().also { it[i] = v }))) }, "EN ${i + 1}", Modifier.weight(1f))
                Field(p.objectives.ar.getOrElse(i) { "" }, { v -> update(p.copy(objectives = p.objectives.copy(ar = p.objectives.ar.toMutableList().also { while (it.size <= i) it += ""; it[i] = v }))) }, "AR ${i + 1}", Modifier.weight(1f))
            }
            Gap()
        }
        Gap()
        BilingualList("How to help (supported)", p.supported) { update(p.copy(supported = it)) }
        BilingualList("Stretch ideas (challenge)", p.challenge) { update(p.copy(challenge = it)) }
        SectionLabel("Stop tips")
        p.stopTips.forEachIndexed { i, t ->
            Row(horizontalArrangement = Arrangement.spacedBy(AdminTokens.gutter / 2), verticalAlignment = Alignment.Bottom) {
                Text(t.stopId.substringAfterLast(':'), Modifier.width(AdminTokens.gradeCard).padding(bottom = AdminTokens.gutter / 2), style = MaterialTheme.typography.bodySmall, color = Palette.parentInkSoft)
                Field(t.en, { v -> update(p.copy(stopTips = p.stopTips.toMutableList().also { it[i] = StopTip(t.stopId, v, t.ar) })) }, "EN", Modifier.weight(1f))
                Field(t.ar, { v -> update(p.copy(stopTips = p.stopTips.toMutableList().also { it[i] = StopTip(t.stopId, t.en, v) })) }, "AR", Modifier.weight(1f))
            }
            Gap()
        }
        if (p.modelAnswers.isNotEmpty()) SectionLabel("Model answers (open stops)")
        p.modelAnswers.forEachIndexed { i, m ->
            Row(horizontalArrangement = Arrangement.spacedBy(AdminTokens.gutter / 2), verticalAlignment = Alignment.Bottom) {
                Text(m.stopId.substringAfterLast(':'), Modifier.width(AdminTokens.gradeCard).padding(bottom = AdminTokens.gutter / 2), style = MaterialTheme.typography.bodySmall, color = Palette.parentInkSoft)
                Field(m.en, { v -> update(p.copy(modelAnswers = p.modelAnswers.toMutableList().also { it[i] = ModelAnswer(m.stopId, v) })) }, "A good answer sounds like…", Modifier.weight(1f))
            }
            Gap()
        }
    }
}

@Composable
private fun BilingualList(title: String, items: List<Bilingual>, onChange: (List<Bilingual>) -> Unit) {
    SectionLabel(title)
    items.forEachIndexed { i, b ->
        Row(horizontalArrangement = Arrangement.spacedBy(AdminTokens.gutter / 2)) {
            Field(b.en, { v -> onChange(items.toMutableList().also { it[i] = Bilingual(v, b.ar) }) }, "EN", Modifier.weight(1f))
            Field(b.ar, { v -> onChange(items.toMutableList().also { it[i] = Bilingual(b.en, v) }) }, "AR", Modifier.weight(1f))
        }
        Gap()
    }
    Gap()
}

private typealias SchemaValidator = quest.api.validation.SchemaValidator
