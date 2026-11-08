package app.linksy.mg.control

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import app.linksy.mg.control.core.AuthDecision

/**
 * Exported bound service for the Linksy app. Binding itself grants nothing: the uid of each
 * message's sender (Message.sendingUid, set by the kernel-backed Binder, not by the caller) is
 * checked at BEGIN against package + pinned certificate + user approval, and only that uid may
 * then INJECT or END. Binder.getCallingUid() in onBind is not usable for this, hence per message.
 */
class ControlBridgeService : Service() {
    private var authorizedUid = NO_UID

    private val handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                BridgeProtocol.MSG_BEGIN -> begin(msg)
                BridgeProtocol.MSG_END -> if (isSession(msg)) endSession()
                BridgeProtocol.MSG_INJECT -> if (isSession(msg)) {
                    msg.data?.getString(BridgeProtocol.KEY_JSON)?.let { ControlAccessibilityService.instance?.inject(it) }
                }
            }
        }
    }

    private val messenger = Messenger(handler)

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onUnbind(intent: Intent?): Boolean {
        endSession()
        return false
    }

    override fun onDestroy() {
        endSession()
        super.onDestroy()
    }

    private fun isSession(msg: Message) = authorizedUid != NO_UID && msg.sendingUid == authorizedUid

    private fun begin(msg: Message) {
        val code = authorize(msg.sendingUid)
        reply(msg, code)
        if (code != BridgeProtocol.RESULT_OK) return
        endSession() // a new session replaces any old one, closing a held stroke first
        authorizedUid = msg.sendingUid
        ControlAccessibilityService.instance?.beginSession()
    }

    private fun endSession() {
        if (authorizedUid == NO_UID) return
        authorizedUid = NO_UID
        ControlAccessibilityService.instance?.endSession()
    }

    private fun authorize(uid: Int): Int {
        val auth = Graph.auth(this)
        val pkg = if (uid >= 0) CallerIdentity.packageOfUid(this, uid) else null
        val decision = auth.check(pkg ?: "", pkg?.let { CallerIdentity.certsOf(this, it) } ?: emptyList())
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

    private fun reply(msg: Message, code: Int) {
        val to = msg.replyTo ?: return
        try {
            to.send(Message.obtain(null, BridgeProtocol.REPLY_BEGIN, code, 0))
        } catch (_: android.os.RemoteException) {
            // The caller is gone; nothing to tell.
        }
    }

    private companion object {
        const val NO_UID = -1
    }
}
