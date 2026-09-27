package quest.feature.chat.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import quest.api.ApiException
import quest.api.dto.ChatFrame
import quest.api.dto.ChatStaffRole
import quest.api.dto.ChatThread
import quest.api.dto.Curriculum
import quest.core.mvi.MviEffect
import quest.core.mvi.MviIntent
import quest.core.mvi.MviState
import quest.core.mvi.MviViewModel
import quest.feature.chat.domain.ChatRepository
import quest.feature.chat.domain.applyStatus
import quest.feature.children.domain.ChildrenRepository
import quest.feature.parent.presentation.ParentButton
import quest.feature.parent.presentation.ParentCard
import quest.feature.parent.presentation.ParentShell
import quest.feature.parent.presentation.SectionTitle
import quest.feature.parent.presentation.Strings
import quest.feature.school.domain.Flags
import quest.feature.school.presentation.FeatureGate
import quest.ui.design.Dimens
import quest.ui.design.Palette

object ChatThreadsContract {
    data class State(
        val loading: Boolean = true,
        val childNotPlaced: Boolean = false,
        val errorMessage: String? = null,
        val threads: List<ChatThread> = emptyList(),
        /** The child's track, so a manager row names her department rather than the child's class (RM4). */
        val curriculum: Curriculum? = null,
    ) : MviState {
        /** Her teachers, the coordinators (R8) and the department manager (RM4) she has a thread with — three headings. */
        val teacherThreads: List<ChatThread> get() = threads.filter { it.staffRole == ChatStaffRole.TEACHER }
        val coordinatorThreads: List<ChatThread> get() = threads.filter { it.staffRole == ChatStaffRole.COORDINATOR }
        val managerThreads: List<ChatThread> get() = threads.filter { it.staffRole == ChatStaffRole.MANAGERIAL }
    }

    sealed interface Intent : MviIntent {
        data object Load : Intent
    }

    sealed interface Effect : MviEffect
}

class ChatThreadsViewModel(
    private val children: ChildrenRepository,
    private val chat: ChatRepository,
) : MviViewModel<ChatThreadsContract.State, ChatThreadsContract.Intent, ChatThreadsContract.Effect>(ChatThreadsContract.State()) {

    override suspend fun handle(intent: ChatThreadsContract.Intent) {
        when (intent) {
            ChatThreadsContract.Intent.Load -> loadThreads()
        }
    }

    private suspend fun loadThreads() {
        val child = children.currentChild.value
        if (child == null) {
            reduce { copy(loading = false, threads = emptyList()) }
            return
        }

        chat.connect()

        try {
            val list = chat.threads(child.id)
            reduce { copy(loading = false, childNotPlaced = false, errorMessage = null, threads = list, curriculum = child.curriculum) }
        } catch (e: ApiException) {
            if (e.error.code == "child_not_placed") {
                reduce { copy(loading = false, childNotPlaced = true, errorMessage = null, threads = emptyList()) }
            } else {
                reduce { copy(loading = false, errorMessage = e.message, threads = emptyList()) }
            }
        } catch (e: Throwable) {
            reduce { copy(loading = false, errorMessage = e.message, threads = emptyList()) }
        }
    }

    init {
        // Refresh thread list when new incoming messages or reads land; a `status` frame (R4) needs no request —
        // it carries everything the row's chip shows, so the list moves even while the network is gone.
        launch {
            chat.incomingFrames.collect { frame ->
                when (frame) {
                    is ChatFrame.Status -> reduce { copy(threads = applyStatus(threads, frame.threadId, frame.status, frame.at)) }
                    is ChatFrame.Message, is ChatFrame.Read -> {
                        val child = children.currentChild.value ?: return@collect
                        runCatching {
                            val fresh = chat.threads(child.id)
                            reduce { copy(threads = fresh) }
                        }
                    }
                    else -> {}
                }
            }
        }
    }
}

@Composable
fun ChatThreadsRoute(
    onBack: () -> Unit,
    onOpenConversation: (ChatThread) -> Unit,
    onMessageCoordinator: () -> Unit,
) {
    val vm: ChatThreadsViewModel = koinViewModel()
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(vm) {
        vm.dispatch(ChatThreadsContract.Intent.Load)
    }

    FeatureGate(Flags.CHAT) {
        ParentShell(title = { it.messages }, onBack = onBack) { strings ->
            ChatThreadsScreen(
                state = state,
                strings = strings,
                onSelectThread = onOpenConversation,
                onMessageCoordinator = onMessageCoordinator,
            )
        }
    }
}

@Composable
fun ChatThreadsScreen(
    state: ChatThreadsContract.State,
    strings: Strings,
    onSelectThread: (ChatThread) -> Unit,
    onMessageCoordinator: () -> Unit = {},
) {
    Column(
        Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Dimens.s16, vertical = Dimens.s8),
    ) {
        if (state.loading) {
            Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
            return
        }

        if (state.childNotPlaced) {
            ParentCard(Modifier.padding(vertical = Dimens.s8)) {
                Text(strings.childNotPlaced, style = MaterialTheme.typography.bodyLarge, color = Palette.parentInk)
            }
            return
        }

        if (state.threads.isEmpty()) {
            ParentCard(Modifier.padding(vertical = Dimens.s8)) {
                Text(strings.noTeachers, style = MaterialTheme.typography.bodyLarge, color = Palette.parentInkSoft)
            }
        }

        if (state.teacherThreads.isNotEmpty()) {
            SectionTitle(strings.teachersGroup)
            state.teacherThreads.forEach { ChatThreadRow(it, strings, { onSelectThread(it) }) }
        }

        if (state.coordinatorThreads.isNotEmpty()) {
            SectionTitle(strings.coordinatorsGroup)
            state.coordinatorThreads.forEach { ChatThreadRow(it, strings, { onSelectThread(it) }) }
        }

        if (state.managerThreads.isNotEmpty()) {
            SectionTitle(strings.managersGroup)
            val department = departmentWord(state.curriculum, strings)
            state.managerThreads.forEach { ChatThreadRow(it, strings, { onSelectThread(it) }, department = department) }
        }

        Spacer(Modifier.height(Dimens.s16))
        ParentButton(strings.messageStaff, onMessageCoordinator, primary = false, icon = "+")
        Spacer(Modifier.height(Dimens.s16))
    }
}
