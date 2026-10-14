package quest.feature.chat.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.isoDayNumber
import quest.api.dto.OfficeHoursStatus
import quest.core.text.ltr

/**
 * Feature 19: the two lines the parent reads at the top of a conversation when the staff member is outside her
 * office hours — when she will reply, and how to reach the school if it cannot wait.
 *
 * Pure, and pure on purpose: **the phone's clock is never asked.** "Later today", "tomorrow" and a weekday are all
 * worked out from [OfficeHoursStatus.today], the school's own date, against [OfficeHoursStatus.nextStart], the next
 * working period in the school's zone. A phone an hour ahead, in another time zone or simply wrong therefore cannot
 * make the banner say the wrong day.
 */
data class OfficeHoursBanner(
    /** "Ms Sara is outside office hours now and will reply in the next working period (tomorrow 7:00 AM)". */
    val outside: String,
    /** "For urgent matters: call the school +966…", or null when the Admin has recorded no number. */
    val urgent: String?,
)

/**
 * The banner for [status], or **null when there is nothing to say**: she is inside her hours, the server sent no
 * status at all (the flag is off, or this is not a parent's row), or it named no next start, in which case the app
 * says nothing rather than guessing when she will be back.
 *
 * [staffName] is used exactly as it came — "أ. سارة", "Ms Sara" — because the server already words it the way the
 * school does.
 */
fun officeHoursBanner(staffName: String, status: OfficeHoursStatus?, words: OfficeHoursWords): OfficeHoursBanner? {
    if (status == null || status.open) return null
    val next = status.nextStart?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() } ?: return null
    val today = runCatching { LocalDate.parse(status.today) }.getOrNull() ?: return null
    val time = words.clock(next)
    val away = next.date.toEpochDays() - today.toEpochDays()
    val whenBack = when {
        away <= 0 -> words.laterToday.replace("{time}", time)
        away == 1 -> words.tomorrow.replace("{time}", time)
        else -> words.onWeekday.replace("{day}", words.weekdays[next.date.dayOfWeek.isoDayNumber - 1]).replace("{time}", time)
    }
    return OfficeHoursBanner(
        outside = words.outside.replace("{name}", staffName).replace("{when}", whenBack),
        // A phone number is read left to right whatever the screen does, `+966…` and not `…966+`.
        urgent = status.schoolPhone?.takeIf { it.isNotBlank() }?.let { words.urgent.replace("{phone}", ltr(it)) },
    )
}

/**
 * The banner's words in one language. It lives beside the banner rather than on the parent's `Strings` because
 * [officeHoursBanner] is domain and has no business importing a presentation table; `Strings.officeHours` hands this
 * one over, so there is still a single place a translator looks.
 *
 * [weekdays] is Monday-first, the order `DayOfWeek.isoDayNumber` counts in, and holds the **full** names — a banner
 * that says "الأحد" cannot say "ح".
 */
data class OfficeHoursWords(
    val weekdays: List<String>,
    val am: String,
    val pm: String,
    val outside: String,
    val laterToday: String,
    val tomorrow: String,
    val onWeekday: String,
    val urgent: String,
) {
    /** A 12-hour clock with no leading zero on the hour: "7:00 AM", "1:05 م", and midnight as "12:00". */
    fun clock(at: LocalDateTime): String {
        val hour = when (at.hour % 12) { 0 -> 12; else -> at.hour % 12 }
        val minute = if (at.minute < 10) "0${at.minute}" else "${at.minute}"
        return "$hour:$minute ${if (at.hour < 12) am else pm}"
    }

    companion object {
        val en = OfficeHoursWords(
            weekdays = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"),
            am = "AM", pm = "PM",
            outside = "{name} is outside office hours now and will reply in the next working period ({when})",
            laterToday = "today {time}",
            tomorrow = "tomorrow {time}",
            onWeekday = "{day} {time}",
            urgent = "For urgent matters: call the school {phone}",
        )

        val ar = OfficeHoursWords(
            weekdays = listOf("الاثنين", "الثلاثاء", "الأربعاء", "الخميس", "الجمعة", "السبت", "الأحد"),
            am = "ص", pm = "م",
            outside = "{name} خارج ساعات الدوام الآن، وسترد في الدوام القادم ({when})",
            laterToday = "اليوم {time}",
            tomorrow = "غدًا {time}",
            onWeekday = "{day} {time}",
            urgent = "للأمور العاجلة: اتصل بالمدرسة {phone}",
        )

        fun forLanguage(code: String) = if (code == "ar") ar else en
    }
}
