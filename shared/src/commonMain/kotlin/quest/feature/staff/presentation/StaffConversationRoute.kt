package quest.feature.staff.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import org.koin.core.qualifier.named
import quest.api.dto.ChatPeerRole
import quest.api.dto.ChatStaffRole
import quest.core.navigation.Routes
import quest.core.runCancellable
import quest.feature.chat.presentation.ChatConversationContract
import quest.feature.chat.presentation.ChatConversationScreen
import quest.feature.chat.presentation.ChatConversationViewModel
import quest.feature.chat.presentation.staffLabel
import quest.feature.chat.presentation.typingLine
import quest.feature.chat.domain.resolverOf
import quest.feature.parent.domain.ParentRepository
import quest.feature.parent.presentation.LocalStrings
import quest.feature.parent.presentation.Strings
import quest.feature.school.domain.Flags
import quest.feature.school.presentation.FeatureGate
import quest.feature.school.presentation.LocalFlags
import quest.feature.staff.domain.STAFF_CONVERSATION
import quest.feature.staff.domain.StaffBadges
import quest.feature.staff.domain.StaffProfile
import quest.feature.staff.domain.StaffRole
import quest.feature.staff.domain.StaffSessionView
import quest.feature.staff.domain.StaffThreadKey
import quest.feature.staff.domain.StaffThreads
import quest.ui.design.ParentTheme

/**
 * Feature 13: a staff member's open conversation — **the parent's screen, from the other end.**
 *
 * There is no second chat here: the composable is `ChatConversationScreen` and the view model is
 * `ChatConversationViewModel`, with a `StaffConversationSource` under it. All this route adds is her context — her
 * school's flags, her language (Arabic is never behind the parent panel's flag for a staff member) and the handful of
 * words the staff end calls the other side by, laid over the state on its way to the screen.
 */
@Composable
fun StaffConversationRoute(route: Routes.StaffConversation, onBack: () -> Unit) {
    val session: StaffSessionView = koinInject()
    val threads: StaffThreads = koinInject()
    val badges: StaffBadges = koinInject()
    val parent: ParentRepository = koinInject()
    val language by parent.language.collectAsStateWithLifecycle()
    val profile by session.profile.collectAsStateWithLifecycle()
    val staffStrings = StaffStrings.forLanguage(language)
    val strings = Strings.forLanguage(language)
    val me = profile?.userId

    // The conversation is not built until the thread is known on both ends: which bubbles are hers, and (for a
    // teacher's parent thread) that her `typing` names the child rather than the thread. A route from a tapped push
    // carries the id alone, so that case is looked up in her own rows first; a row she tapped carried it all already.
    var resolved by remember(route.threadId) { mutableStateOf<StaffThreadKey?>(null) }
    LaunchedEffect(route.threadId, me) {
        val userId = me ?: return@LaunchedEffect
        if (route.peerName.isNotBlank()) { resolved = route.toKey(userId, profile); return@LaunchedEffect }
        val row = runCancellable { threads.list() }.getOrNull()?.firstOrNull { it.id == route.threadId }
        resolved = if (row != null) StaffThreadKey.of(row, userId, profile?.role) else route.toKey(userId, profile)
        row?.unread?.takeIf { it > 0 }?.let { badges.readMessages(it) }
    }
    val key = resolved ?: return

    val vm: ChatConversationViewModel = koinViewModel(
        qualifier = named(STAFF_CONVERSATION), key = "staff-${route.threadId}",
    ) { parametersOf(key) }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.dispatch(ChatConversationContract.Intent.Load) }

    CompositionLocalProvider(LocalFlags provides session.flagStore, LocalStrings provides strings, LocalStaffStrings provides staffStrings) {
        FeatureGate(Flags.STAFF_MOBILE_APP) {
            FeatureGate(Flags.CHAT) {
                ParentTheme(rtl = staffStrings.isRtl) {
                    ChatConversationScreen(
                        state = state.staffLabelled(key, staffStrings, strings),
                        strings = strings,
                        onBack = onBack,
                        onInputChange = { vm.dispatch(ChatConversationContract.Intent.UpdateInput(it)) },
                        onSend = { vm.dispatch(ChatConversationContract.Intent.SendMessage) },
                        onRetry = { vm.dispatch(ChatConversationContract.Intent.RetrySend(it)) },
                        onPick = { vm.dispatch(ChatConversationContract.Intent.AddFiles(it)) },
                        onRemoveDraft = { vm.dispatch(ChatConversationContract.Intent.RemoveDraft(it)) },
                        onRetryDraft = { vm.dispatch(ChatConversationContract.Intent.RetryDraft(it)) },
                    )
                }
            }
        }
    }
}

private fun Routes.StaffConversation.toKey(myUserId: String, profile: StaffProfile?): StaffThreadKey = StaffThreadKey(
    threadId = threadId,
    myUserId = myUserId,
    peerUserId = peerUserId,
    peerName = peerName,
    childId = childId,
    childName = childName,
    typeByChild = withParent && profile?.role == StaffRole.TEACHER && childId != null,
    withParent = withParent,
    peerRole = ChatPeerRole.entries.firstOrNull { it.name == peerRole },
)

/** Her end of [this] thread as the view model starts it; the labels are laid on by [staffLabelled] when it draws. */
fun StaffThreadKey.conversationState(): ChatConversationContract.State = ChatConversationContract.State(
    teacherId = peerUserId,
    teacherName = peerName,
    threadId = threadId.takeIf { it.isNotBlank() },
    childId = childId.orEmpty(),
)

/**
 * The four labels the staff end needs: who is on the other side, what to call her, what an empty thread says, and
 * who the typing line names — a parent, or a colleague by her role, which is what `resolverOf` already spells.
 */
internal fun ChatConversationContract.State.staffLabelled(
    key: StaffThreadKey,
    staffStrings: StaffStrings,
    strings: Strings,
): ChatConversationContract.State = copy(
    headerName = key.peerName.takeIf { it.isNotBlank() },
    headerSubtitle = if (key.withParent) {
        listOfNotNull(staffStrings.parent, key.childName?.takeIf { it.isNotBlank() }).joinToString(" · ")
    } else {
        staffLabel(key.staffRole(), null, null, strings)
    },
    emptyMessage = if (key.withParent) staffStrings.emptyConversationParent else staffStrings.emptyConversationStaff,
    typingText = if (key.withParent) staffStrings.typingParent else typingLine(resolverOf(key.peerRole, ChatStaffRole.TEACHER, false), strings),
)

private fun StaffThreadKey.staffRole(): ChatStaffRole = when (peerRole) {
    ChatPeerRole.COORDINATOR -> ChatStaffRole.COORDINATOR
    ChatPeerRole.MANAGERIAL, ChatPeerRole.ADMIN -> ChatStaffRole.MANAGERIAL
    else -> ChatStaffRole.TEACHER
}
