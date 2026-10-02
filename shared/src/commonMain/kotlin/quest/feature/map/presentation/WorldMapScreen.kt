package quest.feature.map.presentation

import androidx.compose.ui.platform.testTag
import quest.ui.design.TestTags
import quest.ui.design.StudentAvatar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.datetime.LocalDate
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import quest.api.dto.Island
import quest.api.dto.IslandKind
import quest.api.dto.IslandState
import quest.api.dto.Subject
import quest.core.platform.Speaker
import quest.feature.map.presentation.MapContract.Effect
import quest.feature.map.presentation.MapContract.Intent
import quest.feature.map.presentation.MapContract.State
import quest.feature.parent.domain.ParentRepository
import quest.feature.parent.presentation.Strings
import quest.feature.school.domain.Flags
import quest.feature.school.presentation.featureEnabled
import quest.ui.design.AcademicTheme
import quest.ui.design.AnimatedLoadingView
import quest.ui.design.DashboardButton
import quest.ui.design.DashboardButtonVariant
import quest.ui.design.DashboardCard
import quest.ui.design.DashboardFilterChip
import quest.ui.design.DashboardPill
import quest.ui.design.DashboardPillVariant
import quest.ui.design.DashboardTokens
import quest.ui.design.ReadAloudButton
import quest.ui.design.SubjectMeta

@Composable
fun WorldMapRoute(
    onSwitchChild: () -> Unit,
    onOpenLesson: (String, Int, Int) -> Unit,
    onGrownUps: () -> Unit,
    onNeedsChild: () -> Unit,
    onNotifications: () -> Unit = {},
    onMessages: () -> Unit = {},
    onSettings: () -> Unit = {},
) {
    val vm: MapViewModel = koinViewModel()
    val speaker: Speaker = koinInject()
    val state by vm.state.collectAsStateWithLifecycle()
    // The child screen reads in the language the parent picked, the same rule `ChatConversationRoute` follows: a
    // school without `parentPanel.arabic` has no Arabic at all, so it stays English rather than half-translated.
    val parent: ParentRepository = koinInject()
    val language by parent.language.collectAsStateWithLifecycle()
    val strings = if (featureEnabled(Flags.PARENT_PANEL_ARABIC)) Strings.forLanguage(language) else Strings.en
    LaunchedEffect(vm) {
        vm.dispatch(Intent.Load)
        vm.effects.collect { e ->
            when (e) {
                is Effect.Speak -> speaker.speak(e.text)
                is Effect.OpenLesson -> onOpenLesson(e.lessonId, e.level, e.variant)
                Effect.NeedsChild -> onNeedsChild()
            }
        }
    }
    WorldMapScreen(
        state = state,
        dispatch = vm::dispatch,
        onGrownUps = onGrownUps,
        onSwitchChild = onSwitchChild,
        onNotifications = onNotifications,
        onMessages = onMessages,
        onSettings = onSettings,
        strings = strings,
    )
}

/**
 * The student home (all grades):
 * Clean dashboard aesthetic with student profile header, subject filter chips,
 * formal coursework cards, parent access icon, and bottom navigation bar.
 */
@Composable
fun WorldMapScreen(
    state: State,
    dispatch: (Intent) -> Unit,
    onGrownUps: () -> Unit,
    onSwitchChild: () -> Unit = {},
    onNotifications: () -> Unit = {},
    onMessages: () -> Unit = {},
    onSettings: () -> Unit = {},
    strings: Strings = Strings.en,
) {
    FormalStudentScreen(
        state = state,
        dispatch = dispatch,
        onGrownUps = onGrownUps,
        onSwitchChild = onSwitchChild,
        onNotifications = onNotifications,
        onMessages = onMessages,
        onSettings = onSettings,
        strings = strings,
    )
}

