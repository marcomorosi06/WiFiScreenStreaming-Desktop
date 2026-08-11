import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avfilter.AVFilterContext
import org.bytedeco.ffmpeg.avfilter.AVFilterGraph
import org.bytedeco.ffmpeg.avutil.AVBufferRef
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avfilter
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.Pointer

const val GPU_CUDA = "cuda"
const val GPU_D3D11 = "d3d11"
const val GPU_READBACK = "readback"

class GpuNative(private val opt: Options, private val onEvent: (String) -> Unit) {

    private var graph: AVFilterGraph? = null
    private var device: AVBufferRef? = null
    private var outlet: AVFilterContext? = null
    private var encoder: AVCodecContext? = null

    var width = 0
        private set

    var height = 0
        private set

    var name = ""
        private set

    var route = ""
        private set

    private val sampling = opt.oversample.coerceIn(1, 4)

    private fun err(code: Int): String {
        val text = BytePointer(256L)
        avutil.av_strerror(code, text, 256)
        val message = text.string
        text.deallocate()
        return message
    }

    private fun fail(code: Int, what: String): Boolean {
        onEvent("gpu: $what: ${err(code)}")
        return false
    }

    fun survey() {
        val kinds = ArrayList<String>()
        var kind = avutil.av_hwdevice_iterate_types(avutil.AV_HWDEVICE_TYPE_NONE)
        while (kind != avutil.AV_HWDEVICE_TYPE_NONE) {
            avutil.av_hwdevice_get_type_name(kind)?.let { kinds.add(it.string) }
            kind = avutil.av_hwdevice_iterate_types(kind)
        }
        onEvent("gpu: hardware this build knows: ${if (kinds.isEmpty()) "none" else kinds.joinToString(", ")}")

        val wanted = listOf("ddagrab", "hwmap", "scale_cuda", "hwdownload", "scale", "format")
        val missing = wanted.filter { avfilter.avfilter_get_by_name(it).let { f -> f == null || f.isNull } }
        if (missing.isNotEmpty()) onEvent("gpu: filters missing from this build: ${missing.joinToString(", ")}")
    }

    private fun filter(g: AVFilterGraph, kind: String, label: String, args: String?): AVFilterContext? {
        val definition = avfilter.avfilter_get_by_name(kind)
        if (definition == null || definition.isNull) {
            onEvent("gpu: this build has no $kind filter")
            return null
        }
        val context = avfilter.avfilter_graph_alloc_filter(g, definition, label)
        if (context == null || context.isNull) {
            onEvent("gpu: cannot create $kind")
            return null
        }
        if (kind == "ddagrab") device?.let { context.hw_device_ctx(avutil.av_buffer_ref(it)) }
        val code = avfilter.avfilter_init_str(context, args)
        if (code < 0) {
            fail(code, "$kind did not start")
            return null
        }
        return context
    }

    private fun link(from: AVFilterContext, to: AVFilterContext, what: String): Boolean {
        val code = avfilter.avfilter_link(from, 0, to, 0)
        if (code < 0) return fail(code, "cannot link $what")
        return true
    }

    fun open(encoderName: String, intraRefresh: Boolean): Boolean {
        if (!encoderName.endsWith("_nvenc")) {
            onEvent("gpu: the in-process pipeline only knows nvenc, not $encoderName")
            return false
        }

        for (mode in listOf(GPU_D3D11, GPU_CUDA, GPU_READBACK)) {
            if (mode == GPU_D3D11 && opt.scale < 0.999) continue
            close()
            if (build(mode) && openEncoder(encoderName, intraRefresh, mode)) {
                route = mode
                name = encoderName
                return true
            }
            onEvent("gpu: the $mode route did not open")
        }
        close()
        return false
    }

