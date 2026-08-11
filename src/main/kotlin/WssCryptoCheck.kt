/*
 * Copyright (c) 2026 Marco Morosi
 *
 * Licensed under the EUPL, Version 1.2 or – as soon they will be approved by
 * the European Commission - subsequent versions of the EUPL (the "Licence");
 * You may not use this work except in compliance with the Licence.
 * You may obtain a copy of the Licence at:
 *
 * https://joinup.ec.europa.eu/software/page/eupl
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the Licence is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Licence for the specific language governing permissions and
 * limitations under the Licence.
 */
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

private var failures = 0
private var checks = 0

private fun check(name: String, condition: Boolean, detail: String = "") {
    checks++
    if (condition) {
        println("  ok    $name")
    } else {
        println("  FAIL  $name ${if (detail.isNotBlank()) "-> $detail" else ""}")
        failures++
    }
}

private fun mustReject(name: String, body: () -> Unit) {
    checks++
    try {
        body()
        println("  FAIL  $name -> accepted, it had to be refused")
        failures++
    } catch (e: WssProtocolException) {
        println("  ok    $name -> refused: ${e.message}")
    } catch (e: Exception) {
        println("  ok    $name -> refused: ${e.javaClass.simpleName}")
    }
}

private fun hex(bytes: ByteArray): String =
    bytes.joinToString("") { "%02x".format(it) }

private fun unhex(text: String): ByteArray {
    val clean = text.filter { !it.isWhitespace() }
    return ByteArray(clean.length / 2) {
        ((Character.digit(clean[it * 2], 16) shl 4) or Character.digit(clean[it * 2 + 1], 16)).toByte()
    }
}

private val NULL_OUTPUT = object : java.io.OutputStream() {
    override fun write(b: Int) {}
    override fun write(b: ByteArray, off: Int, len: Int) {}
}

private val EMPTY_INPUT = ByteArrayInputStream(ByteArray(0))

private fun clientChannel(keys: SessionKeys, out: java.io.OutputStream) = SecureChannel(
    EMPTY_INPUT, out,
    keys.clientToServerKey, keys.clientToServerPrefix,
    keys.serverToClientKey, keys.serverToClientPrefix
)

private fun serverChannel(keys: SessionKeys, input: java.io.InputStream) = SecureChannel(
    input, NULL_OUTPUT,
    keys.serverToClientKey, keys.serverToClientPrefix,
    keys.clientToServerKey, keys.clientToServerPrefix
)

private fun frames(bytes: ByteArray): List<ByteArray> {
    val out = ArrayList<ByteArray>()
    var at = 0
    while (at + 4 <= bytes.size) {
        var length = 0
        for (i in 0 until 4) length = (length shl 8) or (bytes[at + i].toInt() and 0xFF)
        val end = at + 4 + length
        if (end > bytes.size) break
        out.add(bytes.copyOfRange(at, end))
        at = end
    }
    return out
}

private fun join(vararg parts: ByteArray): ByteArray {
    val out = ByteArrayOutputStream()
    parts.forEach { out.write(it) }
    return out.toByteArray()
}

