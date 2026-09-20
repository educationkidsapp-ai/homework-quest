package quest.feature.children.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import quest.api.dashboard.ClassLookup
import quest.api.dashboard.JoinSchoolInfo
import quest.api.dto.Child
import quest.api.dto.CreateChildRequest
import quest.api.dto.Curriculum
import quest.api.dto.UpdateChildRequest
import quest.core.mvi.MviEffect
import quest.core.mvi.MviIntent
import quest.core.mvi.MviState
import quest.core.mvi.MviViewModel
import quest.core.runCancellable
import quest.feature.children.domain.AddChildUseCase
import quest.feature.children.domain.ChildrenRepository
import quest.feature.parent.presentation.Chip
import quest.feature.parent.presentation.ParentButton
import quest.feature.parent.presentation.ParentCard
import quest.feature.parent.presentation.ParentShell
import quest.feature.parent.presentation.SectionTitle
import quest.feature.parent.presentation.Strings
import quest.feature.school.domain.SchoolSession
import quest.feature.school.presentation.SchoolLogo
import quest.ui.design.AvatarColors
import quest.ui.design.Dimens
import quest.ui.design.Palette
import quest.ui.design.Pip
import quest.ui.design.PipPose

// hq-flag: none (this is the screen a child joins a school on, so there is no school whose flags could gate it)
object AddChildContract {
    /** How far the parent has got with the school code (§2). */
    enum class JoinStep { NONE, FOUND, CONFIRMED }

    data class State(
        val editingId: String? = null, val name: String = "", val avatar: String = "sky", val curriculum: Curriculum = Curriculum.BRITISH, val grade: Int = 1,
        val languages: List<String> = listOf("en"), val busy: Boolean = false, val error: String? = null, val loaded: Boolean = false,
        // ---- join school
        val schoolCode: String = "", val school: JoinSchoolInfo? = null, val joinStep: JoinStep = JoinStep.NONE,
        val lookingUp: Boolean = false, val schoolNotFound: Boolean = false,
        /**
         * The parent had already joined a school before this form opened (D16 slice 2). The code is then shown as a
         * fact rather than asked for, and Save reuses it. "Use a different code" clears this and asks again.
         */
        val alreadyJoined: Boolean = false,
        /** The school's name off the device, so the card still reads right when the lookup cannot be made. */
        val joinedName: String? = null,
        // ---- class join code (§2): optional, and the more specific of the two codes
        val classCode: String = "", val section: ClassLookup? = null,
        val lookingUpClass: Boolean = false, val classNotFound: Boolean = false,
    ) : MviState {
        /** The chooser's options: the school's when one is confirmed, otherwise everything the app supports. */
        val curriculumOptions: List<Curriculum>
            get() = school?.curriculumOptions?.takeIf { joinStep == JoinStep.CONFIRMED && it.isNotEmpty() } ?: listOf(Curriculum.AMERICAN, Curriculum.BRITISH)
        val gradeOptions: List<Int>
            get() = school?.gradeOptions?.takeIf { joinStep == JoinStep.CONFIRMED && it.isNotEmpty() } ?: listOf(1, 2, 3)

        /**
         * A class card answers the course, so the choosers come off the form rather than disagreeing with the
         * section the child is about to join — the server ignores them in that case anyway.
         */
        val courseIsFixed: Boolean get() = section != null
    }

    sealed interface Intent : MviIntent {
        data object Load : Intent; data class Name(val v: String) : Intent; data class Avatar(val v: String) : Intent
        data class SetCurriculum(val v: Curriculum) : Intent; data class Grade(val v: Int) : Intent; data class ToggleLanguage(val code: String) : Intent; data object Save : Intent; data object Delete : Intent
        data class SchoolCode(val v: String) : Intent
        /** Look the typed code up. */
        data object FindSchool : Intent
        /** "Yes, this is my school": the theme applies now and the choosers narrow to it. */
        data object ConfirmSchool : Intent
        data object ClearSchool : Intent
        data class ClassCode(val v: String) : Intent
        /** Look the typed class code up. */
        data object FindClass : Intent
        data object ClearClass : Intent
    }
    sealed interface Effect : MviEffect { data class Saved(val child: Child) : Effect; data object Deleted : Effect }

