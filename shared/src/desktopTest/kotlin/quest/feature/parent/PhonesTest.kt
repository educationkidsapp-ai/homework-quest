package quest.feature.parent

import quest.feature.parent.domain.Phones
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * MH3: the client's copy of the server's telephone rule (`quest.server.platform.Phones`). The two have to agree
 * exactly — a field that accepted what the server refuses would turn a typo into a 400, and one that refused what the
 * server accepts would lock a parent out of a number that is fine.
 */
class PhonesTest {

    @Test fun separatorsAreDroppedAndALeadingPlusIsKept() {
        assertEquals("+971501234567", Phones.normalise("+971 50 123 4567"))
        assertEquals("+971501234567", Phones.normalise("+971-50-123-4567"))
        assertEquals("+201002030405", Phones.normalise("+20 (100) 203.04.05"))
        assertEquals("0501234567", Phones.normalise("050 123 4567"))
        assertEquals("+971501234567", Phones.normalise("  +971501234567  "))
    }

    /** A leading `00` is a country code written the other way round, and the server stores it as a `+`. */
    @Test fun aLeadingDoubleZeroBecomesAPlus() {
        assertEquals("+971501234567", Phones.normalise("00971501234567"))
        assertEquals("+971501234567", Phones.normalise("00 971 50 123 4567"))
    }

    /** A blank field clears the number: it is an empty string on the wire, not a refusal and not a literal "". */
    @Test fun blankClearsTheNumber() {
        assertEquals("", Phones.normalise(""))
        assertEquals("", Phones.normalise("   "))
        assertTrue(Phones.isValid(""))
    }

    @Test fun tooShortOrTooLongIsRefused() {
        assertNull(Phones.normalise("123456"))
        assertEquals("1234567", Phones.normalise("1234567"))
        assertEquals("+123456789012345", Phones.normalise("+123456789012345"))
        assertNull(Phones.normalise("+1234567890123456"))
    }

    @Test fun aPlusAnywhereButTheFrontIsRefusedAndSoIsAnyOtherCharacter() {
        assertNull(Phones.normalise("050+1002030"))
        assertNull(Phones.normalise("050 123 456x"))
        assertNull(Phones.normalise("ext 4567890"))
        assertFalse(Phones.isValid("+971 50 1"))
        assertTrue(Phones.isValid("+971501234567"))
    }
}
