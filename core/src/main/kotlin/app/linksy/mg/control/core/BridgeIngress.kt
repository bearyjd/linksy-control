package app.linksy.mg.control.core

/** What to do with an incoming bridge message. */
enum class Admission {
    ADMIT,

    /** Ignore silently (non-owner traffic over its budget). */
    DROP,

    /** A BEGIN that was rate limited: answer with a cheap BUSY reply. */
    BUSY,

    /** A BEGIN from a uid that is not the pinned package's: answer WRONG_PACKAGE, no lookups. */
    WRONG_PACKAGE,

    /** The owner exceeded its generous budget: end the session (releases still run). */
    OWNER_OVER_BUDGET,
}

/**
 * Decides which incoming bridge messages are admitted, before any costly work.
 *
 * - BEGIN from a uid that is not the pinned package's gets WRONG_PACKAGE without package or
 *   certificate lookups, metered by the shared global bucket (BUSY once that is empty).
 *   BEGIN from the pinned uid costs [beginCost] from its own bucket (BUSY when empty).
 * - The owner's messages draw on [ownerLimiter], a generous budget. Over it, the session ends
 *   ([Admission.OWNER_OVER_BUDGET]) instead of single messages being dropped, so input is never
 *   thinned silently and the release still runs. END is never metered.
 * - Other traffic is metered by [limiter] (global bucket for ordinary uids) and dropped when empty.
 */
class BridgeIngress(
    private val session: BridgeSession,
    private val limiter: UidRateLimiter,
    private val beginCost: Double = 20.0,
    private val ownerLimiter: UidRateLimiter,
    private val isLinksyUid: (Int) -> Boolean = { true },
) {
    companion object {
        /** Owner budget: well above a 1000 Hz mouse plus 200 keys/s; beyond it the session ends. */
        const val OWNER_BURST = 3000.0
        const val OWNER_PER_SEC = 1500.0
    }

    fun admit(sender: Int, isBegin: Boolean, isEnd: Boolean = false): Admission = when {
        isBegin && !isLinksyUid(sender) ->
            if (limiter.allow(sender)) Admission.WRONG_PACKAGE else Admission.BUSY
        isBegin -> if (limiter.allow(sender, beginCost)) Admission.ADMIT else Admission.BUSY
        session.isOwner(sender) -> when {
            isEnd || ownerLimiter.allow(sender) -> Admission.ADMIT
            else -> Admission.OWNER_OVER_BUDGET
        }
        else -> if (limiter.allow(sender)) Admission.ADMIT else Admission.DROP
    }
}