    /** A join code is exactly six characters, upper-case; the field itself enforces the shape. */
    const val CODE_LENGTH = 6
    fun normaliseCode(raw: String): String = raw.filter { it.isLetterOrDigit() }.uppercase().take(CODE_LENGTH)
}

class AddChildViewModel(
    private val editingId: String?,
    private val children: ChildrenRepository,
    private val addChild: AddChildUseCase,
    private val school: SchoolSession,
) : MviViewModel<AddChildContract.State, AddChildContract.Intent, AddChildContract.Effect>(AddChildContract.State(editingId = editingId)) {
    init { dispatch(AddChildContract.Intent.Load) }
    override suspend fun handle(intent: AddChildContract.Intent) {
        when (intent) {
            AddChildContract.Intent.Load -> {
                val c = editingId?.let { id -> children.children().firstOrNull { it.id == id } }
                reduce { if (c == null) copy(loaded = true) else copy(loaded = true, name = c.name, avatar = c.avatarColor, curriculum = c.curriculum, grade = c.grade, languages = c.languages) }
                if (editingId == null) restoreJoin()
            }
            is AddChildContract.Intent.Name -> reduce { copy(name = intent.v, error = null) }
            is AddChildContract.Intent.Avatar -> reduce { copy(avatar = intent.v) }
            is AddChildContract.Intent.SetCurriculum -> reduce { copy(curriculum = intent.v) }
            is AddChildContract.Intent.Grade -> reduce { copy(grade = intent.v) }
            is AddChildContract.Intent.ToggleLanguage -> reduce { copy(languages = if (intent.code in languages) (languages - intent.code).ifEmpty { listOf("en") } else languages + intent.code) }
            AddChildContract.Intent.Delete -> { editingId?.let { children.delete(it) }; effect(AddChildContract.Effect.Deleted) }

            is AddChildContract.Intent.SchoolCode -> {
                val code = AddChildContract.normaliseCode(intent.v)
                reduce { copy(schoolCode = code, schoolNotFound = false, school = null, joinStep = AddChildContract.JoinStep.NONE) }
                if (code.length == AddChildContract.CODE_LENGTH) dispatch(AddChildContract.Intent.FindSchool)
            }
            AddChildContract.Intent.FindSchool -> {
                val code = current.schoolCode
                if (code.length != AddChildContract.CODE_LENGTH) return
                reduce { copy(lookingUp = true, schoolNotFound = false) }
                runCancellable { school.lookUp(code) }
                    .onSuccess { info -> reduce { copy(lookingUp = false, school = info, joinStep = AddChildContract.JoinStep.FOUND, schoolNotFound = false) } }
                    // Every failure reads the same to the parent: a wrong code and a school that is offline are the
                    // same problem from the kitchen table, and neither is worth a second message.
                    .onFailure { reduce { copy(lookingUp = false, school = null, joinStep = AddChildContract.JoinStep.NONE, schoolNotFound = true) } }
            }
            is AddChildContract.Intent.ClassCode -> {
                val code = AddChildContract.normaliseCode(intent.v)
                reduce { copy(classCode = code, classNotFound = false, section = null) }
                if (code.length == AddChildContract.CODE_LENGTH) dispatch(AddChildContract.Intent.FindClass)
            }
            AddChildContract.Intent.FindClass -> {
                val code = current.classCode
                if (code.length != AddChildContract.CODE_LENGTH) return
                reduce { copy(lookingUpClass = true, classNotFound = false) }
                runCancellable { school.lookUpClass(code) }
                    // The section answers the course, so the choosers move to it rather than sitting there
                    // contradicting the card the parent is holding.
                    .onSuccess { found -> reduce { copy(lookingUpClass = false, section = found, classNotFound = false, curriculum = found.curriculum, grade = found.grade) } }
                    .onFailure { reduce { copy(lookingUpClass = false, section = null, classNotFound = true) } }
            }
            AddChildContract.Intent.ClearClass -> reduce { copy(classCode = "", section = null, classNotFound = false) }

            AddChildContract.Intent.ConfirmSchool -> {
                val info = current.school ?: return
                school.confirm(current.schoolCode, info)   // the colour transition starts here, before the child exists
                reduce {
                    copy(
                        joinStep = AddChildContract.JoinStep.CONFIRMED,
                        curriculum = info.curriculumOptions.takeIf { it.isNotEmpty() }?.let { if (curriculum in it) curriculum else it.first() } ?: curriculum,
                        grade = info.gradeOptions.takeIf { it.isNotEmpty() }?.let { if (grade in it) grade else it.first() } ?: grade,
                    )
                }
            }
            AddChildContract.Intent.ClearSchool -> {
                school.cancelJoin()   // and the colours fade back out over the same 300 ms
                reduce { copy(schoolCode = "", school = null, joinStep = AddChildContract.JoinStep.NONE, schoolNotFound = false, alreadyJoined = false, joinedName = null) }
            }

            AddChildContract.Intent.Save -> {
                reduce { copy(busy = true, error = null) }
                // CONFIRMED covers both "just joined on this screen" and "joined earlier and restored above", so a
                // second child reaches the same school without the parent hunting for the letter again (F6).
                val code = current.schoolCode.takeIf { current.joinStep == AddChildContract.JoinStep.CONFIRMED }
                // A class code only travels once the lookup confirmed it; a half-typed one is not sent and rejected.
                val classCode = current.classCode.takeIf { current.section != null }
                val section = current.section
                runCancellable {
                    if (editingId == null) addChild(CreateChildRequest(current.name.trim(), current.avatar, current.curriculum, current.grade, current.languages, code, classCode))
                    else children.update(editingId, UpdateChildRequest(current.name.trim(), current.avatar, current.curriculum, current.grade, current.languages))
                }.onSuccess { child ->
                    // The section is a local note, so it is written before anything that touches the network.
                    children.rememberSection(child.id, section?.name)
                    // The server decides which school the code belongs to; that id is what the theme and flags follow.
                    runCancellable { school.use(child.schoolId) }
                    reduce { copy(busy = false) }
                    effect(AddChildContract.Effect.Saved(child))
                }.onFailure { e -> reduce { copy(busy = false, error = e.message) } }
            }
        }
    }

    /**
     * §2's join is the parent's, not the child's (D16 slice 2). If a school was joined on this device the form does
     * not ask again: the stored code is put back into the state as CONFIRMED, and the school is looked up once so the
     * card shows its name and logo and the curriculum/grade choosers narrow to it. Offline the lookup fails and the
     * cached name carries the card, with every curriculum and grade offered — which is what the form did before any
     * school was joined, so nothing is worse than it was.
     */
    private suspend fun restoreJoin() {
        val code = school.joinedCode.value?.takeIf { it.isNotBlank() } ?: return
        // Read outside `reduce`: inside it `school` is the state's own JoinSchoolInfo, not the session.
        val cachedName = school.branding.value.schoolName
        reduce { copy(schoolCode = code, joinStep = AddChildContract.JoinStep.CONFIRMED, alreadyJoined = true, joinedName = cachedName) }
        runCancellable { school.lookUp(code) }.onSuccess { info ->
            reduce {
                copy(
                    school = info, joinedName = info.name,
                    curriculum = info.curriculumOptions.takeIf { it.isNotEmpty() }?.let { if (curriculum in it) curriculum else it.first() } ?: curriculum,
                    grade = info.gradeOptions.takeIf { it.isNotEmpty() }?.let { if (grade in it) grade else it.first() } ?: grade,
                )
            }
        }
    }
}

