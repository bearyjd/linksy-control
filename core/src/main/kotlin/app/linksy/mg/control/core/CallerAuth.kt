package app.linksy.mg.control.core

enum class AuthDecision { ALLOWED, DISABLED_NOT_CONFIGURED, WRONG_PACKAGE, WRONG_CERT, NOT_APPROVED }

interface ApprovalStore {
    fun isApproved(pkg: String, certSha256: String): Boolean
    fun approve(pkg: String, certSha256: String)
    fun revoke(pkg: String)
}

class InMemoryApprovalStore : ApprovalStore {
    override fun isApproved(pkg: String, certSha256: String) = false
    override fun approve(pkg: String, certSha256: String) {}
    override fun revoke(pkg: String) {}
}

class CallerAuth(
    private val linksyPackage: String,
    private val pinnedCertSha256: String,
    private val approvals: ApprovalStore,
) {
    val enabled: Boolean get() = false
    fun checkIdentity(pkg: String, certSha256: Collection<String>): AuthDecision = AuthDecision.ALLOWED
    fun check(pkg: String, certSha256: Collection<String>): AuthDecision = AuthDecision.ALLOWED
}
