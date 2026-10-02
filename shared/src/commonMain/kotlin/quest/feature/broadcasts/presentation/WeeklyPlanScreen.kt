package quest.feature.broadcasts.presentation

import quest.ui.design.DashboardTokens
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import quest.api.ApiException
import quest.api.dto.ApiError
import quest.api.dto.BroadcastView
import quest.core.mvi.MviEffect
import quest.core.mvi.MviIntent
import quest.core.mvi.MviState
import quest.core.mvi.MviViewModel
import quest.core.platform.Today
import quest.feature.broadcasts.domain.BroadcastsRepository
import quest.feature.broadcasts.domain.WeeklyPlans
import quest.feature.broadcasts.domain.broadcastBody
import quest.feature.broadcasts.domain.isImage
import quest.feature.broadcasts.domain.weeklyPlans
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

/**
 * MH3 (the owner's manager list 2, item 6): the **Weekly plan** page. MH1 made a plan "one grade's week as an image",
 * so this page is that image — this week's plan for the child's grade pinned at the top, then the earlier weeks of
 * `GET /children/{id}/weekly-plans` as collapsed rows.
 *
 * It is the other half of splitting RM4's *School news* in two. Plans stopped belonging on the announcements feed when
 * they became images with an archive of their own; that page is now [BroadcastsScreen] and this one is the plans, each
 * with its own unread badge on the parent home. Both sit behind `announcements`.
 */
object WeeklyPlanContract {
    data class State(
        val loading: Boolean = true,
        val refreshing: Boolean = false,
        val notEnabled: Boolean = false,
        val childNotPlaced: Boolean = false,
        val errorMessage: String? = null,
        val unread: Int = 0,
        /** The child's grade, so the week's plan for *her* grade is the one pinned and the label can name it. */
        val grade: Int? = null,
        val plans: WeeklyPlans = WeeklyPlans(),
        /** Which earlier week the parent has opened; only one at a time, and the pinned plan is always open. */
        val openId: String? = null,
    ) : MviState

    sealed interface Intent : MviIntent {
        data object Load : Intent
        data object Refresh : Intent
        /** Opening a plan marks it read (`POST …/read`) and, for an earlier week, expands its image. */
        data class Open(val id: String) : Intent
    }

    sealed interface Effect : MviEffect
}

class WeeklyPlanViewModel(
    private val children: ChildrenRepository,
    private val broadcasts: BroadcastsRepository,
) : MviViewModel<WeeklyPlanContract.State, WeeklyPlanContract.Intent, WeeklyPlanContract.Effect>(WeeklyPlanContract.State()) {

    override suspend fun handle(intent: WeeklyPlanContract.Intent) {
        when (intent) {
            WeeklyPlanContract.Intent.Load -> load(refresh = false)
            WeeklyPlanContract.Intent.Refresh -> load(refresh = true)
            is WeeklyPlanContract.Intent.Open -> open(intent.id)
        }
    }

    private suspend fun load(refresh: Boolean) {
        val child = children.currentChild.value
        if (child == null) {
            reduce { copy(loading = false, refreshing = false, plans = WeeklyPlans()) }
            return
        }
        reduce { copy(refreshing = refresh, loading = !refresh && plans.isEmpty, grade = child.grade) }
        try {
            val archive = broadcasts.plans(child.id)
            val grouped = weeklyPlans(archive, Today.date(), child.grade)
            reduce {
                copy(
                    loading = false, refreshing = false, notEnabled = false, childNotPlaced = false, errorMessage = null,
                    unread = archive.unread, plans = grouped,
                )
            }
            // The pinned plan is on the screen the moment the page opens, so it is read — the same rule the feed applies
            // to a card the parent tapped. Nothing else is: an earlier week is read when she opens it.
            grouped.current?.takeIf { !it.read }?.let { open(it.id) }
        } catch (e: ApiException) {
            // 404 is the flag being off for this school, not a failure the parent should be asked to retry.
            val off = e.error.code == ApiError.NOT_FOUND
            val notPlaced = e.error.code == "child_not_placed"
            reduce {
                copy(
                    loading = false, refreshing = false, notEnabled = off, childNotPlaced = notPlaced,
                    errorMessage = if (off || notPlaced) null else e.message, plans = WeeklyPlans(),
                )
            }
        } catch (e: Throwable) {
            reduce { copy(loading = false, refreshing = false, errorMessage = e.message, plans = WeeklyPlans()) }
        }
    }

    /** The archive is patched in place with the row the server answers, so nothing reorders under the parent's thumb. */
    private suspend fun open(id: String) {
        val child = children.currentChild.value ?: return
        val expanded = if (current.openId == id) null else id
        reduce { copy(openId = expanded) }
        val already = current.plans.let { p -> p.current?.takeIf { it.id == id } ?: p.earlier.flatMap { it.plans }.firstOrNull { it.id == id } }
        if (already?.read == true) return
        val updated = runCatching { broadcasts.markRead(child.id, id) }.getOrNull() ?: return
        reduce {
            copy(
                unread = (unread - 1).coerceAtLeast(0),
                plans = plans.copy(
                    current = if (plans.current?.id == id) updated else plans.current,
                    earlier = plans.earlier.map { week -> week.copy(plans = week.plans.map { if (it.id == id) updated else it }) },
                ),
            )
        }
    }
}

