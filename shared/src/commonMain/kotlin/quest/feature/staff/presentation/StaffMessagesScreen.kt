package quest.feature.staff.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import org.koin.compose.viewmodel.koinViewModel
import quest.api.dto.ChatFrame
import quest.api.dto.ChatThread
import quest.core.mvi.MviEffect
import quest.core.mvi.MviIntent
import quest.core.mvi.MviState
import quest.core.mvi.MviViewModel
import quest.feature.chat.domain.TYPING_TIMEOUT_MS
import quest.feature.chat.presentation.ChatThreadRow
import quest.feature.parent.presentation.ParentCard
import quest.feature.parent.presentation.Strings
import quest.feature.school.domain.Flags
import quest.feature.school.presentation.FeatureGate
import quest.feature.staff.domain.StaffBadges
import quest.feature.staff.domain.StaffThreads
import quest.ui.design.AnimatedDotsLoader
import quest.ui.design.DashboardTokens
import quest.ui.design.Dimens

object StaffMessagesContract {
    data class State(
        val loading: Boolean = true,
        val threads: List<ChatThread> = emptyList(),
        val typing: Set<String> = emptySet(),
        val errorMessage: String? = null,
        /** A WORKER has no chat partners yet, so the tab says so rather than showing an empty list. */
        val noChat: Boolean = false,
    ) : MviState

    sealed interface Intent : MviIntent { data object Load : Intent }
    sealed interface Effect : MviEffect
}

/**
 * Her one inbox: `GET /staff/chat/threads`, newest first, with the parent threads and the staff threads merged by the
 * facade. The rows are the parent app's own [ChatThreadRow] — one chat UI, drawn from the other end.
 */
class StaffMessagesViewModel(
    private val threads: StaffThreads,
    private val badges: StaffBadges,
) : MviViewModel<StaffMessagesContract.State, StaffMessagesContract.Intent, StaffMessagesContract.Effect>(StaffMessagesContract.State()) {

    private val typingJobs = mutableMapOf<String, Job>()

    override suspend fun handle(intent: StaffMessagesContract.Intent) {
        when (intent) {
            StaffMessagesContract.Intent.Load -> load()
        }
    }

    private suspend fun load() {
        if (!threads.hasChat()) {
            reduce { copy(loading = false, noChat = true, threads = emptyList()) }
            return
        }
        threads.connect()
        try {
            val rows = threads.list()
            reduce { copy(loading = false, threads = rows, errorMessage = null, noChat = false) }
            badges.refresh()
        } catch (e: Throwable) {
            reduce { copy(loading = false, errorMessage = e.message, threads = emptyList()) }
        }
    }

    private fun stopTyping(threadId: String) {
        typingJobs.remove(threadId)?.cancel()
        reduce { copy(typing = typing - threadId) }
    }

    init {
        launch {
            threads.frames.collect { frame ->
                when (frame) {
                    // A `typing` frame never reaches the person who sent it, so one about a thread of hers is the
                    // other side's whichever word `from` carries (a staff thread has `teacher` at both ends).
                    is ChatFrame.Typing -> if (current.threads.any { it.id == frame.threadId }) {
                        reduce { copy(typing = typing + frame.threadId) }
                        typingJobs.remove(frame.threadId)?.cancel()
                        typingJobs[frame.threadId] = launch { delay(TYPING_TIMEOUT_MS); stopTyping(frame.threadId) }
                    }
                    is ChatFrame.Message, is ChatFrame.Read -> {
                        if (frame is ChatFrame.Message) stopTyping(frame.message.threadId)
                        runCatching {
                            val fresh = threads.list()
                            reduce { copy(threads = fresh) }
                            badges.refresh()
                        }
                    }
                    else -> {}
                }
            }
        }
    }
}

@Composable
fun StaffMessagesRoute(
    onOpenThread: (ChatThread) -> Unit,
    onHome: () -> Unit,
    onNotifications: () -> Unit,
    onAccount: () -> Unit,
) {
    val vm: StaffMessagesViewModel = koinViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.dispatch(StaffMessagesContract.Intent.Load) }
    StaffShell(
        title = { it.messages },
        currentTab = StaffTab.MESSAGES,
        onTabSelected = { tab ->
            when (tab) {
                StaffTab.ATTENDANCE -> onHome()
                StaffTab.MESSAGES -> Unit
                StaffTab.NOTIFICATIONS -> onNotifications()
                StaffTab.ACCOUNT -> onAccount()
            }
        },
        onBack = onHome,
    ) { s, chatStrings -> StaffMessagesScreen(state, s, chatStrings, onOpenThread) }
}

@Composable
fun StaffMessagesScreen(
    state: StaffMessagesContract.State,
    strings: StaffStrings,
    chatStrings: Strings,
    onOpenThread: (ChatThread) -> Unit,
) {
    FeatureGate(Flags.STAFF_MOBILE_APP) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Dimens.s16, vertical = Dimens.s8)) {
            if (state.loading) {
                Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                    AnimatedDotsLoader(dotSize = 12.dp, spacing = 8.dp)
                }
                return@FeatureGate
            }
            val empty = when {
                state.noChat -> strings.workerNoChat
                state.errorMessage != null -> state.errorMessage
                state.threads.isEmpty() -> strings.noThreads
                else -> null
            }
            if (empty != null) {
                ParentCard(Modifier.padding(vertical = Dimens.s8)) {
                    Text(empty, style = MaterialTheme.typography.bodyLarge, color = DashboardTokens.inkSoft)
                }
                return@FeatureGate
            }
            state.threads.forEach { thread ->
                ChatThreadRow(
                    thread = thread,
                    strings = chatStrings,
                    onClick = { onOpenThread(thread) },
                    typing = thread.id in state.typing,
                    name = staffRowName(thread, chatStrings),
                    label = staffRowLabel(thread, strings, chatStrings),
                )
            }
            Spacer(Modifier.height(Dimens.s16))
        }
    }
}
