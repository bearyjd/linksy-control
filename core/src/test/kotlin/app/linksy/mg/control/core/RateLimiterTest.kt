package app.linksy.mg.control.core

import kotlin.test.Test
import kotlin.test.assertEquals
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

    @Test fun unprivilegedUidsShareOneGlobalBucket() {
        repeat(10) { assertTrue(rl.allow(1)) }
        assertFalse(rl.allow(2)) // a different uid cannot mint its own tokens
    }

    @Test fun privilegedUidsEachGetTheirOwnBucket() {
        val priv = UidRateLimiter({ now }, capacity = 10.0, refillPerSec = 10.0, isPrivileged = { it == 7 || it == 8 })
        repeat(10) { assertTrue(priv.allow(7)) }
        assertFalse(priv.allow(7))
        assertTrue(priv.allow(8))
        assertTrue(priv.allow(99)) // global bucket untouched by the privileged uids
    }

    @Test fun costlyMessagesDrainFaster() {
        assertTrue(rl.allow(1, cost = 8.0))
        assertFalse(rl.allow(1, cost = 8.0))
    }

    @Test fun manyUidsDoNotGrowWithoutBound() {
        repeat(10_000) { rl.allow(it) }
        assertTrue(rl.trackedUids <= UidRateLimiter.MAX_UIDS)
        val priv = UidRateLimiter({ now }, capacity = 10.0, refillPerSec = 10.0, isPrivileged = { true })
        repeat(10_000) { priv.allow(it) }
        assertTrue(priv.trackedUids <= UidRateLimiter.MAX_UIDS)
    }

    @Test fun backwardsClockDoesNotMintTokens() {
        now = 10_000
        repeat(10) { rl.allow(1) }
        now = 0
        assertFalse(rl.allow(1))
        now = 10_000 // forward again: only the time since the high-water mark counts
        assertFalse(rl.allow(1))
    }

    @Test fun cyclingManyAttackerUidsCannotStarveTheLinksyUid() {
        val linksy = 10_001
        val l = UidRateLimiter({ now }, capacity = 200.0, refillPerSec = 200.0, isPrivileged = { it == linksy })
        var linksyAdmitted = 0
        repeat(101) { round ->
            now += 100
            for (uid in 20_000 until 20_000 + UidRateLimiter.MAX_UIDS + 1) l.allow(uid, cost = 20.0)
            if (l.allow(linksy, cost = 20.0)) linksyAdmitted++
        }
        assertEquals(101, linksyAdmitted) // every BEGIN of the real Linksy is still admitted
    }

    @Test fun attackerUidsTogetherAreBoundedByTheGlobalBucket() {
        var allowed = 0
        repeat(5) { for (uid in 0 until 1000) if (rl.allow(uid)) allowed++ }
        assertEquals(10, allowed)
    }

    @Test fun theOwnersBucketIsNeverEvictedByChurn() {
        var owner = 1
        val l = UidRateLimiter({ now }, capacity = 10.0, refillPerSec = 10.0, isPrivileged = { it == owner })
        repeat(10) { assertTrue(l.allow(owner)) }
        for (uid in 100 until 200) l.allow(uid)
        assertFalse(l.allow(owner)) // same (empty) bucket, not a refreshed one
        now += 1000
        assertTrue(l.allow(owner))
    }
}
