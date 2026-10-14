package quest.feature.staff.presentation

import quest.feature.push.domain.CHAT_AFTER_HOURS_SUMMARY
import quest.feature.push.domain.afterHoursCount

/**
 * Feature 19: the after-hours summary row of the bell, worded in her language from the count on its link
 * (`/staff/chat/threads?count=3`) — the server's own title and body are the English fallback, exactly as an
 * attendance row's are ([attendanceText]). Null for any other row, and for a summary that carries no count, which
 * keeps the server's words. Pure, so every form is tested without a screen.
 */
fun afterHoursText(kind: String, link: String?, s: StaffStrings): Pair<String, String>? {
    if (kind != CHAT_AFTER_HOURS_SUMMARY) return null
    val count = afterHoursCount(link) ?: return null
    return s.messages to s.afterHoursSummary(count)
}
