package quest.feature.school

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import quest.api.AuthProvider
import quest.api.dto.Child
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * D16 slice 3 — the **class** join code (`docs/teacher-flow.md` §2). It is the narrower of the two codes: it names a
 * section, so the child is created already on its roster and her course comes off the card rather than out of the
 * form. Without one she is created unplaced, exactly as the app did before, which stays supported.
 */
class ClassCodeTest {
    private val db = Db(DriverFactory(null))
    private val settings = SettingsStore(db)
    private val auth: AuthProvider = FakeAuth(settings)
    private val api = FakeContentApi(auth, delayMillis = 0)
    private val children = ChildrenRepositoryImpl(api, db, settings, auth)
    private val session = SchoolSessionImpl(api, api, settings)

    @BeforeTest fun setUp() = Dispatchers.setMain(Dispatchers.Default)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = AddChildViewModel(null, children, AddChildUseCase(children), session)

    private suspend fun AddChildViewModel.settle(predicate: (AddChildContract.State) -> Boolean) {
        repeat(400) {
            if (predicate(state.value)) return
            delay(5)
        }
        error("state never satisfied the predicate; last was ${state.value}")
    }

    private suspend fun awaitSection(childId: String): String? {
        repeat(400) {
            children.sectionName(childId)?.let { return it }
            delay(5)
        }
        return null
    }

    private suspend fun awaitChild(name: String): Child {
        repeat(400) {
            children.children().firstOrNull { it.name == name }?.let { return it }
            delay(5)
        }
        error("no child called $name was ever saved")
    }

    @Test fun aClassCodeNamesTheSectionAndTakesTheCourseOffTheForm() = runBlocking {
        auth.signIn("parent@example.com", "secret123")
        val vm = viewModel()
        vm.dispatch(AddChildContract.Intent.ClassCode("class-1"))
        vm.settle { it.section != null }

        val section = vm.state.value.section!!
        assertEquals("CLASS1", vm.state.value.classCode)
        assertEquals("1A British", section.name)
        assertEquals("Al Noor School", section.schoolName)
        assertTrue(vm.state.value.courseIsFixed, "the card answers the course, so the choosers come off")
        assertEquals(Curriculum.BRITISH, vm.state.value.curriculum)
        assertEquals(1, vm.state.value.grade)
        assertFalse(vm.state.value.classNotFound)
    }

    @Test fun savingWithAClassCodeCreatesThePlacedChildAndRemembersTheSection() = runBlocking {
        auth.signIn("parent@example.com", "secret123")
        val vm = viewModel()
        vm.dispatch(AddChildContract.Intent.Name("Nour"))
        // An American grade 3 picked by hand, then a British grade 1 class card: the card wins, as it does on the server.
        vm.dispatch(AddChildContract.Intent.SetCurriculum(Curriculum.AMERICAN))
        vm.dispatch(AddChildContract.Intent.Grade(3))
        vm.dispatch(AddChildContract.Intent.ClassCode("CLASS1"))
        vm.settle { it.section != null }
        vm.dispatch(AddChildContract.Intent.Save)

        val child = awaitChild("Nour")
        assertEquals(Curriculum.BRITISH, child.curriculum)
        assertEquals(1, child.grade)
        assertEquals(FakeContentApi.AL_NOOR_ID, child.schoolId, "the class code names the school too")
        assertEquals("1A British", awaitSection(child.id), "the child list can now say which class she is in")
    }

    @Test fun anUnknownClassCodeShowsTheRedBandAndIsNeverSent() = runBlocking {
        auth.signIn("parent@example.com", "secret123")
        val vm = viewModel()
        vm.dispatch(AddChildContract.Intent.Name("Omar"))
        vm.dispatch(AddChildContract.Intent.ClassCode("ZZZ999"))
        vm.settle { it.classNotFound }
        assertNull(vm.state.value.section)
        assertFalse(vm.state.value.courseIsFixed)

        // Saving with a code the server never confirmed must not fail the save; it is simply not sent.
        vm.dispatch(AddChildContract.Intent.Save)
        val child = awaitChild("Omar")
        assertEquals("default", child.schoolId)
        assertNull(children.sectionName(child.id))
    }

    @Test fun noClassCodeStillCreatesAnUnplacedChild() = runBlocking {
        auth.signIn("parent@example.com", "secret123")
        val vm = viewModel()
        vm.dispatch(AddChildContract.Intent.Name("Sara"))
        vm.dispatch(AddChildContract.Intent.Grade(2))
        vm.dispatch(AddChildContract.Intent.Save)

        val child = awaitChild("Sara")
        assertEquals(2, child.grade)
        assertEquals("default", child.schoolId)
        assertNull(children.sectionName(child.id), "no card, no section — the teacher places her later")
    }

    @Test fun clearingTheClassCodePutsTheCourseChoosersBack() = runBlocking {
        auth.signIn("parent@example.com", "secret123")
        val vm = viewModel()
        vm.dispatch(AddChildContract.Intent.ClassCode("CLASS2"))
        vm.settle { it.section != null }
        assertEquals(Curriculum.AMERICAN, vm.state.value.curriculum)

        vm.dispatch(AddChildContract.Intent.ClearClass)
        vm.settle { it.section == null }
        assertEquals("", vm.state.value.classCode)
        assertFalse(vm.state.value.courseIsFixed)
    }
}
