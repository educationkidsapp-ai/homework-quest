package quest.feature.school

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import quest.api.AuthProvider
import quest.api.dto.Curriculum
import quest.core.db.Db
import quest.core.db.SettingsStore
import quest.core.platform.DriverFactory
import quest.feature.auth.data.FakeAuth
import quest.feature.children.data.ChildrenRepositoryImpl
import quest.feature.children.domain.AddChildUseCase
import quest.feature.children.presentation.AddChildContract
import quest.feature.children.presentation.AddChildViewModel
import quest.feature.content.data.FakeContentApi
import quest.feature.school.data.SchoolSessionImpl
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * §2 join school, end to end against the fake API: a code becomes a school, confirming it themes the app and narrows
 * the choosers, and saving sends the code so the child comes back with the school's id.
 */
class JoinSchoolTest {
    private val db = Db(DriverFactory(null))
    private val settings = SettingsStore(db)
    private val auth: AuthProvider = FakeAuth(settings)
    private val api = FakeContentApi(auth, delayMillis = 0)
    private val children = ChildrenRepositoryImpl(api, db, settings, auth)
    private val session = SchoolSessionImpl(api, api, settings)

    /**
     * The view model queues its intents on `viewModelScope`, and the repositories hop to `Dispatchers.Default` for the
     * database — so these tests run in real time and wait for a state rather than advancing a virtual clock.
     */
    @BeforeTest fun setUp() = Dispatchers.setMain(Dispatchers.Default)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = AddChildViewModel(null, children, AddChildUseCase(children), session)

    private suspend fun awaitChild(name: String): quest.api.dto.Child {
        repeat(400) {
            children.children().firstOrNull { it.name == name }?.let { return it }
            delay(5)
        }
        error("no child called $name was ever saved")
    }

    private suspend fun AddChildViewModel.settle(predicate: (AddChildContract.State) -> Boolean) {
        repeat(400) {
            if (predicate(state.value)) return
            delay(5)
        }
        error("state never satisfied the predicate; last was ${state.value}")
    }

    @Test fun codeIsNormalisedAndLooksTheSchoolUpOnTheSixthCharacter() = runBlocking {
        auth.signIn("parent@example.com", "secret123")
        val vm = viewModel()
        vm.dispatch(AddChildContract.Intent.SchoolCode("al no"))
        vm.settle { it.schoolCode == "ALNO" }
        assertNull(vm.state.value.school, "a short code is not looked up")

        vm.dispatch(AddChildContract.Intent.SchoolCode("al-noor"))
        vm.settle { it.school != null }
        assertEquals("ALNOOR", vm.state.value.schoolCode)
        assertEquals("Al Noor School", vm.state.value.school?.name)
        assertEquals(AddChildContract.JoinStep.FOUND, vm.state.value.joinStep)
        assertFalse(vm.state.value.schoolNotFound)
    }

    @Test fun unknownCodeShowsTheRedBandAndNoSchool() = runBlocking {
        auth.signIn("parent@example.com", "secret123")
        val vm = viewModel()
        vm.dispatch(AddChildContract.Intent.SchoolCode("ZZZ999"))
        vm.settle { it.schoolNotFound }
        assertNull(vm.state.value.school)
        assertEquals(AddChildContract.JoinStep.NONE, vm.state.value.joinStep)
    }

    @Test fun confirmingAppliesTheThemeAndNarrowsTheChoosers() = runBlocking {
        auth.signIn("parent@example.com", "secret123")
        val vm = viewModel()
        assertEquals(listOf(1, 2, 3), vm.state.value.gradeOptions)

        vm.dispatch(AddChildContract.Intent.SchoolCode("ALNOOR"))
        vm.settle { it.school != null }
        // Found but not confirmed: nothing has changed colour and every option is still offered.
        assertNull(session.theme.value)

        vm.dispatch(AddChildContract.Intent.ConfirmSchool)
        vm.settle { it.joinStep == AddChildContract.JoinStep.CONFIRMED }
        assertEquals(FakeContentApi.alNoorTheme, session.theme.value)
        assertEquals("Al Noor Quest", session.branding.value.appName)
        assertEquals(FakeContentApi.alNoor.curriculumOptions, vm.state.value.curriculumOptions)
        assertEquals(FakeContentApi.alNoor.gradeOptions, vm.state.value.gradeOptions)
    }

    @Test fun savingSendsTheCodeAndTheChildComesBackInTheSchool() = runBlocking {
        auth.signIn("parent@example.com", "secret123")
        val vm = viewModel()
        vm.dispatch(AddChildContract.Intent.Name("Maya"))
        vm.dispatch(AddChildContract.Intent.SchoolCode("ALNOOR"))
        vm.settle { it.school != null }
        vm.dispatch(AddChildContract.Intent.ConfirmSchool)
        vm.settle { it.joinStep == AddChildContract.JoinStep.CONFIRMED }
        vm.dispatch(AddChildContract.Intent.Grade(2))
        vm.dispatch(AddChildContract.Intent.Save)
        vm.settle { !it.busy && it.error == null && session.schoolId.value != null }

        val child = children.children().single()
        assertEquals("Maya", child.name)
        assertEquals(FakeContentApi.AL_NOOR_ID, child.schoolId)
        assertEquals(2, child.grade)
        assertEquals(FakeContentApi.AL_NOOR_ID, session.schoolId.value)

        // The school outlives this process: a fresh session restores its theme, name and flags from the device.
        val restored = SchoolSessionImpl(api, api, settings)
        restored.restore()
        assertEquals(FakeContentApi.AL_NOOR_ID, restored.schoolId.value)
        assertEquals(FakeContentApi.alNoorTheme, restored.theme.value)
        assertEquals("Al Noor School", restored.branding.value.schoolName)
    }

