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
import java.io.File
import java.net.NetworkInterface
import java.util.Properties

const val MAGIC = 0x57535333

const val FRAME_CONFIG = 1
const val FRAME_KEY = 2

data class Options(
    val port: Int = 5000,
    val fps: Int = 60,
    val bitrate: Int = 10_000_000,
    val scale: Double = 1.0,
    val hevc: Boolean = false,
    val dda: Boolean = false,
    val display: Int = 0,
    val encoder: String? = null,
    val sendBuffer: Int = 128 * 1024,
    val gopSeconds: Int = 4,
    val intraRefresh: Boolean = true,
    val stats: Boolean = true,
    val pipeline: Boolean = true,
    val video: Boolean = true,
    val gpu: Boolean = false,
    val gpuNative: Boolean = true,
    val oversample: Int = 2,
    val ffmpeg: String? = null,
    val linkMode: String = LINK_AUTO,
    val mdns: Boolean = true,
    val speed: String = "p4",
    val quality: Int = 21,
    val audioPort: Int = 0,
    val audioRate: Int = 48000,
    val audioChannels: Int = 2,
    val audioInternal: Boolean = true,
    val audioMuteLocal: Boolean = false,
    val input: Boolean = false,
    val theme: String = THEME_SYSTEM,
    val accent: Long = 0L,
    val closeToTray: Boolean = true,
    val startMinimized: Boolean = false
)

const val THEME_LIGHT = "light"
const val THEME_DARK = "dark"
const val THEME_SYSTEM = "system"

const val LINK_AUTO = "auto"
const val LINK_USB = "usb"
const val LINK_WIFI = "wifi"

val TUNE_NAMES = listOf("game", "video", "text")

fun applyTune(o: Options, name: String): Options = when (name.lowercase()) {
    "game", "gaming" ->
        o.copy(
            fps = 60, bitrate = 20_000_000, intraRefresh = true, gopSeconds = 60,
            speed = "p2", quality = 23, sendBuffer = 48 * 1024
        )
    "video", "movie" ->
        o.copy(fps = 30, bitrate = 10_000_000, intraRefresh = false, gopSeconds = 2, speed = "p5", quality = 21)
    "text", "read", "desktop", "work" ->
        o.copy(fps = 30, bitrate = 25_000_000, intraRefresh = false, gopSeconds = 4, speed = "p6", quality = 16)
    else -> error("unknown --tune: $name (game | video | text)")
}

fun parse(args: Array<String>, base: Options = Options()): Options {
    var o = base

    var t = 0
    while (t < args.size) {
        if (args[t] == "--tune") {
            if (t + 1 >= args.size) error("--tune needs a value")
            o = applyTune(o, args[t + 1])
        }
        t++
    }

    var i = 0
    fun next(flag: String): String {
        if (i + 1 >= args.size) error("$flag needs a value")
        i++
        return args[i]
    }
    while (i < args.size) {
        when (val a = args[i]) {
            "--tune" -> next(a)
            "--port" -> o = o.copy(port = next(a).toInt())
            "--fps" -> o = o.copy(fps = next(a).toInt())
            "--bitrate" -> o = o.copy(bitrate = (next(a).toDouble() * 1_000_000).toInt())
            "--scale" -> o = o.copy(scale = next(a).toDouble())
            "--half" -> o = o.copy(scale = 0.5)
            "--hevc" -> o = o.copy(hevc = true)
            "--h264" -> o = o.copy(hevc = false)
            "--ddagrab" -> o = o.copy(dda = true)
            "--gdigrab" -> o = o.copy(dda = false)
            "--display" -> o = o.copy(display = next(a).toInt())
            "--encoder" -> o = o.copy(encoder = next(a))
            "--send-buffer" -> o = o.copy(sendBuffer = next(a).toInt() * 1024)
            "--gop" -> o = o.copy(gopSeconds = next(a).toInt())
            "--speed" -> o = o.copy(speed = next(a))
            "--quality" -> o = o.copy(quality = next(a).toInt())
            "--intra-refresh" -> o = o.copy(intraRefresh = true)
            "--no-intra-refresh" -> o = o.copy(intraRefresh = false)
            "--no-stats" -> o = o.copy(stats = false)
            "--pipeline" -> o = o.copy(pipeline = true)
            "--no-pipeline" -> o = o.copy(pipeline = false)
            "--video" -> o = o.copy(video = true)
            "--no-video" -> o = o.copy(video = false)
            "--gpu" -> o = o.copy(gpu = true, dda = true)
            "--no-gpu" -> o = o.copy(gpu = false)
            "--oversample" -> o = o.copy(oversample = next(a).toInt().coerceIn(1, 4))
            "--gpu-native" -> o = o.copy(gpu = true, dda = true, gpuNative = true)
            "--gpu-ffmpeg" -> o = o.copy(gpu = true, dda = true, gpuNative = false)
            "--ffmpeg" -> o = o.copy(ffmpeg = next(a))
            "--usb" -> o = o.copy(linkMode = LINK_USB)
            "--no-usb" -> o = o.copy(linkMode = LINK_WIFI)
            "--link" -> o = o.copy(linkMode = next(a).lowercase())
            "--mdns" -> o = o.copy(mdns = true)
            "--no-mdns" -> o = o.copy(mdns = false)
            "--audio-port" -> o = o.copy(audioPort = next(a).toInt())
            "--audio" -> o = o.copy(audioPort = 9090)
            "--no-audio" -> o = o.copy(audioPort = 0)
            "--audio-rate" -> o = o.copy(audioRate = next(a).toInt())
            "--audio-channels" -> o = o.copy(audioChannels = next(a).toInt())
            "--audio-external" -> o = o.copy(audioInternal = false)
            "--audio-internal" -> o = o.copy(audioInternal = true)
            "--audio-mute-local" -> o = o.copy(audioMuteLocal = true)
            "--input" -> o = o.copy(input = true)
            "--no-input" -> o = o.copy(input = false)
            "--help", "-h" -> { usage(); kotlin.system.exitProcess(0) }
            else -> error("unknown option: $a")
        }
        i++
    }
    return o
}

