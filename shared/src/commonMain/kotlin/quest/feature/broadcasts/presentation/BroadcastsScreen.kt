package quest.feature.broadcasts.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import quest.ui.design.AnimatedDotsLoader
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.datetime.LocalDate
import org.koin.compose.viewmodel.koinViewModel
import quest.api.ApiException
import quest.api.dto.ApiError
import quest.api.dto.BroadcastView
import quest.api.dto.ChatStaffRole
import quest.api.dto.Curriculum
import quest.core.mvi.MviEffect
import quest.core.mvi.MviIntent
import quest.core.mvi.MviState
import quest.core.mvi.MviViewModel
import quest.core.platform.Today
import quest.feature.broadcasts.domain.BroadcastGroups
import quest.feature.broadcasts.domain.BroadcastsRepository
import quest.feature.broadcasts.domain.broadcastBody
import quest.feature.broadcasts.domain.groupBroadcasts
import quest.feature.broadcasts.domain.isImage
import quest.feature.broadcasts.domain.isWebUrl
import quest.feature.broadcasts.domain.unreadAnnouncements
import quest.feature.children.domain.ChildrenRepository
import quest.feature.parent.presentation.Chip
import quest.feature.parent.presentation.ParentCard
import quest.feature.parent.presentation.ParentShell
import quest.feature.parent.presentation.SectionTitle
import quest.feature.parent.presentation.Strings
import quest.feature.school.domain.Flags
import quest.feature.school.presentation.FeatureGate
import quest.feature.school.presentation.GateFallback
import quest.ui.design.Dimens
import quest.ui.design.Palette

/**
 * RM4, narrowed by MH3: the **Announcements** page — what the coordinator or the manager told the parents of this
 * child's section, and what is happening, newest first.
 *
 * **The weekly plans are gone from here.** MH1 made a plan one grade's week as an image with an archive of its own, so
 * it has a page of its own too ([WeeklyPlanScreen]) and its own badge. `BroadcastService.forChild` still answers them
 * on this feed, so `announcementRows` drops them client-side; the same reason the badge is counted from the rows rather
 * than taken from `BroadcastFeed.unread`, which counts plans too.
 *
 * The flag is `announcements` — the key the superseded feature carried — and the server answers 404 while it is off,
 * which is the "not enabled" state rather than an error.
 */
object BroadcastsContract {
    data class State(
        val loading: Boolean = true,
        val refreshing: Boolean = false,
        val notEnabled: Boolean = false,
        val childNotPlaced: Boolean = false,
        val errorMessage: String? = null,
        val unread: Int = 0,
        val groups: BroadcastGroups = BroadcastGroups(),
    ) : MviState

    sealed interface Intent : MviIntent {
        data object Load : Intent
        data object Refresh : Intent
        /** Tapping a card marks it read (`POST …/read`); the unread styling goes with it. */
        data class Open(val id: String) : Intent
    }

    /** Nothing here is one-shot: an image attachment is drawn in the card and opened full-screen from there. */
    sealed interface Effect : MviEffect
}

class BroadcastsViewModel(
    private val children: ChildrenRepository,
    private val broadcasts: BroadcastsRepository,
) : MviViewModel<BroadcastsContract.State, BroadcastsContract.Intent, BroadcastsContract.Effect>(BroadcastsContract.State()) {

    override suspend fun handle(intent: BroadcastsContract.Intent) {
        when (intent) {
            BroadcastsContract.Intent.Load -> load(refresh = false)
            BroadcastsContract.Intent.Refresh -> load(refresh = true)
            is BroadcastsContract.Intent.Open -> open(intent.id)
        }
    }

    private suspend fun load(refresh: Boolean) {
        val child = children.currentChild.value
        if (child == null) {
            reduce { copy(loading = false, refreshing = false, groups = BroadcastGroups()) }
            return
        }
        reduce { copy(refreshing = refresh, loading = !refresh && groups.isEmpty) }
        try {
            val feed = broadcasts.feed(child.id)
            reduce {
                copy(
                    loading = false, refreshing = false, notEnabled = false, childNotPlaced = false, errorMessage = null,
                    unread = unreadAnnouncements(feed.items, Today.epochMillis()),
                    groups = groupBroadcasts(feed.items, Today.epochMillis()),
                )
            }
        } catch (e: ApiException) {
            // 404 is the flag being off for this school, not a failure the parent should be asked to retry.
            val off = e.error.code == ApiError.NOT_FOUND
            val notPlaced = e.error.code == "child_not_placed"
            reduce {
                copy(
                    loading = false, refreshing = false, notEnabled = off, childNotPlaced = notPlaced,
                    errorMessage = if (off || notPlaced) null else e.message, groups = BroadcastGroups(),
                )
            }
        } catch (e: Throwable) {
            reduce { copy(loading = false, refreshing = false, errorMessage = e.message, groups = BroadcastGroups()) }
        }
    }

    /**
     * Marking read is the parent's, not the row's: the server answers with the row it changed, so the list is patched
     * in place rather than refetched — a feed that reordered under her thumb is how a tap lands on the wrong card.
     */
    private suspend fun open(id: String) {
        val child = children.currentChild.value ?: return
        val updated = runCatching { broadcasts.markRead(child.id, id) }.getOrNull() ?: return
        reduce {
            copy(
                unread = (unread - 1).coerceAtLeast(0),
                groups = groups.copy(
                    announcements = groups.announcements.map { if (it.id == id) updated else it },
                    events = groups.events.map { if (it.id == id) updated else it },
                ),
            )
        }
    }
}

