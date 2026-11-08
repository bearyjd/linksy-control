package app.linksy.mg.control

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.widget.Toast
import app.linksy.mg.control.core.AuthDecision

/**
 * One-time approval. The caller is taken from getCallingPackage() (set by the system for
 * startActivityForResult), then checked for the pinned package and certificate before the user
 * is even asked. Nothing is stored unless the user taps Allow.
 */
class PairActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pkg = callingPackage
        val certs = pkg?.let { CallerIdentity.certsOf(this, it) } ?: emptyList()
        val auth = Graph.auth(this)
        if (pkg == null || auth.checkIdentity(pkg, certs) != AuthDecision.ALLOWED) {
            Toast.makeText(this, R.string.pair_rejected, Toast.LENGTH_LONG).show()
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        // Tapjacking: ignore touches while another window covers this one (activity and dialog).
        window.decorView.filterTouchesWhenObscured = true
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.pair_title)
            .setMessage(getString(R.string.pair_message, pkg))
            .setPositiveButton(R.string.pair_allow) { _, _ ->
                Graph.approvals(this).approve(pkg, auth.matchedCert(certs) ?: return@setPositiveButton)
                setResult(RESULT_OK)
                finish()
            }
            .setNegativeButton(R.string.pair_deny) { _, _ ->
                setResult(RESULT_CANCELED)
                finish()
            }
            .setOnCancelListener {
                setResult(RESULT_CANCELED)
                finish()
            }
            .create()
        dialog.window?.decorView?.filterTouchesWhenObscured = true
        dialog.show()
    }
}
