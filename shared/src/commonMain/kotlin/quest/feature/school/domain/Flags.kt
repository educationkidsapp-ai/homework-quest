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

    /**
     * The highest level a school offers. Level 3 is the Challenge path; without [LEVEL_THREE] a lesson tops out at 2.
     *
     * This is spelled once, here, because "the Harder path is not offered" has to hold at *every* door into it — the
     * journey's level selector, the finish screen's next-level button, and the route itself — and three separate
     * `if (flag) 3 else 2` expressions is exactly how one of those doors gets left open.
     */
    fun topLevel(levelThreeEnabled: Boolean): Int = if (levelThreeEnabled) 3 else 2

    /** The levels a school's journey offers, in order. */
    fun levels(levelThreeEnabled: Boolean): List<Int> = (1..topLevel(levelThreeEnabled)).toList()

    /** False for a level this school does not sell — a route into it must land somewhere else. */
    fun levelAllowed(level: Int, levelThreeEnabled: Boolean): Boolean = level <= topLevel(levelThreeEnabled)
}
