package quest.feature.parent.presentation

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import quest.feature.parent.domain.ParentRepository
import quest.feature.school.domain.Flags
import quest.feature.school.presentation.FeatureGate
import quest.feature.school.presentation.featureEnabled
import quest.ui.design.DashboardTokens
import quest.ui.design.Dimens
import quest.ui.design.ParentTheme

import quest.ui.design.DashboardBottomNavigation
import quest.ui.design.DashboardTab

/**
 * Wraps every parent route: applies the parent theme, RTL when Arabic, the modern header
 * with the language toggle aligned with the TailAdmin dashboard design system, and
 * the persistent dashboard bottom navigation bar on parent portal tabs.
 */
@Composable
fun ParentShell(
    title: (Strings) -> String,
    onBack: (() -> Unit)?,
    currentTab: DashboardTab? = null,
    onTabSelected: ((DashboardTab) -> Unit)? = null,
    content: @Composable (Strings) -> Unit,
) {
    val parent: ParentRepository = koinInject()
    val language by parent.language.collectAsStateWithLifecycle()
    // §4 `parentPanel.arabic`: a school without it has an English-only parent mode — no toggle, and a parent who set
    // Arabic before the flag was turned off is put back into English rather than stranded in a language with no way out.
    val arabic = featureEnabled(Flags.PARENT_PANEL_ARABIC)
    val strings = if (arabic) Strings.forLanguage(language) else Strings.en
    val scope = rememberCoroutineScope()
    ParentTheme(rtl = strings.isRtl) {
        CompositionLocalProvider(LocalStrings provides strings) {
            Column(
                Modifier
                    .fillMaxSize()
                    .background(DashboardTokens.bg)
                    .safeDrawingPadding(),
            ) {
                // Modern elevated header bar
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Dimens.s12, vertical = Dimens.s8),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (onBack != null) {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(DashboardTokens.radiusSm))
                                .semantics { contentDescription = "Back" },
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = DashboardTokens.ink)
                        }
                    } else Spacer(Modifier.width(Dimens.s8))
                    Text(
                        title(strings),
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = DashboardTokens.inkStrong,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = Dimens.s8),
                    )
                    FeatureGate(Flags.PARENT_PANEL_ARABIC) {
                        LanguageToggle(language) { code -> scope.launch { parent.setLanguage(code) } }
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) { content(strings) }

                if (currentTab != null && onTabSelected != null) {
                    // §4 / `FeatureGate.kt`: entry points are hidden by their own gate. Without this the Notification
                    // tab still routed to `Routes.Broadcasts`, whose `GateFallback` sent the parent straight back.
                    DashboardBottomNavigation(
                        currentTab = currentTab,
                        onTabSelected = onTabSelected,
                        isRtl = strings.isRtl,
                        showNotifications = featureEnabled(Flags.ANNOUNCEMENTS),
                    )
                }
            }
        }
    }
}

@Composable
private fun LanguageToggle(current: String, onChange: (String) -> Unit) {
    val containerShape = RoundedCornerShape(DashboardTokens.radiusSm)
    Row(
        Modifier
            .background(DashboardTokens.bgSubtle, containerShape)
            .border(1.dp, DashboardTokens.rule, containerShape)
            .padding(2.dp)
            .semantics { contentDescription = "Language" },
    ) {
        listOf("en" to "EN", "ar" to "ع").forEach { (code, label) ->
            val on = code == current
            val itemShape = RoundedCornerShape(6.dp)
            Box(
                Modifier
                    .background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent, itemShape)
                    .clip(itemShape)
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

/**
 * Modern Dashboard Card (TailAdmin style):
 * Pure white surface, 12 dp rounded corners, 1 dp subtle border, and soft elevation shadow.
 */
@Composable
fun ParentCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(DashboardTokens.radiusMd)
    val interaction = remember { MutableInteractionSource() }
    val isPressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed && onClick != null) 0.985f else 1f, label = "card_scale")

    Column(
        modifier
            .fillMaxWidth()
            .scale(scale)
            .shadow(1.dp, shape, spotColor = Color(0x0A101828), ambientColor = Color(0x05101828))
            .background(MaterialTheme.colorScheme.surface, shape)
            .border(DashboardTokens.cardBorderWidth, MaterialTheme.colorScheme.outline, shape)
            .clip(shape)
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interaction,
                        indication = null,
                        role = Role.Button,
                        onClick = onClick,
                    )
                } else Modifier,
            )
            .padding(16.dp),
    ) { content() }
}

/**
 * Modern Dashboard Button (TailAdmin style):
 * Primary uses brand blue (#465FFF) with white text, secondary uses clean surface with subtle rule border.
 */
@Composable
fun ParentButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    enabled: Boolean = true,
    icon: String? = null,
) {
    val shape = RoundedCornerShape(DashboardTokens.radiusSm)
    val interaction = remember { MutableInteractionSource() }
    val isPressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed && enabled) 0.98f else 1f, label = "btn_scale")

    val bg = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
    val textColor = if (primary) MaterialTheme.colorScheme.onPrimary else DashboardTokens.ink
    val borderModifier = if (primary) Modifier else Modifier.border(1.dp, DashboardTokens.ruleControl, shape)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = DashboardTokens.buttonHeight)
            .scale(scale)
            .alpha(if (enabled) 1f else 0.5f)
            .background(bg, shape)
            .then(borderModifier)
            .clip(shape)
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            if (icon != null) {
                Text(icon, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = textColor,
            )
        }
    }
}

/**
 * Modern Pill / Badge (TailAdmin status badge style):
 * Rounded pill with subtle background tint, border, and readable typography.
 */
@Composable
fun Chip(
    text: String,
    color: Color = MaterialTheme.colorScheme.primaryContainer,
    modifier: Modifier = Modifier,
    selected: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val shape = CircleShape
    Box(
        modifier = modifier
            .background(if (selected) color else Color.Transparent, shape)
            .border(1.dp, if (selected) color.copy(alpha = 0.8f) else DashboardTokens.rule, shape)
            .clip(shape)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 12.sp),
            color = DashboardTokens.ink,
        )
    }
}

/**
 * Section Title with modern dashboard typography.
 */
@Composable
fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium.copy(
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
        ),
        color = DashboardTokens.inkStrong,
        modifier = Modifier.padding(top = 18.dp, bottom = 8.dp),
    )
}
