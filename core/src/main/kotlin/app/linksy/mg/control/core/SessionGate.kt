package app.linksy.mg.control.core

class SessionGate(
    private val clock: () -> Long,
    private val maxDurationMs: Long = 30 * 60_000L,
    private val idleTimeoutMs: Long = 60_000L,
) {
    fun begin() {}
    fun end(): Boolean = false
    fun isActive(): Boolean = true
    fun touch(): Boolean = true
    fun expireIfDue(): Boolean = false
}
