package app.linksy.mg.control.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ActionQueueTest {
    private val q = ActionQueue()
    private val tap = Action.Tap(Point(1f, 1f))
    private fun p(y: Float) = Point(0f, y)
    private fun drag(from: Float, to: Float, continuing: Boolean) =
        Action.Drag(listOf(p(from), p(to)), 10, continuing)

    private fun drain(): List<Action> = generateSequence { q.next() }.toList()

    private fun holdFinger() {
        q.enqueue(listOf(drag(0f, 1f, continuing = true)))
        q.next() // dispatched: a stroke is now open at p(1)
    }

    @Test fun endDropsQueuedOrdinaryActionsButKeepsTheClosingRelease() {
        holdFinger()
        q.enqueue(listOf(tap, Action.Back, drag(1f, 2f, continuing = true)))
        val release = drag(1f, 2f, continuing = false)
        q.endSession(listOf(release))
        assertEquals(listOf(release), drain())
    }

    @Test fun releaseWithNoDispatchedStrokeIsDroppedAtEnd() {
        val release = drag(1f, 2f, continuing = false)
        q.endSession(listOf(release))
        assertTrue(drain().isEmpty())
    }

    @Test fun releaseIsDroppedAfterStrokeLost() {
        holdFinger()
        q.endSession(listOf(drag(1f, 2f, continuing = false)))
        q.strokeLost()
        assertTrue(drain().isEmpty())
    }

    @Test fun terminalDragWithoutAnOpenStrokeIsCappedLikeAnyAction() {
        repeat(ActionQueue.MAX_QUEUE) { q.enqueue(listOf(tap)) }
        q.enqueue(listOf(drag(0f, 1f, continuing = false)))
        assertEquals(ActionQueue.MAX_QUEUE, q.size)
    }

    @Test fun terminalDragWithoutAnOpenStrokeIsGenerationScoped() {
        val old = q.generation
        q.endSession(emptyList())
        q.enqueue(listOf(drag(0f, 1f, continuing = false)), old)
        assertTrue(drain().isEmpty())
    }

    @Test fun terminalDragWithoutAnOpenStrokeDoesNotRunAfterEnd() {
        q.enqueue(listOf(drag(0f, 1f, continuing = false)))
        q.endSession(emptyList())
        q.beginSession()
        assertTrue(drain().isEmpty())
    }

    @Test fun onlyTheFirstOfTwoReleasesRuns() {
        holdFinger()
        q.endSession(listOf(drag(1f, 2f, continuing = false), drag(1f, 3f, continuing = false)))
        assertEquals(1, drain().size)
    }

    @Test fun consecutiveMovesCoalesceIntoOneKeepingThePolyline() {
        q.enqueue(listOf(drag(0f, 1f, true), drag(1f, 2f, true), drag(2f, 3f, true)))
        assertEquals(1, q.size)
        assertEquals(listOf(p(0f), p(1f), p(2f), p(3f)), assertIs<Action.Drag>(q.next()).path)
    }

    @Test fun coalescingKeepsEveryNonMoveActionInOrder() {
        val text = Action.SetText("x")
        q.enqueue(listOf(drag(0f, 1f, true), drag(1f, 2f, true), tap, Action.Back, text,
            drag(2f, 3f, true), drag(3f, 4f, true)))
        val out = drain()
        assertEquals(5, out.size)
        assertEquals(listOf(tap, Action.Back, text), out.subList(1, 4))
        assertEquals(listOf(p(0f), p(1f), p(2f)), assertIs<Action.Drag>(out[0]).path)
        assertEquals(listOf(p(2f), p(3f), p(4f)), assertIs<Action.Drag>(out[4]).path)
    }

    @Test fun aMoveFloodCannotStarveLaterButtonsAndKeys() {
        repeat(1000) { q.enqueue(listOf(drag(it.toFloat(), it + 1f, true))) }
        q.enqueue(listOf(tap, Action.Back, Action.SetText("a")))
        assertEquals(4, q.size)
    }

    @Test fun aTerminalDragIsNeverCoalesced() {
        q.enqueue(listOf(drag(0f, 1f, true), drag(1f, 2f, false)))
        assertEquals(2, q.size)
    }

    @Test fun nothingQueuedBeforeEndRunsAfterALaterBegin() {
        q.enqueue(listOf(tap, Action.Home, drag(0f, 1f, continuing = true)))
        q.endSession(emptyList())
        q.beginSession()
        assertTrue(drain().isEmpty())
    }

    @Test fun staleGenerationIsDroppedAfterBegin() {
        val old = q.generation
        q.beginSession()
        q.enqueue(listOf(tap), old)
        assertEquals(0, q.size)
        q.enqueue(listOf(tap))
        assertEquals(1, q.size)
    }

    @Test fun replacingBeginNeverDeletesAPendingRelease() {
        holdFinger()
        val release = drag(1f, 2f, continuing = false)
        q.endSession(listOf(release))
        q.beginSession()
        q.enqueue(listOf(Action.Back))
        val out = drain()
        assertEquals(2, out.size)
        assertEquals(false, assertIs<Action.Drag>(out.first()).continuing)
        assertEquals(Action.Back, out.last())
    }

    @Test fun capAppliesToEveryKindOfAction() {
        repeat(1000) { q.enqueue(listOf(tap, Action.Back, Action.SetText("x"))) }
        assertEquals(ActionQueue.MAX_QUEUE, q.size)
        q.enqueue(listOf(drag(0f, 1f, continuing = false)))
        assertEquals(ActionQueue.MAX_QUEUE, q.size)
    }

    @Test fun droppedMidDragSegmentIsBridgedFromTheLastDispatchedEndpoint() {
        val small = ActionQueue(maxQueue = 2)
        small.enqueue(listOf(drag(0f, 1f, true), Action.Back, drag(1f, 2f, true))) // third dropped (full)
        assertEquals(drag(0f, 1f, true), small.next())
        assertEquals(Action.Back, small.next())
        small.enqueue(listOf(drag(2f, 3f, true)))
        val next = assertIs<Action.Drag>(small.next())
        assertEquals(listOf(p(1f), p(2f), p(3f)), next.path)
    }

    @Test fun segmentsClearedByEndAreBridgedToo() {
        q.enqueue(listOf(drag(0f, 1f, true), tap, drag(1f, 2f, true)))
        q.next()
        q.endSession(listOf(drag(2f, 3f, false)))
        assertEquals(listOf(p(1f), p(2f), p(3f)), assertIs<Action.Drag>(q.next()).path)
        assertNull(q.next())
    }

    @Test fun contiguousSegmentsAreLeftAlone() {
        q.enqueue(listOf(drag(0f, 1f, true), drag(1f, 2f, false)))
        q.next()
        assertEquals(listOf(p(1f), p(2f)), assertIs<Action.Drag>(q.next()).path)
    }

    @Test fun lostStrokeStartsFresh() {
        q.enqueue(listOf(drag(0f, 1f, true)))
        q.next()
        q.strokeLost()
        q.enqueue(listOf(drag(5f, 6f, true)))
        assertEquals(listOf(p(5f), p(6f)), assertIs<Action.Drag>(q.next()).path)
    }


    private fun fill() = repeat(ActionQueue.MAX_QUEUE) { q.enqueue(listOf(Action.Back)) }

    // --- round 3: release survives the cap, END synthesizes, no stale prefix ---

    @Test fun buttonUpAtAFullCapStillReleasesTheFinger() {
        holdFinger()
        fill()
        q.enqueue(listOf(drag(1f, 2f, continuing = false)))
        assertTrue(q.size <= ActionQueue.MAX_QUEUE)
        val out = drain()
        val last = assertIs<Action.Drag>(out.last())
        assertEquals(false, last.continuing)
    }

    @Test fun buttonUpAtAFullCapEvictsAnOrdinaryEntryNotAnotherRelease() {
        holdFinger()
        fill()
        q.enqueue(listOf(drag(1f, 2f, continuing = false)))
        assertEquals(ActionQueue.MAX_QUEUE, q.size)
        assertEquals(ActionQueue.MAX_QUEUE - 1, drain().count { it == Action.Back })
    }

    @Test fun buttonUpAtAFullCapThenEndStillReleasesExactlyOnce() {
        holdFinger()
        fill()
        q.enqueue(listOf(drag(1f, 2f, continuing = false)))
        q.endSession(emptyList())
        val out = drain()
        assertEquals(1, out.size)
        assertEquals(false, assertIs<Action.Drag>(out.single()).continuing)
    }

    @Test fun buttonUpForAStrokeOpenedByAQueuedMoveAlsoSurvivesTheCap() {
        q.enqueue(listOf(drag(0f, 1f, continuing = true)))
        repeat(ActionQueue.MAX_QUEUE - 1) { q.enqueue(listOf(Action.Back)) }
        q.enqueue(listOf(drag(1f, 2f, continuing = false)))
        assertTrue(drain().any { it is Action.Drag && !it.continuing })
    }

    @Test fun aTerminalDragWithNoOpenStrokeStillCannotEvictAnything() {
        fill()
        q.enqueue(listOf(drag(1f, 2f, continuing = false)))
        assertEquals(ActionQueue.MAX_QUEUE, drain().count { it == Action.Back })
    }

    @Test fun endWithoutAQueuedReleaseSynthesizesAStationaryOne() {
        holdFinger()
        q.endSession(emptyList())
        val out = assertIs<Action.Drag>(q.next())
        assertEquals(false, out.continuing)
        assertEquals(listOf(p(1f)), out.path)
        assertNull(q.next())
    }

    @Test fun nothingIsSynthesizedWhenNoStrokeIsOpen() {
        q.endSession(emptyList())
        assertTrue(drain().isEmpty())
    }

    @Test fun nextSessionsFirstDragHasNoStalePrefix() {
        holdFinger()
        fill()
        q.endSession(emptyList())
        drain()
        q.beginSession()
        q.enqueue(listOf(drag(5f, 6f, continuing = true)))
        assertEquals(listOf(p(5f), p(6f)), assertIs<Action.Drag>(q.next()).path)
    }

    @Test fun beginAloneNeverLeavesAnOpenStrokeWithoutARelease() {
        holdFinger()
        q.beginSession()
        val release = assertIs<Action.Drag>(q.next())
        assertEquals(false, release.continuing)
        q.enqueue(listOf(drag(5f, 6f, continuing = true)))
        assertEquals(listOf(p(5f), p(6f)), assertIs<Action.Drag>(q.next()).path)
    }

    @Test fun dispatchedReleaseClosesTheStroke() {
        holdFinger()
        q.endSession(listOf(drag(1f, 2f, continuing = false)))
        drain()
        q.endSession(emptyList())
        assertTrue(drain().isEmpty())
    }

    // --- round 3: a tap, long press or swipe replaces the held stroke ---

    @Test fun aDispatchedTapSwipeOrLongPressMakesTheLaterTerminalDragNotARelease() {
        val replacers = listOf(
            tap,
            Action.LongPress(Point(1f, 1f), 700),
            Action.Swipe(Point(0f, 0f), Point(0f, 9f), 200),
        )
        for (r in replacers) {
            val queue = ActionQueue()
            queue.enqueue(listOf(drag(0f, 1f, continuing = true)))
            queue.next()
            queue.enqueue(listOf(r))
            assertEquals(r, queue.next())
            queue.endSession(listOf(drag(1f, 2f, continuing = false)))
            assertNull(queue.next(), "no release without an open stroke after $r")
        }
    }

    @Test fun aWheelSwipeStillQueuedAtEndDoesNotHideTheRealRelease() {
        holdFinger()
        q.enqueue(listOf(Action.Swipe(Point(0f, 0f), Point(0f, 9f), 200)))
        q.endSession(listOf(drag(1f, 2f, continuing = false)))
        val out = drain()
        assertEquals(1, out.size)
        assertEquals(listOf(p(1f), p(2f)), assertIs<Action.Drag>(out.single()).path)
    }

    @Test fun nextDragAfterAWheelSwipeIsNotPrefixedWithTheOldEndpoint() {
        holdFinger()
        q.enqueue(listOf(Action.Swipe(Point(0f, 0f), Point(0f, 9f), 200)))
        q.next()
        q.enqueue(listOf(drag(5f, 6f, continuing = true)))
        assertEquals(listOf(p(5f), p(6f)), assertIs<Action.Drag>(q.next()).path)
    }

    // --- round 3: coalescing keeps the geometry ---

    @Test fun reversalAToBToAKeepsTheTurnaround() {
        q.enqueue(listOf(drag(0f, 5f, true), drag(5f, 0f, true)))
        assertEquals(listOf(p(0f), p(5f), p(0f)), assertIs<Action.Drag>(q.next()).path)
    }

    @Test fun curvesKeepTheirCorners() {
        val a = Action.Drag(listOf(Point(0f, 0f), Point(10f, 0f)), 10, true)
        val b = Action.Drag(listOf(Point(10f, 0f), Point(10f, 10f)), 10, true)
        q.enqueue(listOf(a, b))
        assertEquals(listOf(Point(0f, 0f), Point(10f, 0f), Point(10f, 10f)), assertIs<Action.Drag>(q.next()).path)
    }

    @Test fun coalescedDurationsAddUp() {
        q.enqueue(listOf(
            Action.Drag(listOf(p(0f), p(1f)), 30, true),
            Action.Drag(listOf(p(1f), p(2f)), 70, true),
            Action.Drag(listOf(p(2f), p(3f)), 100, true),
        ))
        assertEquals(200L, assertIs<Action.Drag>(q.next()).durationMs)
    }

    @Test fun coalescedDurationIsCapped() {
        repeat(5) { i ->
            q.enqueue(listOf(Action.Drag(listOf(p(i.toFloat()), p(i + 1f)), GestureMapper.MAX_SEGMENT_MS, true)))
        }
        assertEquals(GestureMapper.MAX_SEGMENT_MS, assertIs<Action.Drag>(q.next()).durationMs)
    }

    @Test fun coalescedPathIsBoundedAndKeepsFirstAndLastPoint() {
        repeat(500) { i -> q.enqueue(listOf(drag(i.toFloat(), i + 1f, true))) }
        val path = assertIs<Action.Drag>(q.next()).path
        assertEquals(GestureMapper.MAX_PATH_POINTS, path.size)
        assertEquals(p(0f), path.first())
        assertEquals(p(500f), path.last())
        assertEquals(path.sortedBy { it.y }, path) // downsampling keeps the order
    }

    @Test fun reversalSurvivesDownsampling() {
        repeat(200) { i -> q.enqueue(listOf(drag(i.toFloat(), i + 1f, true))) }
        repeat(200) { i -> q.enqueue(listOf(drag(200f - i, 199f - i, true))) }
        val path = assertIs<Action.Drag>(q.next()).path
        assertTrue(path.size <= GestureMapper.MAX_PATH_POINTS)
        assertTrue(path.maxOf { it.y } >= 190f, "the turnaround must survive")
        assertEquals(p(0f), path.last())
    }
}
