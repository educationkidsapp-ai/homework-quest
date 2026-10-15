package quest.feature.complaints

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import quest.api.dto.ChatThreadStatus
import quest.api.dto.Complaint
import quest.api.dto.ComplaintCategory
import quest.api.dto.ComplaintDetail
import quest.api.dto.ComplaintUrgency
import quest.core.json.AppJson
import quest.feature.complaints.presentation.expectedReplyLine
import quest.feature.parent.presentation.Strings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Feature 27: the one line the parent is told about timing. The formatting is pure, so the clock and the zone are
 * handed in rather than asked of the machine the test runs on.
 */
class ExpectedReplyTest {
    private val riyadh = TimeZone.of("Asia/Riyadh")
    private val tokyo = TimeZone.of("Asia/Tokyo")

    /** 2026-10-15 is a Thursday; the parent is reading at half past nine in the morning. */
    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int, zone: TimeZone = riyadh) =
        LocalDateTime(year, month, day, hour, minute).toInstant(zone).toEpochMilliseconds()

    private val now = at(2026, 10, 15, 9, 30)

    private fun line(expected: Long?, status: ChatThreadStatus = ChatThreadStatus.OPEN, strings: Strings = Strings.en, zone: TimeZone = riyadh) =
        expectedReplyLine(expected, status, strings, now, zone)

    /** The clock is feature 19's, so the banner and this line read alike: "2:00 PM", "2:00 م". */
    @Test fun laterTodayIsSaidAsToday() {
        assertEquals("Expected reply: today by 2:00 PM", line(at(2026, 10, 15, 14, 0)))
        assertEquals("الرد المتوقع: اليوم قبل 2:00 م", line(at(2026, 10, 15, 14, 0), strings = Strings.ar))
    }

    @Test fun theNextMorningIsSaidAsTomorrow() {
        assertEquals("Expected reply: tomorrow by 7:05 AM", line(at(2026, 10, 16, 7, 5)))
        assertEquals("الرد المتوقع: غدًا قبل 7:05 ص", line(at(2026, 10, 16, 7, 5), strings = Strings.ar))
    }

    @Test fun aDayFurtherOutIsNamedByItsWeekday() {
        // 2026-10-19 is the Monday after the weekend: a parent counts days badly, so the app does not make her.
        assertEquals("Expected reply: Monday by 8:00 AM", line(at(2026, 10, 19, 8, 0)))
        assertEquals("الرد المتوقع: الاثنين قبل 8:00 ص", line(at(2026, 10, 19, 8, 0), strings = Strings.ar))
    }

    @Test fun theDayIsTheDevicesOwnDay() {
        // The same instant: half past eleven at night in Riyadh is already the next morning in Tokyo.
        val late = at(2026, 10, 15, 23, 30)
        assertEquals("Expected reply: today by 11:30 PM", line(late))
        assertEquals("Expected reply: tomorrow by 5:30 AM", line(late, zone = tokyo))
    }

    /** A promise that has run out is worse than none: the line goes rather than stand there stale. */
    @Test fun aTimeAlreadyPastIsNotShown() {
        assertNull(line(at(2026, 10, 15, 9, 0)))
        assertNull(line(at(2026, 10, 14, 14, 0)))
        // The minute it is still due, it is still said.
        assertEquals("Expected reply: today by 9:30 AM", line(now))
    }

    @Test fun nothingIsPromisedWhenTheServerSentNoTime() {
        assertNull(line(null))
    }

    @Test fun aResolvedComplaintPromisesNothing_evenWithATime() {
        assertNull(line(at(2026, 10, 15, 14, 0), status = ChatThreadStatus.RESOLVED))
    }

    /** The parent's own routes carry it; a detail decoded without the field simply says nothing. */
    @Test fun theFieldIsOptionalOnTheWire() {
        val withoutIt = AppJson.decodeFromString(ComplaintDetail.serializer(), """{"complaint":$COMPLAINT_JSON}""")
        assertNull(withoutIt.expectedReplyBy)
        val withIt = AppJson.decodeFromString(ComplaintDetail.serializer(), """{"complaint":$COMPLAINT_JSON,"expectedReplyBy":1792000000000}""")
        assertEquals(1_792_000_000_000L, withIt.expectedReplyBy)
    }

    /**
     * The triage is the staff's working note and is never sent to a parent — but the app shares one [Complaint] with
     * the dashboards' contract, so it must decode a row that carries one and a row that does not, and show neither.
     */
    @Test fun aStaffTriageDecodesAndIsIgnored() {
        val plain = AppJson.decodeFromString(Complaint.serializer(), COMPLAINT_JSON)
        assertNull(plain.triage)
        val triaged = AppJson.decodeFromString(
            Complaint.serializer(),
            """{${COMPLAINT_JSON.trim('{', '}')},"triage":{"category":"transport","urgency":"high",
               "summaryAr":"الحافلة تتأخر","summaryEn":"The bus is late","replyAr":"سنراجع","replyEn":"We will check"}}""",
        )
        assertEquals(ComplaintCategory.TRANSPORT, triaged.triage?.category)
        assertEquals(ComplaintUrgency.HIGH, triaged.triage?.urgency)
        // Nothing of it reaches the parent's line, which is arithmetic on office hours alone.
        assertNull(expectedReplyLine(null, triaged.status, Strings.en, now, riyadh))
    }

    /** A field the app has never heard of must not break a parent's complaint either. */
    @Test fun anUnknownFieldIsIgnored() {
        val decoded = AppJson.decodeFromString(Complaint.serializer(), """{${COMPLAINT_JSON.trim('{', '}')},"somethingNew":7}""")
        assertEquals("cp-1", decoded.id)
    }

    private companion object {
        const val COMPLAINT_JSON = """{"id":"cp-1","childId":"ch-1","childName":"Maya","title":"The bus is late",
            "status":"open","recipientId":"mg-nour","recipientName":"Ms. Nour","recipientRole":"MANAGERIAL",
            "createdAt":1791000000000}"""
    }
}
