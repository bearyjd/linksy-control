package app.linksy.mg.control.core

import kotlin.math.hypot

/**
 * The backlog of [Action]s waiting to be dispatched one at a time. Pure logic, main thread only.
 *
 * - Every entry carries the session generation it was produced in; [beginSession] and
 *   [endSession] bump it, and an ordinary entry from an older generation is never dispatched.
 * - A "release" is a terminal Drag (`continuing == false`) that closes a stroke which is open: one
 *   already dispatched (`dispatchedEnd != null`) or one a queued move will open. Only a release
 *   whose stroke was really dispatched survives END/BEGIN and runs; otherwise it is skipped. A
 *   terminal drag without an open stroke is an ordinary action: capped and generation-scoped.
 * - At most [maxQueue] actions wait; more are dropped, with one exception: the release of an open
 *   stroke is never dropped by the cap. If the queue is full, an ordinary entry is evicted to make
 *   room (bounded, since only one stroke can be open), so the finger is not left down.
 * - A queued move (a continuing Drag) directly followed by another is coalesced into one. The merged
 *   path keeps the polyline (corners and reversals) down-sampled to [GestureMapper.MAX_PATH_POINTS]
 *   and the durations add up, capped at [GestureMapper.MAX_SEGMENT_MS]. Button, key, text, END and
 *   terminal drags are never merged.
 * - Android requires a continuing stroke segment to start exactly where the previous dispatched one
 *   ended. If segments were dropped or cleared, the next one is prefixed with the last dispatched
 *   endpoint so the stroke stays continuous.
 * - Dispatching a Tap, LongPress or Swipe replaces the held stroke, so the endpoint is forgotten.
 * - After [endSession] or [beginSession] an open stroke always has a release queued (one is
 *   synthesized at the last endpoint if none is), so no endpoint leaks into the next session.
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
                items[items.lastIndex] = last.copy(action = merge(last.action, a))
            } else if (isTerminalDrag(a) && openStroke()) {
                if (items.size >= maxQueue) evictOrdinary()
                items.addLast(Entry(a, gen, release = true))
            } else if (items.size < maxQueue) {
                items.addLast(Entry(a, gen))
            }
        }
    }

    /**
     * Whether a stroke may be held down by the time the queue reaches the end of it. Deliberately
     * generous (a queued tap does not close it): a release that turns out not to be needed is
     * skipped at dispatch, but a needed one must never be refused room.
     */
    private fun openStroke(): Boolean {
        var open = dispatchedEnd != null
        for (e in items) {
            if (!e.release && e.generation != generation) continue
            val a = e.action
            if (a is Action.Drag) open = a.continuing
        }
        return open
    }

    private fun replacesStroke(a: Action) = a is Action.Tap || a is Action.LongPress || a is Action.Swipe

    /** Makes room for a release: drops the first ordinary entry, preferring one that is not a move. */
    private fun evictOrdinary() {
        val i = items.indexOfFirst { !it.release && !isMove(it.action) }.takeIf { it >= 0 }
            ?: items.indexOfFirst { !it.release }
        if (i >= 0) items.removeAt(i)
    }

    private fun merge(first: Action.Drag, second: Action.Drag): Action.Drag {
        val joined = if (first.path.lastOrNull() == second.path.firstOrNull()) first.path + second.path.drop(1)
        else first.path + second.path
        return Action.Drag(
            path = prune(joined),
            durationMs = (first.durationMs + second.durationMs).coerceAtMost(GestureMapper.MAX_SEGMENT_MS),
            continuing = true,
        )
    }

    /** Drops the points that bend the path least until at most MAX_PATH_POINTS remain; ends are kept. */
    private fun prune(path: List<Point>): List<Point> {
        val pts = path.toMutableList()
        while (pts.size > GestureMapper.MAX_PATH_POINTS) {
            var drop = 1
            var least = Float.MAX_VALUE
            for (i in 1 until pts.lastIndex) {
                val d = deviation(pts[i - 1], pts[i], pts[i + 1])
                if (d < least) {
                    least = d
                    drop = i
                }
            }
            pts.removeAt(drop)
        }
        return pts
    }

    /** Distance of [b] from the segment a-c, so a full reversal along a line still counts as a bend. */
    private fun deviation(a: Point, b: Point, c: Point): Float {
        val dx = c.x - a.x
        val dy = c.y - a.y
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0f) 0f else (((b.x - a.x) * dx + (b.y - a.y) * dy) / len2).coerceIn(0f, 1f)
        return hypot(b.x - (a.x + t * dx), b.y - (a.y + t * dy))
    }

    /** A new session starts: ordinary leftovers go, the release of a held finger stays. */
    fun beginSession() {
        generation++
        keepReleases()
        ensureRelease()
    }

    /** The session is over: drop ordinary actions, keep a held finger's release, then queue [releases]. */
    fun endSession(releases: List<Action>) {
        generation++
        keepReleases()
        for (a in releases) items.addLast(Entry(a, generation, release = true))
        ensureRelease()
    }

    private fun keepReleases() {
        val kept = items.filter { it.release }
        items.clear()
        items.addAll(kept)
    }

    /** An open stroke with no release queued gets a stationary one at its last endpoint. */
    private fun ensureRelease() {
        val end = dispatchedEnd ?: return
        if (items.none { it.release }) items.addLast(Entry(Action.Drag(listOf(end), 1, false), generation, release = true))
    }

    /** The next action to dispatch, or null. */
    fun next(): Action? {
        while (true) {
            val e = items.removeFirstOrNull() ?: return null
            val a = e.action
            if (e.release) {
                if (dispatchedEnd == null) continue // nothing is held any more
            } else if (e.generation != generation) continue
            if (replacesStroke(a)) dispatchedEnd = null
            return if (a is Action.Drag) bridge(a) else a
        }
    }

    /** The platform cancelled or rejected the stroke; the next drag segment starts a fresh one. */
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
