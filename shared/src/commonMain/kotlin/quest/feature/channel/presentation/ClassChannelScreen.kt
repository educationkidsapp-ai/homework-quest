package quest.feature.channel.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import quest.api.ApiException
import quest.api.dto.ChannelPost
import quest.api.dto.TranslationSource
import quest.core.mvi.MviEffect
import quest.core.mvi.MviIntent
import quest.core.mvi.MviState
import quest.core.mvi.MviViewModel
import quest.core.text.isolate
import quest.feature.channel.domain.ClassChannelRepository
import quest.feature.chat.domain.staffRoleOf
import quest.feature.chat.presentation.MessageAttachments
import quest.feature.chat.presentation.formatClock
import quest.feature.chat.presentation.staffLabel
import quest.feature.children.domain.ChildrenRepository
import quest.feature.parent.presentation.ParentCard
import quest.feature.parent.presentation.ParentShell
import quest.feature.parent.presentation.Strings
import quest.feature.school.domain.Flags
import quest.feature.school.presentation.FeatureGate
import quest.feature.school.presentation.GateFallback
import quest.feature.translate.presentation.TranslateBlock
import quest.ui.design.AnimatedDotsLoader
import quest.ui.design.DashboardTokens
import quest.ui.design.Dimens

/**
 * Feature 32: the **class channel** a parent reads — what her child's teachers posted for the whole class, oldest
 * first with the newest at the bottom, so the page reads like the notices it is.
 *
 * It is read-only and has no composer at all: a parent never posts here, and her answer to one teacher is that
 * teacher's own one-to-one thread ("Reply privately"), which is also why no parent ever sees another parent.
 */
object ClassChannelContract {
    data class State(
        val loading: Boolean = true,
        val loadingEarlier: Boolean = false,
        val errorMessage: String? = null,
        val childId: String? = null,
        val className: String? = null,
        /** Oldest first, as the server pages them — the list is drawn in this order and scrolls to its end. */
        val posts: List<ChannelPost> = emptyList(),
        /** True while a full page came back, which is the only honest sign that there is an earlier one. */
        val hasEarlier: Boolean = false,
    ) : MviState

    sealed interface Intent : MviIntent {
        /** Opening the channel: the newest page, and every post marked read. */
        data object Load : Intent
        data object LoadEarlier : Intent
    }

    sealed interface Effect : MviEffect
}

class ClassChannelViewModel(
    private val children: ChildrenRepository,
    private val channel: ClassChannelRepository,
) : MviViewModel<ClassChannelContract.State, ClassChannelContract.Intent, ClassChannelContract.Effect>(ClassChannelContract.State()) {

    override suspend fun handle(intent: ClassChannelContract.Intent) {
        when (intent) {
            ClassChannelContract.Intent.Load -> load()
            ClassChannelContract.Intent.LoadEarlier -> loadEarlier()
        }
    }

    /**
     * One request, not two: `POST …/channel/read` answers her page, so opening the channel both reads it and clears
     * the badge the Messages tab was showing.
     */
    private suspend fun load() {
        val child = children.currentChild.value
        if (child == null) {
            reduce { copy(loading = false, childId = null, posts = emptyList()) }
            return
        }
        try {
            val page = channel.markRead(child.id)
            reduce {
                copy(
                    loading = false, childId = child.id, errorMessage = null, className = page.className,
                    posts = page.posts, hasEarlier = page.posts.size >= ClassChannelRepository.PAGE_SIZE,
                )
            }
        } catch (e: Throwable) {
            reduce { copy(loading = false, childId = child.id, errorMessage = e.messageOrNull(), posts = emptyList()) }
        }
    }

    private suspend fun loadEarlier() {
        val childId = current.childId ?: return
        val oldest = current.posts.firstOrNull()?.id ?: return
        // The screen hides the action once the start of the channel is reached; the view model refuses it too, so a
        // second tap on a stale frame cannot ask for a page that is known not to be there.
        if (current.loadingEarlier || !current.hasEarlier) return
        reduce { copy(loadingEarlier = true) }
        try {
            val page = channel.page(childId, before = oldest)
            val known = current.posts.map { it.id }.toSet()
            val older = page.posts.filterNot { it.id in known }
            reduce {
                copy(
                    loadingEarlier = false, errorMessage = null, posts = older + posts,
                    // An empty or short page is the end of the channel; nothing more is offered.
                    hasEarlier = page.posts.size >= ClassChannelRepository.PAGE_SIZE,
                )
            }
        } catch (e: Throwable) {
            reduce { copy(loadingEarlier = false, errorMessage = e.messageOrNull()) }
        }
    }

    private fun Throwable.messageOrNull(): String? = (this as? ApiException)?.message ?: message
}

@Composable
fun ClassChannelRoute(
    onBack: () -> Unit,
    onReplyPrivately: (ChannelPost) -> Unit,
) {
    val vm: ClassChannelViewModel = koinViewModel()
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(vm) { vm.dispatch(ClassChannelContract.Intent.Load) }

    GateFallback(Flags.CLASS_CHANNEL, onBack)
    FeatureGate(Flags.CHAT) {
        FeatureGate(Flags.CLASS_CHANNEL) {
            ParentShell(title = { it.teamChannel.channelTitle }, onBack = onBack) { strings ->
                ClassChannelScreen(
                    state = state, strings = strings,
                    onReplyPrivately = onReplyPrivately,
                    onLoadEarlier = { vm.dispatch(ClassChannelContract.Intent.LoadEarlier) },
                )
            }
        }
    }
}

