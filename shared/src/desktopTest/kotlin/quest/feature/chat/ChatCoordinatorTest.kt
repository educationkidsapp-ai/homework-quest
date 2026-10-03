package quest.feature.chat

import quest.feature.chat.presentation.staffName
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import quest.api.AuthProvider
import quest.api.AuthState
import quest.api.ApiException
import quest.api.dto.ApiError
import quest.api.dto.SendChatMessageRequest
import quest.api.dto.ChatStaffRole
import quest.api.dto.ChatThread
import quest.api.dto.ChatThreadStatus
import quest.api.dto.ChatTopic
import quest.feature.chat.data.ChatRepositoryImpl
import quest.feature.chat.data.ChatSocketClient
import quest.feature.chat.domain.ChatPeer
import quest.feature.chat.presentation.ChatThreadsContract
import quest.feature.chat.presentation.avatarInitial
import quest.feature.chat.presentation.staffLabel
import quest.feature.chat.presentation.subjectLabel
import quest.feature.chat.presentation.threadDescription
import quest.feature.content.data.FakeContentApi
import quest.feature.parent.presentation.Strings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * R8 (DR3): the parent side of a coordinator conversation. B6 / M8: a Messages row is never a complaint and carries no
 * status — complaints are their own conversations (`ComplaintsTest`).
 */
class ChatCoordinatorTest {

    private class TestAuth : AuthProvider {
        private val _state = MutableStateFlow<AuthState>(AuthState.SignedIn("p1", "parent@example.com"))
        override val state: StateFlow<AuthState> = _state
        override suspend fun signIn(email: String, password: String) {}
        override suspend fun signOut() {}
        override suspend fun idToken(forceRefresh: Boolean): String = "mock-token"
    }

    private fun repo(): ChatRepositoryImpl {
        val auth = TestAuth()
        return ChatRepositoryImpl(FakeContentApi(auth), ChatSocketClient("http://localhost:8080", auth, HttpClient()))
    }

    private fun row(
        id: String?,
        name: String,
        role: ChatStaffRole,
        subject: String? = null,
        unread: Int = 0,
    ) = ChatThread(
        id = id, childId = "c1", childName = "Maya", teacherId = "s-$name", teacherName = name,
        className = "1A British", subject = subject, unread = unread, staffRole = role,
    )

    // ---- 1. the row: staffRole decides the label on it

    @Test
    fun aRowNamesTheRoleAndTheSubject() {
        val strings = Strings.en
        assertEquals("Teacher · Math · 1A British", staffLabel(row(null, "Ms. Sara", ChatStaffRole.TEACHER, "Math"), strings))
        assertEquals(
            "Subject coordinator · Math · 1A British",
            staffLabel(row(null, "Ms. Lina", ChatStaffRole.COORDINATOR, "Math"), strings),
        )
        // A row with no subject still says whose side it is rather than falling back to an empty line.
        assertEquals("Subject coordinator", staffLabel(ChatStaffRole.COORDINATOR, null, null, strings))
        assertEquals("منسّق المادة · رياضيات", staffLabel(ChatStaffRole.COORDINATOR, "math", null, Strings.ar))
    }

    @Test
    fun aRowFromAServerPredatingR4ReadsAsATeacherQuestion() {
        // Every R4 field has a contract default, so the C3 shape decodes unchanged: teacher, question, open.
        val old = ChatThread(childId = "c1", childName = "Maya", teacherId = "t1", teacherName = "Ms. Sara")
        assertEquals(ChatStaffRole.TEACHER, old.staffRole)
        assertEquals(ChatTopic.QUESTION, old.topic)
        assertEquals(ChatThreadStatus.OPEN, old.status)
        assertNull(old.resolvedAt)
        assertEquals("Teacher", staffLabel(old, Strings.en))
    }

    @Test
    fun theDescriptionSpeaksTheRowWithoutAnyComplaintWords() {
        // M8: an old thread the server still labels `complaint` / `resolved` reads as a plain Messages row here.
        val old = row("th-1", "Ms. Lina", ChatStaffRole.COORDINATOR, "Math", unread = 2).copy(topic = ChatTopic.COMPLAINT, status = ChatThreadStatus.RESOLVED)
        val said = threadDescription(old, Strings.en)
        assertTrue(said.startsWith("Ms. Lina, Subject coordinator · Math · 1A British"), said)
        assertFalse(said.contains("Complaint"), said)
        assertFalse(said.contains("Resolved"), said)
        assertTrue(said.contains("2 Messages"), said)
    }

    @Test
    fun theListSplitsTeachersFromCoordinators() {
        val state = ChatThreadsContract.State(
            loading = false,
            threads = listOf(
                row("th-1", "Ms. Sara", ChatStaffRole.TEACHER, "Math"),
                row(null, "Ms. Noor", ChatStaffRole.TEACHER, "English"),
                row("th-3", "Ms. Lina", ChatStaffRole.COORDINATOR, "Math"),
            ),
        )
        assertEquals(listOf("Ms. Sara", "Ms. Noor"), state.teacherThreads.map { it.teacherName })
        assertEquals(listOf("Ms. Lina"), state.coordinatorThreads.map { it.teacherName })
    }

