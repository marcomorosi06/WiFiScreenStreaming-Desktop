import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

private var failures = 0
private var checks = 0

private fun ok(name: String, detail: String = "") {
    checks++
    println("  ok    $name" + if (detail.isNotBlank()) " -> $detail" else "")
}

private fun bad(name: String, detail: String = "") {
    checks++
    failures++
    println("  FAIL  $name" + if (detail.isNotBlank()) " -> $detail" else "")
}

private fun check(name: String, condition: Boolean, detail: String = "") {
    if (condition) ok(name) else bad(name, detail)
}

private class SocketPair(val server: Socket, val client: Socket) {
    fun close() {
        runCatching { server.close() }
        runCatching { client.close() }
    }
}

private class Handshaked(
    val server: Session?,
    val client: Session?,
    private val sockets: SocketPair
) {
    fun close() = sockets.close()
}

private fun socketPair(): SocketPair {
    val listener = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    val client = Socket(InetAddress.getLoopbackAddress(), listener.localPort)
    val server = listener.accept()
    listener.close()
    client.tcpNoDelay = true
    server.tcpNoDelay = true
    return SocketPair(server, client)
}

private val pool = Executors.newCachedThreadPool { runnable ->
    Thread(runnable).also { it.isDaemon = true }
}

private fun <T> async(body: () -> T): Future<T> = pool.submit<T> { body() }

private class Fixture(temp: File) {
    val serverIdentity = WssIdentity.loadOrCreate(File(temp, "server.key"))
    val clientIdentity = WssIdentity.loadOrCreate(File(temp, "client.key"))
    val devices = WssDevices(File(temp, "devices.tsv"))
}

private fun pair(fixture: Fixture, pin: String, clientPin: String = pin): Handshaked {
    val sockets = socketPair()
    val window = PairingWindow(pin = pin)
    val serverSide = async {
        WssHandshake.serve(
            sockets.server,
            fixture.serverIdentity, fixture.devices, window, "Test PC"
        ) { true }
    }
    val clientSide = async {
        WssHandshake.connect(
            sockets.client,
            fixture.clientIdentity, null, clientPin, "Test phone"
        ) { true }
    }
    val s = runCatching { serverSide.get(90, TimeUnit.SECONDS) }.getOrNull()
    val c = runCatching { clientSide.get(90, TimeUnit.SECONDS) }.getOrNull()
    return Handshaked(s, c, sockets)
}

private fun session(
    fixture: Fixture,
    trustedServerKey: ByteArray?,
    clientIdentity: WssIdentity = fixture.clientIdentity
): Handshaked {
    val sockets = socketPair()
    val serverSide = async {
        WssHandshake.serve(
            sockets.server,
            fixture.serverIdentity, fixture.devices, null, "Test PC"
        ) { true }
    }
    val clientSide = async {
        WssHandshake.connect(
            sockets.client,
            clientIdentity, trustedServerKey, null, "Test phone"
        ) { true }
    }
    val s = runCatching { serverSide.get(30, TimeUnit.SECONDS) }.getOrNull()
    val c = runCatching { clientSide.get(30, TimeUnit.SECONDS) }.getOrNull()
    return Handshaked(s, c, sockets)
}