@Composable
fun ClassChannelScreen(
    state: ClassChannelContract.State,
    strings: Strings,
    onReplyPrivately: (ChannelPost) -> Unit = {},
    onLoadEarlier: () -> Unit = {},
) {
    val scroll = rememberScrollState()
    // Newest at the bottom: a channel opened at its top would hide the post she was told about.
    LaunchedEffect(state.posts.lastOrNull()?.id) { scroll.scrollTo(scroll.maxValue) }
    Column(
        Modifier.fillMaxSize().verticalScroll(scroll)
            .padding(horizontal = Dimens.s16, vertical = Dimens.s8),
    ) {
        if (state.loading) {
            Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                AnimatedDotsLoader(dotSize = 12.dp, spacing = 8.dp)
            }
            return
        }

        if (state.className != null) {
            Text(
                state.className, style = MaterialTheme.typography.bodyMedium,
                color = DashboardTokens.inkSoft, modifier = Modifier.padding(bottom = Dimens.s8),
            )
        }

        val problem = when {
            state.errorMessage != null && state.posts.isEmpty() -> strings.somethingWrong
            state.posts.isEmpty() -> strings.teamChannel.channelEmpty
            else -> null
        }
        if (problem != null) {
            ParentCard(Modifier.padding(vertical = Dimens.s8)) {
                Text(problem, style = MaterialTheme.typography.bodyLarge, color = DashboardTokens.inkSoft)
            }
            return
        }

        if (state.hasEarlier) {
            TextAction(strings.teamChannel.showEarlier, onLoadEarlier)
            if (state.loadingEarlier) {
                Box(Modifier.fillMaxWidth().height(40.dp), contentAlignment = Alignment.Center) {
                    AnimatedDotsLoader(dotSize = 8.dp, spacing = 6.dp)
                }
            }
        }

        state.posts.forEach { post ->
            ChannelPostCard(post, state.childId.orEmpty(), strings, onReplyPrivately)
        }

        // Why there is no composer, said once at the end rather than as a disabled box at the bottom.
        Text(
            strings.teamChannel.channelReadOnly,
            style = MaterialTheme.typography.bodySmall,
            color = DashboardTokens.inkSoft,
            modifier = Modifier.padding(vertical = Dimens.s8),
        )
        Spacer(Modifier.height(Dimens.s16))
    }
}

/**
 * One post: who wrote it and what she teaches, when, the words themselves, the files, feature 29's Translate, and
 * **Reply privately** — the author's own one-to-one thread, which is the only way a parent answers.
 */
@Composable
private fun ChannelPostCard(post: ChannelPost, childId: String, strings: Strings, onReplyPrivately: (ChannelPost) -> Unit) {
    val removed = post.deleted == true
    val label = staffLabel(staffRoleOf(post.authorRole), post.subject, className = null, strings = strings)
    ParentCard(Modifier.fillMaxWidth().padding(bottom = Dimens.s8)) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Text(
                post.authorName, style = MaterialTheme.typography.titleMedium,
                color = DashboardTokens.ink, fontWeight = FontWeight.SemiBold,
            )
            Text(formatClock(post.createdAt), style = MaterialTheme.typography.bodySmall, color = DashboardTokens.inkSoft)
        }
        if (label.isNotBlank()) {
            Spacer(Modifier.height(Dimens.s4))
            Text(label, style = MaterialTheme.typography.bodySmall, color = DashboardTokens.inkSoft)
        }
        Spacer(Modifier.height(Dimens.s8))

        if (removed) {
            // The row stays so the dates around it still read in order; what it said does not come back.
            Text(strings.teamChannel.postRemoved, style = MaterialTheme.typography.bodyLarge, color = DashboardTokens.inkSoft)
            return@ParentCard
        }

        // The author's own Arabic when she wrote one and this parent reads Arabic; a machine translation is below,
        // labelled as one, and never swapped in for the original.
        val body = if (strings.isRtl) post.bodyAr?.takeIf { it.isNotBlank() } ?: post.body else post.body
        if (body.isNotBlank()) {
            Text(isolate(body), style = MaterialTheme.typography.bodyLarge, color = DashboardTokens.ink)
        }

        post.attachments?.takeIf { it.isNotEmpty() }?.let { files ->
            Spacer(Modifier.height(Dimens.s8))
            MessageAttachments(files, strings)
        }

        if (childId.isNotBlank() && body.isNotBlank()) TranslateBlock(
            childId = childId,
            // The post *is* a chat message (there is no second chat system), so it translates as one.
            kind = TranslationSource.CHAT,
            sourceId = post.id,
            original = body,
            strings = strings.translate,
        )

        Spacer(Modifier.height(Dimens.s4))
        TextAction(strings.teamChannel.replyPrivately) { onReplyPrivately(post) }
    }
}

/** A plain word to tap, on a 48 dp target, in the accent ink the rest of the parent mode uses for an action. */
@Composable
private fun TextAction(text: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(DashboardTokens.radiusSm)
    Box(
        Modifier.heightIn(min = 48.dp).clip(shape).clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Dimens.s8),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = DashboardTokens.accentInk)
    }
}