@Composable
fun AddChildRoute(editingId: String?, onSaved: () -> Unit, onBack: (() -> Unit)?) {
    val vm: AddChildViewModel = koinViewModel(key = "child-$editingId") { parametersOf(editingId) }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.effects.collect { when (it) { is AddChildContract.Effect.Saved -> onSaved(); AddChildContract.Effect.Deleted -> onSaved() } } }
    ParentShell(title = { if (editingId == null) it.addChild else it.childProfile }, onBack = onBack) { s -> AddChildScreen(state, s, vm::dispatch) }
}

/** Screen 13: name, Pip in four colours, curriculum (American / British), grade (1 / 2 / 3), subject languages. */
@Composable
fun AddChildScreen(state: AddChildContract.State, s: Strings, dispatch: (AddChildContract.Intent) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Dimens.s16)) {
        if (state.editingId == null) {
            JoinSchoolSection(state, s, dispatch)
            ClassCodeSection(state, s, dispatch)
        }
        SectionTitle(s.childName)
        OutlinedTextField(state.name, { dispatch(AddChildContract.Intent.Name(it)) }, Modifier.fillMaxWidth(), placeholder = { Text(s.childName) }, singleLine = true)
        SectionTitle(s.avatar)
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s12)) {
            AvatarColors.keys.forEach { key ->
                Box(
                    Modifier.size(76.dp).background(Palette.parentSurface).border(if (state.avatar == key) 3.dp else 1.dp, if (state.avatar == key) MaterialTheme.colorScheme.secondary else Palette.parentRule)
                        .clickable(role = Role.Button) { dispatch(AddChildContract.Intent.Avatar(key)) }.semantics { contentDescription = "avatar $key" + if (state.avatar == key) ", selected" else "" },
                    contentAlignment = Alignment.Center,
                ) { Pip(PipPose.IDLE, 60.dp, animated = false, color = key) }
            }
        }
        // A class card already says which course the child is in, so the choosers come off rather than offering a
        // choice the server would ignore (`ChildService.create`: the join code is the more specific answer).
        if (!state.courseIsFixed) {
            SectionTitle(s.curriculum)
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s8)) {
                // Only the curricula this school teaches once one is joined; all of them otherwise.
                state.curriculumOptions.forEach { c ->
                    Chip(if (c == Curriculum.AMERICAN) s.american else s.british, selected = state.curriculum == c) { dispatch(AddChildContract.Intent.SetCurriculum(c)) }
                }
            }
            SectionTitle(s.grade)
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s8)) { state.gradeOptions.forEach { g -> Chip("$g", selected = state.grade == g) { dispatch(AddChildContract.Intent.Grade(g)) } } }
        }
        SectionTitle(s.languages)
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s8)) {
            Chip("English", selected = "en" in state.languages) { dispatch(AddChildContract.Intent.ToggleLanguage("en")) }
            Chip("العربية", selected = "ar" in state.languages) { dispatch(AddChildContract.Intent.ToggleLanguage("ar")) }
        }
        state.error?.let { Text(it, color = Palette.parentAccent, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = Dimens.s8)) }
        Spacer(Modifier.height(Dimens.s24))
        ParentButton(s.save, { dispatch(AddChildContract.Intent.Save) }, enabled = state.name.isNotBlank() && !state.busy)
        if (state.editingId != null) {
            Spacer(Modifier.height(Dimens.s24))
            var confirm by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
            if (!confirm) ParentButton(s.deleteChild, { confirm = true }, primary = false, icon = "🗑️")
            else ParentCard {
                Text(s.deleteChildBody, style = MaterialTheme.typography.bodyMedium, color = Palette.parentInkSoft)
                Spacer(Modifier.height(Dimens.s12))
                ParentButton(s.deleteChildConfirm, { dispatch(AddChildContract.Intent.Delete) })
            }
        }
        Spacer(Modifier.height(Dimens.s24))
    }
}

