package quest.feature.staff

import quest.api.dto.ChatPeerRole
import quest.api.dto.ChatStaffRole
import quest.api.dto.ChatThread
import quest.feature.chat.presentation.ChatConversationContract
import quest.feature.parent.presentation.Strings
import quest.feature.staff.domain.StaffThreadKey
import quest.feature.staff.presentation.StaffStrings
import quest.feature.staff.presentation.staffLabelled
import quest.feature.staff.presentation.staffRowLabel
import quest.feature.staff.presentation.staffRowName
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Feature 13 (fix): what the staff end calls the person on the other side.
 *
 * `ChatThread.staffRole` is `MANAGERIAL` on every thread the platform admin holds, whoever is on the other end, so a
 * thread with her was titled by her login (`admin@quest.local`) and labelled "Department manager". The school's own
 * words are what both lines should carry, in either language.
 */
class StaffRowLabelsTest {

    private val en = Strings.forLanguage("en")
    private val ar = Strings.forLanguage("ar")
    private val staffEn = StaffStrings.forLanguage("en")

    private val adminThread = ChatThread(
        id = "t-1", childId = "", childName = "", teacherId = "u-admin", teacherName = "admin@quest.local",
        staffRole = ChatStaffRole.MANAGERIAL, peerRole = ChatPeerRole.ADMIN,
    )

    private val managerThread = adminThread.copy(teacherId = "u-m", teacherName = "Ms. Hala", peerRole = ChatPeerRole.MANAGERIAL)

    @Test fun `an admin counterpart is the school administration, not a department manager`() {
        assertEquals(en.schoolAdministration, staffRowName(adminThread, en))
        assertEquals("", staffRowLabel(adminThread, staffEn, en), "her name is the row; there is no role word under it")
        assertEquals(ar.schoolAdministration, staffRowName(adminThread, ar))
    }

    /** A real department manager still reads as one: only the admin was being mislabelled. */
    @Test fun `a manager counterpart keeps her role word`() {
        assertEquals("Ms. Hala", staffRowName(managerThread, en))
        assertEquals(en.managerRole, staffRowLabel(managerThread, staffEn, en))
    }

    @Test fun `the conversation header names the school administration too`() {
        val key = StaffThreadKey.of(adminThread, myUserId = "u-1", myRole = null)
        val labelled = ChatConversationContract.State().staffLabelled(key, staffEn, en)
        assertEquals(en.schoolAdministration, labelled.headerName)
        assertEquals("", labelled.headerSubtitle, "no second line rather than a role word she does not hold")
    }

    @Test fun `the conversation header keeps a manager's role word`() {
        val key = StaffThreadKey.of(managerThread, myUserId = "u-1", myRole = null)
        val labelled = ChatConversationContract.State().staffLabelled(key, staffEn, en)
        assertEquals("Ms. Hala", labelled.headerName)
        assertEquals(en.managerRole, labelled.headerSubtitle)
    }
}
