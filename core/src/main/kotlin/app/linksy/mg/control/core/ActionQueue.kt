package app.linksy.mg.control.core

/**
 * The backlog of [Action]s waiting to be dispatched one at a time. Pure logic, main thread only.
 *
 * - Every entry carries the session generation it was produced in; [beginSession] and
 *   [endSession] bump it, and an ordinary entry from an older generation is never dispatched.
 * - A "release" is a terminal Drag (`continuing == false`) that closes a stroke which was actually
 *   dispatched and is still open (`dispatchedEnd != null`) when the session ends or is replaced. Only
 *   those survive END/BEGIN, so a held finger is lifted; they are dropped if the stroke was lost. Any
 *   other terminal drag is an ordinary action: capped and generation-scoped.
 * - At most [maxQueue] actions wait; more are dropped (all kinds). A queued move (a continuing Drag)
 *   directly followed by another is coalesced into one (start of the first, end of the latest), so
 *   a mouse flood cannot fill the cap and starve button, key, text or END actions, which are never merged.
 * - Android requires a continuing stroke segment to start exactly where the previous dispatched one
 *   ended. If segments were dropped or cleared, the next one is prefixed with the last dispatched
 *   endpoint so the stroke stays continuous.
 */
class ActionQueue(private val maxQueue: Int = MAX_QUEUE) {
    companion object {
        const val MAX_QUEUE = 32
    }

    private data class Entry(val action: Action, val generation: Long, val release: Boolean = false)

    private val items = ArrayDeque<Entry>()
    private var dispatchedEnd: Point? = null

    var generation = 0L
        private set

    val size: Int get() = items.size

    private fun isMove(a: Action) = a is Action.Drag && a.continuing

    private fun isTerminalDrag(a: Action) = a is Action.Drag && !a.continuing

    /** Adds [actions] produced in [gen]. Stale actions and ones over the cap are dropped. */
    fun enqueue(actions: List<Action>, gen: Long = generation) {
        if (gen != generation) return
        for (a in actions) {
            val last = items.lastOrNull()
            if (a is Action.Drag && isMove(a) && last != null && !last.release && last.generation == gen &&
                last.action is Action.Drag && isMove(last.action)
            ) {
                items[items.lastIndex] = last.copy(
                    action = a.copy(path = listOf(last.action.path.first(), a.path.last())),
                )
            } else if (items.size < maxQueue) {
                items.addLast(Entry(a, gen))
            }
        }
    }

    /** A new session starts: ordinary leftovers go, the release of a held finger stays. */
    fun beginSession() {
        generation++
        keepReleases()
    }

    /** The session is over: drop ordinary actions, keep a held finger's release, then queue [releases]. */
    fun endSession(releases: List<Action>) {
        generation++
        keepReleases()
        for (a in releases) items.addLast(Entry(a, generation, release = true))
    }

    private fun keepReleases() {
        val open = dispatchedEnd != null
        val kept = items.filter { it.release || (open && isTerminalDrag(it.action)) }.map { it.copy(release = true) }
        items.clear()
        items.addAll(kept)
    }

    /** The next action to dispatch, or null. */
    fun next(): Action? {
        while (true) {
            val e = items.removeFirstOrNull() ?: return null
            val a = e.action
            if (e.release) {
                if (dispatchedEnd == null) continue // nothing is held any more
            } else if (e.generation != generation) continue
            return if (a is Action.Drag) bridge(a) else a
        }
    }

    /** The platform cancelled the stroke; the next drag segment starts a fresh one. */
    fun strokeLost() {
        dispatchedEnd = null
    }

    private fun bridge(a: Action.Drag): Action.Drag {
        val prev = dispatchedEnd
        val fixed = if (prev != null && a.path.firstOrNull() != prev) a.copy(path = listOf(prev) + a.path) else a
        dispatchedEnd = if (a.continuing) a.path.lastOrNull() else null
        return fixed
    }
}
