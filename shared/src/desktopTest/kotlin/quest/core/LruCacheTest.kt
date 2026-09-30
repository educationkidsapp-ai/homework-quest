package quest.core

import quest.api.dto.BroadcastAttachment
import quest.feature.broadcasts.presentation.CARD_MAX_PX
import quest.feature.broadcasts.presentation.FULL_SIZE
import quest.feature.broadcasts.presentation.MAX_DECODED
import quest.feature.broadcasts.presentation.decodeKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * MH3: the bound on decoded weekly-plan images. The app's older picture caches grow for the life of the process, which
 * is fair for a small authored asset and wrong for a manager's 5 MB photograph — a parent working back through her
 * archive would keep every week she opened.
 */
class LruCacheTest {

    @Test fun theLeastRecentlyUsedEntryIsTheOneDropped() {
        val cache = LruCache<String, Int>(3)
        cache["a"] = 1; cache["b"] = 2; cache["c"] = 3
        assertEquals(listOf("a", "b", "c"), cache.keys)

        cache["d"] = 4
        assertEquals(3, cache.size)
        assertNull(cache["a"], "the oldest is gone")
        assertEquals(listOf("b", "c", "d"), cache.keys)
    }

    /** A read is a use: the pinned plan stays while a parent opens one earlier week after another. */
    @Test fun readingAnEntryKeepsIt() {
        val cache = LruCache<String, Int>(3)
        cache["pinned"] = 1; cache["week-1"] = 2; cache["week-2"] = 3
        assertEquals(1, cache["pinned"])

        cache["week-3"] = 4
        assertEquals(1, cache["pinned"], "read most recently, so not the one evicted")
        assertNull(cache["week-1"])
        // `pinned` was read again on the line above, so it is the newest of the three by the time the order is checked.
        assertEquals(listOf("week-2", "week-3", "pinned"), cache.keys)
    }

    @Test fun writingTheSameKeyTwiceDoesNotGrowTheCache() {
        val cache = LruCache<String, Int>(2)
        cache["a"] = 1; cache["a"] = 2
        assertEquals(1, cache.size)
        assertEquals(2, cache["a"])
    }

    @Test fun clearEmptiesIt() {
        val cache = LruCache<String, Int>(2).also { it["a"] = 1 }
        cache.clear()
        assertEquals(0, cache.size)
        assertNull(cache["a"])
    }

    @Test fun aCacheThatCouldHoldNothingIsRefused() {
        assertFailsWith<IllegalArgumentException> { LruCache<String, Int>(0) }
    }

    // ---- what the attachment cache keys on

    /**
     * The card and the viewer hold the *same* image at two sizes, so they must not share a key — otherwise the card's
     * downsampled copy would be shown full screen, or the viewer's full-size one drawn into a 420 dp card.
     */
    @Test fun theCardAndTheViewerAreSeparateEntries() {
        val attachment = BroadcastAttachment("/media/attachments/a1", "plan.png", "a1", "image/png")
        assertTrue(decodeKey(attachment, CARD_MAX_PX) != decodeKey(attachment, FULL_SIZE))
        assertEquals("a1@$CARD_MAX_PX", decodeKey(attachment, CARD_MAX_PX))

        // A row with no id (written before MH1) still keys on something stable.
        assertEquals("/x/plan.png@$CARD_MAX_PX", decodeKey(BroadcastAttachment("/x/plan.png"), CARD_MAX_PX))
    }

    /** Three: the pinned plan, the one earlier week she has open, and the viewer's full-size copy of one of them. */
    @Test fun threeEntriesCoverThePinnedPlanTheOpenWeekAndTheViewer() {
        assertEquals(3, MAX_DECODED)
    }
}
