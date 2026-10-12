package quest.feature.staff.presentation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import quest.core.runCancellable
import quest.feature.auth.domain.SessionRoles
import quest.feature.parent.domain.ParentRepository
import quest.feature.parent.presentation.LocalStrings
import quest.feature.parent.presentation.Strings
import quest.feature.children.domain.ChildrenRepository
import quest.feature.school.domain.SchoolSession
import quest.feature.school.presentation.LocalFlags
import quest.feature.staff.domain.SessionSide
import quest.feature.staff.domain.RoleRouter
import quest.feature.staff.domain.StaffBadgeCounts
import quest.feature.staff.domain.StaffBadges
import quest.feature.staff.domain.StaffSessionView
import quest.ui.design.DashboardTokens
import quest.ui.design.Dimens
import quest.ui.design.ParentTheme
import quest.ui.design.brandGradient

/** The four tabs of the staff home (`docs/features/13-staff-mode-mobile-app.md`), in their order on screen. */
enum class StaffTab { ATTENDANCE, MESSAGES, NOTIFICATIONS, ACCOUNT }

/**
 * The staff side's frame: the same layout, bottom bar, RTL and tokens the parent's `ParentShell` draws, with the four
 * staff tabs on it.
 *
 * It is also where the staff subtree gets its context: `LocalFlags` becomes **her school's** flag map, from
 * `GET /staff/me`, so every `FeatureGate(…)` below asks her school and not whichever school a child of hers happens
 * to be in. Arabic is not behind the parent panel's flag here — a staff member always has both languages.
 */
@Composable
fun StaffShell(
    title: (StaffStrings) -> String,
    currentTab: StaffTab?,
    onTabSelected: (StaffTab) -> Unit,
    onBack: (() -> Unit)? = null,
    content: @Composable (StaffStrings, Strings) -> Unit,
) {
    val parent: ParentRepository = koinInject()
    val session: StaffSessionView = koinInject()
    val badges: StaffBadges = koinInject()
    val language by parent.language.collectAsStateWithLifecycle()
    val counts by badges.counts.collectAsStateWithLifecycle()
    val staffStrings = StaffStrings.forLanguage(language)
    val strings = Strings.forLanguage(language)
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { badges.refresh() }
    // §3 white label: a staff-only account has no child to take a school from, so her own school is what themes the
    // app. A dual-role account keeps the child's school, which is the same school anyway.
    val school: SchoolSession = koinInject()
    val children: ChildrenRepository = koinInject()
    val profile by session.profile.collectAsStateWithLifecycle()
    LaunchedEffect(profile?.schoolId) {
        val schoolId = profile?.schoolId ?: return@LaunchedEffect
        if (children.currentChild.value == null) runCancellable { school.use(schoolId) }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { scope.launch { badges.refresh() } }

    CompositionLocalProvider(LocalFlags provides session.flagStore, LocalStaffStrings provides staffStrings, LocalStrings provides strings) {
        ParentTheme(rtl = staffStrings.isRtl) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = Dimens.s12, vertical = Dimens.s8),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (onBack != null) {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(DashboardTokens.radiusSm))
                                .semantics { contentDescription = "Back" },
                        ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = DashboardTokens.ink) }
                    } else Spacer(Modifier.width(Dimens.s8))
                    Text(
                        title(staffStrings),
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = DashboardTokens.inkStrong,
                        modifier = Modifier.weight(1f).padding(start = Dimens.s8),
                    )
                    LanguagePills(language) { code -> scope.launch { parent.setLanguage(code) } }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) { content(staffStrings, strings) }
                if (currentTab != null) {
                    StaffBottomNavigation(currentTab, onTabSelected, staffStrings, counts)
                }
            }
        }
    }
}

/**
 * The "الموظف / ولي الأمر" switch. It is drawn only for an account that holds both sides — there is nothing to
 * switch between otherwise — and the choice it makes is the one the app opens on next time.
 */
