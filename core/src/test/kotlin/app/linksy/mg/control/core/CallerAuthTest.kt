package app.linksy.mg.control.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CallerAuthTest {
    private val pkg = "app.linksy.mg"
    private val pin = "ab12".repeat(16) // 64 hex chars
    private val store = InMemoryApprovalStore()
    private fun auth(pin: String = this.pin, p: String = pkg) = CallerAuth(p, pin, store)

    @Test fun emptyPinRejectsEveryoneFailClosed() {
        val a = auth(pin = "")
        assertFalse(a.enabled)
        assertEquals(AuthDecision.DISABLED_NOT_CONFIGURED, a.check(pkg, listOf(pin)))
        assertEquals(AuthDecision.DISABLED_NOT_CONFIGURED, a.checkIdentity(pkg, listOf("")))
        store.approve(pkg, pin)
        assertEquals(AuthDecision.DISABLED_NOT_CONFIGURED, a.check(pkg, listOf(pin)))
    }

    @Test fun malformedPinAndBlankPackageAlsoDisable() {
        for (bad in listOf(" ", "abc", "zz".repeat(32), pin + "00", pin.dropLast(1)))
            assertFalse(auth(pin = bad).enabled, bad)
        assertFalse(auth(p = "").enabled)
    }

    @Test fun wrongPackageRejected() {
        store.approve("evil.app", pin)
        assertEquals(AuthDecision.WRONG_PACKAGE, auth().check("evil.app", listOf(pin)))
        assertEquals(AuthDecision.WRONG_PACKAGE, auth().check("", listOf(pin)))
        assertEquals(AuthDecision.WRONG_PACKAGE, auth().check("app.linksy.mg.evil", listOf(pin)))
    }

    @Test fun wrongOrMissingCertRejected() {
        assertEquals(AuthDecision.WRONG_CERT, auth().check(pkg, listOf("00".repeat(32))))
        assertEquals(AuthDecision.WRONG_CERT, auth().check(pkg, emptyList()))
        assertEquals(AuthDecision.WRONG_CERT, auth().check(pkg, listOf("")))
    }

    @Test fun hexCompareIsCaseInsensitiveAndIgnoresColons() {
        store.approve(pkg, pin)
        assertEquals(AuthDecision.ALLOWED, auth().check(pkg, listOf(pin.uppercase())))
        val colons = pin.chunked(2).joinToString(":").uppercase()
        assertEquals(AuthDecision.ALLOWED, auth(pin = colons).check(pkg, listOf(pin)))
    }

    @Test fun approvalIsRequired() {
        assertEquals(AuthDecision.NOT_APPROVED, auth().check(pkg, listOf(pin)))
        assertEquals(AuthDecision.ALLOWED, auth().checkIdentity(pkg, listOf(pin)))
        store.approve(pkg, pin)
        assertEquals(AuthDecision.ALLOWED, auth().check(pkg, listOf(pin)))
    }

    @Test fun revokedApprovalIsRejectedAgain() {
        store.approve(pkg, pin)
        assertTrue(auth().check(pkg, listOf(pin)) == AuthDecision.ALLOWED)
        store.revoke(pkg)
        assertEquals(AuthDecision.NOT_APPROVED, auth().check(pkg, listOf(pin)))
    }

    @Test fun approvalForOtherCertDoesNotCount() {
        store.approve(pkg, "00".repeat(32))
        assertEquals(AuthDecision.NOT_APPROVED, auth().check(pkg, listOf(pin)))
    }

    @Test fun anyMatchingSignerAllowedWhenSeveral() {
        store.approve(pkg, pin)
        assertEquals(AuthDecision.ALLOWED, auth().check(pkg, listOf("00".repeat(32), pin)))
    }

    @Test fun matchedCertIsThePinMatchingOneNotTheFirst() {
        val other = "cd34".repeat(16)
        assertEquals(pin, auth().matchedCert(listOf(other, pin.uppercase())))
        assertEquals(null, auth().matchedCert(listOf(other)))
        assertEquals(null, auth(pin = "").matchedCert(listOf(pin)))
    }
}
