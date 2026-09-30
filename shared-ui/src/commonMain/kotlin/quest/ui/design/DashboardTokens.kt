package quest.ui.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Modern Dashboard Design System tokens — 100% aligned with the TailAdmin dashboard system
 * (`dashboard/src/styles/_theme.scss`).
 *
 * Used across the formal Parent flow and the formal upper-elementary (Grades 4–6) Child flow.
 */
object DashboardTokens {
    // ---- Page & Surface Roles ------------------------------------------------
    val bg = Color(0xFFF9FAFB)           // Page ground (gray-50)
    val bgSubtle = Color(0xFFF2F4F7)     // Secondary ground / container tint (gray-100)
    val surface = Color(0xFFFFFFFF)      // Pure white cards & containers
    val surfaceRaised = Color(0xFFFFFFFF)// Elevated header & nav bars
    val surfaceDark = Color(0xFF1A2231)  // Dark mode / slate surface

    // ---- Ink (Typography) Roles ----------------------------------------------
    val ink = Color(0xFF1D2939)          // Primary text & headers (gray-800, 14.7:1 contrast)
    val inkStrong = Color(0xFF101828)    // Titles (gray-900)
    val inkSoft = Color(0xFF475467)      // Secondary text (gray-600, 7.69:1 contrast)
    val inkMuted = Color(0xFF98A2B3)     // Captions, hints, placeholders (gray-400)
    val inkLight = Color(0xFFD0D5DD)     // Subtle icon glyphs (gray-300)

    // ---- Rule & Border Roles -------------------------------------------------
    val rule = Color(0xFFE4E7EC)         // Container borders (gray-200)
    val ruleControl = Color(0xFFD0D5DD)  // Button & input borders (gray-300)
    val ruleFocus = Color(0xFF465FFF)    // Focus outline
    val divider = Color(0xFFF2F4F7)      // Dividers inside containers (gray-100)

    // ---- Brand & Accent Roles ------------------------------------------------
    val brand = Color(0xFF465FFF)        // Primary brand blue (brand-500)
    val brandStrong = Color(0xFF3641F5)  // Brand hover & active states (brand-600)
    val brandDeep = Color(0xFF262E89)    // Brand 900
    val brandSoft = Color(0xFFECF3FF)    // Chips, soft selection tint (brand-50)
    val brandSubtle = Color(0xFFDDE9FF)  // Brand 100
    val onBrand = Color(0xFFFFFFFF)      // Text on brand

    // ---- Status & Feedback Roles ---------------------------------------------
    val success = Color(0xFF027A48)      // Success text & icons (success-700)
    val successBg = Color(0xFFECFDF3)    // Success pill background (success-50)
    val successBorder = Color(0xFFD1FADF)// Success pill border (success-100)

    val warning = Color(0xFFB54708)      // Warning text & icons (warning-700)
    val warningBg = Color(0xFFFFFAEB)    // Warning pill background (warning-50)
    val warningBorder = Color(0xFFFEF0C7)// Warning pill border (warning-100)

    val error = Color(0xFFB42318)        // Error text & icons (error-700)
    val errorBg = Color(0xFFFEF3F2)      // Error pill background (error-50)
    val errorBorder = Color(0xFFFEE4E2)  // Error pill border (error-100)

    // ---- Radii ---------------------------------------------------------------
    val radiusSm = 8.dp                  // Buttons, inputs, small chips
    val radiusMd = 12.dp                 // Cards, dialogs
    val radiusLg = 16.dp                 // Sheets, large panels, modal cards
    val radiusFull = 9999.dp             // Pill badges, avatars

    // ---- Elevation / Sizes ---------------------------------------------------
    val cardBorderWidth = 1.dp
    val buttonHeight = 44.dp
    val touchTargetMin = 44.dp
}
