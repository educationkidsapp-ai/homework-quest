package quest.feature.school.domain

import quest.api.DEFAULT_FLAGS

/**
 * The §4 flag keys the app gates something on, spelled once. `FlagKeysTest` asserts every constant here is a key the
 * platform actually defines, so a typo is a failing test rather than a screen that silently never appears.
 *
 * The other seeded keys (`lessons.*`, `complaints`, `announcements`, `teacherQuestions`, `progress.weeklyEmail`) gate
 * dashboard or server features the app does not draw yet; they are deliberately absent.
 */
object Flags {
    const val TREASURE_CHEST = "stickers.treasureChest"
    const val RETELL_RECORDING = "retell.recording"
    const val OPEN_ANSWER_DRAWING = "openAnswer.drawing"
    const val PARENT_PANEL_ARABIC = "parentPanel.arabic"
    const val CERTIFICATES = "certificates"
    const val LEVEL_THREE = "levels.three"

    /** Every key this app gates on. */
    val used = listOf(TREASURE_CHEST, RETELL_RECORDING, OPEN_ANSWER_DRAWING, PARENT_PANEL_ARABIC, CERTIFICATES, LEVEL_THREE)

    /** True when the platform defines [key] at all — the contract's `DEFAULT_FLAGS` is the list of what exists. */
    fun isKnown(key: String): Boolean = key in DEFAULT_FLAGS
}
