package app.linksy.mg.control

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Plain-language explanation, how to enable, and live status. */
class SettingsActivity : Activity() {
    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        setContentView(ScrollView(this).apply { addView(content) })
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        content.removeAllViews()
        title(getString(R.string.app_name))
        text(getString(R.string.intro))
        title(getString(R.string.how_title))
        text(getString(R.string.how_steps))
        button(R.string.open_accessibility) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        button(R.string.open_app_info) {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
            )
        }
        title(getString(R.string.status_title))
        val auth = Graph.auth(this)
        text(getString(if (auth.enabled) R.string.status_binding_on else R.string.status_binding_off))
        val service = ControlAccessibilityService.instance
        text(getString(if (service != null) R.string.status_service_on else R.string.status_service_off))
        text(getString(if (service?.isSessionActive() == true) R.string.status_session_on else R.string.status_session_off))
        val approvals = Graph.approvals(this)
        val approved = approvals.approvedPackages()
        if (approved.isEmpty()) text(getString(R.string.paired_none))
        approved.forEach { pkg ->
            text(getString(R.string.paired_app, pkg))
            button(R.string.revoke) {
                approvals.revoke(pkg)
                render()
            }
        }
    }

    private fun title(s: String) = content.addView(TextView(this).apply {
        text = s
        textSize = 20f
        setPadding(0, 24, 0, 8)
    })

    private fun text(s: String) = content.addView(TextView(this).apply {
        text = s
        textSize = 15f
        setPadding(0, 4, 0, 4)
    })

    private fun button(label: Int, onClick: () -> Unit) = content.addView(Button(this).apply {
        setText(label)
        setOnClickListener { onClick() }
    })
}
