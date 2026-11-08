package app.linksy.mg.control

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder.DeathRecipient
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.SystemClock
import app.linksy.mg.control.core.Admission
import app.linksy.mg.control.core.AuthDecision
import app.linksy.mg.control.core.BridgeIngress
import app.linksy.mg.control.core.BridgeSession
import app.linksy.mg.control.core.PinnedUid
import app.linksy.mg.control.core.SessionCheck
import app.linksy.mg.control.core.UidRateLimiter

/**
 * Exported bound service for the Linksy app. Binding itself grants nothing: the uid of each
 * message's sender (Message.sendingUid, set by the kernel-backed Binder, not by the caller) is
 * checked at BEGIN against package + pinned certificate + user approval, and only that uid may
 * then INJECT or END. Binder.getCallingUid() in onBind is not usable for this, hence per message.
 * The user's approval is re-read from the approval store on every message, so revoking it ends the
 * session at once. A per-uid rate limit runs before any package or certificate lookup, and the
 * session also ends when the BEGIN caller's reply binder dies (onUnbind only fires when every client left).
 * Only the pinned package's uid (resolved from the system, refreshed at most every 10 s) and the
 * current owner have their own rate bucket; every other uid shares one. The session also ends
 * when the action engine expires it (idle or maximum duration).
 */
class ControlBridgeService : Service() {
    private lateinit var session: BridgeSession
    private lateinit var ingress: BridgeIngress
    private var owner: IBinder? = null
    private var death: DeathRecipient? = null
    private var sessionToken = 0L
    private var destroyed = false

    private val handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            val isBegin = msg.what == BridgeProtocol.MSG_BEGIN
            when (ingress.admit(msg.sendingUid, isBegin, isEnd = msg.what == BridgeProtocol.MSG_END)) {
                Admission.ADMIT -> Unit
                Admission.DROP -> return
                Admission.BUSY -> { msg.replyTo?.let { reply(it, BridgeProtocol.RESULT_BUSY) }; return }
                Admission.WRONG_PACKAGE -> { msg.replyTo?.let { reply(it, BridgeProtocol.RESULT_WRONG_PACKAGE) }; return }
                Admission.OWNER_OVER_BUDGET -> { endSession(); return } // releases still run
            }
            when (msg.what) {
                BridgeProtocol.MSG_BEGIN -> begin(msg)
                BridgeProtocol.MSG_END -> if (isSession(msg)) endSession()
                BridgeProtocol.MSG_INJECT -> if (isSession(msg)) {
                    msg.data?.getString(BridgeProtocol.KEY_JSON)?.let { ControlAccessibilityService.instance?.inject(it) }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        session = BridgeSession(Graph.approvals(this))
        val pinned = PinnedUid({ linksyUid() }, SystemClock::elapsedRealtime)
        val limiter = UidRateLimiter(SystemClock::elapsedRealtime, isPrivileged = { pinned.matches(it) || session.isOwner(it) })
        val ownerBudget = UidRateLimiter(
            SystemClock::elapsedRealtime, BridgeIngress.OWNER_BURST, BridgeIngress.OWNER_PER_SEC, isPrivileged = { true },
        )
        ingress = BridgeIngress(session, limiter, BEGIN_COST, ownerBudget) { pinned.matches(it) }
        instance = this
    }

    private val messenger = Messenger(handler)

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onUnbind(intent: Intent?): Boolean {
        endSession()
        return false
    }

    override fun onDestroy() {
        destroyed = true
        endSession()
        instance = null
        super.onDestroy()
    }

    /** True for the session owner with approval intact; an owner whose approval was revoked ends it. */
    private fun isSession(msg: Message): Boolean = when (session.check(msg.sendingUid)) {
        SessionCheck.OK -> true
        SessionCheck.REVOKED -> { endSession(); false }
        SessionCheck.NOT_OWNER -> false
    }

    /** The user revoked approval: end the live session now. */
    fun onRevoked() = endSession()

    /** The action engine expired the session (idle or maximum): the bridge exemption ends with it. */
    fun onEngineEnded() = endSession()

    /** The uid of the pinned package as the system reports it, or null if it is not installed. */
    private fun linksyUid(): Int? = try {
        @Suppress("DEPRECATION") packageManager.getPackageUid(BuildConfig.LINKSY_PACKAGE, 0)
    } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
        null
    }

    private fun begin(msg: Message) {
        if (destroyed) return // a retained Messenger must not start a session on a dead service
        val replyTo = msg.replyTo ?: return // cannot answer, so do not start
        val uid = msg.sendingUid
        val pkg = if (uid >= 0) CallerIdentity.packageOfUid(this, uid) else null
        val certs = pkg?.let { CallerIdentity.certsOf(this, it) } ?: emptyList()
        val auth = Graph.auth(this)
        val code = authorize(auth.check(pkg ?: "", certs))
        val cert = auth.matchedCert(certs)
        if (code != BridgeProtocol.RESULT_OK || pkg == null || cert == null) {
            reply(replyTo, code)
            return
        }
        endSession() // a new session replaces any old one: its held stroke release stays queued
        val token = ++sessionToken
        val binder = replyTo.binder
        // Per-session recipient: a late death notice for an older owner must not end a newer session.
        val recipient = DeathRecipient { handler.post { if (sessionToken == token && owner === binder) endSession() } }
        try {
            binder.linkToDeath(recipient, 0)
        } catch (_: android.os.RemoteException) {
            return // the caller is already gone; nothing was opened and no OK is sent
        }
        death = recipient
        owner = binder
        session.open(uid, pkg, cert)
        ControlAccessibilityService.instance?.beginSession()
        reply(replyTo, BridgeProtocol.RESULT_OK)
    }

    private fun endSession() {
        try {
            death?.let { owner?.unlinkToDeath(it, 0) }
        } catch (_: NoSuchElementException) {
            // The death notice already unlinked it.
        }
        owner = null
        death = null
        sessionToken++ // invalidates any death notice already posted
        if (!session.isOpen()) return
        session.close()
        ControlAccessibilityService.instance?.endSession()
    }

    private fun authorize(decision: AuthDecision): Int {
        return when (decision) {
            AuthDecision.DISABLED_NOT_CONFIGURED -> BridgeProtocol.RESULT_DISABLED_NOT_CONFIGURED
            AuthDecision.WRONG_PACKAGE -> BridgeProtocol.RESULT_WRONG_PACKAGE
            AuthDecision.WRONG_CERT -> BridgeProtocol.RESULT_WRONG_CERT
            AuthDecision.NOT_APPROVED -> BridgeProtocol.RESULT_NOT_APPROVED
            AuthDecision.ALLOWED ->
                if (ControlAccessibilityService.instance == null) BridgeProtocol.RESULT_ACCESSIBILITY_OFF
                else BridgeProtocol.RESULT_OK
        }
    }

    private fun reply(to: Messenger, code: Int) {
        try {
            to.send(Message.obtain(null, BridgeProtocol.REPLY_BEGIN, code, 0))
        } catch (_: android.os.RemoteException) {
            // The caller is gone; nothing to tell.
        }
    }

    companion object {
        private const val BEGIN_COST = 20.0 // a BEGIN costs package and certificate lookups

        /** Set while this service exists, so the settings screen can end a live session on revoke. */
        @Volatile var instance: ControlBridgeService? = null
            private set
    }
}
