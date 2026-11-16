package io.github.breezyslasher.watchparty

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * Standard base64 (RFC 4648, padded). Its own because android.util.Base64 is
 * a stub in plain JVM unit tests and java.util.Base64 needs API 26.
 */
internal object B64 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun encode(data: ByteArray): String {
        val out = StringBuilder((data.size + 2) / 3 * 4)
        var i = 0
        while (i < data.size) {
            val b0 = data[i].toInt() and 0xff
            val b1 = if (i + 1 < data.size) data[i + 1].toInt() and 0xff else 0
            val b2 = if (i + 2 < data.size) data[i + 2].toInt() and 0xff else 0
            out.append(ALPHABET[b0 shr 2])
            out.append(ALPHABET[(b0 and 3) shl 4 or (b1 shr 4)])
            out.append(if (i + 1 < data.size) ALPHABET[(b1 and 15) shl 2 or (b2 shr 6)] else '=')
            out.append(if (i + 2 < data.size) ALPHABET[b2 and 63] else '=')
            i += 3
        }
        return out.toString()
    }

    /** Skips whitespace; stops at padding. */
    fun decode(text: String): ByteArray {
        val out = java.io.ByteArrayOutputStream(text.length * 3 / 4)
        var acc = 0
        var bits = 0
        for (c in text) {
            if (c == '=') break
            if (c.isWhitespace()) continue
            val v = ALPHABET.indexOf(c)
            if (v < 0) throw IOException("bad base64")
            acc = (acc shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((acc shr bits) and 0xff)
            }
        }
        return out.toByteArray()
    }
}

/** Something went wrong talking to adbd; [message] is shown to the user. */
class AdbException(message: String) : IOException(message)

/**
 * The RSA key this app identifies itself to adbd with, like a computer's
 * ~/.android/adbkey. Kept so "Always allow from this computer" sticks.
 */
class AdbKey(val privateKey: RSAPrivateKey, val publicKey: RSAPublicKey) {

    /**
     * Signs adbd's auth token the way adb does: RSA PKCS#1 v1.5 over a SHA-1
     * DigestInfo whose digest is the token itself (adbd checks it with
     * RSA_verify(NID_sha1, token, …)).
     */
    fun sign(token: ByteArray): ByteArray {
        val digestInfo = SHA1_DIGEST_INFO + token
        val size = (privateKey.modulus.bitLength() + 7) / 8
        if (digestInfo.size > size - 11) throw AdbException("Auth token too long (${token.size} bytes)")
        val block = ByteArray(size)
        block[1] = 1
        for (i in 2 until size - digestInfo.size - 1) block[i] = 0xff.toByte()
        System.arraycopy(digestInfo, 0, block, size - digestInfo.size, digestInfo.size)
        val signature = BigInteger(1, block).modPow(privateKey.privateExponent, privateKey.modulus)
        return unsignedBytes(signature, size, littleEndian = false)
    }

    /**
     * The public key in adb's format (android_pubkey_encode: modulus size,
     * n0inv, modulus and R² mod n little-endian, exponent), base64, then
     * " name" and a NUL — what `adb` sends and adbd shows in its prompt.
     */
    fun adbPublicKey(name: String): ByteArray {
        val n = publicKey.modulus
        val bytes = MODULUS_BYTES
        if (n.bitLength() != bytes * 8) throw AdbException("adb needs a 2048-bit key")
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = n.mod(r32).modInverse(r32).negate().mod(r32)
        val rr = BigInteger.ONE.shiftLeft(bytes * 8 * 2).mod(n)
        val buffer = ByteBuffer.allocate(4 + 4 + bytes + bytes + 4).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(bytes / 4)
        buffer.putInt(n0inv.toLong().toInt())
        buffer.put(unsignedBytes(n, bytes, littleEndian = true))
        buffer.put(unsignedBytes(rr, bytes, littleEndian = true))
        buffer.putInt(publicKey.publicExponent.toInt())
        val encoded = B64.encode(buffer.array())
        return "$encoded $name".toByteArray(Charsets.UTF_8) + byteArrayOf(0)
    }

