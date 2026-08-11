import org.bytedeco.javacpp.Loader
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

data class GpuPlan(
    val binary: String,
    val encoder: String,
    val intraRefresh: Boolean,
    val chain: String,
    val srcW: Int,
    val srcH: Int,
    val outW: Int,
    val outH: Int
)

object GpuPipeline {

    private val sizePattern = Regex("(\\d{2,5})x(\\d{2,5})")

    fun eligible(opt: Options): Boolean {
        if (!opt.gpu) return false
        if (!System.getProperty("os.name").lowercase().contains("win")) return false
        return opt.dda
    }

    fun binary(opt: Options, onEvent: (String) -> Unit): String? {
        opt.ffmpeg?.let {
            if (File(it).isFile) return it
            onEvent("gpu: $it is not a file")
            return null
        }

        System.getenv("WFSS_FFMPEG")?.let {
            if (File(it).isFile) return it
            onEvent("gpu: WFSS_FFMPEG points at $it, which is not a file")
        }

        val bundled = runCatching { Loader.load(org.bytedeco.ffmpeg.ffmpeg::class.java) }
        bundled.getOrNull()?.let {
            if (File(it).isFile) return it
            onEvent("gpu: the bundled ffmpeg says it is at $it, but nothing is there")
        }
        bundled.exceptionOrNull()?.let {
            onEvent("gpu: the bundled ffmpeg did not come out: ${it.javaClass.simpleName}: ${it.message}")
        }

        cached(onEvent)?.let { return it }

        val onPath = runCatching {
            val probe = ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).start()
            probe.inputStream.readBytes()
            probe.waitFor(4, TimeUnit.SECONDS) && probe.exitValue() == 0
        }
        if (onPath.getOrDefault(false)) return "ffmpeg"
        onEvent("gpu: no ffmpeg on PATH either")

