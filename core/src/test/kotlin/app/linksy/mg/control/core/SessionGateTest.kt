package app.linksy.mg.control.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionGateTest {
    private var now = 1_000L
    private val gate = SessionGate({ now }, maxDurationMs = 10 * 60_000L, idleTimeoutMs = 60_000L)

    @Test fun inactiveUntilBegun() {
        assertFalse(gate.isActive())
        assertFalse(gate.touch())
        assertFalse(gate.end())
    }

    @Test fun beginActivatesAndTouchAdmits() {
        gate.begin()
        assertTrue(gate.isActive())
        assertTrue(gate.touch())
    }

    @Test fun idleTimeoutExpiresAtExactlyTheLimit() {
        gate.begin()
        now += 59_999
        assertTrue(gate.isActive())
        now += 1
        assertFalse(gate.isActive())
        assertFalse(gate.touch())
    }

    @Test fun touchExtendsIdleWindow() {
        gate.begin()
        now += 50_000; assertTrue(gate.touch())
        now += 50_000; assertTrue(gate.touch())
        assertTrue(gate.isActive())
    }

    @Test fun maxDurationCapsEvenWithConstantActivity() {
        gate.begin()
        repeat(11) { now += 59_000; if (gate.isActive()) gate.touch() }
        assertFalse(gate.isActive())
    }

    @Test fun endReportsWhetherItWasActiveOnce() {
        gate.begin()
        assertTrue(gate.end())
        assertFalse(gate.end())
        assertFalse(gate.isActive())
    }

    @Test fun expireIfDueFiresOnceThenNever() {
        gate.begin()
        assertFalse(gate.expireIfDue())
        now += 60_000
        assertTrue(gate.expireIfDue())
        assertFalse(gate.expireIfDue())
        assertFalse(gate.end()) // already over
    }

    @Test fun beginAgainRestartsTheWindows() {
        gate.begin()
        now += 59_000
        gate.begin()
        now += 59_000
        assertTrue(gate.isActive())
    }

    @Test fun clockGoingBackwardsDoesNotExtendForever() {
        gate.begin()
        now -= 10_000_000
        assertFalse(gate.isActive())
    }
}
