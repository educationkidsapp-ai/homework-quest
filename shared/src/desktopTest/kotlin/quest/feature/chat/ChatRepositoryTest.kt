package quest.feature.chat

import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import quest.api.AuthProvider
import quest.api.AuthState
import quest.api.ContentApi
import quest.api.dto.ChatMessage
import quest.api.dto.ChatReadReceipt
import quest.api.dto.ChatSender
import quest.api.dto.ChatThread
import quest.api.dto.SendChatMessageRequest
import quest.feature.chat.data.ChatRepositoryImpl
import quest.feature.chat.data.ChatSocketClient
import quest.feature.chat.domain.ChatConnectionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatRepositoryTest {

    private class TestAuthProvider : AuthProvider {
        private val _state = MutableStateFlow<AuthState>(AuthState.SignedIn("p1", "parent@example.com"))
        override val state: StateFlow<AuthState> = _state
        override suspend fun signIn(email: String, password: String) {}
        override suspend fun register(email: String, password: String) {}
        override suspend fun signInWithGoogle() {}
        override suspend fun signOut() {}
        override suspend fun idToken(forceRefresh: Boolean): String = "mock-token-123"
    }

    private class TestContentApi : ContentApi by quest.feature.content.data.FakeContentApi(TestAuthProvider()) {
        val sentRequests = mutableListOf<SendChatMessageRequest>()
        val readMarked = mutableListOf<Pair<String, String>>()

        override suspend fun chatThreads(childId: String): List<ChatThread> {
            return listOf(
                ChatThread(
                    id = "th-1",
                    childId = childId,
                    childName = "Maya",
                    teacherId = "t1",
                    teacherName = "Ms. Sara",
                    className = "1A British",
                    subject = "Math",
                    unread = 2,
                    lastMessage = ChatMessage("m1", "th-1", ChatSender.TEACHER, "t1", "Welcome to class!", 1_758_450_000_000L),
                )
            )
        }

        override suspend fun chatMessages(
            childId: String,
            teacherId: String,
            before: String?,
            since: String?,
            limit: Int?,
        ): List<ChatMessage> {
            return listOf(
                ChatMessage("m1", "th-1", ChatSender.TEACHER, teacherId, "Welcome to class!", 1_758_450_000_000L),
                ChatMessage("m2", "th-1", ChatSender.PARENT, "p1", "Thank you!", 1_758_450_100_000L),
            )
        }

        override suspend fun sendChatMessage(
            childId: String,
            teacherId: String,
            request: SendChatMessageRequest,
        ): ChatMessage {
            sentRequests.add(request)
            return ChatMessage("m-new", "th-1", ChatSender.PARENT, "p1", request.body, 1_758_450_200_000L)
        }

        override suspend fun markChatRead(childId: String, teacherId: String): ChatReadReceipt {
            readMarked.add(childId to teacherId)
            return ChatReadReceipt("th-1", ChatSender.PARENT, 1_758_450_300_000L)
        }
    }

    @Test
    fun testThreadsAndMessages() = runTest {
        val auth = TestAuthProvider()
        val contentApi = TestContentApi()
        val socket = ChatSocketClient("http://localhost:8080", auth, HttpClient())
        val repo = ChatRepositoryImpl(contentApi, socket)

        val threads = repo.threads("c1")
        assertEquals(1, threads.size)
        assertEquals("Ms. Sara", threads[0].teacherName)
        assertEquals(2, threads[0].unread)

        val messages = repo.messages("c1", "t1")
        assertEquals(2, messages.size)
        assertEquals("Welcome to class!", messages[0].body)
        assertEquals("Thank you!", messages[1].body)
    }

    @Test
    fun testSendMessageAndMarkRead() = runTest {
        val auth = TestAuthProvider()
        val contentApi = TestContentApi()
        val socket = ChatSocketClient("http://localhost:8080", auth, HttpClient())
        val repo = ChatRepositoryImpl(contentApi, socket)

        val sent = repo.sendMessage("c1", "t1", "Hello Teacher", "client-uuid-1")
        assertEquals("Hello Teacher", sent.body)
        assertEquals(1, contentApi.sentRequests.size)
        assertEquals("client-uuid-1", contentApi.sentRequests[0].clientId)

        repo.markRead("c1", "t1")
        assertEquals(1, contentApi.readMarked.size)
        assertEquals("c1" to "t1", contentApi.readMarked[0])
    }

    @Test
    fun testInitialConnectionState() {
        val auth = TestAuthProvider()
        val contentApi = TestContentApi()
        val socket = ChatSocketClient("http://localhost:8080", auth, HttpClient())
        val repo = ChatRepositoryImpl(contentApi, socket)

        assertEquals(ChatConnectionState.DISCONNECTED, repo.connectionState.value)
    }
}
