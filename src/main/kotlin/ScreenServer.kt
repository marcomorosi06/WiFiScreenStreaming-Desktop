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
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.FFmpegFrameRecorder
import org.bytedeco.javacv.Frame
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

data class Sample(val avg: Double, val p95: Double, val max: Double) {
    override fun toString(): String = "%6.1f %6.1f %6.1f".format(avg, p95, max)
}

data class Stats(
    val fps: Double,
    val mbps: Double,
    val frames: Long,
    val dropped: Long,
    val capture: Sample,
    val encode: Sample,
    val send: Sample,
    val cadence: Sample
)

class Meter(private val window: Int) {

    private val samples = DoubleArray(window)
    private var count = 0
    private var sum = 0.0
    private var worst = 0.0

    @Synchronized
    fun add(ms: Double) {
        if (count < window) samples[count] = ms
        count++
        sum += ms
        if (ms > worst) worst = ms
    }

    @Synchronized
    fun ready(): Boolean = count >= window

    @Synchronized
    fun take(): Sample {
        val n = if (count == 0) 1 else min(count, window)
        val sorted = samples.copyOf(n).sortedArray()
        val result = Sample(if (count == 0) 0.0 else sum / count, sorted[((n - 1) * 95) / 100], worst)
        count = 0
        sum = 0.0
        worst = 0.0
        return result
    }
}

class Collector : OutputStream() {

    private var buf = ByteArray(1 shl 20)
    private var len = 0

    private fun room(more: Int) {
        if (len + more <= buf.size) return
        var size = buf.size
        while (size < len + more) size *= 2
        buf = buf.copyOf(size)
    }

    override fun write(b: Int) {
        room(1)
        buf[len++] = b.toByte()
    }

    override fun write(b: ByteArray, off: Int, n: Int) {
        room(n)
        System.arraycopy(b, off, buf, len, n)
        len += n
    }

    fun bytes(): ByteArray = buf

    fun size(): Int = len

    fun reset() {
        len = 0
    }
}

class FrameSink(private val out: OutputStream, private val onEvent: (String) -> Unit = {}) {

    private val header = ByteArray(8)
    private var misframed = 0L

    private fun aligned(b: ByteArray, len: Int): Boolean =
        len >= 4 && b[0].toInt() == 0 && b[1].toInt() == 0 &&
            (b[2].toInt() == 1 || (b[2].toInt() == 0 && b[3].toInt() == 1))

    fun write(bytes: ByteArray, len: Int, flags: Int) {
        if (!aligned(bytes, len)) {
            misframed++
            if (misframed == 1L) {
                onEvent("the encoder is not handing over whole frames: the stream will glitch, tell me about this")
            }
        }
        header[0] = (len ushr 24).toByte()
        header[1] = (len ushr 16).toByte()
        header[2] = (len ushr 8).toByte()
        header[3] = len.toByte()
        header[4] = (flags ushr 24).toByte()
        header[5] = (flags ushr 16).toByte()
        header[6] = (flags ushr 8).toByte()
        header[7] = flags.toByte()
        out.write(header)
        out.write(bytes, 0, len)
        out.flush()
    }
}

fun frameFlags(bytes: ByteArray, len: Int, hevc: Boolean): Int {
    var flags = 0
    var slices = 0
    var i = 0
    while (i + 3 < len) {
        if (bytes[i].toInt() == 0 && bytes[i + 1].toInt() == 0 &&
            (bytes[i + 2].toInt() == 1 || (bytes[i + 2].toInt() == 0 && bytes[i + 3].toInt() == 1))
        ) {
            val at = if (bytes[i + 2].toInt() == 1) i + 3 else i + 4
            if (at >= len) break
            val b = bytes[at].toInt() and 0xFF
            val type = if (hevc) (b shr 1) and 0x3F else b and 0x1F
            if (hevc) {
                if (type == 32 || type == 33) flags = flags or FRAME_CONFIG
                if (type in 16..21) flags = flags or FRAME_KEY
                if (type <= 31) slices++
            } else {
                if (type == 7) flags = flags or FRAME_CONFIG
                if (type == 5) flags = flags or FRAME_KEY
                if (type in 1..5) slices++
            }
            i = at
        }
        i++
    }
    return if (slices > 0) flags and FRAME_CONFIG.inv() else flags or FRAME_CONFIG
}

