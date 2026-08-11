import kotlinx.coroutines.runBlocking
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

private const val PROTOCOL_VERSION = 2
private const val MAGIC_0 = 0x57.toByte()
private const val MAGIC_1 = 0x46.toByte()
private const val HEADER_SIZE = 10
private const val MTU = 1400
private const val MAX_PAYLOAD = MTU - HEADER_SIZE

private const val MSG_MODE_PROBE = "MODE_PROBE"
private const val MSG_UNICAST = "UNICAST"
private const val MSG_HELLO = "HELLO_FROM_CLIENT"
private const val MSG_HELLO_ACK = "HELLO_ACK"
private const val MSG_INCOMPATIBLE = "WFAS_INCOMPATIBLE"
private const val MSG_BUSY = "WFAS_BUSY"
private const val MSG_PING = "PING"
private const val MSG_BYE = "BYE"
private const val MSG_CLIENT_BYE = "CLIENT_BYE"

private const val SILENCE_EVERY_MS = 20L
private const val PEER_STALE_MS = 30_000L
private const val SEND_FAILURES_BEFORE_GIVING_UP = 20

class WfasSender {

    private var seq = 0
    private var samplePos = 0L

    fun writeHeader(out: ByteArray, silence: Boolean) {
        out[0] = MAGIC_0
        out[1] = MAGIC_1
        out[2] = PROTOCOL_VERSION.toByte()
        out[3] = if (silence) 1 else 0
        out[4] = ((seq shr 8) and 0xFF).toByte()
        out[5] = (seq and 0xFF).toByte()
        out[6] = ((samplePos shr 24) and 0xFF).toByte()
        out[7] = ((samplePos shr 16) and 0xFF).toByte()
        out[8] = ((samplePos shr 8) and 0xFF).toByte()
        out[9] = (samplePos and 0xFF).toByte()
    }

    fun advance(frames: Int) {
        seq = (seq + 1) and 0xFFFF
        samplePos = (samplePos + frames) and 0xFFFFFFFFL
    }

    fun advanceSilence() {
        seq = (seq + 1) and 0xFFFF
    }
}

