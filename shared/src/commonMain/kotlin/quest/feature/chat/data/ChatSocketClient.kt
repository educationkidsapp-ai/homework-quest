package quest.feature.chat.data

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import quest.api.AuthProvider
import quest.api.dto.ChatCommand
import quest.api.dto.ChatFrame
import quest.core.json.AppJson
import quest.feature.chat.domain.ChatConnectionState

/**
 * Manages the WebSocket connection to `/ws/chat`.
 * Handles Bearer token authentication, JSON frame parsing, heartbeat ping/pong,
 * watchdog reconnection after missed pings, and exponential backoff.
 */
class ChatSocketClient(
    private val baseUrl: String,
    private val auth: AuthProvider,
    private val httpClient: HttpClient,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val _connectionState = MutableStateFlow(ChatConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ChatConnectionState> = _connectionState.asStateFlow()

    private val _incomingFrames = MutableSharedFlow<ChatFrame>(extraBufferCapacity = 64)
    val incomingFrames: SharedFlow<ChatFrame> = _incomingFrames.asSharedFlow()

    private var connectionJob: Job? = null
    private var watchdogJob: Job? = null
    private var activeSession: DefaultClientWebSocketSession? = null

    private var lastReceivedMillis: Long = 0L

    fun connect() {
        if (connectionJob?.isActive == true) return
        connectionJob = scope.launch {
            runConnectionLoop()
        }
    }

    fun disconnect() {
        connectionJob?.cancel()
        connectionJob = null
        watchdogJob?.cancel()
        watchdogJob = null
        scope.launch {
            try {
                activeSession?.close()
            } catch (_: Throwable) {}
            activeSession = null
            _connectionState.value = ChatConnectionState.DISCONNECTED
        }
    }

    suspend fun sendCommand(command: ChatCommand): Boolean {
        val session = activeSession ?: return false
        return try {
            val json = AppJson.encodeToString(ChatCommand.serializer(), command)
            session.send(Frame.Text(json))
            true
        } catch (e: Throwable) {
            false
        }
    }

    private suspend fun runConnectionLoop() {
        var backoffMs = 1_000L
        val maxBackoffMs = 30_000L

        while (scope.isActive) {
            _connectionState.value = ChatConnectionState.CONNECTING
            try {
                val token = auth.idToken()
                if (token.isNullOrBlank()) {
                    _connectionState.value = ChatConnectionState.DISCONNECTED
                    delay(2_000L)
                    continue
                }

                val wsUrl = buildWsUrl(baseUrl, token)
                val session = httpClient.webSocketSession(wsUrl) {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }
                activeSession = session
                _connectionState.value = ChatConnectionState.CONNECTED
                backoffMs = 1_000L // Reset backoff on successful connect
                lastReceivedMillis = quest.core.platform.Today.epochMillis()

                startWatchdog(session)

                for (frame in session.incoming) {
                    lastReceivedMillis = quest.core.platform.Today.epochMillis()
                    if (frame is Frame.Text) {
                        handleTextFrame(frame.readText())
                    }
                }
            } catch (e: CancellationException) {
                break
            } catch (e: Throwable) {
                // Connection failed or closed
            } finally {
                watchdogJob?.cancel()
                try {
                    activeSession?.close()
                } catch (_: Throwable) {}
                activeSession = null
                _connectionState.value = ChatConnectionState.DISCONNECTED
            }

            if (!scope.isActive) break
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(maxBackoffMs)
        }
    }

    private fun startWatchdog(session: DefaultClientWebSocketSession) {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (isActive) {
                delay(15_000L)
                val elapsed = quest.core.platform.Today.epochMillis() - lastReceivedMillis
                // If no frame received for 90s (server pings every 30s), treat socket as stalled and reconnect
                if (elapsed > 90_000L) {
                    try {
                        session.close()
                    } catch (_: Throwable) {}
                    break
                }
            }
        }
    }

    private suspend fun handleTextFrame(text: String) {
        try {
            val frame = AppJson.decodeFromString(ChatFrame.serializer(), text)
            when (frame) {
                is ChatFrame.Ping -> {
                    // Immediate pong response as per contract
                    sendCommand(ChatCommand.Pong)
                }
                else -> {
                    _incomingFrames.emit(frame)
                }
            }
        } catch (_: Throwable) {
            // Malformed frame or unexpected payload
        }
    }

    private fun buildWsUrl(httpBase: String, token: String): String {
        val trimmed = httpBase.trimEnd('/')
        val wsBase = when {
            trimmed.startsWith("https://") -> "wss://" + trimmed.removePrefix("https://")
            trimmed.startsWith("http://") -> "ws://" + trimmed.removePrefix("http://")
            else -> "wss://$trimmed"
        }
        return "$wsBase/ws/chat?token=$token"
    }
}
