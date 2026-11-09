package app.linksy.mg.control.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InMemoryApprovalStoreTest {
    private val store = InMemoryApprovalStore()
    private val cert = "ab12".repeat(16)

    @Test fun nothingApprovedInitially() = assertFalse(store.isApproved("p", cert))

    @Test fun approvalMatchesIgnoringCaseColonsAndWhitespace() {
        store.approve("p", cert.chunked(2).joinToString(":").uppercase())
        assertTrue(store.isApproved("p", cert))
        assertTrue(store.isApproved("p", "  ${cert.uppercase()}\n"))
    }

    @Test fun approvalIsPerPackage() {
        store.approve("p", cert)
        assertFalse(store.isApproved("q", cert))
    }

    @Test fun approvingAgainReplacesTheEarlierCert() {
        val other = "cd34".repeat(16)
        store.approve("p", cert)
        store.approve("p", other)
        assertFalse(store.isApproved("p", cert))
        assertTrue(store.isApproved("p", other))
    }

    @Test fun revokeOnlyAffectsThatPackageAndIsIdempotent() {
        store.approve("p", cert)
        store.approve("q", cert)
        store.revoke("p")
        store.revoke("p")
        store.revoke("never-approved")
        assertFalse(store.isApproved("p", cert))
        assertTrue(store.isApproved("q", cert))
    }
}
