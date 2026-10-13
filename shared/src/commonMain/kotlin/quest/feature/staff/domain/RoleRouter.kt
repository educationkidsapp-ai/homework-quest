package quest.feature.staff.domain

/**
 * Feature 13: where the app opens, decided by **which sides are signed in** and nothing else.
 *
 * There is one sign-in screen for everybody, so the answer to "whose app is this?" is no longer "a parent's". It is a
 * pure function of the sides a session restored (or a sign-in produced) and the side she last chose, kept here so the
 * five callers that need it — the first destination, the Staff/Parent switch, the two sign-in paths and the push
 * router — cannot drift apart.
 */
object RoleRouter {
    sealed interface Start {
        /** Nobody is signed in. */
        data object SignIn : Start

        /** The staff home (the Attendance tab, with the Today card on it). */
        data object StaffHome : Start

        /**
         * Feature 13: she signed in with a temporary password and has not replaced it yet. It comes before the staff
         * home on a **restored** session too — the flag is kept with the session (`SettingsStore.KEY_STAFF_MUST_CHANGE`)
         * so being killed between the sign-in and the change does not let her past the step.
         */
        data object StaffPassword : Start

        /** Today's parent path — the child picker and the world map — exactly as it was. */
        data object ParentFlow : Start

        /**
         * Both sides are signed in and she has never chosen. The staff side opens, because that is the side a teacher
         * signed in for, and the switch is at the top of it; the first switch she uses is remembered from then on.
         */
        data object Choose : Start
    }

    fun start(sides: Set<SessionSide>, last: SessionSide?, mustChangePassword: Boolean = false): Start = when {
        sides.isEmpty() -> Start.SignIn
        // Her temporary password is the first thing the app asks about, whichever other side she holds.
        mustChangePassword && SessionSide.STAFF in sides -> Start.StaffPassword
        sides.size == 1 -> if (sides.first() == SessionSide.STAFF) Start.StaffHome else Start.ParentFlow
        last == SessionSide.PARENT -> Start.ParentFlow
        last == SessionSide.STAFF -> Start.StaffHome
        else -> Start.Choose
    }

    /** The side the app actually shows for [start] — [Start.Choose] is the staff side with the switch on it. */
    fun side(sides: Set<SessionSide>, last: SessionSide?, mustChangePassword: Boolean = false): SessionSide? =
        when (start(sides, last, mustChangePassword)) {
            Start.SignIn -> null
            Start.ParentFlow -> SessionSide.PARENT
            Start.StaffHome, Start.Choose, Start.StaffPassword -> SessionSide.STAFF
        }

    /** The switch is drawn only when there is something to switch between. */
    fun canSwitch(sides: Set<SessionSide>): Boolean = sides.size > 1
}