class TimedStream(private val out: OutputStream) : OutputStream() {

    private var nanos = 0L
    private var bytes = 0L

    override fun write(b: Int) {
        val t = System.nanoTime()
        out.write(b)
        nanos += System.nanoTime() - t
        bytes++
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        val t = System.nanoTime()
        out.write(b, off, len)
        nanos += System.nanoTime() - t
        bytes += len
    }

    override fun flush() {
        val t = System.nanoTime()
        out.flush()
        nanos += System.nanoTime() - t
    }

    override fun close() = out.close()

    fun takeNanos(): Long {
        val n = nanos
        nanos = 0
        return n
    }

    fun takeBytes(): Long {
        val n = bytes
        bytes = 0
        return n
    }
}

class ScreenServer(
    private val opt: Options,
    private val onEvent: (String) -> Unit,
    private val onStats: (Stats) -> Unit = {},
    private val onClient: (String?) -> Unit = {},
    private val identity: WssIdentity? = null,
    private val devices: WssDevices? = null,
    private val pairing: () -> PairingWindow? = { null },
    private val onSas: (String) -> Unit = {},
    private val onInput: (Boolean) -> Unit = {},
    private val approve: (DeviceRequest) -> Boolean = { false }
) {

    @Volatile
    private var running = false

    @Volatile
    private var sendingVideo = true
    private var server: ServerSocket? = null
    private var thread: Thread? = null
    private var audio: WfasServer? = null
    private var beacon: WfasBeacon? = null

    private var advertiser: Advertiser? = null

    val isRunning: Boolean get() = running

    private fun advertisedAudioPort(): Int = when {
        opt.audioPort <= 0 -> 0
        !opt.audioInternal -> opt.audioPort
        audio?.isRunning == true -> opt.audioPort
        else -> 0
    }

    fun start() {
        if (running) return
        avutil.av_log_set_level(avutil.AV_LOG_ERROR)

        val addresses = localAddresses()
        val chosen = when (opt.linkMode) {
            LINK_USB -> addresses.firstOrNull { it.kind == LinkKind.USB }
                ?: run {
                    onEvent("cable only mode, but no USB link found.")
                    onEvent("plug the phone in and turn on Settings > Hotspot and tethering > USB tethering")
                    return
                }
            LINK_WIFI -> addresses.firstOrNull { it.kind == LinkKind.NORMAL }
                ?: run {
                    onEvent("Wi-Fi only mode, but no normal network interface found")
                    return
                }
            else -> null
        }

        sendingVideo = true

        val socket = ServerSocket()
        socket.reuseAddress = true
        try {
            val bind = chosen?.let { InetSocketAddress(it.address, opt.port) }
                ?: InetSocketAddress(opt.port)
            socket.bind(bind)
        } catch (e: Exception) {
            onEvent("cannot open port ${opt.port}: ${e.message}")
            runCatching { socket.close() }
            return
        }
        server = socket
        running = true

        if (chosen != null) {
            onEvent("listening only on ${chosen.address}:${opt.port}   ${chosen.label.ifEmpty { "Wi-Fi" }}")
        } else {
            onEvent("listening on port ${opt.port}")
            addresses.forEach {
                onEvent("  ${it.address}:${opt.port}" + if (it.label.isEmpty()) "" else "   ${it.label}")
            }
            if (addresses.none { it.kind == LinkKind.USB }) {
                onEvent("  no USB link: turn on USB tethering on the phone to use the cable")
            }
        }
        onEvent("codec ${if (opt.hevc) "H.265" else "H.264"}, ${opt.fps} fps, ${opt.bitrate / 1_000_000.0} Mbps, quality ${opt.quality}")
        if (opt.gpu && !GpuPipeline.eligible(opt)) {
            onEvent("gpu pipeline asked for, but it needs Windows with DXGI capture: ignored")
        }

        if (opt.audioPort > 0) {
            if (opt.audioInternal) {
                val wfas = WfasServer(
                    port = opt.audioPort,
                    sampleRate = opt.audioRate,
                    channels = opt.audioChannels,
                    muteLocal = opt.audioMuteLocal,
                    onEvent = onEvent
                )
                if (wfas.start()) {
                    audio = wfas
                    beacon = WfasBeacon(
                        name = "${hostName()}-WFSS",
                        port = opt.audioPort,
                        sampleRate = opt.audioRate,
                        channels = opt.audioChannels,
                        onEvent = onEvent
                    ).also { it.start() }
                } else {
                    onEvent("audio: off for this session, the phone will not be told about it")
                }
            } else {

                onEvent("WFAS v2 audio advertised on ${opt.audioPort}: start 'wfas --server --port ${opt.audioPort}'")
            }
        }

        if (opt.mdns) {
            val announce = if (chosen != null) listOf(chosen) else addresses.filter { it.kind != LinkKind.VIRTUAL }
            advertiser = Advertiser(opt, onEvent).also { it.start(announce) }
        }

        thread = Thread {
            while (running) {
                val client = try {
                    socket.accept()
                } catch (e: Exception) {
                    if (running) onEvent("accept failed: ${e.message}")
                    break
                }
                val who = client.inetAddress.hostAddress
                val local = describeLocal(client.localAddress?.hostAddress)
                val via = when (local?.kind) {
                    LinkKind.USB -> "  over the USB cable"
                    LinkKind.VIRTUAL -> "  over a virtual adapter"
                    else -> ""
                }
                onEvent("client connected: $who$via")
                runCatching { advertiser?.stop() }
                onEvent("mDNS: no longer announcing, this PC is taken")
                if (local?.kind != LinkKind.USB && localAddresses().any { it.kind == LinkKind.USB }) {
                    onEvent("  a USB cable is available: connect to that address for more bandwidth")
                }
                onClient(who)
                try {
                    serve(client)
                } catch (e: Exception) {
                    if (running) onEvent("session ended: ${e.javaClass.simpleName}: ${e.message}")
                } finally {
                    runCatching { client.close() }
                    runCatching { audio?.dropClient("the video client went away") }
                    onClient(null)
                    onEvent("client disconnected")
                    if (running && opt.mdns) {
                        val again = if (chosen != null) listOf(chosen) else {
                            localAddresses().filter { it.kind != LinkKind.VIRTUAL }
                        }
                        advertiser = Advertiser(opt, onEvent).also { it.start(again) }
                    }
                }
            }
            running = false
        }.also {
            it.isDaemon = true
            it.name = "screen-server"
            it.priority = Thread.MAX_PRIORITY
            it.start()
        }
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { advertiser?.stop() }
        advertiser = null
        runCatching { beacon?.stop() }
        beacon = null
        runCatching { audio?.stop() }
        audio = null
        runCatching { server?.close() }
        server = null
        thread = null
        onClient(null)
        onEvent("server stopped")
    }

    private fun serve(socket: Socket) {
        socket.tcpNoDelay = true
        socket.sendBufferSize = opt.sendBuffer

        if (!opt.video) {
            serveBlind(socket)
            return
        }

        if (GpuPipeline.eligible(opt)) {
            if (opt.gpuNative) {
                onEvent("route: trying the in-process pipeline")
                if (serveNative(socket)) return
                onEvent("route: the in-process pipeline did not take")
            }
            onEvent("route: trying an external ffmpeg")
            val plan = GpuPipeline.plan(opt, onEvent)
            if (plan != null) {
                serveGpu(socket, plan)
                return
            }
            onEvent("route: no gpu pipeline, back to the normal one")
        }
        onEvent("route: capture through JavaCV, frames go through RAM")
        serveJavacv(socket)
    }

    private fun writeHeader(header: DataOutputStream, outW: Int, outH: Int) {
        header.writeInt(MAGIC)
        header.writeInt(outW)
        header.writeInt(outH)
        header.writeInt(opt.fps)
        header.writeInt(if (opt.hevc) 1 else 0)
        header.writeInt(advertisedAudioPort())
        header.writeInt(opt.audioRate)
        header.writeInt(opt.audioChannels)
        header.writeInt(if (inputEnabled()) 1 else 0)
        header.flush()
    }

    private fun serveBlind(socket: Socket) {
        onEvent("the picture is turned off: this PC only takes commands")

        val timed = TimedStream(socket.getOutputStream())
        val buffered = BufferedOutputStream(timed, 1024)
        val header = DataOutputStream(buffered)
        writeHeader(header, 0, 0)
        timed.flush()

        if (!inputEnabled()) {
            onEvent("nothing to serve: picture off and control off. Turn one of them on.")
            return
        }

        startInput(socket)
        while (running && !socket.isClosed) Thread.sleep(200)
    }

    private fun serveNative(socket: Socket): Boolean {
        val chain = encoderChain(opt).filter { it.endsWith("_nvenc") }
        if (chain.isEmpty()) return false

        var surveyed = false

        for (name in chain) {
            for (refresh in if (opt.intraRefresh) listOf(true, false) else listOf(false)) {
                val engine = GpuNative(opt, onEvent)
                if (!surveyed) {
                    runCatching { engine.survey() }
                    surveyed = true
                }
                val opened = runCatching { engine.open(name, refresh) }
                    .onFailure { onEvent("gpu: the in-process pipeline blew up: ${it.javaClass.simpleName}: ${it.message}") }
                    .getOrDefault(false)

                if (!opened) {
                    onEvent("route: $name did not open in process, letting it go")
                    runCatching { engine.close() }
                    continue
                }

                try {
                    val where = when (engine.route) {
                        GPU_CUDA -> "the frames stay on the card, CUDA"
                        GPU_D3D11 -> "the frames stay on the card, straight from the desktop texture"
                        else -> "the frames come back to RAM: no CUDA route on this build"
                    }
                    val rate = if (opt.oversample > 1) "sampling at ${opt.fps * opt.oversample} to send ${opt.fps}" else "sampling at ${opt.fps}"
                    onEvent("capture ${engine.width}x${engine.height}, $where, $rate")
                    onEvent("encoder ${engine.name}, preset ${opt.speed}, intra-refresh ${if (refresh) "yes" else "no"}, in process")

                    val timed = TimedStream(socket.getOutputStream())
                    val buffered = BufferedOutputStream(timed, 32 * 1024)
                    val header = DataOutputStream(buffered)
                    writeHeader(header, engine.width, engine.height)
                    timed.flush()

                    if (inputEnabled()) startInput(socket)

                    val sink = FrameSink(buffered, onEvent)
                    engine.extradata()?.let { sink.write(it, it.size, FRAME_CONFIG) }
                    engine.run(sink, timed, { running }, { sendingVideo }, onStats)
                } finally {
                    runCatching { engine.close() }
                }
                return true
            }
        }
        return false
    }

    private fun serveGpu(socket: Socket, plan: GpuPlan) {
        onEvent("capture ${plan.srcW}x${plan.srcH} -> send ${plan.outW}x${plan.outH}  (ddagrab DXGI, on the card)")
        onEvent(GpuPipeline.describe(plan, opt))
        onEvent("gpu pipeline: capture and encoding happen inside ffmpeg, only pacing and send are measured here")

        val timed = TimedStream(socket.getOutputStream())
        val buffered = BufferedOutputStream(timed, 32 * 1024)
        val header = DataOutputStream(buffered)
        writeHeader(header, plan.outW, plan.outH)
        timed.flush()

        if (inputEnabled()) startInput(socket)

        GpuPipeline.run(
            plan, opt, FrameSink(buffered, onEvent), timed,
            { running && sendingVideo }, onEvent, onStats
        )
    }

    private fun serveJavacv(socket: Socket) {
        val grabber = openGrabber(opt)
        try {
            val srcW = grabber.imageWidth
            val srcH = grabber.imageHeight
            if (srcW <= 0 || srcH <= 0) error("capture failed: size $srcW x $srcH")

            val outW = even((srcW * opt.scale).toInt())
            val outH = even((srcH * opt.scale).toInt())
            onEvent("capture ${srcW}x$srcH -> send ${outW}x$outH  (${captureBackend(opt)})")

            val (encoder, intraRefresh) = pickEncoder(opt, outW, outH)
            onEvent(
                "encoder $encoder, preset ${opt.speed}, intra-refresh ${if (intraRefresh) "yes" else "no"}, " +
                    "pipeline ${if (opt.pipeline) "yes" else "no"}"
            )

            val timed = TimedStream(socket.getOutputStream())
            val buffered = BufferedOutputStream(timed, 32 * 1024)
            val header = DataOutputStream(buffered)
            writeHeader(header, outW, outH)
            timed.flush()

            if (inputEnabled()) startInput(socket)

            val collector = Collector()
            val sink = FrameSink(buffered, onEvent)
            val recorder = newRecorder(collector, outW, outH, opt, encoder, intraRefresh)
            recorder.start()
            emit(collector, sink)

            try {
                if (opt.pipeline) pumpPipelined(grabber, recorder, collector, sink, timed)
                else pumpSerial(grabber, recorder, collector, sink, timed)
            } finally {
                runCatching { recorder.stop() }
                runCatching { recorder.release() }
            }
        } finally {
            runCatching { grabber.stop() }
            runCatching { grabber.release() }
        }
    }

    private fun inputEnabled(): Boolean = opt.input && identity != null && devices != null

    private fun startInput(socket: Socket) {
        val id = identity ?: return
        val store = devices ?: return

        val injector = InputInjector(onEvent)
        if (!injector.available) {
            onEvent("input: no display available, remote control not active")
            return
        }

        val handshakeDone = java.util.concurrent.CountDownLatch(1)
        val handshakeFailure = java.util.concurrent.atomic.AtomicReference<Exception?>(null)

        Thread {
            val session = try {
                WssHandshake.serve(
                    socket, id, store, pairing(), hostName(), onSas,
                    {
                        onEvent("input: handshake complete, video can start")
                        handshakeDone.countDown()
                    },
                    approve
                )
            } catch (e: Exception) {
                onEvent("input: handshake failed: ${e.message}")
                handshakeFailure.set(e)
                handshakeDone.countDown()
                return@Thread
            } finally {
                handshakeDone.countDown()
            }

            if (session == null) {
                onEvent("input: the phone is only watching")
                return@Thread
            }

            onEvent(
                "input: ${session.peerName} authorized" +
                    if (session.paired) "  (new device, code ${session.sas})" else ""
            )

            onEvent("input: injecting on a ${injector.screenWidth}x${injector.screenHeight} desktop")
            injector.forgetRateWindow()
            onInput(true)

            var received = 0L
            try {
                while (running) {
                    val raw = session.channel.receive()
                    received++
                    if (received == 1L) onEvent("input: first message received, channel is alive")

                    val message = InputMessage.decode(raw)
                    if (message == null) {
                        onEvent("input: unknown message type ${raw[0].toInt() and 0xFF}, ignored")
                        continue
                    }

                    if (message is InputMessage.Video) {
                        sendingVideo = message.wanted
                        onEvent(
                            if (message.wanted) "the phone asked for the picture back"
                            else "the phone only wants to drive: capture paused, nothing is being encoded"
                        )
                        continue
                    }

                    try {
                        injector.apply(message)
                    } catch (e: WssProtocolException) {
                        throw e
                    } catch (e: Exception) {
                        onEvent("input: ${message.javaClass.simpleName} skipped: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                if (running) onEvent("input: channel closed after $received messages: ${e.message}")
            } finally {
                onEvent("input: stopped after $received messages")
                injector.shutdown()
                onInput(false)
            }
        }.also {
            it.isDaemon = true
            it.name = "screen-input"
            it.start()
        }

        if (!handshakeDone.await(150, TimeUnit.SECONDS)) {
            throw WssProtocolException("input: the phone did not finish the handshake")
        }
        handshakeFailure.get()?.let { throw it }
    }

    private fun hostName(): String = runCatching {
        java.net.InetAddress.getLocalHost().hostName.substringBefore('.')
    }.getOrNull()?.ifBlank { null } ?: "PC"

    private fun emit(collector: Collector, sink: FrameSink) {
        if (collector.size() == 0) return
        sink.write(collector.bytes(), collector.size(), frameFlags(collector.bytes(), collector.size(), opt.hevc))
        collector.reset()
    }

    private fun pumpSerial(
        grabber: FFmpegFrameGrabber,
        recorder: FFmpegFrameRecorder,
        collector: Collector,
        sink: FrameSink,
        timed: TimedStream
    ) {
        val window = opt.fps * 5
        val capture = Meter(window)
        val encode = Meter(window)
        val send = Meter(window)
        val cadence = Meter(window)

        var frames = 0L
        var previous = 0L
        var windowBytes = 0L
        var windowStart = System.nanoTime()

        timed.takeNanos()
        timed.takeBytes()

        while (running) {
            if (!sendingVideo) {
                Thread.sleep(40)
                continue
            }
            val t0 = System.nanoTime()
            val frame = grabber.grabImage() ?: break
            val t1 = System.nanoTime()
            recorder.record(frame)
            emit(collector, sink)
            val t2 = System.nanoTime()

            frames++
            if (!opt.stats) continue

            val socketNanos = timed.takeNanos()
            windowBytes += timed.takeBytes()

            capture.add((t1 - t0) / 1e6)
            encode.add((t2 - t1 - socketNanos) / 1e6)
            send.add(socketNanos / 1e6)
            if (previous != 0L) cadence.add((t2 - previous) / 1e6)
            previous = t2

            if (cadence.ready()) {
                val secs = (System.nanoTime() - windowStart) / 1e9
                onStats(
                    Stats(
                        fps = window / secs,
                        mbps = windowBytes * 8.0 / 1_000_000.0 / secs,
                        frames = frames,
                        dropped = 0,
                        capture = capture.take(),
                        encode = encode.take(),
                        send = send.take(),
                        cadence = cadence.take()
                    )
                )
                windowBytes = 0
                windowStart = System.nanoTime()
            }
        }
    }

    private fun pumpPipelined(
        grabber: FFmpegFrameGrabber,
        recorder: FFmpegFrameRecorder,
        collector: Collector,
        sink: FrameSink,
        timed: TimedStream
    ) {
        val seed = grabber.grabImage() ?: return
        val free = ArrayBlockingQueue<Frame>(3)
        val filled = ArrayBlockingQueue<Frame>(1)
        repeat(3) { free.put(seed.clone()) }

        val window = opt.fps * 5
        val capture = Meter(window)
        val encode = Meter(window)
        val send = Meter(window)
        val cadence = Meter(window)

        val grabbing = AtomicBoolean(true)
        val dropped = AtomicLong(0)

        val grabThread = Thread {
            try {
                while (running && grabbing.get()) {
                    if (!sendingVideo) {
                        Thread.sleep(40)
                        continue
                    }
                    val t0 = System.nanoTime()
                    val src = grabber.grabImage() ?: break
                    val t1 = System.nanoTime()

                    val slot = free.poll()
                    if (slot == null) {
                        dropped.incrementAndGet()
                        continue
                    }
                    copyInto(src, slot)

                    if (!filled.offer(slot)) {
                        val stale = filled.poll()
                        if (stale != null) {
                            free.offer(stale)
                            dropped.incrementAndGet()
                        }
                        if (!filled.offer(slot)) free.offer(slot)
                    }
                    if (opt.stats) capture.add((t1 - t0) / 1e6)
                }
            } catch (e: Exception) {
                if (running) onEvent("capture interrupted: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                grabbing.set(false)
            }
        }
        grabThread.isDaemon = true
        grabThread.name = "screen-capture"
        grabThread.priority = Thread.MAX_PRIORITY
        grabThread.start()

        var frames = 0L
        var previous = 0L
        var windowBytes = 0L
        var windowStart = System.nanoTime()

        timed.takeNanos()
        timed.takeBytes()

        try {
            while (running && (grabbing.get() || filled.isNotEmpty())) {
                val frame = filled.poll(200, TimeUnit.MILLISECONDS) ?: continue

                val t1 = System.nanoTime()
                recorder.record(frame)
                emit(collector, sink)
                val t2 = System.nanoTime()
                free.offer(frame)

                frames++
                if (!opt.stats) continue

                val socketNanos = timed.takeNanos()
                windowBytes += timed.takeBytes()

                encode.add((t2 - t1 - socketNanos) / 1e6)
                send.add(socketNanos / 1e6)
                if (previous != 0L) cadence.add((t2 - previous) / 1e6)
                previous = t2

                if (cadence.ready()) {
                    val secs = (System.nanoTime() - windowStart) / 1e9
                    onStats(
                        Stats(
                            fps = window / secs,
                            mbps = windowBytes * 8.0 / 1_000_000.0 / secs,
                            frames = frames,
                            dropped = dropped.get(),
                            capture = capture.take(),
                            encode = encode.take(),
                            send = send.take(),
                            cadence = cadence.take()
                        )
                    )
                    windowBytes = 0
                    windowStart = System.nanoTime()
                }
            }
        } finally {
            grabbing.set(false)
            runCatching { grabThread.join(500) }
        }
    }

    private fun copyInto(src: Frame, dst: Frame) {
        val from = src.image ?: return
        val into = dst.image ?: return
        for (i in from.indices) {
            if (i >= into.size) break
            val a = from[i]
            val b = into[i]
            if (a is ByteBuffer && b is ByteBuffer) {
                a.rewind()
                b.rewind()
                b.put(a)
                a.rewind()
                b.rewind()
            }
        }
        dst.timestamp = src.timestamp
    }
}

fun even(v: Int): Int = if (v % 2 == 0) v else v - 1

fun captureBackend(opt: Options): String {
    val os = System.getProperty("os.name").lowercase()
    return when {
        os.contains("win") && opt.dda -> "ddagrab DXGI"
        os.contains("win") -> "gdigrab GDI"
        os.contains("mac") -> "avfoundation"
        else -> "x11grab"
    }
}

fun openGrabber(opt: Options): FFmpegFrameGrabber {
    val os = System.getProperty("os.name").lowercase()
    val grabber = when {
        os.contains("win") && opt.dda ->
            FFmpegFrameGrabber("ddagrab=output_idx=${opt.display}:framerate=${opt.fps},hwdownload,format=bgra")
                .also { it.format = "lavfi" }

        os.contains("win") ->
            FFmpegFrameGrabber("desktop").also {
                it.format = "gdigrab"
                it.setOption("framerate", opt.fps.toString())
                it.setOption("draw_mouse", "1")
            }

        os.contains("mac") ->
            FFmpegFrameGrabber("${opt.display}:none").also {
                it.format = "avfoundation"
                it.setOption("capture_cursor", "1")
                it.setOption("framerate", opt.fps.toString())
            }

        else ->
            FFmpegFrameGrabber(":0.0").also {
                it.format = "x11grab"
                it.setOption("framerate", opt.fps.toString())
                it.setOption("draw_mouse", "1")
            }
    }
    grabber.start()
    return grabber
}

fun encoderChain(opt: Options): List<String> = when {
    opt.encoder != null -> listOf(opt.encoder)
    opt.hevc -> listOf("hevc_nvenc", "hevc_qsv", "hevc_amf", "hevc_videotoolbox", "libx265")
    else -> listOf("h264_nvenc", "h264_qsv", "h264_amf", "h264_videotoolbox", "libx264")
}

fun supportsIntraRefresh(encoder: String): Boolean =
    encoder.endsWith("_nvenc") || encoder == "libx264" || encoder == "libx265"

fun pickEncoder(opt: Options, w: Int, h: Int): Pair<String, Boolean> {
    if (opt.intraRefresh) {
        for (name in encoderChain(opt)) {
            if (!supportsIntraRefresh(name)) continue
            if (probe(opt, w, h, name, true)) return name to true
        }
    }
    for (name in encoderChain(opt)) {
        if (probe(opt, w, h, name, false)) return name to false
    }
    error("no usable encoder")
}

fun probe(opt: Options, w: Int, h: Int, encoder: String, intraRefresh: Boolean): Boolean {
    val recorder = newRecorder(ByteArrayOutputStream(), w, h, opt, encoder, intraRefresh)
    val outcome = runCatching { recorder.start() }
    runCatching { recorder.stop() }
    runCatching { recorder.release() }
    return outcome.isSuccess
}

fun newRecorder(
    out: OutputStream,
    w: Int,
    h: Int,
    opt: Options,
    encoder: String,
    intraRefresh: Boolean
): FFmpegFrameRecorder {
    val r = FFmpegFrameRecorder(out, w, h, 0)
    r.format = if (opt.hevc) "hevc" else "h264"
    r.setOption("flush_packets", "1")
    r.videoCodec = if (opt.hevc) avcodec.AV_CODEC_ID_HEVC else avcodec.AV_CODEC_ID_H264
    r.videoCodecName = encoder
    r.pixelFormat = avutil.AV_PIX_FMT_YUV420P
    r.frameRate = opt.fps.toDouble()
    r.videoBitrate = opt.bitrate
    r.gopSize = if (intraRefresh) opt.fps * 600 else opt.fps * opt.gopSeconds

    when {
        encoder.endsWith("_nvenc") -> {
            val hurry = opt.speed in listOf("p1", "p2", "p3")
            r.setVideoOption("preset", opt.speed)
            r.setVideoOption("tune", if (hurry) "ull" else "ll")
            r.setVideoOption("delay", "0")
            r.setVideoOption("rc-lookahead", "0")
            r.setVideoOption("rc", "vbr")
            r.setVideoOption("cq", opt.quality.toString())
            r.setVideoOption("maxrate", (opt.bitrate * 2).toString())
            r.setVideoOption("bf", "0")
            r.setVideoOption("spatial-aq", if (hurry) "0" else "1")
            if (intraRefresh) r.setVideoOption("intra-refresh", "1")
        }
        encoder.endsWith("_qsv") -> {
            r.setVideoOption("preset", "faster")
            r.setVideoOption("async_depth", "1")
            r.setVideoOption("bf", "0")
        }
        encoder.endsWith("_amf") -> {
            r.setVideoOption("usage", "lowlatency")
            r.setVideoOption("quality", "balanced")
            r.setVideoOption("bf", "0")
        }
        encoder.endsWith("_videotoolbox") -> {
            r.setVideoOption("realtime", "1")
        }
        else -> {
            r.setVideoOption("preset", "veryfast")
            r.setVideoOption("tune", "zerolatency")
            r.setVideoOption("crf", opt.quality.toString())
            r.setVideoOption("bf", "0")
            if (intraRefresh) r.setVideoOption("intra-refresh", "1")
        }
    }
    return r
}
