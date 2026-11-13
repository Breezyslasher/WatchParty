package dev.watchparty.setup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log

/** Results of the install session committed by [SetupActivity]. */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        Log.i("WatchPartySetup", "install status $status: $message")
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Android asks the viewer to confirm installs by other apps
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) {
                    SetupActivity.lastResult = "Confirm the install on screen."
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            PackageInstaller.STATUS_SUCCESS -> SetupActivity.lastResult =
                "Installed. Open Watch Party and press Grant access — the switch will work now."
            else -> SetupActivity.lastResult = "Install didn't finish (status $status${
                if (message != null) ": $message" else ""})."
        }
    }
}