fun usage() {
    println(
        """
        wifi screen streaming - desktop server (proof of concept)

          (no flags)          opens the window
          --gui               opens the window
          --cli               stays in the terminal

          --tune <p>          preset: game | video | text
                              game  60 fps, 20 Mbps, cq 23, intra-refresh
                              video 30 fps, 10 Mbps, cq 21
                              text  30 fps, 25 Mbps, cq 16, for reading
                              Put it before the other flags, which override it.

          --quality <n>       quantizer: lower = sharper           (default 21)
                              nvenc uses cq, libx264 uses crf. Scale 0-51.
          --speed <p>         nvenc preset p1..p7: p1 very fast and ugly,
                              p7 slow and pretty                   (default p4)

          --port <n>          TCP port                             (default 5000)
          --fps <n>           frames per second                    (default 60)
          --bitrate <mbps>    bitrate in Mbps, decimals allowed    (default 10)
          --scale <f>         scale factor, e.g. 0.5               (default 1.0)
          --half              shorthand for --scale 0.5
          --hevc              use H.265 instead of H.264
          --h264              force H.264 (default)
          --ddagrab           DXGI capture instead of GDI (Windows, faster and
                              above all steadier)
          --gdigrab           force GDI capture
          --display <n>       display index                        (default 0)
          --encoder <name>    force an encoder, e.g. h264_nvenc, libx264
          --send-buffer <kb>  socket SO_SNDBUF                     (default 128)
          --gop <s>           keyframe distance, seconds           (default 4)
          --no-intra-refresh  go back to periodic keyframes
          --no-stats          no latency statistics
          --no-pipeline       capture and encoding on the same thread, as before:
                              only useful to compare pacing
          --gpu               keep the frames on the graphics card: capture and
                              encoding run in one ffmpeg process, no readback to
                              RAM, no colour conversion on the CPU.
                              Windows and DXGI capture only, turns --ddagrab on.
                              If it does not start it says so and falls back.
          --no-video          do not send the picture at all: the PC only takes
                              mouse and keyboard from the phone. Pair it with
                              --audio-port 0 to serve nothing but control.
          --video             send the picture (default)
          --no-gpu            the normal pipeline (default)
          --gpu-native        build the graph inside this process with the ffmpeg
                              libraries that are already here: no external
                              program, exact frame boundaries       (default)
          --gpu-ffmpeg        run an external ffmpeg instead: useful to compare,
                              or if the in-process one does not start
          --oversample <n>    with --gpu-native, capture n times faster than you
                              send and encode the freshest frame of each period
                              (default 2, max 4). It stops the beat between the
                              capture timer and the screen, and halves how old a
                              frame is when it reaches the encoder.
                              1 turns it off.
          --ffmpeg <path>     ffmpeg executable for --gpu (default: the bundled
                              one, then the one on PATH)

        link
          --link <m>          auto | usb | wifi                    (default auto)
                              auto  listen on every interface
                              usb   listen on the cable only: USB tethering must
                                    be on, otherwise it will not start
                              wifi  ignore the cable
          --usb               shorthand for --link usb
          --no-usb            shorthand for --link wifi
          --no-mdns           do not advertise the service over mDNS
                              (by default the phone finds you with Search)

        audio (WFAS v2, served by this process)
          --audio             turn the audio on, port 9090
          --audio-port <n>    turn the audio on, specific port
          --no-audio          no audio
          --audio-rate <n>    sample rate                          (default 48000)
          --audio-channels    channels                             (default 2)
          --audio-mute-local  mute the audio on the PC while streaming
          --audio-external    do not serve the audio, only advertise the port:
                              a separate 'wfas --server --port <n>' serves it

        remote control (mouse and keyboard from the phone)
          --input             enable remote control          (default: disabled)
          --no-input          disable remote control
          --pair              open a pairing window at startup and print the PIN
                              to type on the phone                (text mode only)

        Remote control is end-to-end encrypted and needs a PIN pairing. From the
        window: tick "allow mouse and keyboard from the phone", then press
        "Pair device". See INPUT_PROTOCOL.md.

        The audio rides on the WFAS v2 protocol unchanged, unicast and
        unauthenticated: this proof of concept implements neither an
        authenticated handshake nor encryption for it.
        """.trimIndent()
    )
}