class WfasServer(
    private val port: Int,
    private val sampleRate: Int,
    private val channels: Int,
    private val muteLocal: Boolean,
    private val onEvent: (String) -> Unit
) {

    @Volatile
    private var running = false

    private var socket: DatagramSocket? = null
    private var engine: AudioEngine? = null
    private var control: Thread? = null
    private var pump: Thread? = null
    private var keepAlive: Thread? = null

    @Volatile
    private var peer: InetAddress? = null

    @Volatile
    private var peerPort = 0

    @Volatile
    private var peerSeenAt = 0L

    @Volatile
    private var lastPacketAt = 0L

    val isRunning: Boolean get() = running
    val hasClient: Boolean get() = peer != null

    private fun canTakeOver(from: InetAddress): Boolean {
        val held = peer ?: return true
        if (held == from) return true
        return System.currentTimeMillis() - peerSeenAt >= PEER_STALE_MS
    }

    fun dropClient(reason: String) {
        val target = peer ?: return
        val sock = socket
        if (sock != null) runCatching { send(sock, target, peerPort, MSG_BYE) }
        peer = null
        onEvent("audio: client slot freed ($reason)")
    }

    fun start(): Boolean {
        if (running) return true

        if (!AudioEngine.loadLibrary()) {
            onEvent("audio: native engine not loaded: ${AudioEngine.getLoadError()}")
            return false
        }

        val e = AudioEngine(
            sampleRate = sampleRate,
            channels = channels,
            bufferFrames = 960,
            muteRender = muteLocal
        )
        if (!e.start()) {
            onEvent("audio: capture did not start: ${e.lastError}")
            return false
        }
        engine = e

        val sock = try {
            DatagramSocket(port).also { it.soTimeout = 500 }
        } catch (ex: Exception) {
            onEvent("audio: port $port already in use: ${ex.message}")
            e.stop()
            engine = null
            return false
        }
        socket = sock
        running = true

        onEvent("audio: WFAS v2 server on $port, $sampleRate Hz, $channels channels")

        control = Thread { controlLoop(sock) }.also {
            it.isDaemon = true
            it.name = "wfas-control"
            it.start()
        }
        pump = Thread {
            try {
                pumpLoop(sock, e)
            } catch (ex: Throwable) {
                if (running) {
                    onEvent("audio: the sender died: ${ex.javaClass.simpleName}: ${ex.message}")
                    stop()
                }
            }
        }.also {
            it.isDaemon = true
            it.name = "wfas-audio"
            it.start()
        }
        keepAlive = Thread { keepAliveLoop(sock) }.also {
            it.isDaemon = true
            it.name = "wfas-keepalive"
            it.start()
        }
        return true
    }

    fun stop() {
        if (!running) return
        running = false

        val sock = socket
        val target = peer
        if (sock != null && target != null) {
            runCatching { send(sock, target, peerPort, MSG_BYE) }
        }
        peer = null

        runCatching { sock?.close() }
        socket = null
        runCatching { engine?.stop() }
        engine = null
        control = null
        pump = null
        keepAlive = null
        onEvent("audio: server stopped")
    }

    private fun controlLoop(sock: DatagramSocket) {
        val buffer = ByteArray(1024)
        val packet = DatagramPacket(buffer, buffer.size)

        while (running) {
            packet.setData(buffer, 0, buffer.size)
            try {
                sock.receive(packet)
            } catch (_: SocketTimeoutException) {
                continue
            } catch (e: Exception) {
                if (running) onEvent("audio: receive interrupted: ${e.message}")
                return
            }

            val text = String(buffer, 0, packet.length, Charsets.US_ASCII)
            val from = packet.address
            val fromPort = packet.port
            val current = peer

            val sameClient = current != null && current == from && fromPort == peerPort
            if (sameClient) peerSeenAt = System.currentTimeMillis()

            when {
                text.startsWith(MSG_MODE_PROBE) -> {
                    if (current == null || sameClient || canTakeOver(from)) {
                        send(sock, from, fromPort, MSG_UNICAST)
                    } else {
                        send(sock, from, fromPort, MSG_BUSY)
                    }
                }

                text.startsWith(MSG_HELLO) -> {
                    val version = versionOf(text)
                    when {
                        version != null && version != PROTOCOL_VERSION ->
                            send(sock, from, fromPort, "$MSG_INCOMPATIBLE;v=$PROTOCOL_VERSION")

                        current == null -> {
                            peer = from
                            peerPort = fromPort
                            peerSeenAt = System.currentTimeMillis()
                            lastPacketAt = 0L
                            send(sock, from, fromPort, "$MSG_HELLO_ACK;v=$PROTOCOL_VERSION")
                            onEvent("audio: client connected ${from.hostAddress}")
                        }

                        sameClient ->
                            send(sock, from, fromPort, "$MSG_HELLO_ACK;v=$PROTOCOL_VERSION")

                        canTakeOver(from) -> {
                            val previous = current.hostAddress
                            peer = from
                            peerPort = fromPort
                            peerSeenAt = System.currentTimeMillis()
                            lastPacketAt = 0L
                            send(sock, from, fromPort, "$MSG_HELLO_ACK;v=$PROTOCOL_VERSION")
                            if (current == from) {
                                onEvent("audio: ${from.hostAddress} came back on a new socket, slot handed over")
                            } else {
                                onEvent("audio: $previous went quiet, slot handed to ${from.hostAddress}")
                            }
                        }

                        else -> send(sock, from, fromPort, MSG_BUSY)
                    }
                }

                text.startsWith(MSG_CLIENT_BYE) -> {
                    if (sameClient) {
                        peer = null
                        onEvent("audio: client disconnected")
                    }
                }
            }
        }
    }

    private fun pumpLoop(sock: DatagramSocket, engine: AudioEngine) {
        val sender = WfasSender()
        val packet = ByteArray(MTU)
        val bytesPerFrame = channels * 2
        val framesPerPacket = MAX_PAYLOAD / bytesPerFrame
        var sendFailures = 0
        var quietSince = 0L
        var quietReported = false

        runBlocking {
            while (running) {
                val target = peer
                if (target == null) {
                    Thread.sleep(20)
                    quietSince = 0L
                    quietReported = false
                    continue
                }

                val pcm = try {
                    engine.readFrame()
                } catch (ex: Throwable) {
                    onEvent("audio: capture failed: ${ex.javaClass.simpleName}: ${ex.message}")
                    onEvent("audio: stopping the server so the phone stops waiting")
                    stop()
                    return@runBlocking
                }

                val now = System.currentTimeMillis()

                if (pcm == null || pcm.isEmpty()) {
                    if (quietSince == 0L) quietSince = now
                    if (!quietReported && now - quietSince >= 4000) {
                        quietReported = true
                        onEvent(
                            if (muteLocal)
                                "audio: nothing captured for 4 s. The PC output is muted for the stream, " +
                                    "and on some drivers that silences the loopback too: try turning the mute off."
                            else
                                "audio: nothing captured for 4 s, sending silence. " +
                                    "Play something on the PC to check."
                        )
                    }
                    if (pcm == null) Thread.sleep(5)
                    continue
                }

                quietSince = 0L
                quietReported = false

                val totalFrames = pcm.size / channels
                var offset = 0
                while (offset < totalFrames && running) {
                    val frames = minOf(framesPerPacket, totalFrames - offset)
                    sender.writeHeader(packet, false)

                    var w = HEADER_SIZE
                    var s = offset * channels
                    val end = s + frames * channels
                    while (s < end) {
                        val value = pcm[s].toInt()
                        packet[w] = (value and 0xFF).toByte()
                        packet[w + 1] = ((value shr 8) and 0xFF).toByte()
                        w += 2
                        s++
                    }

                    val length = HEADER_SIZE + frames * bytesPerFrame
                    try {
                        sock.send(DatagramPacket(packet, length, target, peerPort))
                        sendFailures = 0
                        lastPacketAt = System.currentTimeMillis()
                    } catch (e: Exception) {
                        if (!running) return@runBlocking
                        sendFailures++
                        if (sendFailures == 1) onEvent("audio: send failed: ${e.message}")
                        if (sendFailures >= SEND_FAILURES_BEFORE_GIVING_UP) {
                            onEvent("audio: $sendFailures sends in a row failed, dropping the client")
                            dropClient("the socket keeps refusing")
                            sendFailures = 0
                            break
                        }
                    }
                    sender.advance(frames)
                    offset += frames
                }
            }
        }
    }

    /**
     * Runs on its own thread so PING and silence keep-alives keep going at their
     * regular cadence even when [pumpLoop] is stuck inside a slow/blocking native
     * read (WASAPI loopback can block for up to ~2s per call, longer under some
     * schedulers) — otherwise the phone's 5s silence timeout fires while capture
     * is just being slow, not actually dead.
     */
    private fun keepAliveLoop(sock: DatagramSocket) {
        val sender = WfasSender()
        val packet = ByteArray(HEADER_SIZE)
        var lastPing = 0L

        while (running) {
            val target = peer
            if (target == null) {
                Thread.sleep(50)
                lastPing = 0L
                continue
            }

            val now = System.currentTimeMillis()

            if (now - lastPing >= 1000) {
                runCatching { send(sock, target, peerPort, MSG_PING) }
                lastPing = now
            }

            if (now - lastPacketAt >= SILENCE_EVERY_MS) {
                sender.writeHeader(packet, true)
                runCatching { sock.send(DatagramPacket(packet, HEADER_SIZE, target, peerPort)) }
                sender.advanceSilence()
                lastPacketAt = now
            }

            Thread.sleep(SILENCE_EVERY_MS)
        }
    }

    private fun send(sock: DatagramSocket, address: InetAddress, toPort: Int, message: String) {
        val bytes = message.toByteArray(Charsets.US_ASCII)
        sock.send(DatagramPacket(bytes, bytes.size, address, toPort))
    }

    private fun versionOf(message: String): Int? {
        val marker = ";v="
        val at = message.indexOf(marker)
        if (at < 0) return null
        val tail = message.substring(at + marker.length)
        val digits = tail.takeWhile { it.isDigit() }
        return digits.toIntOrNull()
    }
}
