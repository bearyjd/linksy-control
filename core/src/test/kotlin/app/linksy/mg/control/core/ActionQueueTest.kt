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

    @Test fun consecutiveMovesCoalesceKeepingStartAndLatestEnd() {
        q.enqueue(listOf(drag(0f, 1f, true), drag(1f, 2f, true), drag(2f, 3f, true)))
        assertEquals(1, q.size)
        assertEquals(listOf(p(0f), p(3f)), assertIs<Action.Drag>(q.next()).path)
    }

    @Test fun coalescingKeepsEveryNonMoveActionInOrder() {
        val text = Action.SetText("x")
        q.enqueue(listOf(drag(0f, 1f, true), drag(1f, 2f, true), tap, Action.Back, text,
            drag(2f, 3f, true), drag(3f, 4f, true)))
        val out = drain()
        assertEquals(5, out.size)
        assertEquals(listOf(tap, Action.Back, text), out.subList(1, 4))
        assertEquals(listOf(p(0f), p(2f)), assertIs<Action.Drag>(out[0]).path)
        assertEquals(listOf(p(2f), p(4f)), assertIs<Action.Drag>(out[4]).path)
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
        small.enqueue(listOf(drag(0f, 1f, true), tap, drag(1f, 2f, true))) // third dropped (full)
        assertEquals(drag(0f, 1f, true), small.next())
        assertEquals(tap, small.next())
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

}