    /** Both keys, base64, one per line — what [load] reads back. */
    fun serialize(): String =
        B64.encode(privateKey.encoded) + "\n" +
            B64.encode(publicKey.encoded) + "\n"

    companion object {
        const val MODULUS_BYTES = 256
        private val SHA1_DIGEST_INFO = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14,
        )

        fun generate(): AdbKey {
            val generator = KeyPairGenerator.getInstance("RSA")
            generator.initialize(MODULUS_BYTES * 8)
            val pair = generator.generateKeyPair()
            return AdbKey(pair.private as RSAPrivateKey, pair.public as RSAPublicKey)
        }

        fun load(text: String): AdbKey {
            val trimmed = text.trim()
            val newline = trimmed.indexOf('\n')
            if (newline < 0) throw IOException("bad key file")
            val factory = KeyFactory.getInstance("RSA")
            val private = factory.generatePrivate(
                PKCS8EncodedKeySpec(B64.decode(trimmed.substring(0, newline))),
            )
            val public = factory.generatePublic(
                X509EncodedKeySpec(B64.decode(trimmed.substring(newline + 1))),
            )
            return AdbKey(private as RSAPrivateKey, public as RSAPublicKey)
        }

        /** The key in [file], creating it on first use. */
        fun loadOrCreate(file: File): AdbKey {
            if (file.exists()) {
                try {
                    return load(file.readText())
                } catch (e: Exception) {
                    // unreadable: make a new one (the TV just asks to allow it again)
                }
            }
            val key = generate()
            file.writeText(key.serialize())
            return key
        }

        /** [value] as exactly [size] unsigned bytes. */
        internal fun unsignedBytes(value: BigInteger, size: Int, littleEndian: Boolean): ByteArray {
            val raw = value.toByteArray()
            val out = ByteArray(size)
            val copy = minOf(raw.size, size)
            System.arraycopy(raw, raw.size - copy, out, size - copy, copy)  // big-endian, right-aligned
            if (littleEndian) out.reverse()
            return out
        }
    }
}

/** One adb protocol packet: a 24-byte little-endian header and a payload. */
class AdbMessage(val command: Int, val arg0: Int, val arg1: Int, val data: ByteArray = ByteArray(0)) {

    fun writeTo(out: OutputStream) {
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        var checksum = 0
        for (b in data) checksum += b.toInt() and 0xff
        header.putInt(command).putInt(arg0).putInt(arg1).putInt(data.size)
            .putInt(checksum).putInt(command xor -1)
        out.write(header.array())
        out.write(data)
        out.flush()
    }

    companion object {
        const val CNXN = 0x4e584e43
        const val AUTH = 0x48545541
        const val OPEN = 0x4e45504f
        const val OKAY = 0x59414b4f
        const val CLSE = 0x45534c43
        const val WRTE = 0x45545257
        const val STLS = 0x534c5453

        const val AUTH_TOKEN = 1
        const val AUTH_SIGNATURE = 2
        const val AUTH_RSAPUBLICKEY = 3

        const val VERSION = 0x01000001
        const val MAX_PAYLOAD = 256 * 1024

        fun readFrom(input: DataInputStream): AdbMessage {
            val raw = ByteArray(24)
            input.readFully(raw)
            val header = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            val command = header.int
            val arg0 = header.int
            val arg1 = header.int
            val length = header.int
            header.int  // checksum: not checked from version 0x01000001 on
            val magic = header.int
            if (magic != command xor -1) throw AdbException("Unexpected reply from the debugging service")
            if (length < 0 || length > 16 * 1024 * 1024) throw AdbException("Bad message length $length")
            val data = ByteArray(length)
            input.readFully(data)
            return AdbMessage(command, arg0, arg1, data)
        }
    }
}

/**
 * A minimal adb client — enough for this app to connect to the TV's own adb
 * daemon over TCP (plain, non-TLS "network debugging") and run shell commands,
 * as `adb shell` from a computer would. adbd shows its usual "Allow USB
 * debugging?" prompt the first time.
 */
class AdbClient(private val key: AdbKey, private val keyName: String = "watchparty@tv") {

