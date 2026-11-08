package app.linksy.mg.control.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PinnedUidTest {
    private var now = 0L
    private var uid: Int? = 10_001
    private var lookups = 0
    private val pinned = PinnedUid({ lookups++; uid }, { now }, refreshMs = 10_000)

    @Test fun matchesOnlyThePinnedPackagesUid() {
        assertTrue(pinned.matches(10_001))
        assertFalse(pinned.matches(10_002))
    }

    @Test fun anUnknownPackageMatchesNobody() {
        uid = null
        assertFalse(pinned.matches(10_001))
        assertFalse(pinned.matches(-1))
    }

    @Test fun attackerTrafficDoesNotCauseALookupPerMessage() {
        repeat(1000) { pinned.matches(20_000 + it) }
        assertEquals(1, lookups)
    }

    @Test fun refreshesAfterTheIntervalWhenTheUidChanges() {
        assertTrue(pinned.matches(10_001))
        uid = 10_050 // reinstalled
        assertFalse(pinned.matches(10_050)) // inside the interval: cached
        now += 10_000
        assertTrue(pinned.matches(10_050))
        assertFalse(pinned.matches(10_001))
    }

    @Test fun aBackwardsClockNeverForcesARefresh() {
        now = 50_000
        pinned.matches(1)
        now = 0
        repeat(100) { pinned.matches(2) }
        assertEquals(1, lookups)
    }

    @Test fun aMismatchOlderThanOneSecondReResolvesOnce() {
        assertTrue(pinned.matches(10_001))
        uid = 10_050 // reinstalled
        now += 1_000
        assertTrue(pinned.matches(10_050))
        assertEquals(2, lookups)
    }

    @Test fun mismatchTrafficCausesAtMostOneLookupPerSecond() {
        pinned.matches(1)
        repeat(1000) { pinned.matches(20_000 + it) }
        assertEquals(1, lookups)
        now += 1_000
        repeat(1000) { pinned.matches(30_000 + it) }
        assertEquals(2, lookups)
    }
}