fun main() {
    println("WSS Input Protocol v1 - cryptographic layer check")
    println()

    println("HKDF-SHA256, RFC 5869 A.1")
    val a1 = WssCrypto.hkdfRaw(
        ikm = unhex("0b".repeat(22)),
        salt = unhex("000102030405060708090a0b0c"),
        info = unhex("f0f1f2f3f4f5f6f7f8f9"),
        length = 42
    )
    check(
        "vector A.1",
        hex(a1) == "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
        hex(a1)
    )
    println("  A.1 produced: ${hex(a1)}")
    println()

    println("Key separation")
    val secret = ByteArray(32) { it.toByte() }
    val transcript = WssCrypto.sha256("test transcript".toByteArray())
    val keys = SessionKeys.derive(secret, transcript)

    check("c2s key differs from s2c", !keys.clientToServerKey.contentEquals(keys.serverToClientKey))
    check("c2s prefix differs from s2c", !keys.clientToServerPrefix.contentEquals(keys.serverToClientPrefix))
    check("6 digit SAS", keys.sas.length == 6 && keys.sas.all { it.isDigit() }, keys.sas)

    val altered = WssCrypto.sha256("test transcript!".toByteArray())
    val other = SessionKeys.derive(secret, altered)
    check(
        "a different transcript produces different keys",
        !keys.clientToServerKey.contentEquals(other.clientToServerKey)
    )
    check("a different transcript produces a different SAS", keys.sas != other.sas, "${keys.sas} vs ${other.sas}")

    println("  c2s key: ${hex(keys.clientToServerKey)}")
    println("  s2c key: ${hex(keys.serverToClientKey)}")
    println("  c2s prefix: ${hex(keys.clientToServerPrefix)}")
    println("  s2c prefix: ${hex(keys.serverToClientPrefix)}")
    println("  SAS: ${keys.sas}")
    println()

    println("Round trip")
    val messages = listOf(
        "first".toByteArray(),
        byteArrayOf(0x01),
        ByteArray(1024) { (it % 251).toByte() },
        "last".toByteArray()
    )

    val captured = ByteArrayOutputStream()
    val sender = clientChannel(keys, captured)
    messages.forEach { sender.send(it) }
    val wire = captured.toByteArray()

    val reader = serverChannel(keys, ByteArrayInputStream(wire))
    var roundTripOk = true
    for (expected in messages) {
        val actual = reader.receive()
        if (!actual.contentEquals(expected)) roundTripOk = false
    }
    check("the ${messages.size} messages come back identical", roundTripOk)

    val parts = frames(wire)
    check("one frame per message", parts.size == messages.size, "${parts.size}")
    check(
        "the nonce never travels on the wire",
        parts[0].size == 4 + messages[0].size + WssCrypto.TAG_SIZE,
        "${parts[0].size}"
    )
    println("  first frame: ${hex(parts[0])}")
    println()

    println("Attacks: every one MUST be refused")

    mustReject("replay of the first frame") {
        val victim = serverChannel(keys, ByteArrayInputStream(join(parts[0], parts[0])))
        victim.receive()
        victim.receive()
    }

    mustReject("two frames reordered") {
        val victim = serverChannel(keys, ByteArrayInputStream(join(parts[1], parts[0])))
        victim.receive()
    }

    mustReject("first frame skipped") {
        val victim = serverChannel(keys, ByteArrayInputStream(parts[1]))
        victim.receive()
    }

    mustReject("one bit flipped in the ciphertext") {
        val tampered = parts[0].copyOf()
        tampered[6] = (tampered[6].toInt() xor 0x01).toByte()
        val victim = serverChannel(keys, ByteArrayInputStream(tampered))
        victim.receive()
    }

    mustReject("one bit flipped in the tag") {
        val tampered = parts[0].copyOf()
        val last = tampered.size - 1
        tampered[last] = (tampered[last].toInt() xor 0x80).toByte()
        val victim = serverChannel(keys, ByteArrayInputStream(tampered))
        victim.receive()
    }

    mustReject("truncated frame") {
        val truncated = parts[2].copyOf(parts[2].size - 1)
        val victim = serverChannel(keys, ByteArrayInputStream(truncated))
        victim.receive()
    }

    mustReject("declared length larger than the real one") {
        val lying = parts[0].copyOf()
        lying[3] = (lying[3] + 1).toByte()
        val victim = serverChannel(keys, ByteArrayInputStream(lying))
        victim.receive()
    }

    mustReject("length below the minimum") {
        val victim = serverChannel(keys, ByteArrayInputStream(unhex("00000005") + ByteArray(5)))
        victim.receive()
    }

    mustReject("a client frame reflected back at the client") {
        val victim = SecureChannel(
            ByteArrayInputStream(parts[0]), NULL_OUTPUT,
            keys.clientToServerKey, keys.clientToServerPrefix,
            keys.serverToClientKey, keys.serverToClientPrefix
        )
        victim.receive()
    }

    mustReject("wrong session key") {
        val victim = serverChannel(other, ByteArrayInputStream(parts[0]))
        victim.receive()
    }

    println()
    println("Constant time comparison")
    check("equal", WssCrypto.constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 3)))
    check("different", !WssCrypto.constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 4)))
    check("different lengths", !WssCrypto.constantTimeEquals(byteArrayOf(1, 2), byteArrayOf(1, 2, 3)))
    check("degenerate secret refused", runCatching { SessionKeys.derive(ByteArray(32), transcript) }.isFailure)
    mustReject("sending an empty message") { clientChannel(keys, NULL_OUTPUT).send(ByteArray(0)) }

    println()
    println("$checks checks, $failures failed")
    kotlin.system.exitProcess(if (failures == 0) 0 else 1)
}
