package app.linksy.mg.control

/**
 * The contract between the Linksy app and this app (a Messenger over a bound service).
 *
 * Bind: explicit component app.linksy.mg.control/app.linksy.mg.control.ControlBridgeService,
 * action [ACTION_BIND]. Pair (once): startActivityForResult with action [ACTION_PAIR] on
 * app.linksy.mg.control/app.linksy.mg.control.PairActivity; RESULT_OK = approved.
 * Messages (Message.what): [MSG_BEGIN], [MSG_END], [MSG_INJECT] with Bundle string [KEY_JSON]
 * holding one Linksy ControlMsg JSON. BEGIN is answered on Message.replyTo with
 * what=[REPLY_BEGIN], arg1=a RESULT_* code.
 */
object BridgeProtocol {
    const val ACTION_BIND = "app.linksy.mg.control.BIND"
    const val ACTION_PAIR = "app.linksy.mg.control.PAIR"

    const val MSG_BEGIN = 1
    const val MSG_END = 2
    const val MSG_INJECT = 3
    const val REPLY_BEGIN = 100
    const val KEY_JSON = "json"

    const val RESULT_OK = 0
    const val RESULT_DISABLED_NOT_CONFIGURED = 1
    const val RESULT_WRONG_PACKAGE = 2
    const val RESULT_WRONG_CERT = 3
    const val RESULT_NOT_APPROVED = 4
    const val RESULT_ACCESSIBILITY_OFF = 5
}
