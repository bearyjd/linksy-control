package app.linksy.mg.control.core

/**
 * Token buckets that refuse a flooding client before any costly work (package and certificate
 * lookups). The clock is injected (milliseconds, monotonic).
 *
 * Only uids for which [isPrivileged] is true (the pinned Linksy uid and the session owner) get a
 * bucket of their own, and those are never evicted by anyone else's traffic. Every other uid shares
 * ONE global bucket, so many colluding uids can neither starve a privileged uid nor grow memory.
 */
class UidRateLimiter(
    private val clock: () -> Long,
    private val capacity: Double = 200.0,
    private val refillPerSec: Double = 200.0,
    private val isPrivileged: (Int) -> Boolean = { false },
) {
    companion object {
        const val MAX_UIDS = 32
    }

    private class Bucket(var tokens: Double, var lastMs: Long)

    private val global = Bucket(capacity, clock())
    private val own = LinkedHashMap<Int, Bucket>()

    val trackedUids: Int get() = own.size

    @Synchronized fun allow(uid: Int, cost: Double = 1.0): Boolean {
        val now = clock()
        val b = if (isPrivileged(uid)) ownBucket(uid, now) else global
        val elapsed = (now - b.lastMs).coerceAtLeast(0L) // a backwards clock mints nothing
        b.tokens = minOf(capacity, b.tokens + elapsed * refillPerSec / 1000.0)
        b.lastMs = maxOf(b.lastMs, now)
        if (b.tokens < cost) return false
        b.tokens -= cost
        return true
    }

    /** Privileged uids are few; a former one that is no longer privileged ages out first. */
    private fun ownBucket(uid: Int, now: Long): Bucket {
        val b = own.remove(uid) ?: Bucket(capacity, now)
        own[uid] = b // most recently used goes last
        if (own.size > MAX_UIDS) {
            val stale = own.keys.firstOrNull { it != uid && !isPrivileged(it) } ?: own.keys.first { it != uid }
            own.remove(stale)
        }
        return b
    }
}
