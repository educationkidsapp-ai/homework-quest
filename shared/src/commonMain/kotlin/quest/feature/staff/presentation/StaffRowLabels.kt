package quest.feature.staff.presentation

import quest.api.dto.ChatPeerRole
import quest.api.dto.ChatThread
import quest.feature.chat.presentation.staffLabel
import quest.feature.parent.presentation.Strings

/**
 * Feature 13: the same row, read from the staff end.
 *
 * The facade answers a teacher's parent threads with **her** in `teacherId`/`teacherName` and the parent in
 * `parentName` — right for the dashboard, which lists her pupils, and wrong for a row that has to say whom she is
 * talking to. So a parent thread is titled by the parent (falling back to the child, because a roster child nobody
 * has claimed yet has no parent name) with the child and the section under it, and a staff thread keeps the
 * colleague's name and the role word the parent side already spells.
 */
fun staffRowName(thread: ChatThread, strings: Strings): String =
    if (thread.peerRole == ChatPeerRole.PARENT) {
        thread.parentName?.takeIf { it.isNotBlank() } ?: thread.childName.takeIf { it.isNotBlank() } ?: thread.teacherName
    } else {
        if (thread.isAdminCounterpart) strings.schoolAdministration else thread.teacherName
    }

fun staffRowLabel(thread: ChatThread, staffStrings: StaffStrings, strings: Strings): String =
    if (thread.peerRole == ChatPeerRole.PARENT) {
        listOfNotNull(
            staffStrings.parent,
            thread.childName.takeIf { it.isNotBlank() },
            thread.className?.takeIf { it.isNotBlank() },
        ).joinToString(" · ")
    } else if (thread.isAdminCounterpart) {
        // The platform admin is not a department manager: `staffRole` is `MANAGERIAL` on every thread she holds,
        // whoever is on the other end, so the row says who she is and adds no role word under it.
        ""
    } else {
        staffLabel(thread.staffRole, thread.subject, thread.className, strings)
    }

/**
 * Feature 13: the other end is the **platform admin** — `withAdmin` on a row that carries it, and `peerRole` on a
 * staff row, where the admin is the colleague rather than the staff side of a parent's thread.
 */
internal val ChatThread.isAdminCounterpart: Boolean
    get() = withAdmin == true || peerRole == ChatPeerRole.ADMIN
