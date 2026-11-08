package app.linksy.mg.control.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RateLimiterTest {
    private var now = 0L
    private val rl = UidRateLimiter({ now }, capacity = 10.0, refillPerSec = 10.0)

    @Test fun allowsABurstThenRefuses() {
        repeat(10) { assertTrue(rl.allow(1)) }
        assertFalse(rl.allow(1))
    }

    @Test fun refillsOverTimeButNotBeyondCapacity() {
        repeat(10) { rl.allow(1) }
        now += 500
        repeat(5) { assertTrue(rl.allow(1)) }
        assertFalse(rl.allow(1))
        now += 60_000
        repeat(10) { assertTrue(rl.allow(1)) }
        assertFalse(rl.allow(1))
    }

    @Test fun uidsAreIndependent() {
        repeat(10) { rl.allow(1) }
        assertFalse(rl.allow(1))
        assertTrue(rl.allow(2))
    }

    @Test fun costlyMessagesDrainFaster() {
        assertTrue(rl.allow(1, cost = 8.0))
        assertFalse(rl.allow(1, cost = 8.0))
    }

    @Test fun manyUidsDoNotGrowWithoutBound() {
        repeat(10_000) { rl.allow(it) }
        assertTrue(rl.trackedUids <= UidRateLimiter.MAX_UIDS)
    }

    @Test fun backwardsClockDoesNotMintTokens() {
        now = 10_000
        repeat(10) { rl.allow(1) }
        now = 0
        assertFalse(rl.allow(1))
    }
}
