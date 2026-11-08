package app.linksy.mg.control.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GestureMapperTest {
    private val d = Display(1000, 2000)
    private val m = GestureMapper()

    private fun ptr(x: Double, y: Double) = """{"type":"pointer","x":$x,"y":$y}"""
    private fun btn(b: String, down: Boolean) = """{"type":"button","button":"$b","pressed":$down}"""
    private fun key(sym: Long, down: Boolean = true) = """{"type":"key","keysym":$sym,"pressed":$down}"""
    private fun send(json: String, t: Long = 0, disp: Display = d) = m.handle(json, disp, t)
    private fun acts(json: String, t: Long = 0, disp: Display = d) = send(json, t, disp).actions

    @Test fun shortStillPressIsTap() {
        acts(ptr(0.5, 0.5))
        assertTrue(acts(btn("left", true), 0).isEmpty())
        assertEquals(listOf(Action.Tap(Point(500f, 1000f))), acts(btn("left", false), 100))
    }

    @Test fun tapLongPressBoundaryAt300ms() {
        acts(ptr(0.5, 0.5)); acts(btn("left", true), 0)
        assertIs<Action.Tap>(acts(btn("left", false), 299).single())
        acts(btn("left", true), 1000)
        assertIs<Action.LongPress>(acts(btn("left", false), 1300).single())
    }

    @Test fun longStillPressIsLongPressWithDuration() {
        acts(ptr(0.5, 0.5)); acts(btn("left", true), 0)
        assertEquals(listOf(Action.LongPress(Point(500f, 1000f), 700)), acts(btn("left", false), 700))
    }

    @Test fun smallJitterStaysATap() {
        acts(ptr(0.5, 0.5)); acts(btn("left", true), 0)
        acts(ptr(0.504, 0.5), 20) // 4 px
        assertIs<Action.Tap>(acts(btn("left", false), 100).single())
    }

    @Test fun movedAndLongIsDragNotLongPress() {
        acts(ptr(0.5, 0.5)); acts(btn("left", true), 0)
        acts(ptr(0.5, 0.6), 400)
        val out = acts(btn("left", false), 800)
        assertTrue(out.isNotEmpty())
        assertTrue(out.all { it is Action.Drag })
        assertEquals(false, assertIs<Action.Drag>(out.last()).continuing)
    }

    @Test fun dragEmitsContinuousSegmentsEndingNotContinuing() {
        acts(ptr(0.5, 0.5)); acts(btn("left", true), 0)
        val out = mutableListOf<Action>()
        for (i in 1..10) out += acts(ptr(0.5, 0.5 + i * 0.02), i * 60L)
        out += acts(btn("left", false), 700)
        val drags = out.map { assertIs<Action.Drag>(it) }
        assertTrue(drags.size >= 3)
        assertEquals(Point(500f, 1000f), drags.first().path.first())
        assertTrue(drags.dropLast(1).all { it.continuing })
        assertEquals(false, drags.last().continuing)
        assertEquals(Point(500f, 1400f), drags.last().path.last())
        drags.zipWithNext().forEach { (a, b) -> assertEquals(a.path.last(), b.path.first()) }
        assertTrue(drags.all { it.durationMs in 1..5000 })
    }

    @Test fun segmentsAreRateLimited() {
        acts(ptr(0.5, 0.5)); acts(btn("left", true), 0)
        assertTrue(acts(ptr(0.5, 0.6), 10).isEmpty())
        assertTrue(acts(ptr(0.5, 0.65), 20).isEmpty())
        assertIs<Action.Drag>(acts(ptr(0.5, 0.7), 60).single())
    }

    @Test fun pathPointsAreBounded() {
        acts(ptr(0.5, 0.5)); acts(btn("left", true), 0)
        repeat(10_000) { i -> acts(ptr(0.5, 0.5 + (i % 100) / 1000.0), 1) }
        val out = acts(btn("left", false), 2)
        assertTrue(out.isNotEmpty())
        assertTrue(out.all { it is Action.Drag && it.path.size <= GestureMapper.MAX_PATH_POINTS })
    }

    @Test fun hoverWithoutPressDoesNothing() {
        assertTrue(acts(ptr(0.1, 0.1)).isEmpty())
        assertTrue(acts(ptr(0.9, 0.9), 100).isEmpty())
    }

    @Test fun upWithoutDownAndDownWithoutPointerAreIgnored() {
        assertTrue(acts(btn("left", false)).isEmpty())
        assertTrue(acts(btn("left", true)).isEmpty()) // no pointer position yet
        assertTrue(acts(btn("left", false), 50).isEmpty())
    }

    @Test fun duplicateDownKeepsOriginalGesture() {
        acts(ptr(0.5, 0.5)); acts(btn("left", true), 0)
        acts(ptr(0.9, 0.9), 5) // 5 ms: far move -> drag state, no emit yet
        assertTrue(acts(btn("left", true), 10).isEmpty())
        // A restarted gesture would end as a Tap; the original one ends as a drag from the first press.
        val drag = assertIs<Action.Drag>(acts(btn("left", false), 100).single())
        assertEquals(Point(500f, 1000f), drag.path.first())
        assertEquals(Point(900f, 1800f), drag.path.last())
        assertEquals(false, drag.continuing)
    }

    @Test fun rightIsBackMiddleIsHomeReleaseIgnored() {
        assertEquals(listOf(Action.Back), acts(btn("right", true)))
        assertTrue(acts(btn("right", false)).isEmpty())
        assertEquals(listOf(Action.Home), acts(btn("middle", true)))
        assertTrue(acts(btn("middle", false)).isEmpty())
    }

    @Test fun backAndForwardButtonsIgnoredWithoutReject() {
        assertEquals(MapResult(), send(btn("back", true)))
        assertEquals(MapResult(), send(btn("forward", true)))
    }

    @Test fun keysMap() {
        assertEquals(listOf(Action.Back), acts(key(0xff1b)))
        assertEquals(listOf(Action.Home), acts(key(0xffeb)))
        assertEquals(listOf(Action.Home), acts(key(0xffec)))
        assertEquals(listOf(Action.PerformImeAction), acts(key(0xff0d)))
        assertEquals(listOf(Action.PerformImeAction), acts(key(0xff8d)))
        assertEquals(listOf(Action.Backspace), acts(key(0xff08)))
        assertEquals(listOf(Action.SetText("a")), acts(key(0x61)))
        assertEquals(listOf(Action.SetText("€")), acts(key(0x01000000L + 0x20AC)))
    }

    @Test fun keyReleaseAndModifiersAndUnknownKeysignored() {
        for (sym in listOf(0xff1bL, 0xff0dL, 0x61L)) assertTrue(acts(key(sym, false)).isEmpty())
        assertEquals(MapResult(), send(key(0xffe1))) // Shift_L
        assertEquals(MapResult(), send(key(0xffe3))) // Control_L
        assertEquals(MapResult(), send(key(0x01))) // control char
        assertEquals(MapResult(), send(key(0x01000000L + 0xD800))) // lone surrogate
        assertEquals(MapResult(), send(key(0x01000000L + 0x110000))) // beyond Unicode
    }

    @Test fun keysymOutOfRangeIsRejectedNotThrown() {
        assertEquals(Reject.MALFORMED, send(key(-1)).rejected)
        assertEquals(Reject.MALFORMED, send(key(99_999_999_999)).rejected)
    }

    @Test fun pageKeysScroll() {
        val down = acts(key(0xff56)).single() as Action.Swipe
        val up = acts(key(0xff55)).single() as Action.Swipe
        assertTrue(down.to.y < down.from.y) // content moves up: finger swipes up
        assertTrue(up.to.y > up.from.y)
    }

    @Test fun discreteScrollIsSwipeOppositeToWheelAndScaled() {
        val s1 = acts("""{"type":"scroll","dx":0,"dy":1,"discrete":true}""").single() as Action.Swipe
        assertTrue(s1.to.y < s1.from.y)
        assertEquals(s1.from.x, s1.to.x)
        val sUp = acts("""{"type":"scroll","dx":0,"dy":-1,"discrete":true}""").single() as Action.Swipe
        assertTrue(sUp.to.y > sUp.from.y)
        val s3 = acts("""{"type":"scroll","dx":0,"dy":3,"discrete":true}""").single() as Action.Swipe
        assertTrue(s1.from.y - s1.to.y < s3.from.y - s3.to.y)
        val sx = acts("""{"type":"scroll","dx":2,"dy":0,"discrete":true}""").single() as Action.Swipe
        assertTrue(sx.to.x < sx.from.x)
    }

    @Test fun pixelScrollIsExactAndHugeScrollIsClamped() {
        val s = acts("""{"type":"scroll","dx":0,"dy":100,"discrete":false}""").single() as Action.Swipe
        assertEquals(100f, s.from.y - s.to.y)
        val huge = acts("""{"type":"scroll","dx":0,"dy":1e9,"discrete":true}""").single() as Action.Swipe
        assertTrue(huge.from.y - huge.to.y <= 0.4f * d.height + 0.5f)
        assertTrue(huge.to.y >= 0f && huge.to.y <= d.height - 1f)
    }

    @Test fun zeroScrollDoesNothing() {
        assertEquals(MapResult(), send("""{"type":"scroll","dx":0,"dy":0,"discrete":true}"""))
    }

    @Test fun nonFiniteCoordinatesAreRejected() {
        assertEquals(Reject.INVALID, send("""{"type":"pointer","x":NaN,"y":0.5}""").rejected)
        assertEquals(Reject.INVALID, send("""{"type":"pointer","x":0.5,"y":Infinity}""").rejected)
        assertEquals(Reject.INVALID, send("""{"type":"scroll","dx":NaN,"dy":1,"discrete":true}""").rejected)
        assertEquals(Reject.INVALID, send("""{"type":"scroll","dx":0,"dy":1e999,"discrete":true}""").rejected)
    }

    @Test fun outOfRangeFractionsAreClampedIntoDisplay() {
        acts(ptr(1.5, -0.2)); acts(btn("left", true), 0)
        assertEquals(listOf(Action.Tap(Point(999f, 0f))), acts(btn("left", false), 50))
    }

    @Test fun rotationUsesDisplayAtEmission() {
        acts(ptr(0.5, 0.5), 0, Display(1000, 2000)); acts(btn("left", true), 0, Display(1000, 2000))
        assertEquals(listOf(Action.Tap(Point(1000f, 500f))), acts(btn("left", false), 50, Display(2000, 1000)))
    }

    @Test fun invalidDisplayRejected() {
        assertEquals(Reject.INVALID, send(ptr(0.5, 0.5), 0, Display(0, 100)).rejected)
        assertEquals(Reject.INVALID, send(ptr(0.5, 0.5), 0, Display(100, -1)).rejected)
    }

    @Test fun textMapsAndIsBoundedInBytes() {
        assertEquals(listOf(Action.SetText("hello")), acts("""{"type":"text","text":"hello"}"""))
        assertEquals(1, acts("""{"type":"text","text":"${"a".repeat(1024)}"}""").size)
        assertEquals(Reject.TOO_LARGE, send("""{"type":"text","text":"${"a".repeat(1025)}"}""").rejected)
        // 600 chars of 2 bytes each = 1200 bytes, under 1024 chars but over 1024 bytes
        assertEquals(Reject.TOO_LARGE, send("""{"type":"text","text":"${"é".repeat(600)}"}""").rejected)
    }

    @Test fun emptyTextIsIgnored() {
        assertEquals(MapResult(), send("""{"type":"text","text":""}"""))
    }

    @Test fun unknownAndUnsupportedTypesAreIgnored() {
        for (j in listOf(
            """{"type":"bogus"}""", """{"type":"term_close"}""", """{"type":"pointer_by","dx":1,"dy":1}""",
            """{"type":"term_input","data":"x"}""", """{"type":"term_resize","cols":1,"rows":1}""",
        )) assertEquals(MapResult(), send(j), j)
    }

    @Test fun malformedJsonIsRejectedWithoutThrowing() {
        for (j in listOf(
            "", "{", "null", "[]", "42", "\"str\"", """{"type":5}""", """{"x":1}""", """{"type":"pointer"}""",
            """{"type":"pointer","x":"a","y":1}""", """{"type":"button","button":"left"}""",
            """{"type":"button","button":"left","pressed":"yes"}""", """{"type":"text","text":5}""",
            """{"type":"key","keysym":"a","pressed":true}""",
        )) assertEquals(Reject.MALFORMED, send(j).rejected, "input: $j")
    }

    @Test fun oversizeMessageRejected() {
        assertEquals(Reject.TOO_LARGE, send("""{"type":"bogus","pad":"${"x".repeat(20_000)}"}""").rejected)
    }

    @Test fun deeplyNestedJsonDoesNotThrow() {
        val deep = "[".repeat(5000) + "]".repeat(5000)
        assertEquals(Reject.MALFORMED, send(deep).rejected)
    }

    @Test fun releaseMidDragEndsStrokeAndCancelsRest() {
        acts(ptr(0.5, 0.5)); acts(btn("left", true), 0)
        acts(ptr(0.5, 0.8), 100)
        val out = m.release(d, 200)
        val last = assertIs<Action.Drag>(out.last())
        assertEquals(false, last.continuing)
        assertTrue(acts(btn("left", false), 300).isEmpty()) // nothing more after release
        assertTrue(m.release(d, 400).isEmpty())
    }

    @Test fun releaseWhileStillPressedEmitsNothingAndSwallowsUp() {
        acts(ptr(0.5, 0.5)); acts(btn("left", true), 0)
        assertTrue(m.release(d, 50).isEmpty())
        assertTrue(acts(btn("left", false), 80).isEmpty())
    }

    @Test fun neverRejectsValidMessage() {
        assertNull(send(ptr(0.1, 0.1)).rejected)
    }

    @Test fun anExcursionAfterManyPendingPointsIsNotLost() {
        acts(ptr(0.5, 0.5)); acts(btn("left", true), 0)
        for (i in 0 until 70) acts(ptr(0.52 + i * 0.001, 0.5), 10)
        acts(ptr(0.6, 0.6), 10) // B: 200 px off the line
        acts(ptr(0.6, 0.5), 10) // back
        val drag = assertIs<Action.Drag>(acts(btn("left", false), 20).single())
        assertTrue(drag.path.any { it.y > 1100f }, "excursion kept: ${drag.path}")
        assertTrue(drag.path.size <= GestureMapper.MAX_PATH_POINTS)
    }
}
