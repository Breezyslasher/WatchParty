package io.github.breezyslasher.watchparty

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import kotlin.concurrent.thread

/** The library's order: sent as `sort=`, and dropped for a server that rejects it. */
class YamtrackSortTest {
    private val page = """{"pagination":{"total":1},"results":[{"item":{"media_id":"27205","source":"tmdb",
        "media_type":"movie","title":"Inception","image":""},"status":"In progress"}]}"""

    /** A tiny HTTP server on a plain socket (Android's unit tests can't see the JDK's HttpServer). */
    private class Server(private val rejectSort: Boolean, private val body: String) : AutoCloseable {
        val seen: MutableList<String> = Collections.synchronizedList(mutableListOf())
        private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val port get() = socket.localPort

        init {
            thread(isDaemon = true) {
                while (!socket.isClosed) {
                    val conn = try { socket.accept() } catch (e: Exception) { break }
                    conn.use { c ->
                        val reader = c.getInputStream().bufferedReader()
                        val path = reader.readLine()?.split(' ')?.getOrNull(1) ?: ""
                        while (reader.readLine()?.isNotEmpty() == true) { /* headers */ }
                        val query = path.substringAfter('?', "")
                        seen += query
                        val (code, text) = if (rejectSort && "sort=" in query) 400 to """{"detail":"Invalid sorting"}""" else 200 to body
                        val bytes = text.toByteArray()
                        c.getOutputStream().apply {
                            write(("HTTP/1.1 $code X\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\n" +
                                "Connection: close\r\n\r\n").toByteArray())
                            write(bytes)
                            flush()
                        }
                    }
                }
            }
        }

        override fun close() = socket.close()
    }

    @Test fun sendsTheOrder() {
        Server(rejectSort = false, body = page).use { server ->
            val client = YamtrackClient("http://127.0.0.1:${server.port}", "token")
            val p = client.library("movie", YamtrackClient.IN_PROGRESS, 0, sort = "updated_desc")
            assertEquals("Inception", p.items.single().title)
            assertEquals(listOf("limit=40&offset=0&status=1&sort=updated_desc"), server.seen.toList())
        }
    }

    @Test fun anOlderServerIsAskedAgainWithoutIt() {
        Server(rejectSort = true, body = page).use { server ->
            val client = YamtrackClient("http://127.0.0.1:${server.port}", "token")
            assertEquals(1, client.library("tv", null, 40, sort = "updated_desc").items.size)
            assertEquals(listOf("limit=40&offset=40&sort=updated_desc", "limit=40&offset=40"), server.seen.toList())
        }
    }

    @Test fun recentlyUpdatedIsTheDefault() {
        assertEquals("updated_desc" to "Recently updated", YamtrackClient.SORTS.first())
    }
}