@Composable
fun SideSwitch(onSwitched: (SessionSide) -> Unit, modifier: Modifier = Modifier) {
    val roles: SessionRoles = koinInject()
    val sides by roles.sides.collectAsStateWithLifecycle()
    val current by roles.side.collectAsStateWithLifecycle()
    if (!RoleRouter.canSwitch(sides)) return
    // Its own language, not the subtree's: the switch sits on the parent home too, where there is no staff table.
    val parent: ParentRepository = koinInject()
    val language by parent.language.collectAsStateWithLifecycle()
    val staffStrings = StaffStrings.forLanguage(language)
    val scope = rememberCoroutineScope()
    val shape = RoundedCornerShape(DashboardTokens.radiusSm)
    Row(
        modifier.background(DashboardTokens.bgSubtle, shape).border(1.dp, DashboardTokens.rule, shape).padding(2.dp)
            .semantics { contentDescription = "${staffStrings.staff} / ${staffStrings.parent}" },
    ) {
        listOf(SessionSide.STAFF to staffStrings.staff, SessionSide.PARENT to staffStrings.parent).forEach { (side, label) ->
            val on = side == current
            val pill = RoundedCornerShape(6.dp)
            Box(
                Modifier.background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent, pill).clip(pill)
                    .clickable(role = Role.Button) {
                        if (!on) scope.launch { runCancellable { roles.choose(side) }; onSwitched(side) }
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = if (on) FontWeight.Bold else FontWeight.Medium),
                    color = if (on) MaterialTheme.colorScheme.onPrimary else DashboardTokens.inkSoft,
                )
            }
        }
    }
}

@Composable
private fun LanguagePills(current: String, onChange: (String) -> Unit) {
    val shape = RoundedCornerShape(DashboardTokens.radiusSm)
    Row(
        Modifier.background(DashboardTokens.bgSubtle, shape).border(1.dp, DashboardTokens.rule, shape).padding(2.dp)
            .semantics { contentDescription = "Language" },
    ) {
        listOf("en" to "EN", "ar" to "ع").forEach { (code, label) ->
            val on = code == current
            val pill = RoundedCornerShape(6.dp)
            Box(
                Modifier.background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent, pill).clip(pill)
                    .clickable(role = Role.Button) { onChange(code) }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = if (on) FontWeight.Bold else FontWeight.Medium),
                    color = if (on) MaterialTheme.colorScheme.onPrimary else DashboardTokens.inkSoft,
                )
            }
        }
    }
}

/** The parent bar's own shape and tokens with the staff tabs on it — four, so each one has room at 360 dp. */
@Composable
private fun StaffBottomNavigation(
    currentTab: StaffTab,
    onTabSelected: (StaffTab) -> Unit,
    strings: StaffStrings,
    counts: StaffBadgeCounts,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = DashboardTokens.surface,
        border = BorderStroke(1.dp, DashboardTokens.rule),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StaffTab.entries.forEach { tab ->
                val selected = tab == currentTab
                val icon = when (tab) {
                    StaffTab.ATTENDANCE -> Icons.Default.EventAvailable
                    StaffTab.MESSAGES -> Icons.Default.ChatBubble
                    StaffTab.NOTIFICATIONS -> Icons.Default.Notifications
                    StaffTab.ACCOUNT -> Icons.Default.AccountCircle
                }
                val label = when (tab) {
                    StaffTab.ATTENDANCE -> strings.attendance
                    StaffTab.MESSAGES -> strings.messages
                    StaffTab.NOTIFICATIONS -> strings.notifications
                    StaffTab.ACCOUNT -> strings.account
                }
                val badge = when (tab) {
                    StaffTab.MESSAGES -> counts.messages
                    StaffTab.NOTIFICATIONS -> counts.notifications
                    else -> 0
                }
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(DashboardTokens.radiusSm))
                        .clickable(role = Role.Tab) { onTabSelected(tab) }
                        .padding(horizontal = 2.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(contentAlignment = Alignment.TopEnd) {
                        Box(
                            Modifier.size(width = 48.dp, height = 30.dp)
                                .then(if (selected) Modifier.background(brandGradient(), RoundedCornerShape(DashboardTokens.radiusSm)) else Modifier),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = label,
                                tint = if (selected) DashboardTokens.onBrand else DashboardTokens.inkSoft,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                        if (badge > 0) Box(Modifier.size(8.dp).background(DashboardTokens.secondary, CircleShape))
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium),
                        color = if (selected) DashboardTokens.accentInk else DashboardTokens.inkSoft,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}
