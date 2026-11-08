package app.linksy.mg.control.core

enum class Admission { ADMIT, DROP, BUSY, WRONG_PACKAGE, OWNER_OVER_BUDGET }

class BridgeIngress(
    private val session: BridgeSession,
    private val limiter: UidRateLimiter,
    private val beginCost: Double = 20.0,
    private val ownerLimiter: UidRateLimiter = UidRateLimiter({ 0L }),
    private val isLinksyUid: (Int) -> Boolean = { true },
) {
    fun admit(sender: Int, isBegin: Boolean, isEnd: Boolean = false): Admission =
        if (!isBegin && session.isOwner(sender) || limiter.allow(sender, if (isBegin) beginCost else 1.0)) Admission.ADMIT
        else Admission.DROP
}
