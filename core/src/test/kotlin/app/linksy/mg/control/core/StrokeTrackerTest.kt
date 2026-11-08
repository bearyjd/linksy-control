package app.linksy.mg.control.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class StrokeTrackerTest {
    private val q = ActionQueue()
    private val t = StrokeTracker<String>(q)
    private fun p(y: Float) = Point(0f, y)
    private fun drag(from: Float, to: Float, continuing: Boolean) = Action.Drag(listOf(p(from), p(to)), 10, continuing)

    private fun dispatchContinuingSegment() {
        q.enqueue(listOf(drag(0f, 1f, true)))
        q.next()
        t.dispatched("s1", willContinue = true)
    }

    @Test fun aContinuingStrokeIsContinuedByTheNextSegment() {
        dispatchContinuingSegment()
        assertEquals("s1", t.continuation(fresh = false))
    }

    @Test fun aFreshGestureNeverContinuesTheHeldStroke() {
        dispatchContinuingSegment()
        assertNull(t.continuation(fresh = true))
    }

    @Test fun aFinishedStrokeIsNotContinued() {
        dispatchContinuingSegment()
        t.dispatched("s2", willContinue = false)
        assertNull(t.continuation(fresh = false))
    }

    @Test fun aRejectedGestureForgetsTheStrokeAndTheQueueEndpoint() {
        dispatchContinuingSegment()
        t.rejected()
        assertNull(t.continuation(fresh = false))
        q.enqueue(listOf(drag(5f, 6f, true)))
        assertEquals(listOf(p(5f), p(6f)), assertIs<Action.Drag>(q.next()).path)
    }

    @Test fun aCancelledGestureForgetsTheStrokeAndTheQueueEndpoint() {
        dispatchContinuingSegment()
        t.cancelled()
        assertNull(t.continuation(fresh = false))
        q.endSession()
        assertNull(q.next()) // no release for a finger Android no longer has down
    }
}
