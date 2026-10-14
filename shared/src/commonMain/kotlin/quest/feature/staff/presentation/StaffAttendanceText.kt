package quest.feature.staff.presentation

import quest.api.dto.AttendanceLink
import quest.feature.push.domain.isAttendance

/**
 * Feature 16: an attendance row of the bell, worded in her language from its link ([AttendanceLink]) — the server's
 * own title and body are the English fallback, and a reminder, an alert or the digest reads the same in Arabic as the
 * push did. Null for any other row, which keeps the server's words. Pure, so every type is tested without a screen.
 */
fun attendanceText(kind: String, link: String?, s: StaffStrings): Pair<String, String>? {
    if (!isAttendance(kind)) return null
    val p = AttendanceLink.params(link)
    val name = p[AttendanceLink.NAME].orEmpty()
    val minutes = p[AttendanceLink.MINUTES].orEmpty()
    fun alert(template: String) = template.replace("{name}", name).replace("{n}", minutes)
    return when (p[AttendanceLink.TYPE]) {
        AttendanceLink.START_REMINDER -> s.attTitle to s.attStartReminder
        AttendanceLink.END_REMINDER -> s.attTitle to s.attEndReminder
        AttendanceLink.MISSING_CHECKOUT_REMINDER -> s.attTitle to s.attMissingReminder
        AttendanceLink.ALERT_LATE -> s.attTitle to alert(s.attLate)
        AttendanceLink.ALERT_NOT_ARRIVED -> s.attTitle to alert(s.attNotArrived)
        AttendanceLink.ALERT_EARLY_LEAVE -> s.attTitle to alert(s.attEarlyLeave)
        AttendanceLink.ALERT_MISSING_CHECKOUT -> s.attTitle to alert(s.attMissingCheckout)
        AttendanceLink.DIGEST -> {
            val counts = s.attDigest.replace("{late}", p[AttendanceLink.LATE] ?: "0")
                .replace("{notArrived}", p[AttendanceLink.NOT_ARRIVED] ?: "0").replace("{remote}", p[AttendanceLink.REMOTE] ?: "0")
            fun names(key: String, template: String) = p[key]?.takeIf { it.isNotBlank() }
                ?.let { template.replace("{names}", it.split(AttendanceLink.NAME_SEPARATOR).joinToString(s.attNameSeparator)) }
            s.attDigestTitle to listOfNotNull(counts, names(AttendanceLink.LATE_NAMES, s.attDigestLate),
                names(AttendanceLink.NOT_ARRIVED_NAMES, s.attDigestNotArrived)).joinToString("\n")
        }
        else -> null
    }
}