fun main() {
    println("WSS Input Protocol v1 - handshake check")
    println()

    val temp = File(System.getProperty("java.io.tmpdir"), "wss-check-${System.nanoTime()}")
    temp.mkdirs()
    val fixture = Fixture(temp)

    println("Identity")
    check("32 byte server key", fixture.serverIdentity.publicKey.size == 32)
    check("distinct identities", !fixture.serverIdentity.publicKey.contentEquals(fixture.clientIdentity.publicKey))
    val reloaded = WssIdentity.loadOrCreate(File(temp, "server.key"))
    check("the identity survives a restart", reloaded.publicKey.contentEquals(fixture.serverIdentity.publicKey))
    val signature = fixture.serverIdentity.sign("test".toByteArray())
    check("signature verified", WssIdentity.verify(fixture.serverIdentity.publicKey, "test".toByteArray(), signature))
    check(
        "signature over different data refused",
        !WssIdentity.verify(fixture.serverIdentity.publicKey, "test!".toByteArray(), signature)
    )
    check(
        "signature from another identity refused",
        !WssIdentity.verify(fixture.clientIdentity.publicKey, "test".toByteArray(), signature)
    )
    println("  server fingerprint: ${fixture.serverIdentity.fingerprint}")
    println()

    println("Pairing with the right PIN")
    val started = System.currentTimeMillis()
    val paired = pair(fixture, "48210377")
    val serverPaired = paired.server
    val clientPaired = paired.client
    val elapsed = System.currentTimeMillis() - started

    if (serverPaired == null || clientPaired == null) {
        bad("pairing succeeded", "one of the two sides failed")
    } else {
        ok("pairing succeeded", "in ${elapsed} ms")
        check("the verification code matches", serverPaired.sas == clientPaired.sas, serverPaired.sas)
        check("the server stored the device", fixture.devices.isTrusted(fixture.clientIdentity.publicKey))
        check("the client knows the server identity", clientPaired.peerPublicKey.contentEquals(fixture.serverIdentity.publicKey))
        check("the device name arrived", serverPaired.peerName == "Test phone", serverPaired.peerName)
        println("  verification code: ${serverPaired.sas}")
        println("  remembered devices: ${fixture.devices.all()}")

        serverPaired.channel.send(byteArrayOf(0x80.toByte(), 7, 0x80.toByte(), 4, 0x38))
        val received = clientPaired.channel.receive()
        check("the encrypted channel works right after", received.size == 5 && received[0] == 0x80.toByte())
    }
    paired.close()
    println()

    println("Pairing with the wrong PIN: it MUST fail")
    val wrong = pair(fixture, "48210377", clientPin = "48210378")
    check("the server refuses", wrong.server == null)
    check("the client refuses", wrong.client == null)
    wrong.close()
    println()

    println("Later sessions without a PIN")
    val serverKey = fixture.serverIdentity.publicKey
    val first = session(fixture, serverKey)
    val s1 = first.server
    val c1 = first.client
    if (s1 == null || c1 == null) {
        bad("session with a remembered device")
    } else {
        ok("session with a remembered device")
        check("no pairing requested", !s1.paired && !c1.paired)
        check("same verification code on both sides", s1.sas == c1.sas)

        val second = session(fixture, serverKey)
        check("two sessions have different codes (forward secrecy)", second.server != null && second.server.sas != s1.sas)
        second.close()
    }
    first.close()
    println()

    println("Attacks: every one MUST fail")

    val intruderDir = File(temp, "intruder")
    val intruder = WssIdentity.loadOrCreate(File(intruderDir, "intruder.key"))
    val unknown = session(fixture, serverKey, clientIdentity = intruder)
    check("unknown device rejected by the server", unknown.server == null)
    check("unknown device rejected on the client side too", unknown.client == null)
    unknown.close()

    val impersonated = session(fixture, intruder.publicKey)
    check("server identity different from the stored one", impersonated.client == null)
    impersonated.close()

    val closedWindow = PairingWindow(pin = "11112222")
    closedWindow.close()
    val sockets = socketPair()
    val refused = try {
        val serverSide = async {
            WssHandshake.serve(
                sockets.server,
                fixture.serverIdentity, fixture.devices, closedWindow, "Test PC"
            ) { true }
        }
        val clientSide = async {
            WssHandshake.connect(
                sockets.client,
                intruder, null, "11112222", "Intruso"
            ) { true }
        }
        runCatching { serverSide.get(30, TimeUnit.SECONDS) }.getOrNull() to
            runCatching { clientSide.get(30, TimeUnit.SECONDS) }.getOrNull()
    } finally {
        sockets.close()
    }
    check("pairing with a closed window rejected", refused.first == null && refused.second == null)

    val rejecting = PairingWindow(pin = "22223333")
    val sockets2 = socketPair()
    val declined = try {
        val serverSide = async {
            WssHandshake.serve(
                sockets2.server,
                fixture.serverIdentity, fixture.devices, rejecting, "Test PC"
            ) { false }
        }
        val clientSide = async {
            WssHandshake.connect(
                sockets2.client,
                intruder, null, "22223333", "Intruso"
            ) { true }
        }
        runCatching { serverSide.get(60, TimeUnit.SECONDS) }.getOrNull()
    } finally {
        sockets2.close()
    }
    check("a user saying no on the desktop blocks the pairing", declined == null)
    check("the refused device was not stored", !fixture.devices.isTrusted(intruder.publicKey))

    val sockets3 = socketPair()
    val refusedByClient = try {
        val serverSide = async {
            WssHandshake.serve(
                sockets3.server,
                fixture.serverIdentity, fixture.devices, PairingWindow(), "Test PC"
            ) { true }
        }
        WssHandshake.decline(sockets3.client)
        runCatching { serverSide.get(30, TimeUnit.SECONDS) }
    } finally {
        sockets3.close()
    }
    check(
        "a client that declines leaves the server with no session",
        refusedByClient.isSuccess && refusedByClient.getOrNull() == null
    )
    check("declining is not an error", refusedByClient.exceptionOrNull() == null)
    println()

    println("Pairing window")
    val window = PairingWindow(pin = "12345678")
    check("readable format", window.display == "1234-5678", window.display)
    check("three attempts available", window.attemptsLeft == 3)
    window.claim(); window.claim(); window.claim()
    check("it closes after three attempts", !window.isOpen)
    checks++
    try {
        window.claim()
        failures++
        println("  FAIL  a fourth attempt is rejected")
    } catch (e: WssProtocolException) {
        println("  ok    a fourth attempt is rejected -> ${e.message}")
    }
    println()

    println("Remembered devices")
    val store = WssDevices(File(temp, "store.tsv"))
    val alpha = ByteArray(32) { 1 }
    val beta = ByteArray(32) { 2 }
    store.remember(alpha, "Samsung tablet")
    store.remember(beta, "Phone")
    check("two devices", store.all().size == 2)
    check("lookup by key", store.find(alpha)?.name == "Samsung tablet")
    store.rename(alpha, "Tablet in the lounge")
    check("rename", store.find(alpha)?.name == "Tablet in the lounge")
    check("forget", store.forget(beta) && store.all().size == 1)
    val rebuilt = WssDevices(File(temp, "store.tsv"))
    check("survives on disk", rebuilt.find(alpha)?.name == "Tablet in the lounge")
    check("the fingerprint is stable", rebuilt.find(alpha)?.fingerprint == WssIdentity.fingerprintOf(alpha))
    println("  fingerprint: ${WssIdentity.fingerprintOf(alpha)}")
    println()

    temp.deleteRecursively()
    println("$checks checks, $failures failed")
    kotlin.system.exitProcess(if (failures == 0) 0 else 1)
}
