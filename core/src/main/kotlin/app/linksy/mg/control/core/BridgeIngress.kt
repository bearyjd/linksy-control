package app.linksy.mg.control.core

/**
 * Decides which incoming bridge messages are metered. Only pre-auth traffic is rate limited: BEGIN
 * (which triggers package and certificate lookups) and anything from a uid that does not own the
 * open session. The owner's own messages are never metered here, because dropping a button-up or a
 * key would corrupt input; their flood is bounded by the [ActionQueue] cap, coalescing and the
 * idle/max session gates.
 */
class BridgeIngress(
    private val session: BridgeSession,
    private val limiter: UidRateLimiter,
    private val beginCost: Double = 20.0,
) {
    fun admit(sender: Int, isBegin: Boolean): Boolean =
        if (!isBegin && session.isOwner(sender)) true else limiter.allow(sender, if (isBegin) beginCost else 1.0)
}
