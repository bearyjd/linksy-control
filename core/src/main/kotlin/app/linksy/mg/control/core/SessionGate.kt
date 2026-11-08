package app.linksy.mg.control.core

/**
 * Input is applied only while a Linksy control session is active. A session ends by [end], after
 * [idleTimeoutMs] without input, or after [maxDurationMs] in total. The clock is injected.
 */
class SessionGate(
    private val clock: () -> Long,
    private val maxDurationMs: Long = 30 * 60_000L,
    private val idleTimeoutMs: Long = 60_000L,
) {
    private var active = false
    private var startMs = 0L
    private var lastMs = 0L

    @Synchronized fun begin() {
        val now = clock()
        active = true
        startMs = now
        lastMs = now
    }

    /** Returns true if a live session was ended. */
    @Synchronized fun end(): Boolean {
        val live = withinLimits()
        active = false
        return live
    }

    @Synchronized fun isActive(): Boolean = withinLimits()

    /** Counts this input as activity. False = no live session, so drop the input. */
    @Synchronized fun touch(): Boolean {
        if (!withinLimits()) return false
        lastMs = clock()
        return true
    }

    /** True exactly once when a session has timed out; the caller then releases held input. */
    @Synchronized fun expireIfDue(): Boolean {
        if (!active || withinLimits()) return false
        active = false
        return true
    }

    private fun withinLimits(): Boolean {
        if (!active) return false
        val now = clock()
        // A clock that went backwards cannot prove the session is still fresh: treat as over.
        if (now < startMs || now < lastMs) return false
        return now - startMs < maxDurationMs && now - lastMs < idleTimeoutMs
    }
}