    /** S1: the school administration writes to a parent on a `MANAGERIAL` row marked `withAdmin`. */
    @Test
    fun aThreadFromTheSchoolAdministrationHasItsOwnHeadingAndALocalisedName() {
        val admin = row("th-9", "School administration", ChatStaffRole.MANAGERIAL).copy(withAdmin = true)
        val manager = row("th-8", "Ms. Nour", ChatStaffRole.MANAGERIAL)
        val state = ChatThreadsContract.State(loading = false, threads = listOf(admin, manager))

        assertEquals(listOf("Ms. Nour"), state.managerThreads.map { it.teacherName }, "she is not the department manager")
        assertEquals(listOf(admin), state.adminThreads)
        assertEquals("School administration", staffName(admin, Strings.en))
        assertEquals("إدارة المدرسة", staffName(admin, Strings.ar))
        assertEquals("Ms. Nour", staffName(manager, Strings.ar))
        assertEquals("Maya", staffLabel(admin, Strings.en), "the line under the name says whom it is about")

        val peer = ChatPeer.of(admin)
        assertTrue(peer.withAdmin)
    }

    @Test
    fun theAvatarSkipsTheHonorificSoTwoCoordinatorsDiffer() {
        // "Ms. Lina" and "Mr. Omar" both start with M; the circle has to say L and O, or the picker is two same rows.
        assertEquals("L", avatarInitial("Ms. Lina"))
        assertEquals("O", avatarInitial("Mr. Omar"))
        assertEquals("S", avatarInitial("Sara"))
        assertEquals("لينا".take(1), avatarInitial("أ. لينا"))
        assertEquals("M", avatarInitial("  Ms.  "))
    }

    @Test
    fun theSubjectReadsInTheParentsLanguage() {
        // The server sends its own key; AR showed it untranslated before the review.
        assertEquals("Math", subjectLabel("math", Strings.en))
        assertEquals("رياضيات", subjectLabel("math", Strings.ar))
        assertEquals("إنجليزي, علوم", subjectLabel("english, science", Strings.ar))
        // A key this app does not know is shown as the server wrote it, like an unknown score band.
        assertEquals("astronomy", subjectLabel("astronomy", Strings.ar))
        assertEquals("منسّق المادة · رياضيات", staffLabel(ChatStaffRole.COORDINATOR, "math", null, Strings.ar))
    }

    // ---- 2. the coordinator list, and Messages without complaints (B6)

    @Test
    fun theCoordinatorListIsSeparateFromTheThreadList() = runTest {
        val repo = repo()
        val coordinators = repo.coordinators("c1")
        assertTrue(coordinators.isNotEmpty())
        assertTrue(coordinators.all { it.staffRole == ChatStaffRole.COORDINATOR })
        // Nothing written yet, so no thread exists and the parent's own list does not name her.
        assertTrue(coordinators.all { it.id == null })
        assertTrue(repo.threads("c1").none { it.staffRole == ChatStaffRole.COORDINATOR })
    }

    /** B6: Messages refuses the old way of opening a complaint, `400 complaint_moved`; the fake does as the server. */
    @Test
    fun aMessagesSendThatClaimsToBeAComplaintIsRefused() = runTest {
        val api = FakeContentApi(TestAuth())
        val refused = assertFailsWith<ApiException> {
            api.sendChatMessage("c1", "co-lina", SendChatMessageRequest("The homework is too long.", topic = ChatTopic.COMPLAINT))
        }
        assertEquals(ApiError.COMPLAINT_MOVED, refused.error.code)
        assertTrue(api.chatThreads("c1").none { it.topic == ChatTopic.COMPLAINT })
    }

    @Test
    fun aQuestionToACoordinatorIsAQuestion() = runTest {
        val repo = repo()
        val omar = repo.coordinators("c1").first { it.teacherName == "Mr. Omar" }
        repo.sendMessage("c1", omar.teacherId, "When is the science trip?", "cid-4")
        assertEquals(ChatTopic.QUESTION, repo.threads("c1").single { it.teacherId == omar.teacherId }.topic)
    }

    // ---- 4. the peer the conversation opens with

    @Test
    fun aPeerCarriesWhatTheRowAlreadyKnew() {
        val thread = row("th-9", "Ms. Lina", ChatStaffRole.COORDINATOR, "Math")
        val peer = ChatPeer.of(thread)
        assertEquals("s-Ms. Lina", peer.staffId)
        assertEquals(ChatStaffRole.COORDINATOR, peer.staffRole)
        assertEquals("Math", peer.subject)
        assertEquals("th-9", peer.threadId, "the row's own thread id is what every frame is matched against")
    }
}
