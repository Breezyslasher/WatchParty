package dev.watchparty.tv

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The Watch Party relay protocol (v3), as spoken by the Kodi addon's
 * client.py: JSON POSTs to /join, /poll, /command and /leave, each carrying
 * the room code; every response carries server_time, used to keep an
 * EMA-smoothed estimate of the relay's clock.
 */
class RelayClient(baseUrl: String, private val room: String) {

    class RelayException(message: String, val status: Int = 0) : IOException(message)

    private val base: String = normalizeBase(baseUrl)
    var memberId: String? = null
        private set
    private var clockOffsetSec = 0.0
    private var offsetSamples = 0

    /** The relay's clock, in seconds. */
    fun serverNowSec(): Double = System.currentTimeMillis() / 1000.0 + clockOffsetSec

    fun join(name: String): RoomState {
        val data = post("/join", JSONObject().put("name", name))
        memberId = data.getString("member_id")
        return parseState(data.getJSONObject("state"))
    }

    fun leave() {
        val id = memberId ?: return
        try {
            post("/leave", JSONObject().put("member_id", id))
        } catch (e: IOException) {
            // leaving is best-effort; the relay prunes silent members anyway
        }
        memberId = null
    }

    fun poll(positionSec: Double, paused: Boolean, file: String, caching: Boolean,
             onItem: Boolean, corrections: Int): RoomState {
        val body = JSONObject()
            .put("member_id", memberId)
            .put("position", positionSec)
            .put("paused", paused)
            .put("file", file)
            .put("caching", caching)
            .put("on_item", onItem)
            .put("corrections", corrections)
        return parseState(post("/poll", body).getJSONObject("state"))
    }

    fun command(cmd: String, positionSec: Double, item: JSONObject? = null): RoomState {
        val body = JSONObject()
            .put("member_id", memberId)
            .put("cmd", cmd)
            .put("position", positionSec)
        if (item != null) body.put("item", item)
        return parseState(post("/command", body).getJSONObject("state"))
    }

    private fun post(path: String, body: JSONObject): JSONObject {
        body.put("room", room)
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        val conn = URL(base + path).openConnection() as HttpURLConnection
        val t0 = System.currentTimeMillis()
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 5_000
            conn.readTimeout = 5_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("User-Agent", "WatchParty/1.0 (Android TV)")
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            val t1 = System.currentTimeMillis()
            val data = try {
                JSONObject(text)
            } catch (e: Exception) {
                throw RelayException("relay sent HTTP $status, not JSON", status)
            }
            updateOffset(data.optDouble("server_time", 0.0), t0, t1)
            if (!data.optBoolean("ok", false)) {
                throw RelayException(data.optString("error", "relay error (HTTP $status)"), status)
            }
            return data
        } finally {
            conn.disconnect()
        }
    }

    private fun updateOffset(serverTime: Double, t0: Long, t1: Long) {
        if (serverTime <= 0.0) return
        // assume the relay stamped the reply halfway through the round trip
        val sample = serverTime - (t0 + t1) / 2000.0
        clockOffsetSec = if (offsetSamples == 0) sample else 0.8 * clockOffsetSec + 0.2 * sample
        offsetSamples++
    }

    private fun parseState(s: JSONObject): RoomState {
        val itemJson = s.optJSONObject("item")
        val item = itemJson?.let {
            val ids = it.optJSONObject("ids")
            val pkg = ids?.optString("android", "")?.takeIf { p -> p.isNotEmpty() }
            Item(
                file = it.optString("file", ""),
                label = it.optString("label", ""),
                androidPackage = pkg,
            )
        }
        return RoomState(
            seq = s.optLong("seq", 0),
            item = item,
            positionSec = s.optDouble("position", 0.0),
            paused = s.optBoolean("paused", false),
            speed = s.optDouble("speed", 1.0),
            setAtSec = s.optDouble("set_at", 0.0),
            setByMe = memberId != null && s.optString("set_by", "") == memberId,
        )
    }

    companion object {
        /** "relay.example.com", "192.168.1.5:8765" or a full URL → base URL. */
        fun normalizeBase(raw: String): String {
            var url = raw.trim().trimEnd('/')
            if (!url.startsWith("http://") && !url.startsWith("https://")) url = "http://$url"
            return url
        }

        /** The relay item for an Android app link. Kodi members skip it: they
         *  can't play http(s) links, and ids.android means nothing to them. */
        fun appItem(link: String, label: String, pkg: String): JSONObject =
            JSONObject()
                .put("file", link)
                .put("label", label)
                .put("title", label)
                .put("ids", JSONObject().put("android", pkg))
    }
}