        return null
    }

    private fun cached(onEvent: (String) -> Unit): String? {
        val name = if (System.getProperty("os.name").lowercase().contains("win")) "ffmpeg.exe" else "ffmpeg"
        val roots = listOfNotNull(
            System.getProperty("org.bytedeco.javacpp.cachedir"),
            System.getProperty("user.home")?.let { "$it/.javacpp/cache" }
        ).map { File(it) }.filter { it.isDirectory }

        for (root in roots) {
            val hit = root.walkTopDown().maxDepth(4).firstOrNull { it.isFile && it.name == name }
            if (hit != null) {
                onEvent("gpu: found ${hit.path} in the javacpp cache")
                return hit.path
            }
        }
        return null
    }

    fun plan(opt: Options, onEvent: (String) -> Unit): GpuPlan? {
        val binary = binary(opt, onEvent) ?: run {
            onEvent("gpu: no ffmpeg to run. Install it (winget install Gyan.FFmpeg) or set the path in the settings")
            return null
        }

        val size = detectSize(binary, opt) ?: run {
            onEvent("gpu: cannot read the size of display ${opt.display}")
            return null
        }
        val srcW = size.first
        val srcH = size.second
        val outW = even((srcW * opt.scale).toInt())
        val outH = even((srcH * opt.scale).toInt())

        val known = cached
        if (known != null && known.binary == binary && known.srcW == srcW && known.srcH == srcH &&
            known.outW == outW && known.outH == outH && cachedKey == key(opt)
        ) return known

        val attempts = ArrayList<Triple<String, Boolean, String>>()
        for (encoder in encoderChain(opt)) {
            val refreshFirst = opt.intraRefresh && supportsIntraRefresh(encoder)
            for (intraRefresh in if (refreshFirst) listOf(true, false) else listOf(false)) {
                for (chain in chains(encoder, srcW, srcH, outW, outH)) {
                    attempts += Triple(encoder, intraRefresh, chain)
                }
            }
        }

        onEvent("gpu: ffmpeg at $binary")

        for ((encoder, intraRefresh, chain) in attempts) {
            val candidate = GpuPlan(binary, encoder, intraRefresh, chain, srcW, srcH, outW, outH)
            AppDebug.log("gpu probe: " + command(candidate, opt, 8).joinToString(" "))
            if (works(candidate, opt)) {
                cached = candidate
                cachedKey = key(opt)
                return candidate
            }
        }

        onEvent("gpu: no working ffmpeg chain, ${attempts.size} attempts")
        return null
    }

    fun run(
        plan: GpuPlan,
        opt: Options,
        sink: FrameSink,
        timed: TimedStream,
        running: () -> Boolean,
        onEvent: (String) -> Unit,
        onStats: (Stats) -> Unit
    ) {
        val line = command(plan, opt, null)
        AppDebug.log("gpu: " + line.joinToString(" "))
        val process = ProcessBuilder(line).start()
        val stderr = Thread {
            runCatching {
                process.errorStream.bufferedReader().forEachLine {
                    if (it.isNotBlank()) onEvent("ffmpeg: $it")
                }
            }
        }
        stderr.isDaemon = true
        stderr.name = "gpu-stderr"
        stderr.start()

        val window = opt.fps * 5
        val send = Meter(window)
        val cadence = Meter(window)
        val idle = Sample(0.0, 0.0, 0.0)

        var frames = 0L
        var previous = 0L
        var pending = 0
        var windowBytes = 0L
        var windowStart = System.nanoTime()

        timed.takeNanos()
        timed.takeBytes()

        val buffer = ByteArray(64 * 1024)
        val unit = Collector()
        try {
            val stdout = process.inputStream
            while (running()) {
                val n = stdout.read(buffer)
                if (n < 0) break
                if (n == 0) continue

                unit.write(buffer, 0, n)
                if (stdout.available() > 0) continue
                Thread.sleep(1)
                if (stdout.available() > 0) continue

                sink.write(unit.bytes(), unit.size(), frameFlags(unit.bytes(), unit.size(), opt.hevc))
                unit.reset()
                val now = System.nanoTime()

                frames++
                if (!opt.stats) continue

                val socketNanos = timed.takeNanos()
                windowBytes += timed.takeBytes()
                pending++

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
                            dropped = 0,
                            capture = idle,
                            encode = idle,
                            send = send.take(),
                            cadence = cadence.take()
                        )
                    )
                    pending = 0
                    windowBytes = 0
                    windowStart = System.nanoTime()
                }
            }
            if (running()) onEvent("gpu: ffmpeg stopped after $frames frames")
        } finally {
            runCatching { process.destroyForcibly() }
            runCatching { process.waitFor(2, TimeUnit.SECONDS) }
        }
    }

    fun describe(plan: GpuPlan, opt: Options): String =
        "encoder ${plan.encoder}, preset ${opt.speed}, intra-refresh ${if (plan.intraRefresh) "yes" else "no"}, " +
            "filter ${plan.chain.ifEmpty { "none, straight from the desktop texture" }}"

    @Volatile
    private var cached: GpuPlan? = null

    @Volatile
    private var cachedKey: String? = null

    private fun key(opt: Options): String = listOf(
        opt.display, opt.fps, opt.scale, opt.hevc, opt.encoder, opt.intraRefresh,
        opt.speed, opt.quality, opt.bitrate, opt.gopSeconds
    ).joinToString("|")

    private fun chains(encoder: String, srcW: Int, srcH: Int, outW: Int, outH: Int): List<String> {
        val same = srcW == outW && srcH == outH
        val cpu = "hwdownload,format=bgra,scale=$outW:$outH,format=nv12"
        return when {
            encoder.endsWith("_nvenc") -> listOfNotNull(
                "hwmap=derive_device=cuda,scale_cuda=w=$outW:h=$outH:format=nv12",
                "hwmap=derive_device=cuda,scale_cuda=w=$outW:h=$outH:format=yuv420p",
                if (same) "" else null,
                cpu
            )
            encoder.endsWith("_qsv") -> listOfNotNull(
                "hwmap=derive_device=qsv,scale_qsv=w=$outW:h=$outH:format=nv12",
                if (same) "" else null,
                cpu
            )
            encoder.endsWith("_amf") -> listOfNotNull(
                if (same) "" else null,
                cpu
            )
            else -> listOf("hwdownload,format=bgra,scale=$outW:$outH,format=yuv420p")
        }
    }

    private fun command(plan: GpuPlan, opt: Options, frames: Int?): List<String> {
        val args = ArrayList<String>()
        args += plan.binary
        args += listOf("-hide_banner", "-nostdin", "-loglevel", "error")
        args += listOf("-fflags", "nobuffer", "-flags", "low_delay")
        args += listOf(
            "-f", "lavfi",
            "-i", "ddagrab=output_idx=${opt.display}:framerate=${opt.fps}:draw_mouse=1"
        )
        if (plan.chain.isNotEmpty()) args += listOf("-vf", plan.chain)
        if (frames != null) args += listOf("-frames:v", frames.toString())
        args += listOf("-fps_mode", "passthrough")
        args += listOf("-c:v", plan.encoder)
        args += encoderArgs(plan, opt)
        args += listOf("-f", if (opt.hevc) "hevc" else "h264", "-flush_packets", "1", "-")
        return args
    }

    private fun encoderArgs(plan: GpuPlan, opt: Options): List<String> {
        val args = ArrayList<String>()
        args += listOf("-b:v", opt.bitrate.toString())
        args += listOf("-maxrate", (opt.bitrate.toLong() * 2).toString())
        args += listOf("-g", (if (plan.intraRefresh) opt.fps * 600 else opt.fps * opt.gopSeconds).toString())
        args += listOf("-bf", "0")

        when {
            plan.encoder.endsWith("_nvenc") -> {
                val hurry = opt.speed in listOf("p1", "p2", "p3")
                args += listOf("-preset", opt.speed)
                args += listOf("-tune", if (hurry) "ull" else "ll")
                args += listOf("-delay", "0")
                args += listOf("-rc-lookahead", "0")
                args += listOf("-rc", "vbr")
                args += listOf("-cq", opt.quality.toString())
                args += listOf("-spatial-aq", if (hurry) "0" else "1")
                if (plan.intraRefresh) args += listOf("-intra-refresh", "1")
            }
            plan.encoder.endsWith("_qsv") -> {
                args += listOf("-preset", "faster")
                args += listOf("-async_depth", "1")
            }
            plan.encoder.endsWith("_amf") -> {
                args += listOf("-usage", "lowlatency")
                args += listOf("-quality", "balanced")
            }
            else -> {
                args += listOf("-preset", "veryfast")
                args += listOf("-tune", "zerolatency")
                args += listOf("-crf", opt.quality.toString())
                if (plan.intraRefresh) args += listOf("-intra-refresh", "1")
            }
        }
        return args
    }

    private fun works(plan: GpuPlan, opt: Options): Boolean {
        val process = runCatching { ProcessBuilder(command(plan, opt, 8)).start() }.getOrNull() ?: return false
        val seen = CountDownLatch(1)
        var bytes = 0L

        val drain = Thread {
            runCatching {
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = process.inputStream.read(buf)
                    if (n < 0) break
                    bytes += n
                    if (bytes > 0) seen.countDown()
                }
            }
            seen.countDown()
        }
        drain.isDaemon = true
        drain.start()

        val noise = Thread { runCatching { process.errorStream.readBytes() } }
        noise.isDaemon = true
        noise.start()

        val ok = runCatching { seen.await(10, TimeUnit.SECONDS) }.getOrDefault(false) && bytes > 0
        runCatching { process.destroyForcibly() }
        runCatching { process.waitFor(2, TimeUnit.SECONDS) }
        return ok
    }

    private fun detectSize(binary: String, opt: Options): Pair<Int, Int>? {
        val command = listOf(
            binary, "-hide_banner", "-nostdin",
            "-f", "lavfi", "-i", "ddagrab=output_idx=${opt.display}:framerate=${opt.fps}",
            "-frames:v", "1", "-f", "null", "-"
        )
        val text = runCatching {
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor(10, TimeUnit.SECONDS)
            runCatching { process.destroyForcibly() }
            output
        }.getOrNull()

        val line = text?.lineSequence()?.firstOrNull { it.contains("Video:") && sizePattern.containsMatchIn(it) }
        val match = line?.let { sizePattern.find(it) }
        if (match != null) {
            val w = match.groupValues[1].toInt()
            val h = match.groupValues[2].toInt()
            if (w > 0 && h > 0) return w to h
        }
        return desktopSize(opt.display)?.let { even(it.first) to even(it.second) }
    }

    private fun desktopSize(display: Int): Pair<Int, Int>? = runCatching {
        val devices = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
        val device = devices.getOrNull(display) ?: devices.firstOrNull() ?: return null
        val mode = device.displayMode
        if (mode.width > 0 && mode.height > 0) mode.width to mode.height else null
    }.getOrNull()
}
