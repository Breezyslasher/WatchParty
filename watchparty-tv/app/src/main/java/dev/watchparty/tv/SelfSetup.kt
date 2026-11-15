package dev.watchparty.tv

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import java.io.File
import java.net.ConnectException
import java.net.SocketTimeoutException

/**
 * "Set up access" (automatic part): gives this app notification access (and log
 * access, for sharing Disney+ titles) without a computer. Android only lets
 * the user or adb grant these, so the app connects to the TV's own adb daemon
 * — the user turns on debugging and accepts the TV's "Allow debugging?"
 * prompt — and runs the same commands a computer would.
 */
object SelfSetup {

    const val DEFAULT_PORT = 5555

    /** The shell commands that grant this app what it needs on [sdk]. */
    fun commands(packageName: String, listener: String, sdk: Int, enabledListeners: String?): List<String> {
        val list = ArrayList<String>()
        if (sdk >= 28) {
            list.add("cmd notification allow_listener $listener")
        } else {
            val current = (enabledListeners ?: "").trim()
            val value = if (current.isEmpty()) listener else "$current:$listener"
            list.add("settings put secure enabled_notification_listeners $value")
        }
        list.add("pm grant $packageName android.permission.READ_LOGS")
        return list
    }

    /** Ports adbd may listen on: the configured one(s), then the usual 5555. */
    fun ports(configured: List<String>): List<Int> {
        val ports = ArrayList<Int>()
        for (value in configured) {
            val port = value.trim().toIntOrNull() ?: continue
            if (port in 1..65535 && port !in ports) ports.add(port)
        }
        if (DEFAULT_PORT !in ports) ports.add(DEFAULT_PORT)
        return ports
    }

    /** Lines of command output that mean the command failed. */
    fun failures(outputs: List<String>): List<String> {
        val bad = ArrayList<String>()
        for (output in outputs) {
            val text = output.trim()
            if (text.isEmpty()) continue
            val lower = text.lowercase()
            if ("exception" in lower || "error" in lower || "unknown" in lower || "not found" in lower ||
                "denied" in lower || "usage:" in lower
            ) {
                bad.add(text)
            }
        }
        return bad
    }

    fun hasAccess(context: Context): Boolean {
        val component = ComponentName(context, SessionAccessService::class.java)
        val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: ""
        return TextUtils.split(enabled, ":").any { ComponentName.unflattenFromString(it) == component }
    }

    fun debuggingOn(context: Context): Boolean =
        Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1

    fun developerOptionsOn(context: Context): Boolean =
        Settings.Global.getInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1

    /**
     * Runs the setup, reporting progress through [status]. Blocking: call it
     * off the main thread. Returns whether the app has notification access
     * afterwards.
     */
    fun run(context: Context, quietWhenDebuggingOff: Boolean = false, status: (String) -> Unit): Boolean {
        if (hasAccess(context)) {
            status("Watch Party already has access.")
            return true
        }
        if (!debuggingOn(context)) {
            // TVs with a settings switch skip straight to it instead
            if (!quietWhenDebuggingOff) status(TURN_ON_DEBUGGING)
            return false
        }
        status("Connecting to the TV's debugging service…")
        val key = AdbKey.loadOrCreate(File(context.filesDir, "adbkey"))
        val listener = ComponentName(context, SessionAccessService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        val commands = commands(context.packageName, listener, Build.VERSION.SDK_INT, enabled)
        val client = AdbClient(key)

        val ports = ports(listOf(getprop("service.adb.tcp.port"), getprop("persist.adb.tcp.port")))
        var reached = false
        for (port in ports) {
            val outputs = try {
                client.runCommands("127.0.0.1", port, commands, onPrompt = { status(ACCEPT_PROMPT) })
            } catch (e: ConnectException) {
                continue  // nothing listening on this port
            } catch (e: SocketTimeoutException) {
                continue
            } catch (e: AdbException) {
                status("Setup stopped: ${e.message}.\n\n$TRY_AGAIN")
                return false
            } catch (e: Exception) {
                status("Setup stopped: ${e.javaClass.simpleName} ${e.message ?: ""}\n\n$TRY_AGAIN")
                return false
            }
            reached = true
            val failed = failures(outputs)
            if (hasAccess(context)) {
                status(DONE)
                return true
            }
            status(
                "The TV ran the setup but Watch Party still has no access." +
                    (if (failed.isEmpty()) "" else "\nThe TV said: ${failed.joinToString("\n")}") +
                    "\n\n$TRY_AGAIN",
            )
            return false
        }
        if (!reached) {
            val tls = getprop("service.adb.tls.port").trim().toIntOrNull() ?: 0
            status(
                "Couldn't reach this TV's debugging service." +
                    (if (tls > 0) "\nThis TV has Wireless debugging on, which needs a pairing code — " +
                        "not supported by automatic setup yet." else "") +
                    "\n\nIn Developer options, turn on “Network debugging” or “ADB over network” if your TV " +
                    "has it (on some TVs, USB debugging alone is enough), then press Set up access " +
                    "again. Or run this from a computer:\nadb shell cmd notification allow_listener $listener",
            )
        }
        return false
    }

    /** An Android system property, or "" (apps may read most of them). */
    private fun getprop(name: String): String = try {
        val process = Runtime.getRuntime().exec(arrayOf("getprop", name))
        val value = process.inputStream.bufferedReader().readText().trim()
        process.waitFor()
        value
    } catch (e: Exception) {
        ""
    }

    const val TURN_ON_DEBUGGING =
        "Setup needs debugging turned on, just for a moment:\n" +
            "1. Settings → Device Preferences → About: press OK on “Build” 7 times " +
            "(“You are now a developer”).\n" +
            "2. Back in Device Preferences → Developer options: turn on “USB debugging” " +
            "(and “Network debugging” if there is one).\n" +
            "3. Come back here and press Set up access."

    const val ACCEPT_PROMPT =
        "On the TV: choose “Always allow from this computer” and press OK " +
            "(the “Allow USB debugging?” message is Watch Party asking)."

    const val TRY_AGAIN =
        "Check that USB debugging is on in Developer options, then press Set up access again."

    const val DONE =
        "Done — Watch Party can now see and control what's playing.\n" +
            "You can turn USB debugging off again in Developer options."
}
