package app.linksy.mg.control

import android.content.Context
import android.content.pm.PackageManager
import app.linksy.mg.control.core.ApprovalStore
import app.linksy.mg.control.core.CallerAuth
import java.security.MessageDigest

/** SharedPreferences-backed approvals (private to this app). */
class PrefsApprovalStore(context: Context) : ApprovalStore {
    private val prefs = context.applicationContext.getSharedPreferences("approvals", Context.MODE_PRIVATE)

    override fun isApproved(pkg: String, certSha256: String) =
        prefs.getString(pkg, null) == CallerAuth.normalizeHex(certSha256)

    override fun approve(pkg: String, certSha256: String) {
        prefs.edit().putString(pkg, CallerAuth.normalizeHex(certSha256)).apply()
    }

    override fun revoke(pkg: String) {
        prefs.edit().remove(pkg).apply()
    }

    fun approvedPackages(): Set<String> = prefs.all.keys
}

object Graph {
    fun auth(context: Context) = CallerAuth(BuildConfig.LINKSY_PACKAGE, BuildConfig.LINKSY_CERT_SHA256, approvals(context))
    fun approvals(context: Context) = PrefsApprovalStore(context)
}

/** Resolves who a caller really is from the system, never from anything the caller says. */
object CallerIdentity {
    /** The single package of [uid], or null (shared uids and unknown uids fail closed). */
    fun packageOfUid(context: Context, uid: Int): String? =
        context.packageManager.getPackagesForUid(uid)?.singleOrNull()

    /** SHA-256 hex of the package's current signing certificate(s); empty if unknown. */
    fun certsOf(context: Context, pkg: String): List<String> {
        val info = try {
            context.packageManager.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
        } catch (_: PackageManager.NameNotFoundException) {
            return emptyList()
        }
        val signers = info.signingInfo?.apkContentsSigners ?: return emptyList()
        return signers.map { sig ->
            MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }
}
