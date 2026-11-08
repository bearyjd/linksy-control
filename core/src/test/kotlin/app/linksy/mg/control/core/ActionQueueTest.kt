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

    @Test fun endDropsQueuedOrdinaryActionsButKeepsTheClosingRelease() {
        q.enqueue(listOf(tap, Action.Back, drag(0f, 1f, continuing = true)))
        val release = drag(1f, 2f, continuing = false)
        q.endSession(listOf(release))
        assertEquals(listOf(release), drain())
    }

    @Test fun nothingOrdinaryRunsAfterEndEvenIfEnqueuedWithTheOldGeneration() {
        val old = q.generation
        q.endSession(emptyList())
        q.enqueue(listOf(tap, Action.Home, drag(0f, 1f, continuing = true)), old)
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
        q.enqueue(listOf(drag(0f, 1f, continuing = true)))
        val release = drag(1f, 2f, continuing = false)
        q.endSession(listOf(release))
        q.beginSession()
        q.enqueue(listOf(Action.Back))
        val out = drain()
        assertEquals(2, out.size)
        assertEquals(false, assertIs<Action.Drag>(out.first()).continuing)
        assertEquals(Action.Back, out.last())
    }

    @Test fun anEarlierTerminalDragSurvivesEnd() {
        val up = drag(1f, 2f, continuing = false)
        q.enqueue(listOf(up, tap))
        q.endSession(emptyList())
        assertEquals(listOf<Action>(up), drain())
    }

    @Test fun capAppliesToEveryKindOfActionButNeverToReleases() {
        repeat(1000) { q.enqueue(listOf(tap, Action.Back, Action.SetText("x"))) }
        assertEquals(ActionQueue.MAX_QUEUE, q.size)
        q.enqueue(listOf(drag(0f, 1f, continuing = false)))
        q.endSession(listOf(drag(1f, 2f, continuing = false)))
        assertEquals(2, q.size)
    }

    @Test fun droppedMidDragSegmentIsBridgedFromTheLastDispatchedEndpoint() {
        val small = ActionQueue(maxQueue = 1)
        small.enqueue(listOf(drag(0f, 1f, true), drag(1f, 2f, true))) // second dropped (full)
        assertEquals(drag(0f, 1f, true), small.next())
        small.enqueue(listOf(drag(2f, 3f, true)))
        val next = assertIs<Action.Drag>(small.next())
        assertEquals(listOf(p(1f), p(2f), p(3f)), next.path)
    }

    @Test fun segmentsClearedByEndAreBridgedToo() {
        q.enqueue(listOf(drag(0f, 1f, true), drag(1f, 2f, true)))
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

}
