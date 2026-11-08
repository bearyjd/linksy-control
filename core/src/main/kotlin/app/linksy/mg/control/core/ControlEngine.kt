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
    @Synchronized fun begin() {
        mapper.reset()
        gate.begin()
    }

    /** Ends the session; returns actions that release any gesture still held. */
    @Synchronized fun end(display: Display): List<Action> {
        gate.end()
        return mapper.release(display, clock())
    }

    /** Call periodically while a session is live: closes it on timeout and releases held input. */
    @Synchronized fun tick(display: Display): List<Action> =
        if (gate.expireIfDue()) mapper.release(display, clock()) else emptyList()

    @Synchronized fun inject(json: String, display: Display): MapResult {
        val released = tick(display)
        if (!gate.touch()) return MapResult(released)
        return mapper.handle(json, display, clock())
    }
}
