package app.linksy.mg.control.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Edge cases of the small service-glue classes that the per-class tests do not pin down. */
class SessionGlueEdgeCasesTest {
    // --- BridgeSession ---------------------------------------------------------------------

    private val store = InMemoryApprovalStore()
    private val session = BridgeSession(store)

    @Test fun sessionIsClosedUntilOpenedAndAfterClose() {
        assertFalse(session.isOpen())
        session.open(1000, "p", "aa")
        assertTrue(session.isOpen())
        session.close()
        assertFalse(session.isOpen())
    }

    @Test fun ownershipFollowsOpenAndClose() {
        assertFalse(session.isOwner(1000))
        session.open(1000, "p", "aa")
        assertTrue(session.isOwner(1000))
        assertFalse(session.isOwner(1001))
        session.close()
        assertFalse(session.isOwner(1000))
    }

    @Test fun sentinelUidNeverOwnsAClosedSession() {
        assertFalse(session.isOwner(-1))
        assertEquals(SessionCheck.NOT_OWNER, session.check(-1))
    }

    @Test fun openingAgainHandsOwnershipAndApprovalToTheNewCaller() {
        store.approve("p", "aa")
        session.open(1000, "p", "aa")
        session.open(2000, "q", "bb")
        assertEquals(SessionCheck.NOT_OWNER, session.check(1000))
        // The new owner has no approval, so it is revoked rather than OK.
        assertEquals(SessionCheck.REVOKED, session.check(2000))
        store.approve("q", "bb")
        assertEquals(SessionCheck.OK, session.check(2000))
    }

    @Test fun nonOwnerIsNeverToldRevokedEvenWithoutApproval() {
        session.open(1000, "p", "aa") // never approved
        assertEquals(SessionCheck.NOT_OWNER, session.check(1001))
    }

    @Test fun approvalForADifferentCertDoesNotKeepTheSessionAlive() {
        store.approve("p", "other")
        session.open(1000, "p", "aa")
        assertEquals(SessionCheck.REVOKED, session.check(1000))
    }

    // --- SessionGate -----------------------------------------------------------------------

    private var now = 5_000L
    private val gate = SessionGate({ now }, maxDurationMs = 1_000L, idleTimeoutMs = 400L)

    @Test fun maxDurationExpiresAtExactlyTheLimit() {
        gate.begin()
        repeat(3) { now += 300; assertTrue(gate.touch()) } // 900 ms, kept alive by activity
        now += 99
        assertTrue(gate.isActive()) // 999 ms
        now += 1
        assertFalse(gate.isActive()) // 1000 ms
    }

    @Test fun anExpiredSessionIsNotRevivedByTouchOrByTheClockRecovering() {
        gate.begin()
        now += 400
        assertFalse(gate.touch())
        now -= 400 // clock returns to a fresh-looking time
        assertTrue(gate.isActive()) // within limits again: nothing was latched by touch()
        assertFalse(gate.expireIfDue())
    }

    @Test fun expireIfDueLatchesTheEndSoALaterRewindDoesNotReopenIt() {
        gate.begin()
        now += 400
        assertTrue(gate.expireIfDue())
        now -= 400
        assertFalse(gate.isActive())
        assertFalse(gate.touch())
    }

    @Test fun clockBeforeLastActivityIsTreatedAsOver() {
        gate.begin()
        now += 300
        assertTrue(gate.touch())
        now -= 100 // after start, but before the last touch
        assertFalse(gate.isActive())
    }

    @Test fun endThenTouchIsDropped() {
        gate.begin()
        assertTrue(gate.end())
        assertFalse(gate.touch())
    }

    // --- Display / MapResult defaults -------------------------------------------------------

    @Test fun displayIsValidOnlyWithPositiveSides() {
        assertTrue(Display(1, 1).valid)
        assertFalse(Display(0, 100).valid)
        assertFalse(Display(100, 0).valid)
        assertFalse(Display(-1, 100).valid)
        assertFalse(Display(100, -1).valid)
    }

    @Test fun defaultMapResultIsEmptyAndNotRejected() {
        val r = MapResult()
        assertTrue(r.actions.isEmpty())
        assertNull(r.rejected)
    }

    // --- TextEdit --------------------------------------------------------------------------

    @Test fun insertingEmptyTextOnlyDeletesTheSelection() =
        assertEquals(EditResult("ho", 1), TextEdit.insert("hello", 1, 4, ""))

    @Test fun cursorAfterInsertCountsUtf16UnitsOfTheInsertedText() =
        assertEquals(EditResult("a😀b", 3), TextEdit.insert("ab", 1, 1, "😀"))

    @Test fun insertWithOneUnknownBoundAppends() =
        assertEquals(EditResult("helloX", 6), TextEdit.insert("hello", 2, -1, "X"))

    @Test fun backspaceOnEmptyTextIsNoop() {
        assertEquals(EditResult("", 0), TextEdit.backspace("", 0, 0))
        assertEquals(EditResult("", 0), TextEdit.backspace("", -1, -1))
    }

    @Test fun backspaceDeletesAReversedSelection() =
        assertEquals(EditResult("ho", 1), TextEdit.backspace("hello", 4, 1))

    @Test fun backspaceClampsAnOutOfRangeCursorToTheEnd() =
        assertEquals(EditResult("hell", 4), TextEdit.backspace("hello", 99, 99))

    @Test fun backspaceDeletesALoneSurrogateOneUnitAtATime() {
        val lone = "a\uD83D" // a high surrogate with no pair
        assertEquals(EditResult("a", 1), TextEdit.backspace(lone, 2, 2))
        val reversedPair = "\uDE00\uD83D" // low then high: not a pair
        assertEquals(EditResult("\uDE00", 1), TextEdit.backspace(reversedPair, 2, 2))
    }

    @Test fun backspaceDeletesAPairOnlyWhenTheCursorIsAfterIt() {
        assertEquals(EditResult("ab", 1), TextEdit.backspace("a😀b", 3, 3))
        // Cursor between the two halves of the pair: only the high half is removed.
        assertEquals(EditResult("a\uDE00b", 1), TextEdit.backspace("a😀b", 2, 2))
    }
}
