package quest.feature.map.presentation

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
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
import quest.feature.school.domain.Flags
import quest.feature.school.presentation.FeatureGate
import quest.feature.school.presentation.LocalSchoolBranding
import quest.feature.school.presentation.SchoolLogo
import quest.ui.design.AcademicTheme
import quest.ui.design.AnimatedLoadingView
import quest.ui.design.DashboardBottomNavigation
import quest.ui.design.DashboardButton
import quest.ui.design.DashboardButtonVariant
import quest.ui.design.DashboardCard
import quest.ui.design.DashboardFilterChip
import quest.ui.design.DashboardPill
import quest.ui.design.DashboardPillVariant
import quest.ui.design.DashboardProgressBar
import quest.ui.design.DashboardTab
import quest.ui.design.DashboardTokens
import quest.ui.design.Dimens
import quest.ui.design.LocalThemeOverrides
import quest.ui.design.Palette
import quest.ui.design.Pip
import quest.ui.design.PipPose
import quest.ui.design.ReadAloudButton
import quest.ui.design.RoundIconButton
import quest.ui.design.SpeechBubble
import quest.ui.design.StarRow
import quest.ui.design.SubjectMeta
import quest.ui.design.animationsEnabled

@Composable
fun WorldMapRoute(
    onSwitchChild: () -> Unit,
    onOpenLesson: (String, Int, Int) -> Unit,
    onStickers: () -> Unit,
    onChest: () -> Unit,
    onGrownUps: () -> Unit,
    onNeedsChild: () -> Unit,
    onNotifications: () -> Unit = {},
    onMessages: () -> Unit = {},
    onSettings: () -> Unit = {},
) {
    val vm: MapViewModel = koinViewModel()
    val speaker: Speaker = koinInject()
    val state by vm.state.collectAsStateWithLifecycle()
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
        onStickers = onStickers,
        onChest = onChest,
        onGrownUps = onGrownUps,
        onSwitchChild = onSwitchChild,
        onNotifications = onNotifications,
        onMessages = onMessages,
        onSettings = onSettings,
    )
}

/**
 * Modern TailAdmin Dashboard Student Home (all grades):
 * Clean dashboard aesthetic with student profile header, subject filter chips,
 * formal coursework cards, parent access icon, and bottom navigation bar.
 */
@Composable
fun WorldMapScreen(
    state: State,
    dispatch: (Intent) -> Unit,
    onStickers: () -> Unit,
    onChest: () -> Unit,
    onGrownUps: () -> Unit,
    onSwitchChild: () -> Unit = {},
    onNotifications: () -> Unit = {},
    onMessages: () -> Unit = {},
    onSettings: () -> Unit = {},
) {
    FormalStudentScreen(
        state = state,
        dispatch = dispatch,
        onStickers = onStickers,
        onChest = onChest,
        onGrownUps = onGrownUps,
        onSwitchChild = onSwitchChild,
        onNotifications = onNotifications,
        onMessages = onMessages,
        onSettings = onSettings,
    )
}

// =============================================================================
// GRADES 1–3: PLAYFUL GAME MAP (PRESERVED)
// =============================================================================