@Composable
fun WeeklyPlanRoute(onBack: () -> Unit) {
    val vm: WeeklyPlanViewModel = koinViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    // Inside the gate, so a deep link into a school without the flag fires no request at all.
    GateFallback(Flags.ANNOUNCEMENTS, onBack)
    FeatureGate(Flags.ANNOUNCEMENTS) {
        LaunchedEffect(vm) { vm.dispatch(WeeklyPlanContract.Intent.Load) }
        ParentShell(title = { it.weeklyPlan }, onBack = onBack) { strings ->
            WeeklyPlanScreen(
                state = state,
                strings = strings,
                onRefresh = { vm.dispatch(WeeklyPlanContract.Intent.Refresh) },
                onOpen = { vm.dispatch(WeeklyPlanContract.Intent.Open(it)) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeeklyPlanScreen(
    state: WeeklyPlanContract.State,
    strings: Strings,
    onRefresh: () -> Unit = {},
    onOpen: (String) -> Unit = {},
) {
    PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.s16, vertical = Dimens.s8),
        ) {
            if (state.loading) {
                Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
                return@Column
            }

            val problem = when {
                state.notEnabled -> strings.broadcastsDisabled
                state.childNotPlaced -> strings.childNotPlaced
                state.errorMessage != null -> strings.somethingWrong
                state.plans.isEmpty -> strings.noWeeklyPlan
                else -> null
            }
            if (problem != null) {
                ParentCard(Modifier.padding(vertical = Dimens.s8)) {
                    Text(problem, style = MaterialTheme.typography.bodyLarge, color = DashboardTokens.inkSoft)
                }
                return@Column
            }

            state.plans.current?.let { plan ->
                SectionTitle(strings.thisWeeksPlan)
                PlanCard(plan, state.grade, strings, expanded = true)
            }
            if (state.plans.earlier.isNotEmpty()) {
                SectionTitle(strings.earlierPlans)
                state.plans.earlier.forEach { week ->
                    week.plans.forEach { plan ->
                        PlanCard(plan, state.grade, strings, expanded = state.openId == plan.id, onOpen = { onOpen(plan.id) })
                    }
                }
            }
            Spacer(Modifier.height(Dimens.s24))
        }
    }
}

/**
 * One plan. Collapsed it is the week, the author and a New chip; open it is the image as well. An unread row is heavier
 * with a chip beside it and never coloured (§7).
 */
@Composable
private fun PlanCard(
    plan: BroadcastView,
    grade: Int?,
    strings: Strings,
    expanded: Boolean,
    onOpen: (() -> Unit)? = null,
) {
    ParentCard(Modifier.fillMaxWidth().padding(bottom = Dimens.s8), onClick = onOpen) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Text(
                text = plan.weekStart?.let { weekLabel(it, strings) } ?: strings.weeklyPlan,
                style = MaterialTheme.typography.titleMedium,
                color = DashboardTokens.ink,
                fontWeight = if (plan.read) FontWeight.Normal else FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            if (!plan.read) Chip(strings.newBadge, DashboardTokens.warningBg)
        }
        Spacer(Modifier.height(Dimens.s4))
        Text(authorLine(plan, strings), style = MaterialTheme.typography.bodySmall, color = DashboardTokens.inkSoft)

        if (!expanded) return@ParentCard

        Spacer(Modifier.height(Dimens.s8))
        val attachment = plan.attachment
        // MH1 requires an image on a plan, but QA still holds rows written before it: those fall back to their body.
        if (attachment != null && attachment.isImage) AttachmentImage(attachment, planDescription(plan, grade, strings), strings)
        else Text(broadcastBody(plan, strings.isRtl), style = MaterialTheme.typography.bodyLarge, color = DashboardTokens.ink)
    }
}

/** The a11y line the brief asks for: "Weekly plan, Grade 1, week of 27 September". */
fun planDescription(plan: BroadcastView, childGrade: Int?, strings: Strings): String {
    val grade = plan.grade ?: childGrade
    return listOfNotNull(
        strings.weeklyPlan,
        grade?.let { "${strings.grade} $it" },
        plan.weekStart?.let { weekLabel(it, strings) },
    ).joinToString(", ")
}
