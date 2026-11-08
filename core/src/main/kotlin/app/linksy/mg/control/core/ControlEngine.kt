package app.linksy.mg.control.core

/**
 * Glue-facing entry point: a session gate in front of a gesture mapper. Everything the phone does
 * on behalf of Linksy goes through [inject]; with no live session it does nothing.
 * Call [end] before [begin] when a new session replaces an old one, so a held stroke is closed.
 */
class ControlEngine(
    private val clock: () -> Long,
    private val gate: SessionGate = SessionGate(clock),
    private val mapper: GestureMapper = GestureMapper(),
) {
    private var shown: Display? = null

    @Synchronized fun begin() {
        mapper.reset()
        shown = null
        gate.begin()
    }

    @Synchronized fun isActive(): Boolean = gate.isActive()

    /** Ends the session; returns actions that release any gesture still held. */
    @Synchronized fun end(display: Display): List<Action> {
        gate.end()
        val at = shown ?: display
        shown = null
        return mapper.release(at, clock())
    }

    /** Call periodically while a session is live: closes it on timeout and releases held input. */
    @Synchronized fun tick(display: Display): List<Action> {
        if (gate.expireIfDue()) {
            val at = shown ?: display
            shown = null
            return mapper.release(at, clock())
        }
        return if (gate.isActive()) displayChanged(display) else emptyList()
    }

    /** A held drag cannot survive a display change (rotation): release it in the old coordinates. */
    private fun displayChanged(display: Display): List<Action> {
        if (!display.valid) return emptyList()
        val previous = shown
        shown = display
        return if (previous != null && previous != display) mapper.release(previous, clock()) else emptyList()
    }

    @Synchronized fun inject(json: String, display: Display): MapResult {
        val released = tick(display)
        if (!gate.touch()) return MapResult(released)
        val result = mapper.handle(json, display, clock())
        return if (released.isEmpty()) result else result.copy(actions = released + result.actions)
    }
}