@Composable
fun GameWorldMapScreen(
    state: State,
    dispatch: (Intent) -> Unit,
    onStickers: () -> Unit,
    onChest: () -> Unit,
    onGrownUps: () -> Unit,
    onSwitchChild: () -> Unit = {},
) {
    Box(Modifier.fillMaxSize().background(Palette.sea)) {
        Waves()
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = Dimens.s16)) {
            Row(Modifier.fillMaxWidth().padding(top = Dimens.s8), verticalAlignment = Alignment.CenterVertically) {
                RoundIconButton(onSwitchChild, "Switch child") {
                    Pip(PipPose.IDLE, 44.dp, animated = false, color = state.child?.avatarColor ?: "sky")
                }
                Spacer(Modifier.width(Dimens.s8))
                RoundIconButton(onStickers, "Sticker book") { Text("🌟", fontSize = 26.sp) }
                FeatureGate(Flags.TREASURE_CHEST) {
                    Spacer(Modifier.width(Dimens.s8))
                    RoundIconButton(onChest, "Treasure chest") { Text("🎁", fontSize = 26.sp) }
                }
                Spacer(Modifier.weight(1f))
                SchoolMark()
                if (state.streakDays > 0) {
                    Text(
                        "🔥 ${state.streakDays}",
                        style = MaterialTheme.typography.labelLarge,
                        color = Palette.white,
                        modifier = Modifier.semantics { contentDescription = "${state.streakDays} day streak" },
                    )
                    Spacer(Modifier.width(Dimens.s12))
                }
                ReadAloudButton({ dispatch(Intent.ReadAloud) })
            }
            Spacer(Modifier.height(Dimens.s12))
            Box(Modifier.weight(1f)) {
                if (state.isEmpty && !state.loading) EmptyMap() else IslandList(state, dispatch)
            }
            Text(
                "Grown-ups",
                color = Palette.white.copy(alpha = 0.85f),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .padding(bottom = Dimens.s12)
                    .size(width = 140.dp, height = Dimens.minTarget)
                    .clickable(role = Role.Button, onClick = onGrownUps)
                    .semantics { contentDescription = "Grown-ups" },
            )
        }
    }
}

@Composable
private fun SchoolMark() {
    val branding = LocalSchoolBranding.current
    val name = branding.schoolName ?: return
    val overrides = LocalThemeOverrides.current
    SchoolLogo(
        branding.logoUrl, name, size = 44.dp,
        background = overrides.primary ?: Palette.cream,
        ink = overrides.primaryInk ?: Palette.ink,
    )
    Spacer(Modifier.width(Dimens.s12))
}

@Composable
private fun Waves() {
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        for (i in 0..12) {
            val y = h * i / 12f
            drawLine(Palette.seaDeep.copy(alpha = 0.25f), Offset(0f, y), Offset(w, y + 18f), strokeWidth = 3f)
        }
    }
}

@Composable
private fun EmptyMap() {
    Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(220.dp, 60.dp).background(Palette.sand, RoundedCornerShape(20.dp)).semantics { contentDescription = "raft" })
        Pip(PipPose.SLEEPING, Dimens.pipLarge)
        Spacer(Modifier.height(Dimens.s16))
        SpeechBubble("No quest today yet. Check the map tomorrow!")
    }
}

@Composable
private fun IslandList(state: State, dispatch: (Intent) -> Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Dimens.s12)) {
        val hello = state.child?.name?.takeIf { it.isNotBlank() }?.let { "$it's quest" } ?: "Today's quest"
        Text(hello, style = MaterialTheme.typography.headlineMedium, color = Palette.white, modifier = Modifier.padding(start = Dimens.s8))
        state.islands.forEachIndexed { i, island ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = if (i % 2 == 0) Arrangement.Start else Arrangement.End) {
                IslandView(island, onClick = { dispatch(Intent.TapIsland(island.id)) })
            }
        }
    }
}