enum class LinkKind { USB, NORMAL, VIRTUAL }

data class LocalAddress(val address: String, val kind: LinkKind, val via: String) {

    val label: String
        get() = when (kind) {
            LinkKind.USB -> "USB cable"
            LinkKind.VIRTUAL -> "virtual"
            LinkKind.NORMAL -> ""
        }

    override fun toString(): String =
        if (label.isEmpty()) address else "$address  ($label)"
}

private fun classify(name: String, display: String, address: String): LinkKind {
    val text = (name + " " + display).lowercase()

    if (text.contains("rndis") ||
        text.contains("remote ndis") ||
        text.contains("android") ||
        text.contains("usb") ||
        address.startsWith("192.168.42.")
    ) return LinkKind.USB

    if (text.contains("vethernet") ||
        text.contains("hyper-v") ||
        text.contains("wsl") ||
        text.contains("virtualbox") ||
        text.contains("vmware") ||
        text.contains("docker") ||
        text.contains("loopback") ||
        text.contains("tailscale") ||
        text.contains("zerotier")
    ) return LinkKind.VIRTUAL

    return LinkKind.NORMAL
}

fun localAddresses(): List<LocalAddress> = runCatching {
    NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { nif ->
            nif.inetAddresses.toList()
                .filter { !it.isLoopbackAddress && it.address.size == 4 }
                .map { addr ->
                    val host = addr.hostAddress ?: ""
                    LocalAddress(host, classify(nif.name, nif.displayName ?: "", host), nif.displayName ?: nif.name)
                }
        }
        .sortedBy { it.kind.ordinal }
}.getOrDefault(emptyList())

fun describeLocal(address: String?): LocalAddress? =
    if (address == null) null else localAddresses().firstOrNull { it.address == address }

object Config {

    fun identityFile(): File = File(file().parentFile, "identity.key")

    fun devicesFile(): File = File(file().parentFile, "devices.tsv")

    fun file(): File {
        val os = System.getProperty("os.name", "").lowercase()
        val home = System.getProperty("user.home")
        val dir = when {
            os.contains("win") ->
                File(System.getenv("APPDATA") ?: "$home\\AppData\\Roaming", "wfss")
            os.contains("mac") ->
                File("$home/Library/Application Support", "wfss")
            else ->
                File(System.getenv("XDG_CONFIG_HOME") ?: "$home/.config", "wfss")
        }
        return File(dir, "config.properties")
    }