/**
 * §2 join school: type the 6-character code → the school's name and logo fade in → confirm.
 *
 * Confirming is a separate tap on purpose: the code is printed on a letter and typed by a tired parent, so the school
 * has to be *shown* before the child is bound to it. The theme applies on confirm — the rest of this form is already in
 * the school's colours while the parent finishes it — and the code only reaches the server on Save.
 *
 * The whole section is optional: a parent with no code fills the form as before and the child lands in the default
 * school. Only shown while adding; a child's school is not something a parent re-picks in the profile.
 */
@Composable
private fun JoinSchoolSection(state: AddChildContract.State, s: Strings, dispatch: (AddChildContract.Intent) -> Unit) {
    SectionTitle(if (state.alreadyJoined) s.yourSchool else s.schoolCode)
    if (state.joinStep == AddChildContract.JoinStep.CONFIRMED) {
        SchoolCard(state, s, confirmed = true, dispatch = dispatch)
        return
    }
    OutlinedTextField(
        state.schoolCode,
        { dispatch(AddChildContract.Intent.SchoolCode(it)) },
        Modifier.fillMaxWidth().semantics { contentDescription = s.schoolCode },
        placeholder = { Text(s.schoolCodePlaceholder) },
        singleLine = true,
        isError = state.schoolNotFound,
        supportingText = { Text(s.schoolCodeHint, style = MaterialTheme.typography.bodySmall, color = Palette.parentInkSoft) },
    )
    if (state.schoolNotFound) {
        Spacer(Modifier.height(Dimens.s8))
        // The red band, the one shape the design system has for "this did not work".
        Row(
            Modifier.fillMaxWidth().background(Palette.parentAccentSoft).border(2.dp, Palette.parentAccent).padding(horizontal = Dimens.s12, vertical = Dimens.s8),
            verticalAlignment = Alignment.CenterVertically,
        ) { Text(s.schoolNotFound, style = MaterialTheme.typography.bodyMedium, color = Palette.parentInk) }
    }
    if (state.school != null) {
        Spacer(Modifier.height(Dimens.s12))
        SchoolCard(state, s, confirmed = false, dispatch = dispatch)
    }
    Spacer(Modifier.height(Dimens.s8))
}