    private fun build(mode: String): Boolean {
        val g = avfilter.avfilter_graph_alloc()
        if (g == null || g.isNull) {
            onEvent("gpu: cannot allocate the filter graph")
            return false
        }
        graph = g

        val dev = AVBufferRef(null as Pointer?)
        val made = avutil.av_hwdevice_ctx_create(
            dev, avutil.AV_HWDEVICE_TYPE_D3D11VA, null as BytePointer?, null, 0
        )
        if (made < 0) return fail(made, "no Direct3D 11 device")
        if (dev.isNull) {
            onEvent("gpu: the Direct3D 11 device came back empty")
            return false
        }
        device = dev

        val common = "output_idx=${opt.display}:framerate=${opt.fps * sampling}:draw_mouse=1"
        val source = filter(g, "ddagrab", "grab", "$common:output_fmt=8bit")
            ?: filter(g, "ddagrab", "grab8", common)
            ?: return false
        val sink = filter(g, "buffersink", "out", null) ?: return false

        var last = source
        when (mode) {
            GPU_CUDA -> {
                val map = filter(g, "hwmap", "map", "derive_device=cuda") ?: return false
                if (!link(last, map, "ddagrab to hwmap")) return false
                last = map

                val size = if (opt.scale >= 0.999) "" else "w=trunc(iw*${opt.scale}/2)*2:h=trunc(ih*${opt.scale}/2)*2:"
                val scaler = filter(g, "scale_cuda", "scale", "${size}format=nv12") ?: return false
                if (!link(last, scaler, "hwmap to scale_cuda")) return false
                last = scaler
            }

            GPU_READBACK -> {
                val down = filter(g, "hwdownload", "down", null) ?: return false
                if (!link(last, down, "ddagrab to hwdownload")) return false
                last = down

                if (opt.scale < 0.999) {
                    val scaler = filter(
                        g, "scale", "scale",
                        "w=trunc(iw*${opt.scale}/2)*2:h=trunc(ih*${opt.scale}/2)*2"
                    ) ?: return false
                    if (!link(last, scaler, "hwdownload to scale")) return false
                    last = scaler
                }

                val shape = filter(g, "format", "shape", "pix_fmts=nv12") ?: return false
                if (!link(last, shape, "to nv12")) return false
                last = shape
            }
        }

        if (!link(last, sink, "the last filter to the outlet")) return false

        val code = avfilter.avfilter_graph_config(g, null)
        if (code < 0) return fail(code, "the $mode graph does not hold together")
        outlet = sink

        width = avfilter.av_buffersink_get_w(sink)
        height = avfilter.av_buffersink_get_h(sink)
        if (width <= 0 || height <= 0) {
            onEvent("gpu: the graph gives a size of ${width}x$height")
            return false
        }
        return true
    }

    private fun openEncoder(encoderName: String, intraRefresh: Boolean, mode: String): Boolean {
        val sink = outlet ?: return false

        val codec = avcodec.avcodec_find_encoder_by_name(encoderName)
        if (codec == null || codec.isNull) {
            onEvent("gpu: no $encoderName in this build")
            return false
        }

        val enc = avcodec.avcodec_alloc_context3(codec)
        if (enc == null || enc.isNull) {
            onEvent("gpu: cannot allocate the encoder")
            return false
        }
        encoder = enc

        enc.width(width)
        enc.height(height)
        enc.time_base(avfilter.av_buffersink_get_time_base(sink))
        enc.framerate(avutil.av_make_q(opt.fps, 1))
        enc.bit_rate(opt.bitrate.toLong())
        enc.rc_max_rate(opt.bitrate.toLong() * 2)
        enc.gop_size(if (intraRefresh) opt.fps * 600 else opt.fps * opt.gopSeconds)
        enc.max_b_frames(0)

        when (mode) {
            GPU_CUDA -> {
                enc.pix_fmt(avutil.AV_PIX_FMT_CUDA)
                enc.sw_pix_fmt(avutil.AV_PIX_FMT_NV12)
            }
            GPU_D3D11 -> {
                enc.pix_fmt(avutil.AV_PIX_FMT_D3D11)
                enc.sw_pix_fmt(avutil.AV_PIX_FMT_BGRA)
            }
            else -> enc.pix_fmt(avutil.AV_PIX_FMT_NV12)
        }

        if (mode != GPU_READBACK) {
            val frames = avfilter.av_buffersink_get_hw_frames_ctx(sink)
            if (frames == null || frames.isNull) {
                onEvent("gpu: the frames did not stay on the card")
                return false
            }
            enc.hw_frames_ctx(avutil.av_buffer_ref(frames))
        }

        val hurry = opt.speed in listOf("p1", "p2", "p3")
        val options = AVDictionary()
        fun set(key: String, value: String) {
            avutil.av_dict_set(options, key, value, 0)
        }
        set("preset", opt.speed)
        set("tune", if (hurry) "ull" else "ll")
        set("delay", "0")
        set("rc-lookahead", "0")
        set("rc", "vbr")
        set("cq", opt.quality.toString())
        set("bf", "0")
        set("spatial-aq", if (hurry) "0" else "1")
        if (intraRefresh) set("intra-refresh", "1")

        val code = avcodec.avcodec_open2(enc, codec, options)
        avutil.av_dict_free(options)
        if (code < 0) return fail(code, "$encoderName did not open")
        return true
    }

