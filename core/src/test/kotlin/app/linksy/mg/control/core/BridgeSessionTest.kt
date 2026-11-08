package app.linksy.mg.control.core

import kotlin.test.Test
import kotlin.test.assertEquals

class BridgeSessionTest {
    private val store = InMemoryApprovalStore()
    private val s = BridgeSession(store)

    @Test fun noOwnerBeforeOpen() {
        assertEquals(SessionCheck.NOT_OWNER, s.check(1000))
    }

    @Test fun ownerPassesOthersDoNot() {
        store.approve("p", "aa")
        s.open(1000, "p", "aa")
        assertEquals(SessionCheck.OK, s.check(1000))
        assertEquals(SessionCheck.NOT_OWNER, s.check(1001))
    }

    @Test fun revokingApprovalIsSeenOnTheNextCheck() {
        store.approve("p", "aa")
        s.open(1000, "p", "aa")
        store.revoke("p")
        assertEquals(SessionCheck.REVOKED, s.check(1000))
        assertEquals(SessionCheck.NOT_OWNER, s.check(1001))
    }

    @Test fun closeForgetsTheOwner() {
        store.approve("p", "aa")
        s.open(1000, "p", "aa")
        s.close()
        assertEquals(SessionCheck.NOT_OWNER, s.check(1000))
    }
}
