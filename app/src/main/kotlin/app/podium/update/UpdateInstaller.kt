package app.podium.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import app.podium.PodiumApplication
import java.io.File

/** Installing a downloaded update (D-99); tests replace it. */
internal interface UpdateInstaller {
    /** Whether Android lets Podium install apps (the listener allows it once, in Android's settings). */
    fun allowed(): Boolean

    /** Android's page where the listener allows it. */
    fun permissionPage(): Intent

    /** What the downloaded file is, as Android reads it. */
    fun inspect(file: File): ApkCheck

    /** Hands the file to Android's installer, which asks the listener to confirm. */
    fun install(file: File)
}

internal enum class ApkCheck {
    /** This app, newer, signed with the same key: Android will take it as an update. */
    UPDATE,

    /** Another app, or this one signed with another key: Android would refuse it. */
    OTHER_APP,

    /** This app, but not newer than what's installed. */
    NOT_NEWER,

    /** Not an app Android can read. */
    UNREADABLE,
}

/**
 * Android's own package installer, through a session (D-99). Before it's handed over, the file must
 * be this very app, newer than the one installed, and signed by the same key, so a download that
 * isn't Podium's never reaches the installer. Android asks the listener to confirm the update.
 */
internal class PackageUpdateInstaller(context: Context) : UpdateInstaller {
    private val app = context.applicationContext
    private val pm get() = app.packageManager

    override fun allowed(): Boolean = pm.canRequestPackageInstalls()

    override fun permissionPage(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))

    override fun inspect(file: File): ApkCheck {
        val archive = runCatching { pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES) }.getOrNull()
            ?: return ApkCheck.UNREADABLE
        if (archive.packageName != app.packageName) return ApkCheck.OTHER_APP
        val signers = archive.signingInfo?.let { if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory }
        if (signers.isNullOrEmpty()) return ApkCheck.UNREADABLE
        val sameKey = signers.any { pm.hasSigningCertificate(app.packageName, it.toByteArray(), PackageManager.CERT_INPUT_RAW_X509) }
        if (!sameKey) return ApkCheck.OTHER_APP
        val installed = pm.getPackageInfo(app.packageName, 0).longVersionCode
        return if (archive.longVersionCode > installed) ApkCheck.UPDATE else ApkCheck.NOT_NEWER
    }

    override fun install(file: File) {
        val installer = pm.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            setSize(file.length())
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite(Releases.APK_NAME, 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out, 64 * 1024) }
                session.fsync(out)
            }
            val status = Intent(app, UpdateInstallReceiver::class.java).setAction(ACTION_STATUS)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            session.commit(PendingIntent.getBroadcast(app, id, status, flags).intentSender)
        }
    }

    companion object {
        const val ACTION_STATUS = "app.podium.update.INSTALL_STATUS"
    }
}

/**
 * What Android's installer says about an update (D-99): it needs the listener's confirmation (its
 * dialog is shown), it failed or was cancelled (the notice says so), or it worked (Android then
 * restarts Podium as the new version, so nothing comes back).
 */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != PackageUpdateInstaller.ACTION_STATUS) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val updates = (context.applicationContext as? PodiumApplication)?.graph?.updates
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm != null) {
                    runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        .onFailure { updates?.installFinished(InstallOutcome.FAILED, null) }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> updates?.installFinished(InstallOutcome.DONE, null)
            PackageInstaller.STATUS_FAILURE_ABORTED -> updates?.installFinished(InstallOutcome.CANCELLED, null)
            else -> {
                Log.w("PodiumUpdate", "install failed: status $status")
                updates?.installFinished(InstallOutcome.FAILED, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
            }
        }
    }
}

internal enum class InstallOutcome { DONE, CANCELLED, FAILED }
