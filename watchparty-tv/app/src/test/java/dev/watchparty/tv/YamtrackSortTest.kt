package dev.watchparty.tv

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetSocketAddress

/** The library's order: sent as `sort=`, and dropped for a server that rejects it. */
class YamtrackSortTest {
    private val page = """{"pagination":{"total":1},"results":[{"item":{"media_id":"27205","source":"tmdb",
        "media_type":"movie","title":"Inception","image":""},"status":"In progress"}]}"""

    private fun serve(rejectSort: Boolean, seen: MutableList<String>): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                val query = ex.requestURI.rawQuery ?: ""
                seen += query
                val (code, body) = if (rejectSort && "sort=" in query) 400 to """{"detail":"Invalid sorting"}""" else 200 to page
                val bytes = body.toByteArray()
                ex.sendResponseHeaders(code, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            start()
        }

    @Test fun sendsTheOrder() {
        val seen = mutableListOf<String>()
        val server = serve(rejectSort = false, seen = seen)
        try {
            val client = YamtrackClient("http://127.0.0.1:${server.address.port}", "token")
            val p = client.library("movie", YamtrackClient.IN_PROGRESS, 0, sort = "updated_desc")
            assertEquals("Inception", p.items.single().title)
            assertEquals(listOf("limit=40&offset=0&status=1&sort=updated_desc"), seen)
        } finally {
            server.stop(0)
        }
    }

    @Test fun anOlderServerIsAskedAgainWithoutIt() {
        val seen = mutableListOf<String>()
        val server = serve(rejectSort = true, seen = seen)
        try {
            val client = YamtrackClient("http://127.0.0.1:${server.address.port}", "token")
            assertEquals(1, client.library("tv", null, 40, sort = "updated_desc").items.size)
            assertEquals(listOf("limit=40&offset=40&sort=updated_desc", "limit=40&offset=40"), seen)
        } finally {
            server.stop(0)
        }
    }

    @Test fun recentlyUpdatedIsTheDefault() {
        assertEquals("updated_desc" to "Recently updated", YamtrackClient.SORTS.first())
    }
}
