package app.linksy.mg.control.core

/**
 * Whether a sender uid is the pinned package's uid. [resolve] asks the system for that uid and is
 * called at most once per [refreshMs], so attacker traffic cannot turn into a lookup per message.
 * A changed uid (reinstall) is picked up at the next refresh.
 */
class PinnedUid(
    private val resolve: () -> Int?,
    private val clock: () -> Long,
    private val refreshMs: Long = 10_000L,
) {
    private var uid: Int? = null
    private var resolvedAt: Long? = null

    @Synchronized fun matches(sender: Int): Boolean {
        val now = clock()
        val at = resolvedAt
        if (at == null || now - at >= refreshMs) {
            uid = resolve()
            resolvedAt = now
        }
        return sender >= 0 && sender == uid
    }
}
