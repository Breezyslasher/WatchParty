package dev.watchparty.sessionprobe

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.text.TextUtils
import android.util.Log

/**
 * Remote control from adb, without bringing Session Probe to the front — so
 * the app being tested (which may stop when it leaves the screen) keeps
 * playing. This is the same call a watch party app would make:
 *
 *   adb shell am broadcast -n dev.watchparty.sessionprobe/.ControlReceiver --es cmd seek --es to 1:00:00
 *
 * cmd: status | play | pause | seek (--es to h:mm:ss) | skip (--es by ±seconds)
 * Optional --es pkg <package> picks the app; otherwise the one that's playing.
 *
 * Guarded by android.permission.DUMP, which adb's shell holds and ordinary
 * apps can't get, so only adb can drive it.
 */
class ControlReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val reply = try {
            handle(context, intent)
        } catch (e: Exception) {
            "error: $e"
        }
        Log.i("SessionProbe", "control: $reply")
        resultData = reply   // printed by `am broadcast` as data="..."
    }

    private fun handle(context: Context, intent: Intent): String {
        val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            ?: return "error: no media session service"
        val sessions = try {
            manager.getActiveSessions(ComponentName(context, ProbeListenerService::class.java))
        } catch (e: SecurityException) {
            return "error: notification access not granted (see README)"
        }
        val pkg = intent.getStringExtra("pkg")
        val candidates = sessions.filter {
            it.packageName != context.packageName && (pkg == null || it.packageName == pkg)
        }
        val c = candidates.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: candidates.firstOrNull()
            ?: return "error: no media session" + (if (pkg != null) " from $pkg" else "") +
                " — is something playing?"

        val s = c.playbackState
        val now = if (s != null) positionOf(s) else -1L
        val title = c.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "(no title)"
        val where = "${c.packageName} \"$title\""
        val controls = c.transportControls

        return when (val cmd = intent.getStringExtra("cmd") ?: "status") {
            "status" -> "$where: ${stateName(s?.state ?: PlaybackState.STATE_NONE)} at ${fmt(now)}"
            "play" -> { controls.play(); "$where: play (was ${fmt(now)})" }
            "pause" -> { controls.pause(); "$where: pause at ${fmt(now)}" }
            "seek" -> {
                val to = parseTime(intent.getStringExtra("to"))
                    ?: return "error: seek needs --es to h:mm:ss (or mm:ss, or seconds)"
                controls.seekTo(to)
                "$where: seek ${fmt(now)} -> ${fmt(to)}"
            }
            "skip" -> {
                val by = intent.getStringExtra("by")?.trim()?.removePrefix("+")?.toLongOrNull()
                    ?: return "error: skip needs --es by <seconds>, e.g. 600 or -30"
                if (now < 0) return "error: $where reports no position to skip from"
                val to = (now + by * 1000).coerceAtLeast(0)
                controls.seekTo(to)
                "$where: skip ${by}s ${fmt(now)} -> ${fmt(to)}"
            }
            "meta" -> describeEverything(c)
            else -> "error: unknown cmd '$cmd' (status, play, pause, seek, skip, meta)"
        }
    }

    /**
     * Everything the app publishes about what's playing — to find out which
     * apps expose a content id a watch party could rebuild a link from.
     * Also written to logcat line by line (adb logcat -s SessionProbe).
     */
    private fun describeEverything(c: android.media.session.MediaController): String {
        val out = StringBuilder("${c.packageName}\n")
        val md = c.metadata
        if (md == null) {
            out.append("  metadata: none\n")
        } else {
            for (key in md.keySet().sorted()) {
                val text = md.getText(key)
                val value = when {
                    text != null -> "\"$text\""
                    md.getBitmap(key) != null -> md.getBitmap(key).let { "<image ${it.width}x${it.height}>" }
                    md.getRating(key) != null -> "rating ${md.getRating(key)}"
                    else -> md.getLong(key).toString()
                }
                out.append("  metadata ").append(key).append(" = ").append(value).append('\n')
            }
            val d = md.description
            out.append("  description.mediaId = ").append(d.mediaId).append('\n')
            if (android.os.Build.VERSION.SDK_INT >= 23) {
                out.append("  description.mediaUri = ").append(d.mediaUri).append('\n')
            }
            appendBundle(out, "description.extras", d.extras)
        }
        val s = c.playbackState
        if (s != null) {
            out.append("  state.activeQueueItemId = ").append(s.activeQueueItemId).append('\n')
            if (android.os.Build.VERSION.SDK_INT >= 22) appendBundle(out, "state.extras", s.extras)
            for (a in s.customActions) {
                out.append("  state.customAction ").append(a.action).append(" \"").append(a.name).append("\"\n")
            }
        }
        appendBundle(out, "session.extras", c.extras)
        out.append("  queueTitle = ").append(c.queueTitle).append('\n')
        val queue = c.queue
        if (queue != null) {
            out.append("  queue: ").append(queue.size).append(" items\n")
            for (q in queue.take(5)) {
                out.append("    #").append(q.queueId).append(" mediaId=").append(q.description.mediaId)
                    .append(if (android.os.Build.VERSION.SDK_INT >= 23) " uri=${q.description.mediaUri}" else "")
                    .append(" title=\"").append(q.description.title).append("\"\n")
            }
        }
        out.append("  sessionActivity = ").append(c.sessionActivity?.creatorPackage).append('\n')
        val text = out.toString()
        for (line in TextUtils.split(text, "\n")) if (line.isNotEmpty()) Log.i("SessionProbe", "meta: $line")
        return text
    }

    /** Another app's extras can hold its own classes, which can't be decoded
     *  here — report that instead of failing the whole dump. */
    private fun appendBundle(out: StringBuilder, name: String, b: android.os.Bundle?) {
        try {
            if (b == null || b.isEmpty) {
                out.append("  ").append(name).append(": none\n")
                return
            }
            for (key in b.keySet().sorted()) {
                val value = try {
                    @Suppress("DEPRECATION") b.get(key).toString()
                } catch (e: Exception) {
                    "<unreadable: ${e.javaClass.simpleName}>"
                }
                out.append("  ").append(name).append(' ').append(key).append(" = ").append(value).append('\n')
            }
        } catch (e: Exception) {
            out.append("  ").append(name).append(": <unreadable: ").append(e.javaClass.simpleName).append(">\n")
        }
    }
}

/** "1:05:30", "65:30" or "3930" (seconds) → milliseconds. */
internal fun parseTime(text: String?): Long? {
    if (text.isNullOrBlank()) return null
    var seconds = 0L
    for (part in TextUtils.split(text.trim(), ":")) {
        val n = part.toLongOrNull() ?: return null
        if (n < 0) return null
        seconds = seconds * 60 + n
    }
    return seconds * 1000
}