    /**
     * Connects to adbd at [host]:[port] and runs each command with `shell:`,
     * returning their outputs. [onPrompt] is called when the TV is asking the
     * user to allow this app's key.
     */
    fun runCommands(
        host: String,
        port: Int,
        commands: List<String>,
        onPrompt: () -> Unit = {},
        connectTimeoutMs: Int = 3_000,
        replyTimeoutMs: Int = 15_000,
        promptTimeoutMs: Int = 120_000,
    ): List<String> {
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
            socket.soTimeout = replyTimeoutMs
            socket.tcpNoDelay = true
            val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
            val output = BufferedOutputStream(socket.getOutputStream())
            connect(socket, input, output, onPrompt, replyTimeoutMs, promptTimeoutMs)
            val outputs = ArrayList<String>()
            var localId = 1
            for (command in commands) {
                outputs.add(shell(input, output, localId++, command))
            }
            return outputs
        } catch (e: java.net.SocketTimeoutException) {
            throw AdbException("The TV's debugging service stopped answering")
        } finally {
            try { socket.close() } catch (e: IOException) { }
        }
    }

    private fun connect(
        socket: Socket,
        input: DataInputStream,
        output: OutputStream,
        onPrompt: () -> Unit,
        replyTimeoutMs: Int,
        promptTimeoutMs: Int,
    ) {
        AdbMessage(AdbMessage.CNXN, AdbMessage.VERSION, AdbMessage.MAX_PAYLOAD, "host::\u0000".toByteArray())
            .writeTo(output)
        var signed = false
        var sentKey = false
        while (true) {
            val message = try {
                AdbMessage.readFrom(input)
            } catch (e: java.net.SocketTimeoutException) {
                throw AdbException(
                    if (sentKey) "Debugging wasn't allowed on the TV in time" else "The TV's debugging service didn't answer",
                )
            } catch (e: java.io.EOFException) {
                throw AdbException(
                    if (sentKey) "The TV refused the connection (debugging not allowed)"
                    else "The TV closed the connection",
                )
            }
            when (message.command) {
                AdbMessage.CNXN -> {
                    socket.soTimeout = replyTimeoutMs
                    return
                }
                AdbMessage.STLS -> throw AdbException(
                    "This TV's debugging needs a secure pairing (Wireless debugging), which isn't supported yet",
                )
                AdbMessage.AUTH -> {
                    if (message.arg0 != AdbMessage.AUTH_TOKEN) throw AdbException("Unexpected auth request")
                    if (!signed) {
                        signed = true
                        AdbMessage(AdbMessage.AUTH, AdbMessage.AUTH_SIGNATURE, 0, key.sign(message.data))
                            .writeTo(output)
                    } else if (!sentKey) {
                        sentKey = true
                        AdbMessage(AdbMessage.AUTH, AdbMessage.AUTH_RSAPUBLICKEY, 0, key.adbPublicKey(keyName))
                            .writeTo(output)
                        socket.soTimeout = promptTimeoutMs
                        onPrompt()
                    } else {
                        throw AdbException("The TV refused the connection (debugging not allowed)")
                    }
                }
                else -> { }  // ignore anything else before the connection is up
            }
        }
    }

    private fun shell(input: DataInputStream, output: OutputStream, localId: Int, command: String): String {
        AdbMessage(AdbMessage.OPEN, localId, 0, "shell:$command\u0000".toByteArray()).writeTo(output)
        val text = StringBuilder()
        var remoteId = 0
        while (true) {
            val message = try {
                AdbMessage.readFrom(input)
            } catch (e: java.io.EOFException) {
                throw AdbException("The TV closed the connection while running a command")
            }
            if (message.arg1 != localId) continue  // belongs to an earlier stream
            when (message.command) {
                AdbMessage.OKAY -> remoteId = message.arg0
                AdbMessage.WRTE -> {
                    remoteId = message.arg0
                    text.append(String(message.data, Charsets.UTF_8))
                    AdbMessage(AdbMessage.OKAY, localId, remoteId).writeTo(output)
                }
                AdbMessage.CLSE -> {
                    if (remoteId == 0 && text.isEmpty()) throw AdbException("The TV refused to run: $command")
                    AdbMessage(AdbMessage.CLSE, localId, message.arg0).writeTo(output)
                    return text.toString()
                }
            }
        }
    }
}
