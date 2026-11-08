package app.linksy.mg.control.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BridgeIngressTest {
    private var now = 0L
    private val store = InMemoryApprovalStore()
    private val session = BridgeSession(store)
    private val ingress = BridgeIngress(session, UidRateLimiter({ now }, capacity = 10.0, refillPerSec = 10.0), beginCost = 8.0)

    private fun open() {
        store.approve("p", "aa")
        session.open(1000, "p", "aa")
    }

    @Test fun ownerAt1000HzKeepsEveryMessage() {
        open()
        var admitted = 0
        repeat(5000) {
            now += 1
            if (ingress.admit(1000, isBegin = false)) admitted++
        }
        assertEquals(5000, admitted)
    }

    @Test fun nonOwnerFloodIsLimited() {
        open()
        var admitted = 0
        repeat(5000) {
            now += 1
            if (ingress.admit(2000, isBegin = false)) admitted++
        }
        assertTrue(admitted < 100, "admitted=$admitted")
    }

    @Test fun beforeAnySessionEveryoneIsLimited() {
        var admitted = 0
        repeat(1000) { if (ingress.admit(1000, isBegin = false)) admitted++ }
        assertEquals(10, admitted)
    }

    @Test fun ownerBeginIsStillMetered() {
        open()
        var admitted = 0
        repeat(100) { if (ingress.admit(1000, isBegin = true)) admitted++ }
        assertEquals(1, admitted)
    }
}