/**
 * §2 class join code: the code printed on the teacher's class card, and the one the app should be asking for now
 * that sections exist (D16 slice 3).
 *
 * It is optional and it is the *narrower* of the two codes: with it the child is created already on the section's
 * roster, so she sees that class's lessons and only that class's lessons. Without it she is created unplaced, exactly
 * as before — which works, but until the teacher puts her on a roster her map shows one copy of the lesson per
 * section of her course, and the hint below says so rather than leaving the parent to discover it.
 */
@Composable
private fun ClassCodeSection(state: AddChildContract.State, s: Strings, dispatch: (AddChildContract.Intent) -> Unit) {
    SectionTitle(s.classCode)
    val found = state.section
    if (found != null) {
        ParentCard {
            Text(found.name, style = MaterialTheme.typography.titleLarge, color = Palette.parentInk)
            Text(
                "${if (found.curriculum == Curriculum.AMERICAN) s.american else s.british} · ${s.grade} ${found.grade} · ${found.schoolName}",
                style = MaterialTheme.typography.bodyMedium, color = Palette.parentInkSoft,
            )
            Spacer(Modifier.height(Dimens.s12))
            ParentButton(s.changeClass, { dispatch(AddChildContract.Intent.ClearClass) }, primary = false)
        }
        Spacer(Modifier.height(Dimens.s8))
        return
    }
    OutlinedTextField(
        state.classCode,
        { dispatch(AddChildContract.Intent.ClassCode(it)) },
        Modifier.fillMaxWidth().semantics { contentDescription = s.classCode },
        placeholder = { Text(s.schoolCodePlaceholder) },
        singleLine = true,
        isError = state.classNotFound,
        supportingText = { Text(s.classCodeHint, style = MaterialTheme.typography.bodySmall, color = Palette.parentInkSoft) },
    )
    if (state.classNotFound) {
        Spacer(Modifier.height(Dimens.s8))
        Row(
            Modifier.fillMaxWidth().background(Palette.parentAccentSoft).border(2.dp, Palette.parentAccent).padding(horizontal = Dimens.s12, vertical = Dimens.s8),
            verticalAlignment = Alignment.CenterVertically,
        ) { Text(s.classNotFound, style = MaterialTheme.typography.bodyMedium, color = Palette.parentInk) }
    }
    Spacer(Modifier.height(Dimens.s8))
    Text(s.noClassCodeNote, style = MaterialTheme.typography.bodySmall, color = Palette.parentInkSoft)
    Spacer(Modifier.height(Dimens.s8))
}

