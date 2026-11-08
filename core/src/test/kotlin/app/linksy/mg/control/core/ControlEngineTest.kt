package app.linksy.mg.control.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ControlEngineTest {
    private var now = 0L
    private val d = Display(1000, 2000)
    private val e = ControlEngine({ now })

    private fun ptr(y: Double) = """{"type":"pointer","x":0.5,"y":$y}"""
    private val down = """{"type":"button","button":"left","pressed":true}"""
    private val up = """{"type":"button","button":"left","pressed":false}"""

    @Test fun everythingIgnoredWithoutASession() {
        assertEquals(MapResult(), e.inject("""{"type":"button","button":"right","pressed":true}""", d))
        assertEquals(MapResult(), e.inject("{", d))
        assertTrue(e.tick(d).isEmpty())
    }

    @Test fun backWorksInsideSession() {
        e.begin()
        assertEquals(listOf(Action.Back), e.inject("""{"type":"button","button":"right","pressed":true}""", d).actions)
    }

    @Test fun endReleasesInProgressDragAndLaterInputIsIgnored() {
        e.begin(); e.inject(ptr(0.5), d); e.inject(down, d); e.inject(ptr(0.8), d)
        val released = e.end(d)
        assertEquals(false, assertIs<Action.Drag>(released.last()).continuing)
        assertEquals(MapResult(), e.inject(up, d))
    }

    @Test fun idleExpiryReleasesViaTick() {
        e.begin(); e.inject(ptr(0.5), d); e.inject(down, d); e.inject(ptr(0.8), d)
        now += 61_000
        val released = e.tick(d)
        assertEquals(false, assertIs<Action.Drag>(released.last()).continuing)
        assertTrue(e.tick(d).isEmpty())
        assertEquals(MapResult(), e.inject(ptr(0.1), d))
    }

    @Test fun injectAfterExpiryStillReleasesTheStroke() {
        e.begin(); e.inject(ptr(0.5), d); e.inject(down, d); e.inject(ptr(0.8), d)
        now += 61_000
        val r = e.inject(ptr(0.9), d)
        assertEquals(false, assertIs<Action.Drag>(r.actions.last()).continuing)
        assertEquals(null, r.rejected)
    }

    @Test fun newSessionStartsWithCleanGestureState() {
        e.begin(); e.inject(ptr(0.5), d); e.inject(down, d)
        e.begin()
        assertTrue(e.inject(up, d).actions.isEmpty())
    }

    @Test fun activityKeepsSessionAlive() {
        e.begin()
        repeat(5) { now += 50_000; e.inject(ptr(0.5), d) }
        assertEquals(listOf(Action.Back), e.inject("""{"type":"key","keysym":65307,"pressed":true}""", d).actions)
    }

    @Test fun badInputInsideSessionIsReportedNotThrown() {
        e.begin()
        assertEquals(Reject.MALFORMED, e.inject("nope", d).rejected)
    }
}
