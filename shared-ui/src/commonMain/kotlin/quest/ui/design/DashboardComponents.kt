package quest.ui.design

import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Brush
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Modern TailAdmin Dashboard Card:
 * - Pure white surface
 * - 1 dp subtle border (#E4E7EC)
 * - 12 dp rounded corners
 * - Optional soft shadow and tap interaction
 */
@Composable
fun DashboardCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    backgroundColor: Color = MaterialTheme.colorScheme.surface,
    borderColor: Color = MaterialTheme.colorScheme.outline,
    padding: PaddingValues = PaddingValues(16.dp),
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(DashboardTokens.radiusLg)
    val interaction = remember { MutableInteractionSource() }
    val isPressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed && onClick != null) 0.985f else 1f, label = "card_scale")

    Box(
        modifier = modifier
            .fillMaxWidth()
            .scale(scale)
            .shadow(1.dp, shape, spotColor = DashboardTokens.shadowSpot, ambientColor = DashboardTokens.shadowAmbient)
            .background(backgroundColor, shape)
            .border(DashboardTokens.cardBorderWidth, borderColor, shape)
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
            .padding(padding),
    ) {
        content()
    }
}

/**
 * The hero card: the brand gradient with white text on it — the head of a lesson or an exam. One per screen at most;
 * everything under it stays on plain surfaces, so the gradient leads without the page turning blue.
 */
@Composable
fun DashboardHeroCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(DashboardTokens.radiusLg)
    Box(modifier.fillMaxWidth().background(brandGradient(forText = true), shape).clip(shape).padding(20.dp)) { content() }
}

/**
 * Modern Dashboard Button:
 * - Primary: filled brand accent (#465FFF) with white text
 * - Secondary: white surface with gray border (#D0D5DD) and dark text
 * - Destructive: soft red with red text
 */
enum class DashboardButtonVariant { PRIMARY, SECONDARY, DESTRUCTIVE }

