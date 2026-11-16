package io.github.breezyslasher.watchparty

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.math.BigInteger
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Runs [AdbClient] against a fake adbd that follows the real protocol
 * (adb's protocol.txt / adbd auth), checking every packet the client sends.
 */
class AdbClientTest {

    private val key = AdbKey.generate()

    private enum class Auth { KNOWN_KEY, NEW_KEY, NONE, REJECT, TLS }

    /** What the fake adbd saw. */
    private class Seen {
        val commands = ArrayList<String>()
        var publicKey: String? = null
        val error = AtomicReference<Throwable?>(null)
    }

    private fun fakeAdbd(auth: Auth, seen: Seen, outputs: Map<String, String> = emptyMap()): ServerSocket {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        Thread {
            try {
                server.accept().use { socket ->
                    val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
                    val output = socket.getOutputStream()
                    val hello = AdbMessage.readFrom(input)
                    assertEquals(AdbMessage.CNXN, hello.command)
                    assertEquals(AdbMessage.VERSION, hello.arg0)
                    assertEquals("host::\u0000", String(hello.data))

                    when (auth) {
                        Auth.TLS -> {
                            AdbMessage(AdbMessage.STLS, 0x01000000, 0).writeTo(output)
                            return@use
                        }
                        Auth.NONE -> { }
                        else -> {
                            val token = ByteArray(20).also { SecureRandom().nextBytes(it) }
                            AdbMessage(AdbMessage.AUTH, AdbMessage.AUTH_TOKEN, 0, token).writeTo(output)
                            val signature = AdbMessage.readFrom(input)
                            assertEquals(AdbMessage.AUTH, signature.command)
                            assertEquals(AdbMessage.AUTH_SIGNATURE, signature.arg0)
                            assertSignatureValid(token, signature.data)
                            if (auth != Auth.KNOWN_KEY) {
                                // unknown key: ask again, the client then offers its public key
                                AdbMessage(AdbMessage.AUTH, AdbMessage.AUTH_TOKEN, 0, token).writeTo(output)
                                val publicKey = AdbMessage.readFrom(input)
                                assertEquals(AdbMessage.AUTH_RSAPUBLICKEY, publicKey.arg0)
                                seen.publicKey = String(publicKey.data)
                                if (auth == Auth.REJECT) return@use  // user pressed Cancel: adbd hangs up
                            }
                        }
                    }
                    AdbMessage(AdbMessage.CNXN, AdbMessage.VERSION, 1 shl 20, "device::ro.product.name=fake;\u0000".toByteArray())
                        .writeTo(output)

                    var remote = 100
                    while (true) {
                        val open = try { AdbMessage.readFrom(input) } catch (e: java.io.EOFException) { break }
                        assertEquals(AdbMessage.OPEN, open.command)
                        val service = String(open.data)
                        assertTrue(service.endsWith("\u0000"))
                        val command = service.removeSuffix("\u0000").removePrefix("shell:")
                        seen.commands.add(command)
                        val local = open.arg0
                        remote++
                        AdbMessage(AdbMessage.OKAY, remote, local).writeTo(output)
                        val text = outputs[command]
                        if (text != null) {
                            AdbMessage(AdbMessage.WRTE, remote, local, text.toByteArray()).writeTo(output)
                            val ack = AdbMessage.readFrom(input)
                            assertEquals(AdbMessage.OKAY, ack.command)
                            assertEquals(local, ack.arg0)
                            assertEquals(remote, ack.arg1)
                        }
                        AdbMessage(AdbMessage.CLSE, remote, local).writeTo(output)
                        val close = AdbMessage.readFrom(input)
                        assertEquals(AdbMessage.CLSE, close.command)
                        assertEquals(local, close.arg0)
                    }
                }
            } catch (e: Throwable) {
                seen.error.set(e)
            } finally {
                server.close()
            }
        }.start()
        return server
    }

    /** adbd's check: RSA_verify(NID_sha1, token) — PKCS#1 v1.5 over a SHA-1 DigestInfo of the token. */
    private fun assertSignatureValid(token: ByteArray, signature: ByteArray) {
        assertEquals(256, signature.size)
        val n = key.publicKey.modulus
        val block = AdbKey.unsignedBytes(BigInteger(1, signature).modPow(key.publicKey.publicExponent, n), 256, false)
        val digestInfo = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14,
        ) + token
        val expected = ByteArray(256)
        expected[1] = 1
        for (i in 2 until 256 - digestInfo.size - 1) expected[i] = 0xff.toByte()
        System.arraycopy(digestInfo, 0, expected, 256 - digestInfo.size, digestInfo.size)
        assertArrayEquals(expected, block)
    }

    private fun runAgainst(server: ServerSocket, commands: List<String>, prompts: AtomicInteger = AtomicInteger()) =
        AdbClient(key).runCommands("127.0.0.1", server.localPort, commands, onPrompt = { prompts.incrementAndGet() },
            replyTimeoutMs = 5_000, promptTimeoutMs = 5_000)

    @Test fun knownKeyRunsCommandsAndReturnsOutput() {
        val seen = Seen()
        val server = fakeAdbd(Auth.KNOWN_KEY, seen, mapOf("pm grant x y" to "", "echo hi" to "hi\n"))
        val prompts = AtomicInteger()
        val outputs = runAgainst(server, listOf("echo hi", "pm grant x y"), prompts)
        Thread.sleep(100)
        assertNull(seen.error.get())
        assertEquals(listOf("hi\n", ""), outputs)
        assertEquals(listOf("echo hi", "pm grant x y"), seen.commands)
        assertEquals(0, prompts.get())
        assertNull(seen.publicKey)
    }

    @Test fun newKeyIsOfferedInAdbFormatAndPromptReported() {
        val seen = Seen()
        val server = fakeAdbd(Auth.NEW_KEY, seen, mapOf("id" to "uid=2000(shell)\n"))
        val prompts = AtomicInteger()
        val outputs = runAgainst(server, listOf("id"), prompts)
        Thread.sleep(100)
        assertNull(seen.error.get())
        assertEquals(listOf("uid=2000(shell)\n"), outputs)
        assertEquals(1, prompts.get())

        // android_pubkey_decode's layout
        val offered = seen.publicKey!!
        assertTrue(offered.endsWith(" watchparty@tv\u0000"))
        val raw = java.util.Base64.getDecoder().decode(offered.substring(0, offered.indexOf(' ')))
        assertEquals(524, raw.size)
        val buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(64, buffer.int)
        val n0inv = buffer.int.toLong() and 0xffffffffL
        val modulus = ByteArray(256).also { buffer.get(it) }
        val rr = ByteArray(256).also { buffer.get(it) }
        val exponent = buffer.int
        val n = BigInteger(1, modulus.reversedArray())
        assertEquals(key.publicKey.modulus, n)
        assertEquals(BigInteger.ONE.shiftLeft(4096).mod(n), BigInteger(1, rr.reversedArray()))
        assertEquals(65537, exponent)
        // n0inv * n ≡ -1 (mod 2^32)
        val r32 = BigInteger.ONE.shiftLeft(32)
        assertEquals(r32.subtract(BigInteger.ONE), BigInteger.valueOf(n0inv).multiply(n).mod(r32))
    }

    @Test fun adbdWithoutAuthConnectsDirectly() {
        val seen = Seen()
        val server = fakeAdbd(Auth.NONE, seen, mapOf("true" to ""))
        assertEquals(listOf(""), runAgainst(server, listOf("true")))
        Thread.sleep(100)
        assertNull(seen.error.get())
    }

    @Test fun cancelledPromptIsReported() {
        val seen = Seen()
        val server = fakeAdbd(Auth.REJECT, seen)
        try {
            runAgainst(server, listOf("true"))
            fail("expected AdbException")
        } catch (e: AdbException) {
            assertTrue(e.message!!, e.message!!.contains("not allowed"))
        }
    }

    @Test fun wirelessDebuggingTlsIsReportedAsUnsupported() {
        val seen = Seen()
        val server = fakeAdbd(Auth.TLS, seen)
        try {
            runAgainst(server, listOf("true"))
            fail("expected AdbException")
        } catch (e: AdbException) {
            assertTrue(e.message!!, e.message!!.contains("Wireless debugging"))
        }
    }

    @Test fun nothingListeningIsAConnectException() {
        val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        try {
            AdbClient(key).runCommands("127.0.0.1", port, listOf("true"))
            fail("expected ConnectException")
        } catch (e: java.net.ConnectException) {
            // SelfSetup tries the next port
        }
    }

    @Test fun base64MatchesTheJdk() {
        val random = java.util.Random(7)
        for (size in 0..40) {
            val data = ByteArray(size).also { random.nextBytes(it) }
            val text = B64.encode(data)
            assertEquals(java.util.Base64.getEncoder().encodeToString(data), text)
            assertArrayEquals(data, B64.decode(text))
            assertArrayEquals(data, B64.decode(text.chunked(5).joinToString("\n ")))
        }
    }

    @Test fun keySurvivesSaveAndLoad() {
        val file = File.createTempFile("adbkey", null).also { it.delete() }
        try {
            val first = AdbKey.loadOrCreate(file)
            val again = AdbKey.loadOrCreate(file)
            assertEquals(first.publicKey.modulus, again.publicKey.modulus)
            assertArrayEquals(first.sign(ByteArray(20)), again.sign(ByteArray(20)))
            assertFalse(file.readText().isBlank())
        } finally {
            file.delete()
        }
    }

    /** With ADBKEY_DUMP=<dir>, writes a key, a token's signature and the adb public key for an outside check. */
    @Test fun dumpForCrossCheck() {
        val dir = System.getenv("ADBKEY_DUMP") ?: return
        File(dir, "key.txt").writeText(key.serialize())
        val token = ByteArray(20) { it.toByte() }
        File(dir, "signature.bin").writeBytes(key.sign(token))
        File(dir, "pubkey.txt").writeBytes(key.adbPublicKey("watchparty@tv"))
    }
}
