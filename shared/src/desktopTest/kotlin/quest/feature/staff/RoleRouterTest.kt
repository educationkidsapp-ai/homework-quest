package quest.feature.staff

import quest.feature.staff.domain.RoleRouter
import quest.feature.staff.domain.SessionSide
import quest.feature.staff.domain.StaffRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Feature 13: where the app opens, for each kind of account. One sign-in screen means this is the only thing that
 * decides whether a person sees the child's world map or a staff home, so every case the spec names is here.
 */
class RoleRouterTest {

    @Test fun `a parent lands on the parent flow`() {
        assertEquals(RoleRouter.Start.ParentFlow, RoleRouter.start(setOf(SessionSide.PARENT), null))
        assertEquals(SessionSide.PARENT, RoleRouter.side(setOf(SessionSide.PARENT), null))
    }

    @Test fun `a teacher lands on the staff home`() {
        assertEquals(RoleRouter.Start.StaffHome, RoleRouter.start(setOf(SessionSide.STAFF), null))
    }

    /** A worker holds exactly one side, like a teacher who is not a parent — the role does not change the route. */
    @Test fun `a worker lands on the staff home`() {
        assertTrue(StaffRole.of("WORKER") == StaffRole.WORKER)
        assertFalse(StaffRole.WORKER.hasChat)
        assertEquals(RoleRouter.Start.StaffHome, RoleRouter.start(setOf(SessionSide.STAFF), null))
    }

    @Test fun `nobody signed in goes to the sign-in screen`() {
        assertEquals(RoleRouter.Start.SignIn, RoleRouter.start(emptySet(), null))
        assertEquals(null, RoleRouter.side(emptySet(), SessionSide.STAFF))
    }

    /** A teacher whose child is at the school: the staff side opens, with the switch on it. */
    @Test fun `a dual-role account with no choice yet opens the staff side`() {
        val both = setOf(SessionSide.STAFF, SessionSide.PARENT)
        assertEquals(RoleRouter.Start.Choose, RoleRouter.start(both, null))
        assertEquals(SessionSide.STAFF, RoleRouter.side(both, null))
    }

    @Test fun `the remembered choice decides for a dual-role account`() {
        val both = setOf(SessionSide.STAFF, SessionSide.PARENT)
        assertEquals(RoleRouter.Start.ParentFlow, RoleRouter.start(both, SessionSide.PARENT))
        assertEquals(RoleRouter.Start.StaffHome, RoleRouter.start(both, SessionSide.STAFF))
    }

    /** A remembered side she no longer holds is ignored rather than opening a home she cannot reach. */
    @Test fun `a remembered side that is gone is ignored`() {
        assertEquals(RoleRouter.Start.ParentFlow, RoleRouter.start(setOf(SessionSide.PARENT), SessionSide.STAFF))
        assertEquals(RoleRouter.Start.StaffHome, RoleRouter.start(setOf(SessionSide.STAFF), SessionSide.PARENT))
    }

    @Test fun `the switch is drawn only for an account that holds both sides`() {
        assertTrue(RoleRouter.canSwitch(setOf(SessionSide.STAFF, SessionSide.PARENT)))
        assertFalse(RoleRouter.canSwitch(setOf(SessionSide.STAFF)))
        assertFalse(RoleRouter.canSwitch(emptySet()))
    }
}
