package app.linksy.mg.control.core

/**
 * A token bucket per sender uid, so a flooding client is refused before any costly work (package
 * and certificate lookups). The clock is injected (milliseconds, monotonic). The set of tracked
 * uids is bounded.
 */
class UidRateLimiter(
    private val clock: () -> Long,
    private val capacity: Double = 200.0,
    private val refillPerSec: Double = 200.0,
) {
    companion object {
        const val MAX_UIDS = 32
    }

    private class Bucket(var tokens: Double, var lastMs: Long)

    private val buckets = LinkedHashMap<Int, Bucket>()

    val trackedUids: Int get() = buckets.size

    @Synchronized fun allow(uid: Int, cost: Double = 1.0): Boolean {
        val now = clock()
        val b = buckets.remove(uid) ?: Bucket(capacity, now)
        buckets[uid] = b // most recently used goes last
        if (buckets.size > MAX_UIDS) buckets.remove(buckets.keys.first())
        val elapsed = (now - b.lastMs).coerceAtLeast(0L) // a backwards clock mints nothing
        b.tokens = minOf(capacity, b.tokens + elapsed * refillPerSec / 1000.0)
        b.lastMs = maxOf(b.lastMs, now)
        if (b.tokens < cost) return false
        b.tokens -= cost
        return true
    }
}