/**
 * The school behind the code: logo, name, and either "Join this school" or the joined state with a way back out.
 *
 * The name comes from the lookup when there is one and from the device otherwise, so a parent who is already joined
 * still sees which school this is with no network (D16 slice 2).
 */
@Composable
private fun SchoolCard(state: AddChildContract.State, s: Strings, confirmed: Boolean, dispatch: (AddChildContract.Intent) -> Unit) {
    val name = state.school?.name ?: state.joinedName ?: return
    ParentCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SchoolLogo(state.school?.logoUrl, name, size = 56.dp)
            Spacer(Modifier.size(Dimens.s12))
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleLarge, color = Palette.parentInk)
                Text(
                    if (confirmed) "${s.joinedSchool} · ${state.schoolCode}" else state.schoolCode,
                    style = MaterialTheme.typography.bodyMedium, color = Palette.parentInkSoft,
                )
            }
        }
        Spacer(Modifier.height(Dimens.s12))
        if (confirmed) {
            Text(s.schoolCurriculumNote, style = MaterialTheme.typography.bodySmall, color = Palette.parentInkSoft)
            Spacer(Modifier.height(Dimens.s8))
            ParentButton(s.changeSchool, { dispatch(AddChildContract.Intent.ClearSchool) }, primary = false)
        } else {
            ParentButton(s.joinSchool, { dispatch(AddChildContract.Intent.ConfirmSchool) }, enabled = !state.lookingUp)
        }
    }
}

@Composable
fun ChildPickerRoute(onPicked: () -> Unit, onAdd: () -> Unit, onBack: () -> Unit) {
    val repo: ChildrenRepository = org.koin.compose.koinInject()
    var list by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<List<Child>>(emptyList()) }
    var sections by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Map<String, String>>(emptyMap()) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(Unit) {
        list = repo.refresh()
        sections = list.mapNotNull { c -> repo.sectionName(c.id)?.let { c.id to it } }.toMap()
    }
    ParentShell(title = { it.whoIsPlaying }, onBack = onBack) { s ->
        ChildPickerScreen(list, s, onPick = { c -> scope.launch { repo.select(c.id); onPicked() } }, onAdd = onAdd, sections = sections)
    }
}

/** Pick which child is playing (only shown when the parent has more than one). */
@Composable
fun ChildPickerScreen(children: List<Child>, s: Strings, onPick: (Child) -> Unit, onAdd: () -> Unit, sections: Map<String, String> = emptyMap()) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Dimens.s16)) {
        SectionTitle(s.whoIsPlaying)
        children.forEach { c ->
            ParentCard(Modifier.padding(bottom = Dimens.s12), onClick = { onPick(c) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Pip(PipPose.IDLE, 56.dp, animated = false, color = c.avatarColor)
                    Spacer(Modifier.size(Dimens.s12))
                    Column {
                        Text(c.name, style = MaterialTheme.typography.titleLarge, color = Palette.parentInk)
                        Text(sections[c.id] ?: "${if (c.curriculum == Curriculum.BRITISH) s.british else s.american} · ${s.grade} ${c.grade}", style = MaterialTheme.typography.bodyMedium, color = Palette.parentInkSoft)
                    }
                }
            }
        }
        ParentButton(s.addChild, onAdd, primary = false, icon = "＋")
    }
}