    fun extradata(): ByteArray? {
        val enc = encoder ?: return null
        val size = enc.extradata_size()
        if (size <= 0) return null
        val bytes = ByteArray(size)
        enc.extradata().capacity(size.toLong()).get(bytes)
        return bytes
    }

    fun run(
        sink: FrameSink,
        timed: TimedStream,
        running: () -> Boolean,
        sending: () -> Boolean,
        onStats: (Stats) -> Unit
    ) {
        val enc = encoder ?: return
        val outfilter = outlet ?: return

        val frame = avutil.av_frame_alloc()
        val packet = avcodec.av_packet_alloc()

        val window = opt.fps * 5
        val send = Meter(window)
        val cadence = Meter(window)
        val capture = Meter(window)
        val encodeTime = Meter(window)

        var frames = 0L
        var previous = 0L
        var pending = 0
        var windowBytes = 0L
        var windowStart = System.nanoTime()

        val period = 1_000_000_000L / opt.fps
        val ripe = period - period / (2 * sampling)
        var lastSent = 0L
        var skipped = 0L

        timed.takeNanos()
        timed.takeBytes()

        try {
            while (running()) {
                if (!sending()) {
                    Thread.sleep(40)
                    continue
                }
                val t0 = System.nanoTime()
                val got = avfilter.av_buffersink_get_frame(outfilter, frame)
                if (got == avutil.AVERROR_EAGAIN() || got == avutil.AVERROR_EOF) break
                if (got < 0) {
                    onEvent("gpu: the capture stopped: ${err(got)}")
                    break
                }

                val t1 = System.nanoTime()
                if (lastSent != 0L && t1 - lastSent < ripe) {
                    avutil.av_frame_unref(frame)
                    skipped++
                    continue
                }
                lastSent = t1
                val sent = avcodec.avcodec_send_frame(enc, frame)
                avutil.av_frame_unref(frame)
                if (sent < 0) {
                    onEvent("gpu: the encoder refused a frame: ${err(sent)}")
                    break
                }

                while (true) {
                    val out = avcodec.avcodec_receive_packet(enc, packet)
                    if (out == avutil.AVERROR_EAGAIN() || out == avutil.AVERROR_EOF) break
                    if (out < 0) {
                        onEvent("gpu: the encoder stopped: ${err(out)}")
                        return
                    }

                    val size = packet.size()
                    val bytes = ByteArray(size)
                    packet.data().capacity(size.toLong()).get(bytes)
                    val key = (packet.flags() and avcodec.AV_PKT_FLAG_KEY) != 0
                    avcodec.av_packet_unref(packet)

                    val t2 = System.nanoTime()
                    sink.write(bytes, size, if (key) FRAME_KEY else 0)
                    val now = System.nanoTime()

                    frames++
                    if (!opt.stats) continue

                    val socketNanos = timed.takeNanos()
                    windowBytes += timed.takeBytes()
                    pending++

                    capture.add((t1 - t0) / 1e6)
                    encodeTime.add((t2 - t1) / 1e6)
                    send.add(socketNanos / 1e6)
                    if (previous != 0L) cadence.add((now - previous) / 1e6)
                    previous = now

                    if (pending >= window) {
                        val secs = (System.nanoTime() - windowStart) / 1e9
                        onStats(
                            Stats(
                                fps = pending / secs,
                                mbps = windowBytes * 8.0 / 1_000_000.0 / secs,
                                frames = frames,
                                dropped = skipped,
                                capture = capture.take(),
                                encode = encodeTime.take(),
                                send = send.take(),
                                cadence = cadence.take()
                            )
                        )
                        pending = 0
                        windowBytes = 0
                        windowStart = System.nanoTime()
                    }
                }
            }
        } finally {
            avutil.av_frame_free(frame)
            avcodec.av_packet_free(packet)
        }
    }

    fun close() {
        encoder?.let { runCatching { avcodec.avcodec_free_context(it) } }
        encoder = null
        graph?.let { runCatching { avfilter.avfilter_graph_free(it) } }
        graph = null
        outlet = null
        device?.let { runCatching { avutil.av_buffer_unref(it) } }
        device = null
    }
}