    @Test fun aChildAddedWithoutACodeStaysInTheDefaultSchool() = runBlocking {
        auth.signIn("parent@example.com", "secret123")
        val vm = viewModel()
        vm.dispatch(AddChildContract.Intent.Name("Omar"))
        vm.dispatch(AddChildContract.Intent.Save)
        vm.settle { !it.busy && it.loaded }

        val child = children.children().single()
        assertEquals("default", child.schoolId)
        assertNull(session.schoolId.value, "no school was joined, so nothing is themed")
        assertNull(session.theme.value)
    }

    @Test fun clearingTheCodeUndoesTheJoin() = runBlocking {
        auth.signIn("parent@example.com", "secret123")
        val vm = viewModel()
        vm.dispatch(AddChildContract.Intent.SchoolCode("ALNOOR"))
        vm.settle { it.school != null }
        vm.dispatch(AddChildContract.Intent.ConfirmSchool)
        vm.settle { it.joinStep == AddChildContract.JoinStep.CONFIRMED }
        vm.dispatch(AddChildContract.Intent.ClearSchool)
        vm.settle { it.joinStep == AddChildContract.JoinStep.NONE }

        assertEquals("", vm.state.value.schoolCode)
        assertNull(vm.state.value.school)
        assertEquals(listOf(Curriculum.AMERICAN, Curriculum.BRITISH), vm.state.value.curriculumOptions)
        // and the app is no longer wearing a school it never joined
        assertNull(session.theme.value)
        assertEquals("Homework Quest", session.branding.value.appName)
    }

    /** D16 slice 2 / F6: the join is the parent's, so the second child is not asked for the code again. */
    @Test fun theCodeIsAskedOnceAndTheNextChildReusesIt() = runBlocking {
        auth.signIn("parent@example.com", "secret123")
        val first = viewModel()
        first.dispatch(AddChildContract.Intent.Name("Maya"))
        first.dispatch(AddChildContract.Intent.SchoolCode("ALNOOR"))
        first.settle { it.school != null }
        first.dispatch(AddChildContract.Intent.ConfirmSchool)
        first.settle { it.joinStep == AddChildContract.JoinStep.CONFIRMED }
        first.dispatch(AddChildContract.Intent.Save)
        first.settle { !it.busy && it.error == null && session.schoolId.value != null }
        assertEquals("ALNOOR", session.joinedCode.value)

        // A second Add child opens with the school already filled in and never shows the empty code field.
        val second = viewModel()
        second.settle { it.alreadyJoined && it.school != null }
        assertEquals("ALNOOR", second.state.value.schoolCode)
        assertEquals(AddChildContract.JoinStep.CONFIRMED, second.state.value.joinStep)
        assertEquals("Al Noor School", second.state.value.joinedName)
        assertEquals(FakeContentApi.alNoor.gradeOptions, second.state.value.gradeOptions)

        second.dispatch(AddChildContract.Intent.Name("Omar"))
        second.dispatch(AddChildContract.Intent.Save)
        val omar = awaitChild("Omar")
        assertEquals(FakeContentApi.AL_NOOR_ID, omar.schoolId, "the second child reached the school without a code being typed")
    }

    /** The code outlives the process: a relaunch still knows which school this parent joined. */
    @Test fun theJoinedCodeIsRestoredOnTheNextLaunch() = runBlocking {
        auth.signIn("parent@example.com", "secret123")
        val vm = viewModel()
        vm.dispatch(AddChildContract.Intent.Name("Maya"))
        vm.dispatch(AddChildContract.Intent.SchoolCode("ALNOOR"))
        vm.settle { it.school != null }
        vm.dispatch(AddChildContract.Intent.ConfirmSchool)
        vm.settle { it.joinStep == AddChildContract.JoinStep.CONFIRMED }
        vm.dispatch(AddChildContract.Intent.Save)
        vm.settle { !it.busy && session.schoolId.value != null }

        val restored = SchoolSessionImpl(api, api, settings)
        restored.restore()
        assertEquals("ALNOOR", restored.joinedCode.value)
    }

    /** "Use a different code" puts the parent back in front of an empty field. */
    @Test fun changingSchoolAsksForACodeAgain() = runBlocking {
        auth.signIn("parent@example.com", "secret123")
        val vm = viewModel()
        vm.dispatch(AddChildContract.Intent.Name("Maya"))
        vm.dispatch(AddChildContract.Intent.SchoolCode("ALNOOR"))
        vm.settle { it.school != null }
        vm.dispatch(AddChildContract.Intent.ConfirmSchool)
        vm.settle { it.joinStep == AddChildContract.JoinStep.CONFIRMED }
        vm.dispatch(AddChildContract.Intent.Save)
        vm.settle { !it.busy && session.schoolId.value != null }

        val second = viewModel()
        second.settle { it.alreadyJoined }
        second.dispatch(AddChildContract.Intent.ClearSchool)
        second.settle { !it.alreadyJoined }
        assertEquals("", second.state.value.schoolCode)
        assertEquals(AddChildContract.JoinStep.NONE, second.state.value.joinStep)
        assertNull(second.state.value.joinedName)
    }

    @Test fun theCodeShapeIsSixUpperCaseAlphanumerics() {
        assertEquals("ALNOOR", AddChildContract.normaliseCode("  al-noor "))
        assertEquals("ABC123", AddChildContract.normaliseCode("abc123456"))
        assertEquals("", AddChildContract.normaliseCode("--- ---"))
        assertTrue(AddChildContract.normaliseCode("abcdefgh").length == AddChildContract.CODE_LENGTH)
        assertNotNull(AddChildContract.normaliseCode(""))
    }
}