@Composable
fun BroadcastsRoute(
    onBack: () -> Unit,
    onHome: () -> Unit = onBack,
    onMessages: () -> Unit = {},
    onSettings: () -> Unit = {},
) {
    val vm: BroadcastsViewModel = koinViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    // Inside the gate, so a deep link into a school without the flag fires no request at all, and `GateFallback`
    // sends it back where it came from rather than leaving the route composed over nothing.
    GateFallback(Flags.ANNOUNCEMENTS, onBack)
    FeatureGate(Flags.ANNOUNCEMENTS) {
        LaunchedEffect(vm) { vm.dispatch(BroadcastsContract.Intent.Load) }
        ParentShell(
            title = { it.announcements },
            onBack = onBack,
            currentTab = quest.ui.design.DashboardTab.NOTIFICATION,
            onTabSelected = { tab ->
                when (tab) {
                    quest.ui.design.DashboardTab.HOME -> onHome()
                    quest.ui.design.DashboardTab.NOTIFICATION -> {}
                    quest.ui.design.DashboardTab.MESSAGES -> onMessages()
                    quest.ui.design.DashboardTab.SETTINGS -> onSettings()
                }
            },
        ) { strings ->
            BroadcastsScreen(
                state = state,
                strings = strings,
                onRefresh = { vm.dispatch(BroadcastsContract.Intent.Refresh) },
                onOpen = { vm.dispatch(BroadcastsContract.Intent.Open(it.id)) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BroadcastsScreen(
    state: BroadcastsContract.State,
    strings: Strings,
    onRefresh: () -> Unit = {},
    onOpen: (BroadcastView) -> Unit = {},
) {
    PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.s16, vertical = Dimens.s8),
        ) {
            if (state.loading) {
                Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                    AnimatedDotsLoader(dotSize = 12.dp, spacing = 8.dp)
                }
                return@Column
            }

            val problem = when {
                state.notEnabled -> strings.broadcastsDisabled
                state.childNotPlaced -> strings.childNotPlaced
                state.errorMessage != null -> strings.somethingWrong
                state.groups.isEmpty -> strings.noBroadcasts
                else -> null
            }
            if (problem != null) {
                ParentCard(Modifier.padding(vertical = Dimens.s8)) {
                    Text(problem, style = MaterialTheme.typography.bodyLarge, color = Palette.parentInkSoft)
                }
                return@Column
            }

            BroadcastGroupSection(strings.announcementsGroup, state.groups.announcements, strings, onOpen)
            BroadcastGroupSection(strings.eventsGroup, state.groups.events, strings, onOpen)
            Spacer(Modifier.height(Dimens.s24))
        }
    }
}

@Composable
private fun BroadcastGroupSection(
    title: String,
    rows: List<BroadcastView>,
    strings: Strings,
    onOpen: (BroadcastView) -> Unit,
) {
    if (rows.isEmpty()) return
    SectionTitle(title)
    rows.forEach { row -> BroadcastCard(row, strings) { onOpen(row) } }
}

/**
 * The author line the brief asks for: a coordinator speaks for her subject, a manager for her department, and anyone
 * the feed labels otherwise is named plainly rather than given a title the server never claimed.
 */
fun authorLine(view: BroadcastView, strings: Strings): String {
    val subject = view.subject?.takeIf { it.isNotBlank() }
    val curriculum = view.curriculum
    return when {
        view.authorRole == ChatStaffRole.COORDINATOR && subject != null ->
            strings.fromCoordinator.replace("{subject}", subjectWords(subject, strings))
        view.authorRole == ChatStaffRole.MANAGERIAL && curriculum != null ->
            strings.fromManager.replace("{curriculum}", curriculumWord(curriculum, strings))
        else -> strings.fromAuthor.replace("{name}", view.authorName)
    }
}

private fun subjectWords(subject: String, strings: Strings): String =
    subject.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        .joinToString(", ") { strings.subjectNames[it.lowercase()] ?: it }

private fun curriculumWord(curriculum: Curriculum, strings: Strings): String =
    if (curriculum == Curriculum.BRITISH) strings.british else strings.american

/**
 * "Week of 27 September" from the ISO date the server snapped back to a Sunday. A date this app cannot parse is shown
 * as the server wrote it rather than hidden — the same rule an unknown subject key follows.
 */
fun weekLabel(iso: String, strings: Strings): String {
    val date = runCatching { LocalDate.parse(iso) }.getOrNull() ?: return iso
    return strings.weekOf.replace("{date}", "${date.dayOfMonth} ${strings.months[date.monthNumber - 1]}")
}

/** Screen-reader copy: who wrote, what it says, and whether it is new — the same three things the card shows. */
fun broadcastDescription(view: BroadcastView, strings: Strings): String = buildList {
    if (!view.read) add(strings.newBadge)
    view.title?.takeIf { it.isNotBlank() }?.let { add(it) }
    add(authorLine(view, strings))
    add(broadcastBody(view, strings.isRtl))
    view.attachment?.let { attachment ->
        val name = attachment.name ?: strings.attachment
        // An image is drawn and an external link is tappable, so both are just named; only the one the app can neither
        // render nor open has to say where it can be.
        add(if (attachment.isWebUrl || attachment.isImage) name else "$name, ${strings.attachmentOnDashboard}")
    }
}.joinToString(", ")

@Composable
fun BroadcastCard(
    view: BroadcastView,
    strings: Strings,
    onOpen: () -> Unit = {},
) {
    val uriHandler = LocalUriHandler.current
    ParentCard(
        // `mergeDescendants`, not `clearAndSetSemantics`: clearing the subtree would also clear any descendant that
        // carries an action, so a control inside the card would become unreachable to a screen reader.
        modifier = Modifier.fillMaxWidth().padding(bottom = Dimens.s8)
            .semantics(mergeDescendants = true) { contentDescription = broadcastDescription(view, strings) },
        onClick = onOpen,
    ) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Text(
                text = view.title?.takeIf { it.isNotBlank() } ?: authorLine(view, strings),
                style = MaterialTheme.typography.titleMedium,
                color = Palette.parentInk,
                // An unread row is heavier, not coloured: §7 keeps the alarm palette off a parent's reading list.
                fontWeight = if (view.read) FontWeight.Normal else FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            if (!view.read) Chip(strings.newBadge, Palette.sun)
        }

        Spacer(Modifier.height(Dimens.s4))
        Text(authorLine(view, strings), style = MaterialTheme.typography.bodySmall, color = Palette.parentInkSoft)

        Spacer(Modifier.height(Dimens.s8))
        Text(broadcastBody(view, strings.isRtl), style = MaterialTheme.typography.bodyLarge, color = Palette.parentInk)

        // MH3: an image is drawn here, off `GET /media/attachments/{id}` with the parent's bearer — the route is
        // authenticated, so the system viewer would land on a 401 and the bytes come through the app's own client.
        // #171's external link is unchanged below it: an `http(s)` URL the composer typed is somebody else's page and
        // the platform opens it. Anything else the app can neither render nor open says where it can be.
        view.attachment?.let { attachment ->
            Spacer(Modifier.height(Dimens.s8))
            // `isWebUrl` first: a pre-MH1 row can be an `https://…/plan.png` that this app has no way to fetch, and
            // #171's chip is the right answer for it. `isImage` now also requires an id, so the two cannot both be
            // true for such a row — the order is belt and braces, and it is the cheaper test besides.
            if (attachment.isWebUrl) {
                Chip(
                    text = "📎 ${attachment.name ?: strings.attachment} ↗",
                    color = MaterialTheme.colorScheme.primaryContainer,
                    onClick = {
                        onOpen()
                        runCatching { uriHandler.openUri(attachment.url) }
                    },
                )
            } else if (attachment.isImage) {
                AttachmentImage(attachment, attachment.name ?: strings.attachment, strings)
            } else {
                Text(
                    text = "📎 ${attachment.name ?: strings.attachment} · ${strings.attachmentOnDashboard}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.parentInkSoft,
                )
            }
        }
    }
}