@Composable
fun DashboardButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: DashboardButtonVariant = DashboardButtonVariant.PRIMARY,
    enabled: Boolean = true,
    icon: String? = null,
    height: Dp = DashboardTokens.buttonHeight,
) {
    val shape = RoundedCornerShape(DashboardTokens.radiusSm)
    val interaction = remember { MutableInteractionSource() }
    val isPressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed && enabled) 0.98f else 1f, label = "btn_scale")

    // A primary action wears the brand gradient (deep enough end to end for its label); the others are flat.
    val bg = when (variant) {
        DashboardButtonVariant.PRIMARY -> primaryFill()
        DashboardButtonVariant.SECONDARY -> SolidColor(MaterialTheme.colorScheme.surface)
        DashboardButtonVariant.DESTRUCTIVE -> SolidColor(DashboardTokens.errorBg)
    }
    val textColor = when (variant) {
        DashboardButtonVariant.PRIMARY -> MaterialTheme.colorScheme.onPrimary
        DashboardButtonVariant.SECONDARY -> DashboardTokens.ink
        DashboardButtonVariant.DESTRUCTIVE -> DashboardTokens.error
    }
    val borderModifier = when (variant) {
        DashboardButtonVariant.PRIMARY -> Modifier
        DashboardButtonVariant.SECONDARY -> Modifier.border(1.dp, DashboardTokens.ruleControl, shape)
        DashboardButtonVariant.DESTRUCTIVE -> Modifier.border(1.dp, DashboardTokens.errorBorder, shape)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
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
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (icon != null) {
                Text(icon, fontSize = 18.sp)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Modern Status Badge / Pill:
 * Pill shape (CircleShape / 9999dp) with tinted background and high-contrast text.
 */
/** [HIGHLIGHT] is the brand's orange — identity, not status: the streak. */
enum class DashboardPillVariant { SUCCESS, WARNING, ERROR, INFO, NEUTRAL, HIGHLIGHT }

@Composable
fun DashboardPill(
    text: String,
    modifier: Modifier = Modifier,
    variant: DashboardPillVariant = DashboardPillVariant.NEUTRAL,
    icon: String? = null,
) {
    val bg = when (variant) {
        DashboardPillVariant.SUCCESS -> DashboardTokens.successBg
        DashboardPillVariant.WARNING -> DashboardTokens.warningBg
        DashboardPillVariant.ERROR -> DashboardTokens.errorBg
        DashboardPillVariant.INFO -> MaterialTheme.colorScheme.primaryContainer
        DashboardPillVariant.NEUTRAL -> DashboardTokens.bgSubtle
        DashboardPillVariant.HIGHLIGHT -> DashboardTokens.tertiarySoft
    }
    val textColor = when (variant) {
        DashboardPillVariant.SUCCESS -> DashboardTokens.success
        DashboardPillVariant.WARNING -> DashboardTokens.warning
        DashboardPillVariant.ERROR -> DashboardTokens.error
        DashboardPillVariant.INFO -> DashboardTokens.accentInk
        DashboardPillVariant.NEUTRAL -> DashboardTokens.inkSoft
        DashboardPillVariant.HIGHLIGHT -> DashboardTokens.tertiaryInk
    }
    val borderColor = when (variant) {
        DashboardPillVariant.SUCCESS -> DashboardTokens.successBorder
        DashboardPillVariant.WARNING -> DashboardTokens.warningBorder
        DashboardPillVariant.ERROR -> DashboardTokens.errorBorder
        DashboardPillVariant.INFO -> MaterialTheme.colorScheme.primaryContainer
        DashboardPillVariant.NEUTRAL -> DashboardTokens.rule
        DashboardPillVariant.HIGHLIGHT -> DashboardTokens.tertiarySoft
    }

    Box(
        modifier = modifier
            .background(bg, CircleShape)
            .border(1.dp, borderColor, CircleShape)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Text(icon, fontSize = 12.sp)
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                ),
                color = textColor,
                maxLines = 1,
            )
        }
    }
}

/**
 * Filter Chip (Subject or Category selector) with smooth selection state.
 */
@Composable
fun DashboardFilterChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: String? = null,
) {
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    val border = if (selected) MaterialTheme.colorScheme.primary else DashboardTokens.ruleControl
    val textColor = if (selected) DashboardTokens.accentInk else DashboardTokens.inkSoft

    Box(
        modifier = modifier
            .heightIn(min = 36.dp)
            .background(bg, RoundedCornerShape(DashboardTokens.radiusSm))
            .border(1.dp, border, RoundedCornerShape(DashboardTokens.radiusSm))
            .clip(RoundedCornerShape(DashboardTokens.radiusSm))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Text(icon, fontSize = 14.sp)
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                ),
                color = textColor,
            )
        }
    }
}

/**
 * Section title with dashboard styling.
 */
@Composable
fun DashboardSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 20.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                ),
                color = DashboardTokens.ink,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = DashboardTokens.inkSoft,
                )
            }
        }
        if (action != null) {
            action()
        }
    }
}

/**
 * The brand gradient — the logo diamond's light-to-deep blue (`--hq-gradient-brand`) — as a brush running from the
 * top-start corner, the dashboard's 135°. [forText] is `--hq-gradient-brand-fill`: started one step in, for surfaces
 * that carry a normal-size white label. It does not follow a school's accent.
 */
@Composable
fun brandGradient(forText: Boolean = false): Brush =
    LocalDashboardPalette.current.let { Brush.linearGradient(if (forText) it.gradientText else it.gradient) }

/**
 * What a primary action is filled with: the brand gradient while the app wears its own colours, and a school's accent,
 * flat, when it has one — the gradient is the product's, the button is the school's.
 */
@Composable
fun primaryFill(): Brush =
    if (MaterialTheme.colorScheme.primary == LocalDashboardPalette.current.brand) brandGradient(forText = true) else SolidColor(MaterialTheme.colorScheme.primary)

