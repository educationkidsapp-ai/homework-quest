package quest.feature.school.domain

import quest.api.DEFAULT_FLAGS

/**
 * The §4 flag keys the app gates something on, spelled once. `FlagKeysTest` asserts every constant here is a key the
 * platform actually defines, so a typo is a failing test rather than a screen that silently never appears.
 *
 * The other seeded keys (`stickers.treasureChest` — the app has had no treasure chest since the formal redesign —
 * `lessons.*`, `complaints`, `teacherQuestions`, `progress.weeklyEmail`) gate dashboard or
 * server features the app does not draw yet; they are deliberately absent.
 */
object Flags {
    const val RETELL_RECORDING = "retell.recording"
    const val OPEN_ANSWER_DRAWING = "openAnswer.drawing"
    const val PARENT_PANEL_ARABIC = "parentPanel.arabic"
    const val CERTIFICATES = "certificates"
    const val LEVEL_THREE = "levels.three"
    const val CHAT = "chat"

    /**
     * RM4 (DR6): the broadcasts feed — the weekly plan, announcements and events. The key the feature it supersedes
     * already carried, so a school that bought announcements gets the plan and the events with them; the server
     * answers 404 to the feed while it is off.
     */
    const val ANNOUNCEMENTS = "announcements"

    /** §8: exams. M4 (D4) gates the student's result screen on it. */
    const val EXAMS = "exams"

    /**
     * Feature 13: staff sign in to the mobile app. Off by default and turned on per school; its value comes from
     * `GET /staff/me` (the staff member's **own** school), not from the current child's.
     */
    const val STAFF_MOBILE_APP = "staffMobileApp"

    /**
     * Feature 15: geofenced check-in and check-out. A school that does not use it sees no Check in button and no
     * "My record"; the server answers 404 to the four `/staff/attendance` routes while it is off.
     */
    const val STAFF_ATTENDANCE = "staffAttendance"

    /**
     * Feature 19: chat office hours. With it off nothing changes — the parent sees no banner in a conversation and a
     * staff member no after-hours choice in Account; the server leaves `ChatThread.officeHours` off the wire.
     */
    const val CHAT_OFFICE_HOURS = "chatOfficeHours"

    /**
     * Feature 22: a leader turns a spoken note into an announcement. It rides **beside** [ANNOUNCEMENTS] rather than
     * replacing it — the announcement it prepares is an ordinary announcement, so a school that has not bought
     * announcements at all cannot have the voice shortcut either, and both must be on for the card to appear.
     */
    const val VOICE_ANNOUNCEMENT = "voiceAnnouncement"

    /**
     * Feature 28: the parent's evening card about one child's day. The server answers 404 to
     * `GET /children/{id}/evening-card` while it is off, so the home asks for the card only when the school has it —
     * one refused request per home load and per child switch is exactly the bug `announcements` taught.
     */
    const val PARENT_EVENING_CARD = "parentEveningCard"

    /**
     * Feature 29: the parent reads the school's messages in her own language. It gates the Reading language setting
     * and the Translate action under a staff message, a broadcast, an announcement and a complaint reply — never the
     * original, which is the record and is always drawn whatever this flag says.
     */
    const val LANGUAGE_BRIDGE = "languageBridge"

    /** Every key this app gates on. */
    val used = listOf(
        RETELL_RECORDING, OPEN_ANSWER_DRAWING, PARENT_PANEL_ARABIC, CERTIFICATES, LEVEL_THREE, CHAT, ANNOUNCEMENTS,
        EXAMS, STAFF_MOBILE_APP, STAFF_ATTENDANCE, CHAT_OFFICE_HOURS, VOICE_ANNOUNCEMENT, PARENT_EVENING_CARD, LANGUAGE_BRIDGE,
    )

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
