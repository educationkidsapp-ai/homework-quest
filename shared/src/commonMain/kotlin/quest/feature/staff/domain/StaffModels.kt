package quest.feature.staff.domain

import quest.api.DEFAULT_FLAGS
import quest.api.staff.StaffMe
import quest.feature.school.domain.Flags

/**
 * Feature 13: the roles that sign in to the **app**. The wire words are the server's (`permissions.json`,
 * `quest.api.dashboard.Role`); an account whose role this build has never heard of is treated as staff all the same —
 * it holds `staff.app` or the server would not have let it through `GET /staff/me` — and simply shows no role word.
 *
 * Feature 20 adds the floor supervisor: `permissions.json` gives her `staff.app`, and `StaffService` answers her
 * thread list from her floors, so on the app she is an ordinary staff member with messages.
 */
enum class StaffRole(val wire: String) {
    ADMIN("ADMIN"), TEACHER("TEACHER"), MANAGERIAL("MANAGERIAL"), COORDINATOR("COORDINATOR"), WORKER("WORKER"),
    FLOOR_SUPERVISOR("FLOOR_SUPERVISOR");

    /** A WORKER has no chat partners yet (`StaffService`: her thread list is empty and a thread id is 404). */
    val hasChat: Boolean get() = this != WORKER

    companion object {
        fun of(wire: String?): StaffRole? = entries.firstOrNull { it.wire.equals(wire, ignoreCase = true) }
    }
}

/** Which half of the app an account is looking at. A dual-role account switches between the two; the choice is kept. */
enum class SessionSide(val key: String) {
    STAFF("staff"), PARENT("parent");

    companion object {
        fun of(key: String?): SessionSide? = entries.firstOrNull { it.key == key }
    }
}

/**
 * Who is signed in on the staff side, as the shell needs her: `GET /staff/me` plus the role parsed once. [flags] is
 * her **school's** effective flag map, which is why the staff screens gate on this rather than on whichever school a
 * child of hers happens to be in.
 */
data class StaffProfile(
    val userId: String,
    val displayName: String,
    val role: StaffRole?,
    val roleWire: String,
    val phone: String?,
    val schoolId: String,
    val schoolName: String?,
    val flags: Map<String, Boolean>,
    /**
     * Feature 19: she keeps normal alerts for parent messages that arrive outside her office hours. Off by default —
     * the point of the feature is a quiet evening — and only ever shown while her school has `chatOfficeHours` on.
     */
    val afterHoursAlerts: Boolean = false,
) {
    val hasChat: Boolean get() = role?.hasChat ?: true

    /**
     * Feature 22: may she turn a voice note into an announcement? A leader of the school — the Admin or a department
     * manager, which is `broadcast.voice` on the server — with **both** switches on.
     *
     * Spelled once because two places ask it: the card on Home and the view model, which refuses every intent without
     * it so a deep link or a flag flipped mid-screen cannot reach the recorder either.
     */
    val canVoiceAnnounce: Boolean
        get() = (role == StaffRole.ADMIN || role == StaffRole.MANAGERIAL) &&
            flag(Flags.ANNOUNCEMENTS) && flag(Flags.VOICE_ANNOUNCEMENT)

    /** Her school's value, falling back to what a school with no row of its own gets. */
    private fun flag(key: String): Boolean = flags[key] ?: DEFAULT_FLAGS[key] ?: false

    companion object {
        fun of(me: StaffMe): StaffProfile = StaffProfile(
            userId = me.userId,
            displayName = me.displayName,
            role = StaffRole.of(me.role),
            roleWire = me.role,
            phone = me.phone?.takeIf { it.isNotBlank() },
            schoolId = me.schoolId,
            schoolName = me.schoolName?.takeIf { it.isNotBlank() },
            flags = me.flags,
            afterHoursAlerts = me.afterHoursAlerts,
        )
    }
}
