package app.linksy.mg.control.core

/**
 * Whether a sender uid is the pinned package's uid. [resolve] asks the system for that uid and is
 * called at most once per [refreshMs], so attacker traffic cannot turn into a lookup per message.
 * A sender that does not match re-resolves once if the cache is older than [recheckMs], so a fresh
 * install or reinstall of the pinned package is picked up within about a second of its first BEGIN
 * (at most one extra lookup per [recheckMs], whatever the traffic).
 */
class PinnedUid(
    private val resolve: () -> Int?,
    private val clock: () -> Long,
    private val refreshMs: Long = 10_000L,
    private val recheckMs: Long = 1_000L,
) {
    private var uid: Int? = null
    private var resolvedAt: Long? = null

    @Synchronized fun matches(sender: Int): Boolean {
        val now = clock()
        var at = resolvedAt
        if (at == null || now - at >= refreshMs) {
            uid = resolve()
            at = now
            resolvedAt = now
        }
        val hit = sender >= 0 && sender == uid
        if (hit || now - at < recheckMs) return hit
        uid = resolve()
        resolvedAt = now
        return sender >= 0 && sender == uid
    }
}