    fun load(): Options {
        val f = file()
        if (!f.isFile) return Options()
        val p = Properties()
        runCatching { f.inputStream().use { p.load(it) } }.onFailure { return Options() }

        fun int(key: String, fallback: Int) = p.getProperty(key)?.toIntOrNull() ?: fallback
        fun bool(key: String, fallback: Boolean) = p.getProperty(key)?.toBooleanStrictOrNull() ?: fallback
        fun dbl(key: String, fallback: Double) = p.getProperty(key)?.toDoubleOrNull() ?: fallback
        fun lng(key: String, fallback: Long) = p.getProperty(key)?.toLongOrNull() ?: fallback

        val d = Options()
        return Options(
            port = int("port", d.port),
            fps = int("fps", d.fps),
            bitrate = int("bitrate", d.bitrate),
            scale = dbl("scale", d.scale),
            hevc = bool("hevc", d.hevc),
            dda = bool("dda", d.dda),
            display = int("display", d.display),
            encoder = p.getProperty("encoder")?.takeIf { it.isNotBlank() },
            sendBuffer = int("sendBuffer", d.sendBuffer),
            gopSeconds = int("gopSeconds", d.gopSeconds),
            intraRefresh = bool("intraRefresh", d.intraRefresh),
            stats = bool("stats", d.stats),
            pipeline = bool("pipeline", d.pipeline),
            video = bool("video", d.video),
            gpu = bool("gpu", d.gpu),
            gpuNative = bool("gpuNative", d.gpuNative),
            oversample = int("oversample", d.oversample).coerceIn(1, 4),
            ffmpeg = p.getProperty("ffmpeg")?.takeIf { it.isNotBlank() },
            linkMode = p.getProperty("linkMode") ?: d.linkMode,
            mdns = bool("mdns", d.mdns),
            speed = p.getProperty("speed") ?: d.speed,
            quality = int("quality", d.quality),
            audioPort = int("audioPort", d.audioPort),
            audioRate = int("audioRate", d.audioRate),
            audioChannels = int("audioChannels", d.audioChannels),
            audioInternal = bool("audioInternal", d.audioInternal),
            audioMuteLocal = bool("audioMuteLocal", d.audioMuteLocal),
            input = bool("input", d.input),
            theme = p.getProperty("theme")?.takeIf { it.isNotBlank() } ?: d.theme,
            accent = lng("accent", d.accent),
            closeToTray = bool("closeToTray", d.closeToTray),
            startMinimized = bool("startMinimized", d.startMinimized)
        )
    }

    fun save(o: Options) {
        val p = Properties()
        p.setProperty("port", o.port.toString())
        p.setProperty("fps", o.fps.toString())
        p.setProperty("bitrate", o.bitrate.toString())
        p.setProperty("scale", o.scale.toString())
        p.setProperty("hevc", o.hevc.toString())
        p.setProperty("dda", o.dda.toString())
        p.setProperty("display", o.display.toString())
        p.setProperty("encoder", o.encoder ?: "")
        p.setProperty("sendBuffer", o.sendBuffer.toString())
        p.setProperty("gopSeconds", o.gopSeconds.toString())
        p.setProperty("intraRefresh", o.intraRefresh.toString())
        p.setProperty("stats", o.stats.toString())
        p.setProperty("pipeline", o.pipeline.toString())
        p.setProperty("video", o.video.toString())
        p.setProperty("gpu", o.gpu.toString())
        p.setProperty("gpuNative", o.gpuNative.toString())
        p.setProperty("oversample", o.oversample.toString())
        p.setProperty("ffmpeg", o.ffmpeg ?: "")
        p.setProperty("linkMode", o.linkMode)
        p.setProperty("mdns", o.mdns.toString())
        p.setProperty("speed", o.speed)
        p.setProperty("quality", o.quality.toString())
        p.setProperty("audioPort", o.audioPort.toString())
        p.setProperty("audioRate", o.audioRate.toString())
        p.setProperty("audioChannels", o.audioChannels.toString())
        p.setProperty("audioInternal", o.audioInternal.toString())
        p.setProperty("audioMuteLocal", o.audioMuteLocal.toString())
        p.setProperty("input", o.input.toString())
        p.setProperty("theme", o.theme)
        p.setProperty("accent", o.accent.toString())
        p.setProperty("closeToTray", o.closeToTray.toString())
        p.setProperty("startMinimized", o.startMinimized.toString())

        val f = file()
        runCatching {
            f.parentFile?.mkdirs()
            f.outputStream().use { p.store(it, "wifi screen streaming") }
        }
    }
}