/**
 * A student as the app draws one everywhere: the initial of the name on the brand gradient — the dashboard's avatar
 * tile — used on the student home and wherever a child is listed.
 */
@Composable
fun StudentAvatar(name: String, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    Box(modifier.size(size).background(brandGradient(), CircleShape), contentAlignment = Alignment.Center) {
        Text(
            name.trim().take(1).uppercase().ifEmpty { "–" },
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = DashboardTokens.onBrand,
        )
    }
}

/**
 * Clean formal progress bar with rounded ends.
 */
@Composable
fun DashboardProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = DashboardTokens.divider,
    height: Dp = 8.dp,
) {
    val clamped = progress.coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .background(trackColor, CircleShape)
            .clip(CircleShape),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(clamped)
                .height(height)
                .background(color, CircleShape),
        )
    }
}

/**
 * Bottom navigation tabs for the main app view.
 */
enum class DashboardTab(val labelEn: String, val labelAr: String) {
    HOME("Home", "الرئيسية"),
    NOTIFICATION("Notifications", "الإشعارات"),
    MESSAGES("Messages", "الرسائل"),
    SETTINGS("Settings", "الإعدادات");

    fun label(rtl: Boolean) = if (rtl) labelAr else labelEn
}

/**
 * Modern TailAdmin bottom navigation bar for the Home student/parent experience.
 *
 * [showNotifications] is the `announcements` gate reaching the tab bar: §4's rule is that a school without a feature
 * never learns it exists, so a tab whose route would bounce straight back is not drawn at all. `shared-ui` knows no
 * flags, so the caller (`ParentShell`) decides.
 */
@Composable
fun DashboardBottomNavigation(
    currentTab: DashboardTab,
    onTabSelected: (DashboardTab) -> Unit,
    modifier: Modifier = Modifier,
    isRtl: Boolean = false,
    unreadNotifications: Int = 0,
    unreadMessages: Int = 0,
    showNotifications: Boolean = true,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .shadow(6.dp, spotColor = DashboardTokens.shadowSpot, ambientColor = DashboardTokens.shadowAmbient),
        color = DashboardTokens.surface,
        border = BorderStroke(1.dp, DashboardTokens.rule),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val tabs = DashboardTab.entries.filter { showNotifications || it != DashboardTab.NOTIFICATION }
            tabs.forEach { tab ->
                val isSelected = tab == currentTab
                val icon = when (tab) {
                    DashboardTab.HOME -> Icons.Default.Home
                    DashboardTab.NOTIFICATION -> Icons.Default.Notifications
                    DashboardTab.MESSAGES -> Icons.Default.ChatBubble
                    DashboardTab.SETTINGS -> Icons.Default.Settings
                }
                val badgeCount = when (tab) {
                    DashboardTab.NOTIFICATION -> unreadNotifications
                    DashboardTab.MESSAGES -> unreadMessages
                    else -> 0
                }

                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(DashboardTokens.radiusSm))
                        .clickable(role = Role.Tab) { onTabSelected(tab) }
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(contentAlignment = Alignment.TopEnd) {
                        // The dashboard's active nav item: the brand gradient with a white glyph.
                        Box(
                            Modifier.size(width = 48.dp, height = 30.dp)
                                .then(if (isSelected) Modifier.background(brandGradient(), RoundedCornerShape(DashboardTokens.radiusSm)) else Modifier),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = tab.label(isRtl),
                                tint = if (isSelected) DashboardTokens.onBrand else DashboardTokens.inkSoft,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                        if (badgeCount > 0) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(DashboardTokens.secondary, CircleShape),
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = tab.label(isRtl),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        ),
                        color = if (isSelected) DashboardTokens.accentInk else DashboardTokens.inkSoft,
                    )
                }
            }
        }
    }
}

