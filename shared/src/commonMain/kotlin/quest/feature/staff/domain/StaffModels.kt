package quest.feature.staff.domain

import quest.api.staff.StaffMe

/**
 * Feature 13: the five roles that sign in to the **app**. The wire words are the server's (`permissions.json`,
 * `quest.api.dashboard.Role`); an account whose role this build has never heard of is treated as staff all the same —
 * it holds `staff.app` or the server would not have let it through `GET /staff/me` — and simply shows no role word.
 */
enum class StaffRole(val wire: String) {
    ADMIN("ADMIN"), TEACHER("TEACHER"), MANAGERIAL("MANAGERIAL"), COORDINATOR("COORDINATOR"), WORKER("WORKER");

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
) {
    val hasChat: Boolean get() = role?.hasChat ?: true

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
        )
    }
}