@Composable
private fun IslandView(island: Island, onClick: () -> Unit) {
    val glow = if (!animationsEnabled()) 1f else {
        val transition = rememberInfiniteTransition(label = "glow")
        transition.animateFloat(0.96f, 1.04f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse), label = "scale").value
    }
    val today = island.state == IslandState.TODAY
    val worlds = LocalThemeOverrides.current.worldPalettes
    val meta = SubjectMeta.of(island.subject)
    val ground = when {
        island.kind == IslandKind.LOCKED -> Palette.night
        island.state == IslandState.DONE -> Palette.mint
        island.kind == IslandKind.REVIEW -> Palette.peach
        island.subject == Subject.MATH -> worlds.math ?: meta.color
        island.subject == Subject.ENGLISH -> worlds.english ?: meta.color
        island.subject == Subject.FRENCH -> worlds.french ?: meta.color
        island.subject == Subject.SCIENCE -> worlds.science ?: meta.color
        island.subject == Subject.RELIGION -> worlds.religion ?: meta.color
        island.subject == Subject.ARABIC -> worlds.arabic ?: meta.color
        else -> meta.color
    }
    val width = if (island.kind == IslandKind.REVIEW) 150.dp else 200.dp
    val description = when (island.kind) {
        IslandKind.LOCKED -> "Sleeping island. This island is still asleep."
        IslandKind.REVIEW -> "Review island: ${island.title}"
        IslandKind.LESSON -> "${island.state.name.lowercase()} island: ${island.title}, ${dateLabel(island.date)}"
    }
    Column(
        Modifier
            .width(width)
            .scale(if (today && island.kind == IslandKind.LESSON) glow else 1f)
            .shadow(if (today) 12.dp else 4.dp, RoundedCornerShape(50), ambientColor = Palette.sun, spotColor = Palette.sun)
            .background(ground, RoundedCornerShape(50))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = Dimens.s16, horizontal = Dimens.s12)
            .semantics { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (island.kind) {
            IslandKind.LOCKED -> {
                Pip(PipPose.SLEEPING, 64.dp)
                Text("Shh… asleep", style = MaterialTheme.typography.labelLarge, color = Palette.white)
            }
            else -> {
                Text(dateLabel(island.date), style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp), color = Palette.ink.copy(alpha = 0.7f))
                Text(if (island.kind == IslandKind.REVIEW) "🔁" else meta.emoji, fontSize = 30.sp)
                Text(island.title, style = MaterialTheme.typography.labelLarge, color = Palette.ink, textAlign = TextAlign.Center, maxLines = 2)
                Spacer(Modifier.height(Dimens.s4))
                when {
                    island.state == IslandState.DONE -> {
                        Text("✓ Done", style = MaterialTheme.typography.labelLarge, color = Palette.ink)
                        StarRow(3, ((island.starsEarned ?: 0) * 3f / (island.starsTotal ?: 1).coerceAtLeast(1)).let { kotlin.math.round(it).toInt() }.coerceIn(0, 3), starSize = 14.dp)
                    }
                    today -> Box(Modifier.background(Palette.sun, CircleShape).padding(horizontal = 14.dp, vertical = 6.dp)) {
                        Text("Play!", style = MaterialTheme.typography.labelLarge, color = Palette.ink)
                    }
                    else -> Text("Waiting", style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp), color = Palette.ink.copy(alpha = 0.7f))
                }
            }
        }
    }
}

// =============================================================================
// GRADES 4–6: FORMAL ACADEMIC STUDENT HUB
// =============================================================================