@Composable
fun FormalStudentScreen(
    state: State,
    dispatch: (Intent) -> Unit,
    onGrownUps: () -> Unit,
    onSwitchChild: () -> Unit = {},
    onNotifications: () -> Unit = {},
    onMessages: () -> Unit = {},
    onSettings: () -> Unit = {},
    strings: Strings = Strings.en,
) {
    var selectedSubject by remember { mutableStateOf<Subject?>(null) }
    val rtl = strings.isRtl

    AcademicTheme(rtl = rtl) {
        if (state.loading) {
            AnimatedLoadingView(strings.loadingCoursework)
            return@AcademicTheme
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding(),
        ) {
            // The 16 dp gutter sits on each child rather than on this Column, so the filter row can scroll edge to
            // edge: a chip leaving the viewport slides under the screen edge instead of being sliced by the padding.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                // Header Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(top = 12.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Student Profile Chip / Switcher
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(DashboardTokens.radiusSm))
                            .clickable(role = Role.Button, onClick = onSwitchChild)
                            .padding(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StudentAvatar(state.child?.name ?: strings.student, size = 36.dp)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                text = state.child?.name ?: strings.student,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = DashboardTokens.inkStrong,
                            )
                            Text(
                                text = "${strings.grade} ${state.child?.grade ?: 1}",
                                style = MaterialTheme.typography.bodySmall,
                                color = DashboardTokens.inkSoft,
                            )
                        }
                    }

                    Spacer(Modifier.weight(1f))

                    // Streak Badge
                    if (state.streakDays > 0) {
                        DashboardPill(
                            text = strings.dayStreakShort.replace("{n}", "${state.streakDays}"),
                            icon = "🔥",
                            variant = DashboardPillVariant.HIGHLIGHT,
                        )
                        Spacer(Modifier.width(8.dp))
                    }

                    // Parent Mode Icon Button
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .background(DashboardTokens.surface, RoundedCornerShape(DashboardTokens.radiusSm))
                            .border(1.dp, DashboardTokens.rule, RoundedCornerShape(DashboardTokens.radiusSm))
                            .clip(RoundedCornerShape(DashboardTokens.radiusSm))
                            .clickable(role = Role.Button, onClick = onGrownUps)
                            .testTag(TestTags.HOME_PARENT_PORTAL)
                            .semantics { contentDescription = strings.parentPortal },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = strings.parentPortal,
                            tint = DashboardTokens.accentInk,
                            modifier = Modifier.size(20.dp),
                        )
                    }

                    Spacer(Modifier.width(8.dp))
                    ReadAloudButton({ dispatch(Intent.ReadAloud) }, contentDescription = strings.lesson.readAloud)
                }

                // Subject Filter Bar
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        DashboardFilterChip(
                            text = strings.allSubjects,
                            selected = selectedSubject == null,
                            onClick = { selectedSubject = null },
                        )
                    }
                    val availableSubjects = listOf(
                        Subject.MATH, Subject.ENGLISH, Subject.SCIENCE,
                        Subject.FRENCH, Subject.RELIGION, Subject.ARABIC,
                    )
                    items(availableSubjects) { subject ->
                        val meta = SubjectMeta.of(subject)
                        DashboardFilterChip(
                            text = meta.label(rtl),
                            icon = meta.emoji,
                            selected = selectedSubject == subject,
                            onClick = { selectedSubject = if (selectedSubject == subject) null else subject },
                        )
                    }
                }

                // Filtered Coursework List
                val filteredIslands = remember(state.islands, selectedSubject) {
                    if (selectedSubject == null) state.islands
                    else state.islands.filter { it.subject == selectedSubject }
                }

                Box(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                    if (filteredIslands.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            DashboardCard {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Text("📚", fontSize = 40.sp)
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        text = if (state.isEmpty && !state.loading) strings.noCourseworkToday else strings.noCourseworkForSubject,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = DashboardTokens.inkStrong,
                                        textAlign = TextAlign.Center,
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = strings.checkBackSoon,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = DashboardTokens.inkSoft,
                                        textAlign = TextAlign.Center,
                                    )
                                }
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            contentPadding = PaddingValues(vertical = 8.dp),
                        ) {
                            items(filteredIslands, key = { it.id }) { island ->
                                FormalCourseworkCard(
                                    island = island,
                                    strings = strings,
                                    onOpen = { dispatch(Intent.TapIsland(island.id)) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FormalCourseworkCard(
    island: Island,
    strings: Strings,
    onOpen: () -> Unit,
) {
    val meta = SubjectMeta.of(island.subject)
    val isLocked = island.kind == IslandKind.LOCKED
    val isDone = island.state == IslandState.DONE
    val isToday = island.state == IslandState.TODAY
    val isReview = island.kind == IslandKind.REVIEW

    DashboardCard(
        modifier = if (island.lessonId != null) Modifier.testTag(TestTags.homeLesson(island.lessonId!!)) else Modifier,
        onClick = if (!isLocked) onOpen else null,
    ) {
        Column {
            // Meta Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .background(meta.color.copy(alpha = 0.18f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = "${meta.emoji} ${meta.label(strings.isRtl)}",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 12.sp,
                            ),
                            color = DashboardTokens.inkStrong,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = dateLabel(island.date, strings),
                        style = MaterialTheme.typography.bodySmall,
                        color = DashboardTokens.inkMuted,
                    )
                }

                // Status Badge
                when {
                    isLocked -> DashboardPill(text = strings.lessonLocked, variant = DashboardPillVariant.NEUTRAL)
                    isDone -> DashboardPill(text = strings.lessonCompleted, variant = DashboardPillVariant.SUCCESS)
                    isReview -> DashboardPill(text = strings.lessonReview, variant = DashboardPillVariant.WARNING)
                    isToday -> DashboardPill(text = strings.lessonAssignedToday, variant = DashboardPillVariant.INFO)
                    else -> DashboardPill(text = strings.lessonAvailable, variant = DashboardPillVariant.NEUTRAL)
                }
            }

            Spacer(Modifier.height(10.dp))

            // Title
            Text(
                text = island.title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                ),
                color = if (isLocked) DashboardTokens.inkMuted else DashboardTokens.inkStrong,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(12.dp))

            // Action / Details Footer
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isDone) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val stars = island.starsEarned ?: 0
                        val total = island.starsTotal ?: 1
                        Text(
                            text = strings.lessonScore.replace("{earned}", "$stars").replace("{total}", "$total"),
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                            color = DashboardTokens.success,
                        )
                    }
                    DashboardButton(
                        text = strings.lessonReview,
                        onClick = onOpen,
                        variant = DashboardButtonVariant.SECONDARY,
                        modifier = Modifier.width(110.dp),
                        height = 36.dp,
                    )
                } else if (isLocked) {
                    Text(
                        text = strings.lessonUnlockHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = DashboardTokens.inkMuted,
                    )
                } else {
                    Text(
                        text = if (isReview) strings.lessonReinforce else strings.lessonStandard,
                        style = MaterialTheme.typography.bodySmall,
                        color = DashboardTokens.inkSoft,
                    )
                    DashboardButton(
                        text = if (isToday) strings.startLesson else strings.openLesson,
                        onClick = onOpen,
                        variant = DashboardButtonVariant.PRIMARY,
                        modifier = Modifier.width(130.dp),
                        height = 36.dp,
                    )
                }
            }
        }
    }
}

/** `11 Sep` / `11 سبتمبر` — English abbreviates, Arabic does not (a three-letter Arabic month is not a word). */
private fun dateLabel(d: LocalDate, strings: Strings = Strings.en): String {
    val month = strings.months.getOrNull(d.monthNumber - 1) ?: d.month.name
    return "${d.dayOfMonth} ${if (strings.isRtl) month else month.take(3)}"
}
