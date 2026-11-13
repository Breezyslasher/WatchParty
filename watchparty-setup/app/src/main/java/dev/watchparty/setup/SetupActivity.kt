package dev.watchparty.setup

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Installs Watch Party the way app stores do, so Android 13+ doesn't block
 * its notification access.
 *
 * Android 14's system installer labels an APK opened from a file or
 * transfer app as PACKAGE_SOURCE_LOCAL_FILE / DOWNLOADED_FILE
 * (InstallStaging.java), and InstallPackageHelper then sets
 * OP_ACCESS_RESTRICTED_SETTINGS to MODE_ERRORED for it — the greyed-out
 * switch. An app's own PackageInstaller session keeps the source it asks
 * for, PACKAGE_SOURCE_UNSPECIFIED by default, which is not restricted. The
 * flag is never cleared on update, so a blocked copy is removed first.
 */
class SetupActivity : Activity() {

    companion object {
        const val WATCH_PARTY = "dev.watchparty.tv"
        private const val BUNDLED_APK = "WatchPartyTV.apk"

        /** Set by [InstallResultReceiver]; shown on the next refresh. */
        @Volatile var lastResult: String? = null
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var allowButton: Button
    private lateinit var removeButton: Button
    private lateinit var installButton: Button
    private lateinit var openButton: Button

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 40, 64, 40)
        }
        root.addView(text("Watch Party Setup", 26f, bold = true))
        root.addView(text(
            "Installs Watch Party so you can switch on its access to what's playing. " +
                "Android blocks that switch for apps installed from a file or transfer app " +
                "(like Send Files to TV); apps installed by another app, the way app stores " +
                "do it, aren't blocked.", 16f))
        status = text("", 17f)
        root.addView(status)
        allowButton = button("1. Allow Setup to install apps") { openInstallPermission() }
        removeButton = button("2. Remove the blocked Watch Party") { removeWatchParty() }
        installButton = button("3. Install Watch Party") { installWatchParty() }
        openButton = button("Open Watch Party") { openWatchParty() }
        for (b in listOf(allowButton, removeButton, installButton, openButton)) root.addView(b)
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    // ── state ───────────────────────────────────────────────────────────

    private fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < 26 || packageManager.canRequestPackageInstalls()

    private fun installedVersion(): String? = try {
        packageManager.getPackageInfo(WATCH_PARTY, 0).versionName ?: "?"
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    /** Who installed the current copy (null if unknown). Our own installs
     *  are the unblocked ones. */
    private fun installedBy(): String? = try {
        if (Build.VERSION.SDK_INT >= 30) {
            packageManager.getInstallSourceInfo(WATCH_PARTY).installingPackageName
        } else {
            @Suppress("DEPRECATION") packageManager.getInstallerPackageName(WATCH_PARTY)
        }
    } catch (e: Exception) {
        null
    }

    private fun refresh() {
        val installed = installedVersion()
        val by = installedBy()
        // adb install uses PACKAGE_SOURCE_OTHER (not restricted) and shows as the shell
        val ours = installed != null && (by == packageName || by == "com.android.shell")
        val lines = StringBuilder()
        when {
            installed == null -> lines.append("Watch Party isn't installed.")
            ours -> lines.append("Watch Party $installed is installed by ")
                .append(if (by == packageName) "Setup" else "adb")
                .append(" — its access switch isn't blocked.")
            else -> lines.append("Watch Party $installed was installed by ")
                .append(installedBy() ?: "another app")
                .append(". On Android 13+ that copy's access switch is blocked; remove it, then install it here.")
        }
        lastResult?.let { lines.append("\n").append(it) }
        status.text = lines.toString()

        val needsRemoval = installed != null && !ours && Build.VERSION.SDK_INT >= 33
        allowButton.visibility = if (canInstall()) View.GONE else View.VISIBLE
        removeButton.visibility = if (needsRemoval) View.VISIBLE else View.GONE
        installButton.visibility = if (canInstall() && !needsRemoval) View.VISIBLE else View.GONE
        installButton.text = if (installed == null) "3. Install Watch Party" else "Update Watch Party"
        openButton.visibility = if (installed != null && !needsRemoval) View.VISIBLE else View.GONE
    }

    // ── steps ───────────────────────────────────────────────────────────

    /** The per-app "Install unknown apps" switch (not a restricted setting).
     *  Some TV builds lack the per-app page, so fall back like Vita_plex. */
    private fun openInstallPermission() {
        if (Build.VERSION.SDK_INT < 26) return
        val candidates = listOf(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")),
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES),
            Intent(Settings.ACTION_SECURITY_SETTINGS),
        )
        val intent = candidates.firstOrNull { it.resolveActivity(packageManager) != null }
        if (intent == null) {
            lastResult = "This TV has no screen for allowing app installs."
            return
        }
        startActivity(intent)
    }

    private fun removeWatchParty() {
        lastResult = "After removing it, press Install. You'll need to re-enter the relay and room."
        @Suppress("DEPRECATION")
        startActivity(Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.parse("package:$WATCH_PARTY")))
    }

    private fun installWatchParty() {
        lastResult = "Installing…"
        try {
            val installer = packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(WATCH_PARTY)
            // packageSource left at its default, PACKAGE_SOURCE_UNSPECIFIED: not restricted
            val id = installer.createSession(params)
            installer.openSession(id).use { session ->
                assets.open(BUNDLED_APK).use { input ->
                    session.openWrite("base.apk", 0, -1).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
                val callback = Intent(this, InstallResultReceiver::class.java)
                // the installer fills in the result, so the PendingIntent must be mutable
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                session.commit(PendingIntent.getBroadcast(this, id, callback, flags).intentSender)
            }
        } catch (e: Exception) {
            lastResult = "Install failed: $e"
        }
    }

    private fun openWatchParty() {
        val intent = packageManager.getLeanbackLaunchIntentForPackage(WATCH_PARTY)
            ?: packageManager.getLaunchIntentForPackage(WATCH_PARTY)
        if (intent != null) startActivity(intent) else lastResult = "Couldn't open Watch Party."
    }

    // ── views ───────────────────────────────────────────────────────────

    private fun text(s: String, size: Float, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 12, 0, 8)
    }

    private fun button(caption: String, onClick: () -> Unit) = Button(this).apply {
        text = caption
        setAllCaps(false)
        setOnClickListener { onClick() }
    }
}
