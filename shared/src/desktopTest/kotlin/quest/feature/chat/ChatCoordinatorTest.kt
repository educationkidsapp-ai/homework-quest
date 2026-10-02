package quest.feature.chat

import quest.feature.chat.presentation.staffName
import quest.feature.chat.presentation.ChatConversationContract
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import quest.api.AuthProvider
import quest.api.AuthState
import quest.api.dto.ChatStaffRole
import quest.api.dto.ChatThread
import quest.api.dto.ChatThreadStatus
import quest.api.dto.ChatTopic
import quest.feature.chat.data.ChatRepositoryImpl
import quest.feature.chat.data.ChatSocketClient
import quest.feature.chat.domain.ChatPeer
import quest.feature.chat.domain.applyStatus
import quest.feature.chat.presentation.ChatThreadsContract
import quest.feature.chat.presentation.avatarInitial
import quest.feature.chat.presentation.staffLabel
import quest.feature.chat.presentation.subjectLabel
import quest.feature.chat.presentation.threadDescription
import quest.feature.content.data.FakeContentApi
import quest.feature.parent.presentation.Strings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * R8 (DR3): the parent side of a coordinator conversation — the three R4 fields on a row, the additive `status`
 * frame, and the `topic` that only the thread-creating message may carry.
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
        topic: ChatTopic = ChatTopic.QUESTION,
        status: ChatThreadStatus = ChatThreadStatus.OPEN,
        unread: Int = 0,
    ) = ChatThread(
        id = id, childId = "c1", childName = "Maya", teacherId = "s-$name", teacherName = name,
        className = "1A British", subject = subject, unread = unread, staffRole = role, topic = topic, status = status,
    )

    // ---- 1. the row: staffRole, topic and status decide every label on it

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
    fun theDescriptionSpeaksTheChipsForAScreenReader() {
        val complaint = row("th-1", "Ms. Lina", ChatStaffRole.COORDINATOR, "Math", ChatTopic.COMPLAINT, ChatThreadStatus.RESOLVED, unread = 2)
        val said = threadDescription(complaint, Strings.en)
        assertTrue(said.startsWith("Ms. Lina, Subject coordinator · Math · 1A British"), said)
        assertTrue(said.contains("Complaint"), said)
        assertTrue(said.contains("Resolved"), said)
        assertTrue(said.contains("2 Messages"), said)
    }

    @Test
    fun theListSplitsTeachersFromCoordinators() {
        val state = ChatThreadsContract.State(
            loading = false,
            threads = listOf(
                row("th-1", "Ms. Sara", ChatStaffRole.TEACHER, "Math"),
                row(null, "Ms. Noor", ChatStaffRole.TEACHER, "English"),
                row("th-3", "Ms. Lina", ChatStaffRole.COORDINATOR, "Math", ChatTopic.COMPLAINT),
            ),
        )
        assertEquals(listOf("Ms. Sara", "Ms. Noor"), state.teacherThreads.map { it.teacherName })
        assertEquals(listOf("Ms. Lina"), state.coordinatorThreads.map { it.teacherName })
        assertEquals(ChatTopic.COMPLAINT, state.coordinatorThreads.single().topic)
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

        // The parent answers her but cannot turn the thread into a complaint.
        val peer = ChatPeer.of(admin)
        assertTrue(peer.withAdmin)
        assertFalse(ChatConversationContract.State(loading = false, withAdmin = true).canMarkComplaint)
        assertTrue(ChatConversationContract.State(loading = false).canMarkComplaint)
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

    // ---- 2. the `status` frame moves a row without a refetch

    @Test
    fun theStatusFrameResolvesOnlyItsOwnRow() {
        val threads = listOf(
            row("th-1", "Ms. Sara", ChatStaffRole.TEACHER, "Math"),
            row("th-3", "Ms. Lina", ChatStaffRole.COORDINATOR, "Math", ChatTopic.COMPLAINT),
        )
        val resolved = applyStatus(threads, "th-3", ChatThreadStatus.RESOLVED, 1_758_460_000_000L)
        assertEquals(ChatThreadStatus.OPEN, resolved[0].status)
        assertEquals(ChatThreadStatus.RESOLVED, resolved[1].status)
        assertEquals(1_758_460_000_000L, resolved[1].resolvedAt)

        // Re-opening clears the timestamp with it, so no row ever reads "resolved at …" while it is open.
        val reopened = applyStatus(resolved, "th-3", ChatThreadStatus.OPEN, 1_758_470_000_000L)
        assertEquals(ChatThreadStatus.OPEN, reopened[1].status)
        assertNull(reopened[1].resolvedAt)
    }

    @Test
    fun aStatusFrameForAThreadWeDoNotHoldChangesNothing() {
        val threads = listOf(row("th-1", "Ms. Sara", ChatStaffRole.TEACHER, "Math"))
        assertEquals(threads, applyStatus(threads, "th-stranger", ChatThreadStatus.RESOLVED, 1L))
        assertEquals(emptyList(), applyStatus(emptyList(), "th-1", ChatThreadStatus.RESOLVED, 1L))
    }

    // ---- 3. the first message carries the topic; a later one does not, for a teacher as for a coordinator

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

    @Test
    fun theFirstMessageToACoordinatorLabelsTheThreadAComplaint() = runTest {
        val repo = repo()
        val lina = repo.coordinators("c1").first { it.subject == "Math" }
        repo.sendMessage("c1", lina.teacherId, "The homework is too long every night.", "cid-1", ChatTopic.COMPLAINT)

        val thread = repo.threads("c1").single { it.teacherId == lina.teacherId }
        assertEquals(ChatStaffRole.COORDINATOR, thread.staffRole)
        assertEquals(ChatTopic.COMPLAINT, thread.topic)
        assertEquals(ChatThreadStatus.OPEN, thread.status)

        // A second send carries no topic at all — the toggle is gone once the thread exists.
        val peer = ChatPeer.of(thread)
        assertFalse(peer.resolved)
        assertEquals(ChatTopic.COMPLAINT, peer.topic)
        repo.sendMessage("c1", lina.teacherId, "Thank you for looking at it.", "cid-2")
        assertEquals(ChatTopic.COMPLAINT, repo.threads("c1").single { it.teacherId == lina.teacherId }.topic)
    }

    @Test
    fun aComplaintToATeacherLabelsHerThreadAComplaintToo() = runTest {
        // M1: a complaint may go to the teacher, the coordinator or the manager.
        val repo = repo()
        val teacher = repo.threads("c1").first { it.staffRole == ChatStaffRole.TEACHER && it.lastMessage == null }
        repo.sendMessage("c1", teacher.teacherId, "This is a complaint.", "cid-3", ChatTopic.COMPLAINT)
        val thread = repo.threads("c1").single { it.teacherId == teacher.teacherId }
        assertEquals(ChatTopic.COMPLAINT, thread.topic)
        assertEquals(ChatThreadStatus.OPEN, thread.status)
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
        val thread = row("th-9", "Ms. Lina", ChatStaffRole.COORDINATOR, "Math", ChatTopic.COMPLAINT, ChatThreadStatus.RESOLVED)
        val peer = ChatPeer.of(thread)
        assertEquals("s-Ms. Lina", peer.staffId)
        assertEquals(ChatStaffRole.COORDINATOR, peer.staffRole)
        assertEquals("Math", peer.subject)
        assertEquals(ChatTopic.COMPLAINT, peer.topic)
        assertTrue(peer.resolved)
        assertEquals("th-9", peer.threadId, "the row's own thread id is what a status frame is matched against")
    }
}
