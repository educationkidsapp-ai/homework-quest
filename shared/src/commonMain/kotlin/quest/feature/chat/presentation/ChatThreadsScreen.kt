package quest.feature.chat.presentation

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.koin.compose.viewmodel.koinViewModel
import quest.api.ApiException
import quest.api.dto.ChatFrame
import quest.api.dto.ChatThread
import quest.core.mvi.MviEffect
import quest.core.mvi.MviIntent
import quest.core.mvi.MviState
import quest.core.mvi.MviViewModel
import quest.feature.chat.domain.ChatRepository
import quest.feature.children.domain.ChildrenRepository
import quest.feature.parent.presentation.Chip
import quest.feature.school.domain.Flags
import quest.feature.school.presentation.FeatureGate
import quest.feature.parent.presentation.ParentCard
import quest.feature.parent.presentation.ParentShell
import quest.feature.parent.presentation.Strings
import quest.ui.design.Dimens
import quest.ui.design.Palette

object ChatThreadsContract {
    data class State(
        val loading: Boolean = true,
        val childNotPlaced: Boolean = false,
        val errorMessage: String? = null,
        val threads: List<ChatThread> = emptyList(),
    ) : MviState

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
            reduce { copy(loading = false, childNotPlaced = false, errorMessage = null, threads = list) }
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
        // Refresh thread list when new incoming messages or reads land
        launch {
            chat.incomingFrames.collect { frame ->
                if (frame is ChatFrame.Message || frame is ChatFrame.Read) {
                    val child = children.currentChild.value ?: return@collect
                    runCatching {
                        val fresh = chat.threads(child.id)
                        reduce { copy(threads = fresh) }
                    }
                }
            }
        }
    }
}

@Composable
fun ChatThreadsRoute(
    onBack: () -> Unit,
    onOpenConversation: (childId: String, teacherId: String, teacherName: String) -> Unit,
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
                onSelectTeacher = { thread ->
                    onOpenConversation(thread.childId, thread.teacherId, thread.teacherName)
                },
            )
        }
    }
}

@Composable
fun ChatThreadsScreen(
    state: ChatThreadsContract.State,
    strings: Strings,
    onSelectTeacher: (ChatThread) -> Unit,
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
                Text(
                    strings.childNotPlaced,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Palette.parentInk,
                )
            }
            return
        }

        if (state.threads.isEmpty()) {
            ParentCard(Modifier.padding(vertical = Dimens.s8)) {
                Text(
                    strings.noTeachers,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Palette.parentInkSoft,
                )
            }
            return
        }

        state.threads.forEach { thread ->
            ParentCard(
                modifier = Modifier.padding(bottom = Dimens.s8),
                onClick = { onSelectTeacher(thread) },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier.size(48.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = thread.teacherName.take(1).uppercase(),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontWeight = FontWeight.Bold,
                        )
                    }

                    Spacer(Modifier.width(Dimens.s12))

                    Column(Modifier.weight(1f)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = thread.teacherName,
                                style = MaterialTheme.typography.titleMedium,
                                color = Palette.parentInk,
                                fontWeight = FontWeight.SemiBold,
                            )
                            val last = thread.lastMessage
                            if (last != null) {
                                Text(
                                    text = formatTimestamp(last.createdAt),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Palette.parentInkSoft,
                                )
                            }
                        }

                        Spacer(Modifier.height(Dimens.s4))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val preview = thread.lastMessage?.body
                                ?: listOfNotNull(thread.className, thread.subject).joinToString(" · ")
                            Text(
                                text = preview,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Palette.parentInkSoft,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )

                            if (thread.unread > 0) {
                                Spacer(Modifier.width(Dimens.s8))
                                Chip(
                                    text = "${thread.unread}",
                                    color = Palette.sun,
                                    selected = true,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatTimestamp(epochMillis: Long): String {
    val dt = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(TimeZone.currentSystemDefault())
    val hour = dt.hour.toString().padStart(2, '0')
    val minute = dt.minute.toString().padStart(2, '0')
    return "$hour:$minute"
}
