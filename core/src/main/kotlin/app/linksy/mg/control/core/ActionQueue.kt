package app.linksy.mg.control.core

/**
 * The backlog of [Action]s waiting to be dispatched one at a time. Pure logic, main thread only.
 *
 * - Every entry carries the session generation it was produced in; [beginSession] and
 *   [endSession] bump it, and an ordinary entry from an older generation is never dispatched.
 * - A "release" is a Drag with `continuing == false`: it lifts a finger that is still down. Releases
 *   are never dropped, by the cap, by END, or by a replacing BEGIN, or the pointer would stay down.
 * - At most [maxQueue] ordinary actions wait; more are dropped (all kinds, not only mid-drag).
 * - Android requires a continuing stroke segment to start exactly where the previous dispatched one
 *   ended. If segments were dropped or cleared, the next one is prefixed with the last dispatched
 *   endpoint so the stroke stays continuous.
 */
class ActionQueue(private val maxQueue: Int = MAX_QUEUE) {
    companion object {
        const val MAX_QUEUE = 32
    }

    private class Entry(val action: Action, val generation: Long)

    private val items = ArrayDeque<Entry>()
    private var dispatchedEnd: Point? = null

    var generation = 0L
        private set

    val size: Int get() = items.size

    private fun isRelease(a: Action) = a is Action.Drag && !a.continuing

    /** Adds [actions] produced in [gen]. Stale ordinary actions and ones over the cap are dropped. */
    fun enqueue(actions: List<Action>, gen: Long = generation) {
        for (a in actions) {
            if (isRelease(a)) {
                items.addLast(Entry(a, gen))
            } else if (gen == generation && items.size < maxQueue) {
                items.addLast(Entry(a, gen))
            }
        }
    }

    /** A new session starts: ordinary leftovers go, pending releases stay. */
    fun beginSession() {
        generation++
        items.removeAll { !isRelease(it.action) }
    }

    /** The session is over: drop ordinary actions, keep earlier releases, then queue [releases]. */
    fun endSession(releases: List<Action>) {
        generation++
        items.removeAll { !isRelease(it.action) }
        for (a in releases) items.addLast(Entry(a, generation))
    }

    /** The next action to dispatch, or null. */
    fun next(): Action? {
        while (true) {
            val e = items.removeFirstOrNull() ?: return null
            val a = e.action
            if (!isRelease(a) && e.generation != generation) continue
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
