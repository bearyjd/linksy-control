package app.linksy.mg.control.core

enum class SessionCheck { OK, NOT_OWNER, REVOKED }

/**
 * Who currently owns the control session, with the approval re-checked on every message so that
 * revoking it takes effect at once (the approval check is a cheap local lookup, no system calls).
 */
class BridgeSession(private val approvals: ApprovalStore) {
    private var uid = -1
    private var pkg = ""
    private var cert = ""

    @Synchronized fun open(uid: Int, pkg: String, cert: String) {
        this.uid = uid
        this.pkg = pkg
        this.cert = cert
    }

    @Synchronized fun close() {
        uid = -1
        pkg = ""
        cert = ""
    }

    @Synchronized fun isOpen(): Boolean = uid != -1

    @Synchronized fun isOwner(sender: Int): Boolean = uid != -1 && sender == uid

    /** REVOKED = this is the owner but the user withdrew approval: the caller must end the session. */
    @Synchronized fun check(sender: Int): SessionCheck = when {
        uid == -1 || sender != uid -> SessionCheck.NOT_OWNER
        !approvals.isApproved(pkg, cert) -> SessionCheck.REVOKED
        else -> SessionCheck.OK
    }
}
