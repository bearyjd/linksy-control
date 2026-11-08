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
    private fun drag(from: Float, to: Float, continuing: Boolean, id: Long = Action.Drag.NO_GESTURE) =
        Action.Drag(listOf(p(from), p(to)), 10, continuing, id)

    private fun drain(): List<Action> = generateSequence { q.next() }.toList()

    private fun holdFinger(id: Long = Action.Drag.NO_GESTURE) {
        q.enqueue(listOf(drag(0f, 1f, continuing = true, id = id)))
        q.next() // dispatched: a stroke is now open at p(1)
    }

    @Test fun endDropsQueuedOrdinaryActionsButKeepsTheClosingRelease() {
        holdFinger()
        q.enqueue(listOf(tap, Action.Back, drag(1f, 2f, continuing = true)))
        q.endSession()
        assertEquals(listOf(Action.Drag(listOf(p(1f)), 1, false)), drain())
    }

    @Test fun releaseWithNoDispatchedStrokeIsDroppedAtEnd() {
        q.enqueue(listOf(drag(1f, 2f, continuing = false)))
        q.endSession()
        assertTrue(drain().isEmpty())
    }

    @Test fun releaseIsDroppedAfterStrokeLost() {
        holdFinger()
        q.endSession()
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
        q.endSession()
        q.enqueue(listOf(drag(0f, 1f, continuing = false)), old)
        assertTrue(drain().isEmpty())
    }

    @Test fun terminalDragWithoutAnOpenStrokeDoesNotRunAfterEnd() {
        q.enqueue(listOf(drag(0f, 1f, continuing = false)))
        q.endSession()
        q.beginSession()
        assertTrue(drain().isEmpty())
    }

    @Test fun onlyTheFirstOfTwoReleasesRuns() {
        holdFinger()
        q.enqueue(listOf(drag(1f, 2f, continuing = false), drag(1f, 2f, continuing = false)))
        q.endSession()
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
        q.endSession()
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
        q.endSession()
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
        q.endSession()
        assertEquals(listOf(p(1f)), assertIs<Action.Drag>(q.next()).path)
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
        assertTrue(q.size <= ActionQueue.MAX_QUEUE + 1)
        val out = drain()
        val last = assertIs<Action.Drag>(out.last())
        assertEquals(false, last.continuing)
    }

    @Test fun buttonUpAtAFullCapTakesOneExtraSlotAndEvictsNothing() {
        holdFinger()
        fill()
        q.enqueue(listOf(drag(1f, 2f, continuing = false)))
        assertEquals(ActionQueue.MAX_QUEUE + 1, q.size)
        assertEquals(ActionQueue.MAX_QUEUE, drain().count { it == Action.Back })
    }

    @Test fun buttonUpAtAFullCapThenEndStillReleasesExactlyOnce() {
        holdFinger()
        fill()
        q.enqueue(listOf(drag(1f, 2f, continuing = false)))
        q.endSession()
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
        q.endSession()
        val out = assertIs<Action.Drag>(q.next())
        assertEquals(false, out.continuing)
        assertEquals(listOf(p(1f)), out.path)
        assertNull(q.next())
    }

    @Test fun nothingIsSynthesizedWhenNoStrokeIsOpen() {
        q.endSession()
        assertTrue(drain().isEmpty())
    }

    @Test fun nextSessionsFirstDragHasNoStalePrefix() {
        holdFinger()
        fill()
        q.endSession()
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
        q.endSession()
        drain()
        q.endSession()
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
            queue.endSession()
            assertNull(queue.next(), "no release without an open stroke after $r")
        }
    }

    @Test fun aWheelSwipeStillQueuedAtEndDoesNotHideTheRealRelease() {
        holdFinger()
        q.enqueue(listOf(Action.Swipe(Point(0f, 0f), Point(0f, 9f), 200)))
        q.endSession()
        val out = drain()
        assertEquals(1, out.size)
        assertEquals(listOf(p(1f)), assertIs<Action.Drag>(out.single()).path)
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

    // --- round 4 ---

    private val swipe = Action.Swipe(Point(0f, 0f), Point(0f, 9f), 200)

    @Test fun aButtonUpAfterAReplacingSwipeIsNotAPhantomTouch() {
        holdFinger()
        q.enqueue(listOf(swipe))
        assertEquals(swipe, q.next())
        q.enqueue(listOf(drag(1f, 2f, continuing = false)))
        assertNull(q.next())
        assertEquals(0, q.size)
    }

    @Test fun aButtonUpAfterARejectedStrokeIsNotAPhantomTouch() {
        holdFinger()
        q.strokeLost()
        q.enqueue(listOf(drag(1f, 2f, continuing = false)))
        assertNull(q.next())
    }

    @Test fun theWholeMapperScenarioDispatchDragSwipeButtonUpMakesNoTouch() {
        val m = GestureMapper()
        val d = Display(1000, 2000)
        fun feed(json: String, t: Long) = q.enqueue(m.handle(json, d, t).actions)
        feed("""{"type":"pointer","x":0.5,"y":0.5}""", 0)
        feed("""{"type":"button","button":"left","pressed":true}""", 0)
        feed("""{"type":"pointer","x":0.6,"y":0.5}""", 60)
        assertIs<Action.Drag>(q.next())
        feed("""{"type":"scroll","dx":0,"dy":1,"discrete":true}""", 70)
        assertIs<Action.Swipe>(q.next())
        feed("""{"type":"button","button":"left","pressed":false}""", 90)
        assertNull(q.next())
    }

    @Test fun aDragWholeInOneTerminalSegmentAndPlainTapsStillDispatch() {
        q.enqueue(listOf(drag(0f, 1f, continuing = false), tap))
        assertEquals(drag(0f, 1f, continuing = false), q.next())
        assertEquals(tap, q.next())
    }

    @Test fun aNewMoveAfterALostStrokeStillGetsItsRelease() {
        holdFinger()
        q.strokeLost()
        q.enqueue(listOf(drag(5f, 6f, continuing = true)))
        q.next()
        q.enqueue(listOf(drag(6f, 7f, continuing = false)))
        assertEquals(false, assertIs<Action.Drag>(q.next()).continuing)
    }

    @Test fun aMovingFiveSecondReleaseAtEndBecomesOneStationaryLift() {
        holdFinger()
        q.enqueue(listOf(Action.Drag(listOf(p(1f), p(9f)), 5000, false)))
        q.endSession()
        val out = drain().map { assertIs<Action.Drag>(it) }
        assertEquals(1, out.size)
        assertEquals(listOf(p(1f)), out.single().path)
        assertTrue(out.single().durationMs <= 10)
    }

    @Test fun releasesDoNotPileUpAcrossBeginEndCycles() {
        holdFinger()
        repeat(1000) {
            q.endSession()
            q.beginSession()
        }
        assertTrue(q.size <= 1, "size=${q.size}")
    }

    @Test fun aReleaseThatIsSkippedNeverEvictsTypedText() {
        holdFinger()
        q.enqueue(List(31) { Action.SetText("x") } + swipe)
        q.enqueue(listOf(drag(1f, 2f, continuing = false)))
        val out = drain()
        assertEquals(31, out.count { it is Action.SetText })
        assertEquals(1, out.count { it is Action.Swipe })
    }

    @Test fun aKeptReleaseTakesAnExtraSlotInsteadOfEvicting() {
        holdFinger()
        q.enqueue(List(ActionQueue.MAX_QUEUE) { Action.SetText("x") })
        q.enqueue(listOf(drag(1f, 2f, continuing = false)))
        val out = drain()
        assertEquals(ActionQueue.MAX_QUEUE, out.count { it is Action.SetText })
        assertEquals(false, assertIs<Action.Drag>(out.last()).continuing)
    }

    @Test fun aMoveDroppedAtCapacityDoesNotMakeTheLostGestureLiveAgain() {
        holdFinger()
        q.strokeLost()
        repeat(ActionQueue.MAX_QUEUE) { q.enqueue(listOf(tap)) }
        q.enqueue(listOf(drag(5f, 6f, continuing = true))) // dropped: queue is full
        drain()
        q.enqueue(listOf(drag(6f, 7f, continuing = false)))
        assertNull(q.next())
    }

    @Test fun aSkippedReleaseDoesNotSuppressTheNextIndependentQuickDrag() {
        holdFinger()
        q.enqueue(listOf(swipe, drag(1f, 2f, continuing = false)))
        assertIs<Action.Swipe>(q.next())
        assertNull(q.next()) // the release is skipped
        val quick = drag(3f, 4f, continuing = false)
        q.enqueue(listOf(quick))
        assertEquals(quick, q.next())
    }

    // --- round 4 addendum 2: per-gesture identity instead of a global flag ---

    @Test fun recoveredStrokeThenIndependentQuickDragIsDispatched() {
        holdFinger(1)
        q.enqueue(listOf(drag(1f, 2f, continuing = true, id = 1)))
        q.strokeLost() // the platform cancels while the recovered move is still queued: it is discarded
        q.enqueue(listOf(drag(2f, 3f, continuing = false, id = 1)))
        assertTrue(drain().isEmpty()) // and the button-up of the lost gesture is dropped
        val quick = drag(7f, 8f, continuing = false, id = 2)
        q.enqueue(listOf(quick))
        assertEquals(quick, q.next())
    }

    @Test fun anOldReleaseQueuedBehindASwipeIsTheOneSuppressedNotTheNextQuickDrag() {
        holdFinger(1)
        q.enqueue(listOf(swipe, drag(1f, 2f, continuing = false, id = 1)))
        assertIs<Action.Swipe>(q.next()) // the swipe replaces the stroke of gesture 1
        val quick = drag(7f, 8f, continuing = false, id = 2)
        q.enqueue(listOf(quick)) // enqueued before next() reaches gesture 1's old release
        assertEquals(quick, q.next())
        assertNull(q.next())
    }

    @Test fun dragSwipeButtonUpMakesNoTouchByGestureId() {
        holdFinger(1)
        q.enqueue(listOf(swipe))
        assertIs<Action.Swipe>(q.next())
        q.enqueue(listOf(drag(1f, 2f, continuing = false, id = 1)))
        assertNull(q.next())
        assertEquals(0, q.lostGestureCount)
    }

    @Test fun dragRejectedButtonUpMakesNoTouchByGestureId() {
        holdFinger(1)
        q.strokeLost()
        q.enqueue(listOf(drag(1f, 2f, continuing = false, id = 1)))
        assertNull(q.next())
        assertEquals(0, q.lostGestureCount)
    }

    @Test fun backHomeAndTextDoNotReplaceTheStrokeSoButtonUpStillReleases() {
        holdFinger(1)
        q.enqueue(listOf(Action.Back, Action.Home, Action.SetText("x"), drag(1f, 2f, continuing = false, id = 1)))
        val out = drain()
        assertEquals(4, out.size)
        assertEquals(false, assertIs<Action.Drag>(out.last()).continuing)
    }

    @Test fun aNewDragWithMovesAfterASwipeGetsItsOwnRelease() {
        holdFinger(1)
        q.enqueue(listOf(swipe))
        q.next()
        q.enqueue(listOf(drag(5f, 6f, continuing = true, id = 2)))
        assertIs<Action.Drag>(q.next())
        q.enqueue(listOf(drag(6f, 7f, continuing = false, id = 2)))
        assertEquals(false, assertIs<Action.Drag>(q.next()).continuing)
    }

    @Test fun aMoveOfALostGestureStartsAFreshStrokeAndClearsTheLoss() {
        holdFinger(1)
        q.strokeLost()
        assertEquals(1, q.lostGestureCount)
        q.enqueue(listOf(drag(5f, 6f, continuing = true, id = 1)))
        assertEquals(0, q.lostGestureCount)
        q.next()
        q.enqueue(listOf(drag(6f, 7f, continuing = false, id = 1)))
        assertEquals(false, assertIs<Action.Drag>(q.next()).continuing)
    }

    @Test fun endAndBeginForgetLostGestures() {
        holdFinger(1)
        q.strokeLost()
        assertEquals(1, q.lostGestureCount)
        q.endSession()
        assertEquals(0, q.lostGestureCount)
        q.beginSession()
        val quick = drag(3f, 4f, continuing = false, id = 1)
        q.enqueue(listOf(quick))
        assertEquals(quick, q.next())
    }

    @Test fun lostGesturesStayBoundedUnderAFlood() {
        for (id in 1L..1000L) {
            q.enqueue(listOf(drag(0f, 1f, continuing = true, id = id)))
            q.next()
            q.strokeLost()
        }
        assertTrue(q.lostGestureCount <= ActionQueue.MAX_LOST, "count=${q.lostGestureCount}")
        q.enqueue(listOf(drag(0f, 1f, continuing = false, id = 1000)))
        assertNull(q.next()) // the newest loss is still remembered
    }

    // --- round 4 addendum 3: moves queued before a stroke loss belong to the lost stroke ---

    private fun queuedBeforeLoss(): Action.Drag {
        holdFinger(1) // M10 dispatched
        val m11 = drag(1f, 2f, continuing = true, id = 1)
        q.enqueue(listOf(m11))
        q.enqueue(listOf(drag(2f, 3f, continuing = false, id = 1))) // queued release
        return m11
    }

    @Test fun lostNoPostLossMovesTerminalDroppedNothingDispatched() {
        queuedBeforeLoss()
        q.strokeLost()
        assertTrue(drain().isEmpty())
    }

    @Test fun lostPostLossMovesFreshStrokeIsDispatchedThenReleased() {
        queuedBeforeLoss()
        q.strokeLost()
        val m20 = drag(5f, 6f, continuing = true, id = 1)
        q.enqueue(listOf(m20))
        val out = drain()
        assertEquals(2, out.size, "$out")
        assertEquals(m20, out[0]) // not merged with the pre-loss M11, no bridge to the old stroke
        assertEquals(false, assertIs<Action.Drag>(out[1]).continuing)
    }

    @Test fun lostMoreThanOnePostLossMoveStillEndsWithARelease() {
        queuedBeforeLoss()
        q.strokeLost()
        q.enqueue(listOf(drag(5f, 6f, continuing = true, id = 1)))
        q.enqueue(listOf(drag(6f, 7f, continuing = true, id = 1)))
        val out = drain()
        assertEquals(false, assertIs<Action.Drag>(out.last()).continuing)
        assertTrue(out.dropLast(1).all { (it as Action.Drag).continuing && it.path.first().y >= 5f }, "$out")
    }

    @Test fun preLossQueuedMovesAreNeverDispatchedAfterTheLoss() {
        val m11 = queuedBeforeLoss()
        q.strokeLost()
        q.enqueue(listOf(drag(5f, 6f, continuing = true, id = 1)))
        assertTrue(drain().none { it is Action.Drag && it.path.contains(p(2f)) && it.path.contains(p(1f)) }, "$m11")
    }

    @Test fun swipeReplacementDiscardsQueuedMovesOfTheLostGesture() {
        holdFinger(1)
        val swipe = Action.Swipe(Point(0f, 0f), Point(0f, 9f), 100)
        q.enqueue(listOf(swipe, drag(1f, 2f, continuing = true, id = 1), drag(2f, 3f, continuing = false, id = 1)))
        assertEquals(swipe, q.next())
        assertTrue(drain().isEmpty())
    }

    @Test fun swipeReplacementThenPostLossMoveIsReleased() {
        holdFinger(1)
        val swipe = Action.Swipe(Point(0f, 0f), Point(0f, 9f), 100)
        q.enqueue(listOf(swipe, drag(1f, 2f, continuing = true, id = 1), drag(2f, 3f, continuing = false, id = 1)))
        assertEquals(swipe, q.next())
        val m20 = drag(5f, 6f, continuing = true, id = 1)
        q.enqueue(listOf(m20))
        val out = drain()
        assertEquals(m20, out.first())
        assertEquals(false, assertIs<Action.Drag>(out.last()).continuing)
        assertEquals(2, out.size, "$out")
    }

    @Test fun otherGesturesMovesSurviveTheLoss() {
        holdFinger(1)
        val other = drag(8f, 9f, continuing = true, id = 2)
        q.enqueue(listOf(other))
        q.strokeLost()
        assertEquals(other, q.next())
    }
}
