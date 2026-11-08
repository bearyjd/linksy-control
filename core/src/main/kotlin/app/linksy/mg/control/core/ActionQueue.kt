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
 *   stroke is never dropped by the cap. It takes one extra slot (bounded, since only one stroke can
 *   be open and at most one release is queued), so the finger is not left down and no ordinary
 *   entry is evicted.
 * - Stroke loss is tracked per logical gesture ([Action.Drag.gestureId]), never globally. The rule:
 *   when the stroke that is held down is replaced (a dispatched Tap, LongPress or Swipe) or lost
 *   ([strokeLost]: rejected or cancelled), the id of the gesture that owns it joins `lostGestures`
 *   (a ring of the last [MAX_LOST]). A terminal drag (button-up) that does not close an open
 *   stroke is dropped if and only if its own gestureId is in `lostGestures`; a lost id is
 *   forgotten when that terminal is dropped or skipped, when a continuing move of that gesture is
 *   admitted (it starts a fresh stroke, so the gesture is no longer lost) or dispatched, and on
 *   [beginSession]/[endSession]. A terminal drag of any other gesture (an independent quick drag)
 *   is never affected. The release of a stroke that is open is always kept (see above).
 * - A queued move (a continuing Drag) directly followed by another is coalesced into one. The merged
 *   path keeps the polyline (corners and reversals) down-sampled to [GestureMapper.MAX_PATH_POINTS]
 *   and the durations add up, capped at [GestureMapper.MAX_SEGMENT_MS]. Button, key, text, END and
 *   terminal drags are never merged.
 * - Android requires a continuing stroke segment to start exactly where the previous dispatched one
 *   ended. If segments were dropped or cleared, the next one is prefixed with the last dispatched
 *   endpoint so the stroke stays continuous.
 * - Dispatching a Tap, LongPress or Swipe replaces the held stroke, so the endpoint is forgotten.
 * - After [endSession] or [beginSession] a held stroke has exactly one release queued: a single
 *   stationary lift at the last dispatched endpoint (never a moving one), so no endpoint leaks into
 *   the next session and releases cannot pile up across BEGIN/END cycles.
 */
class ActionQueue(private val maxQueue: Int = MAX_QUEUE) {
    companion object {
        const val MAX_QUEUE = 32
        const val MAX_LOST = 8
    }

    private data class Entry(val action: Action, val generation: Long, val release: Boolean = false)

    private val items = ArrayDeque<Entry>()
    private var dispatchedEnd: Point? = null

    /** Gesture that owns the stroke currently held down (set when its move is dispatched). */
    private var openGestureId: Long? = null

    /** Gestures whose stroke was replaced or lost and that have not started a new one; oldest first. */
    private val lostGestures = LinkedHashSet<Long>()

    val lostGestureCount: Int get() = lostGestures.size

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
                last.action is Action.Drag && isMove(last.action) && last.action.gestureId == a.gestureId
            ) {
                items[items.lastIndex] = last.copy(action = merge(last.action, a))
                lostGestures.remove(a.gestureId)
            } else if (isTerminalDrag(a) && openStroke()) {
                items.addLast(Entry(a, gen, release = true)) // may exceed maxQueue by this one release
            } else if (a is Action.Drag && isTerminalDrag(a) && lostGestures.remove(a.gestureId)) {
                // the button-up of a gesture whose stroke is gone: not a new touch
            } else if (items.size < maxQueue) {
                items.addLast(Entry(a, gen))
                if (a is Action.Drag && isMove(a)) lostGestures.remove(a.gestureId) // only an admitted move
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

    private fun merge(first: Action.Drag, second: Action.Drag): Action.Drag {
        val joined = if (first.path.lastOrNull() == second.path.firstOrNull()) first.path + second.path.drop(1)
        else first.path + second.path
        return Action.Drag(
            path = prune(joined),
            durationMs = (first.durationMs + second.durationMs).coerceAtMost(GestureMapper.MAX_SEGMENT_MS),
            continuing = true,
            gestureId = first.gestureId,
        )
    }

    private fun markLost() {
        val id = openGestureId
        if (dispatchedEnd != null && id != null) {
            lostGestures.remove(id)
            lostGestures.add(id)
            while (lostGestures.size > MAX_LOST) lostGestures.remove(lostGestures.first())
        }
        openGestureId = null
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

    /** A new session starts: ordinary leftovers go, a held finger gets one stationary lift. */
    fun beginSession() = restart()

    /** The session is over: drop everything, then lift a held finger where it is, once. */
    fun endSession() = restart()

    private fun restart() {
        generation++
        lostGestures.clear()
        items.clear()
        val end = dispatchedEnd ?: return
        items.addLast(Entry(Action.Drag(listOf(end), 1, false), generation, release = true))
    }

    /** The next action to dispatch, or null. */
    fun next(): Action? {
        while (true) {
            val e = items.removeFirstOrNull() ?: return null
            val a = e.action
            if (e.release) {
                if (dispatchedEnd == null) {
                    if (a is Action.Drag) lostGestures.remove(a.gestureId) // its gesture is over
                    continue // nothing is held any more
                }
            } else if (e.generation != generation) continue
            if (replacesStroke(a)) {
                markLost()
                dispatchedEnd = null
            }
            if (a is Action.Drag) {
                lostGestures.remove(a.gestureId) // a fresh stroke or its end: nothing left to suppress
                openGestureId = if (a.continuing) a.gestureId else null
            }
            return if (a is Action.Drag) bridge(a) else a
        }
    }

    /** The platform cancelled or rejected the stroke; the next drag segment starts a fresh one. */
    fun strokeLost() {
        markLost()
        dispatchedEnd = null
    }

    private fun bridge(a: Action.Drag): Action.Drag {
        val prev = dispatchedEnd
        val fixed = if (prev != null && a.path.firstOrNull() != prev) a.copy(path = listOf(prev) + a.path) else a
        dispatchedEnd = if (a.continuing) a.path.lastOrNull() else null
        return fixed
    }
}
