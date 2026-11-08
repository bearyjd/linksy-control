package app.linksy.mg.control.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BridgeIngressTest {
    private var now = 0L
    private val store = InMemoryApprovalStore()
    private val session = BridgeSession(store)
    private val linksyUid = 1000
    private val limiter = UidRateLimiter({ now }, capacity = 10.0, refillPerSec = 10.0,
        isPrivileged = { it == linksyUid || session.isOwner(it) })
    private val ownerLimiter = UidRateLimiter({ now }, capacity = 100.0, refillPerSec = 200.0, isPrivileged = { true })
    private val ingress = BridgeIngress(session, limiter, beginCost = 8.0, ownerLimiter = ownerLimiter,
        isLinksyUid = { it == linksyUid })

    private fun open() {
        store.approve("p", "aa")
        session.open(linksyUid, "p", "aa")
    }

    private fun count(sender: Int, isBegin: Boolean = false, isEnd: Boolean = false, n: Int, stepMs: Long = 1): Int {
        var admitted = 0
        repeat(n) {
            now += stepMs
            if (ingress.admit(sender, isBegin, isEnd) == Admission.ADMIT) admitted++
        }
        return admitted
    }

    @Test fun ownerAtNormalMouseRatesKeepsEveryMessage() {
        open()
        assertEquals(500, count(linksyUid, n = 500, stepMs = 10)) // 100 Hz for 5 s
    }

    @Test fun ownerFloodIsEventuallyCutOffNotSilentlyThinned() {
        open()
        var firstOver = -1
        repeat(5000) {
            if (firstOver < 0 && ingress.admit(linksyUid, false) == Admission.OWNER_OVER_BUDGET) firstOver = it
        }
        assertTrue(firstOver in 50..200, "firstOver=$firstOver") // capacity 100 at one instant, then over budget
    }

    @Test fun endIsNeverMeteredEvenWhenTheOwnerBudgetIsSpent() {
        open()
        repeat(500) { ingress.admit(linksyUid, false) }
        assertEquals(Admission.OWNER_OVER_BUDGET, ingress.admit(linksyUid, false))
        assertEquals(Admission.ADMIT, ingress.admit(linksyUid, isBegin = false, isEnd = true))
    }

    @Test fun nonOwnerFloodIsLimited() {
        open()
        assertTrue(count(2000, n = 5000) < 100)
    }

    @Test fun beforeAnySessionEveryoneIsLimited() {
        assertEquals(10, count(linksyUid, n = 1000, stepMs = 0))
    }

    @Test fun ownerBeginIsStillMetered() {
        open()
        assertEquals(1, count(linksyUid, isBegin = true, n = 100, stepMs = 0))
    }

    @Test fun aBeginFromAnotherUidIsWrongPackageWithoutLookups() {
        assertEquals(Admission.WRONG_PACKAGE, ingress.admit(2000, isBegin = true))
    }

    @Test fun aRateLimitedBeginGetsABusyReplyNotSilence() {
        count(linksyUid, isBegin = true, n = 5, stepMs = 0)
        assertEquals(Admission.BUSY, ingress.admit(linksyUid, isBegin = true))
    }

    @Test fun attackerBeginsOverTheGlobalBucketAreBusy() {
        repeat(30) { ingress.admit(5000 + it, isBegin = true) }
        assertEquals(Admission.BUSY, ingress.admit(6000, isBegin = true))
    }

    @Test fun colludingUidsCannotMakeTheRealLinksyBusy() {
        var admitted = 0
        repeat(101) {
            now += 1000
            for (uid in 5000 until 5000 + UidRateLimiter.MAX_UIDS + 1) ingress.admit(uid, isBegin = true)
            if (ingress.admit(linksyUid, isBegin = true) == Admission.ADMIT) admitted++
        }
        assertEquals(101, admitted)
    }
}