@Composable
fun FormalStudentScreen(
    state: State,
    dispatch: (Intent) -> Unit,
    onStickers: () -> Unit,
    onChest: () -> Unit,
    onGrownUps: () -> Unit,
    onSwitchChild: () -> Unit = {},
    onNotifications: () -> Unit = {},
    onMessages: () -> Unit = {},
    onSettings: () -> Unit = {},
) {
    var selectedSubject by remember { mutableStateOf<Subject?>(null) }

    AcademicTheme {
        if (state.loading) {
            AnimatedLoadingView("Loading your coursework…")
            return@AcademicTheme
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(DashboardTokens.bg)
                .safeDrawingPadding(),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            ) {
                // Header Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
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
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .background(DashboardTokens.brandSoft, CircleShape)
                                .border(1.dp, DashboardTokens.brandSubtle, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = state.child?.name?.take(1)?.uppercase() ?: "S",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = DashboardTokens.brand,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                text = state.child?.name ?: "Student",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = DashboardTokens.inkStrong,
                            )
                            Text(
                                text = "Grade ${state.child?.grade ?: 1}",
                                style = MaterialTheme.typography.bodySmall,
                                color = DashboardTokens.inkSoft,
                            )
                        }
                    }

                    Spacer(Modifier.weight(1f))

                    // Streak Badge
                    if (state.streakDays > 0) {
                        DashboardPill(
                            text = "${state.streakDays}d streak",
                            icon = "🔥",
                            variant = DashboardPillVariant.WARNING,
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
                            .semantics { contentDescription = "Parent Portal" },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = "Parent Portal",
                            tint = DashboardTokens.brand,
                            modifier = Modifier.size(20.dp),
                        )
                    }

                    Spacer(Modifier.width(8.dp))
                    ReadAloudButton({ dispatch(Intent.ReadAloud) })
                }

                // Subject Filter Bar
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        DashboardFilterChip(
                            text = "All Subjects",
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
                            text = meta.labelEn,
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

                Box(Modifier.weight(1f)) {
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
                                        text = if (state.isEmpty && !state.loading) "No coursework scheduled today" else "No assignments found for this subject",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = DashboardTokens.inkStrong,
                                        textAlign = TextAlign.Center,
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = "Check back soon for new lessons and tasks.",
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
                                    onOpen = { dispatch(Intent.TapIsland(island.id)) },
                                )
                            }
                        }
                    }
                }
            }

            // Bottom Navigation Bar
            DashboardBottomNavigation(
                currentTab = DashboardTab.HOME,
                onTabSelected = { tab ->
                    when (tab) {
                        DashboardTab.HOME -> {}
                        DashboardTab.NOTIFICATION -> onNotifications()
                        DashboardTab.MESSAGES -> onMessages()
                        DashboardTab.SETTINGS -> onSettings()
                    }
                },
            )
        }
    }
}

@Composable
private fun FormalCourseworkCard(
    island: Island,
    onOpen: () -> Unit,
) {
    val meta = SubjectMeta.of(island.subject)
    val isLocked = island.kind == IslandKind.LOCKED
    val isDone = island.state == IslandState.DONE
    val isToday = island.state == IslandState.TODAY
    val isReview = island.kind == IslandKind.REVIEW

    DashboardCard(
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
                            text = "${meta.emoji} ${meta.labelEn}",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 12.sp,
                            ),
                            color = DashboardTokens.inkStrong,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = dateLabel(island.date),
                        style = MaterialTheme.typography.bodySmall,
                        color = DashboardTokens.inkMuted,
                    )
                }

                // Status Badge
                when {
                    isLocked -> DashboardPill(text = "Locked", variant = DashboardPillVariant.NEUTRAL)
                    isDone -> DashboardPill(text = "Completed", variant = DashboardPillVariant.SUCCESS)
                    isReview -> DashboardPill(text = "Review", variant = DashboardPillVariant.WARNING)
                    isToday -> DashboardPill(text = "Assigned Today", variant = DashboardPillVariant.INFO)
                    else -> DashboardPill(text = "Available", variant = DashboardPillVariant.NEUTRAL)
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
                            text = "Score: $stars/$total",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                            color = DashboardTokens.success,
                        )
                    }
                    DashboardButton(
                        text = "Review",
                        onClick = onOpen,
                        variant = DashboardButtonVariant.SECONDARY,
                        modifier = Modifier.width(110.dp),
                        height = 36.dp,
                    )
                } else if (isLocked) {
                    Text(
                        text = "Complete prior lessons to unlock",
                        style = MaterialTheme.typography.bodySmall,
                        color = DashboardTokens.inkMuted,
                    )
                } else {
                    Text(
                        text = if (isReview) "Reinforce concepts" else "Standard curriculum lesson",
                        style = MaterialTheme.typography.bodySmall,
                        color = DashboardTokens.inkSoft,
                    )
                    DashboardButton(
                        text = if (isToday) "Start Lesson" else "Open",
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

private fun dateLabel(d: LocalDate): String = "${d.dayOfMonth} ${d.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }}"
