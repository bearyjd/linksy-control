package app.linksy.mg.control.core

import java.security.MessageDigest

enum class AuthDecision { ALLOWED, DISABLED_NOT_CONFIGURED, WRONG_PACKAGE, WRONG_CERT, NOT_APPROVED }

/** Remembers which (package, signing cert) the user approved. Approvals are revocable. */
interface ApprovalStore {
    fun isApproved(pkg: String, certSha256: String): Boolean
    fun approve(pkg: String, certSha256: String)
    fun revoke(pkg: String)
}

class InMemoryApprovalStore : ApprovalStore {
    private val approved = HashMap<String, String>()

    @Synchronized override fun isApproved(pkg: String, certSha256: String) =
        approved[pkg] == CallerAuth.normalizeHex(certSha256)

    @Synchronized override fun approve(pkg: String, certSha256: String) {
        approved[pkg] = CallerAuth.normalizeHex(certSha256)
    }

    @Synchronized override fun revoke(pkg: String) {
        approved.remove(pkg)
    }
}

/**
 * Who may drive this phone. Fail closed: with no valid pin configured nobody is allowed.
 * A caller must be the Linksy package, signed by the pinned certificate, and approved by the user.
 */
class CallerAuth(
    private val linksyPackage: String,
    pinnedCertSha256: String,
    private val approvals: ApprovalStore,
) {
    companion object {
        /** Placeholder: the official Linksy signing-cert hash goes in at build time (BuildConfig). */
        const val PINNED_LINKSY_CERT_SHA256 = ""

        private val HEX64 = Regex("[0-9a-f]{64}")

        /** Lower-case hex without colons (so `keytool` output compares equal). */
        fun normalizeHex(s: String): String = s.replace(":", "").trim().lowercase()
    }

    private val pin: String = normalizeHex(pinnedCertSha256)

    /** False = binding disabled until the official certificate hash is configured. */
    val enabled: Boolean get() = linksyPackage.isNotBlank() && HEX64.matches(pin)

    /** Package and certificate only; used when asking the user to approve. */
    fun checkIdentity(pkg: String, certSha256: Collection<String>): AuthDecision {
        if (!enabled) return AuthDecision.DISABLED_NOT_CONFIGURED
        if (pkg != linksyPackage) return AuthDecision.WRONG_PACKAGE
        if (matchedCert(certSha256) == null) return AuthDecision.WRONG_CERT
        return AuthDecision.ALLOWED
    }

    /** Identity plus the user's approval. Only ALLOWED lets anything through. */
    fun check(pkg: String, certSha256: Collection<String>): AuthDecision {
        val identity = checkIdentity(pkg, certSha256)
        if (identity != AuthDecision.ALLOWED) return identity
        val cert = matchedCert(certSha256) ?: return AuthDecision.WRONG_CERT
        return if (approvals.isApproved(pkg, cert)) AuthDecision.ALLOWED else AuthDecision.NOT_APPROVED
    }

    /** The caller's certificate (normalized) that equals the pin, or null. Store this one, not the first. */
    fun matchedCert(certs: Collection<String>): String? {
        if (!enabled) return null
        val pinBytes = pin.toByteArray()
        return certs.map { normalizeHex(it) }.firstOrNull { MessageDigest.isEqual(it.toByteArray(), pinBytes) }
    }
}
