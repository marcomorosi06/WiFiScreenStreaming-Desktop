import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket

private const val BEACON_PORT = 9091
private const val BEACON_GROUP = "239.255.0.1"
private const val BEACON_MESSAGE = "WIFI_AUDIO_STREAMER_DISCOVERY"
private const val BEACON_EVERY_MS = 3000L

class WfasBeacon(
    private val name: String,
    private val port: Int,
    private val sampleRate: Int,
    private val channels: Int,
    private val onEvent: (String) -> Unit
) {

    @Volatile
    private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread { loop() }.also {
            it.isDaemon = true
            it.name = "wfas-beacon"
            it.start()
        }
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { thread?.join(400) }
        thread = null
    }

    private fun body(): String =
        "$BEACON_MESSAGE;$name;UNICAST;$port;protocols=WFAS" +
            ";sr=$sampleRate;ch=$channels;bd=16;auth=OFF;enc=0"

    private fun loop() {
        val socket = try {
            MulticastSocket().also { it.timeToLive = 4 }
        } catch (e: Exception) {
            onEvent("audio: cannot announce to WFAS: ${e.message}")
            running = false
            return
        }

        val group = runCatching { InetAddress.getByName(BEACON_GROUP) }.getOrNull()
        if (group == null) {
            onEvent("audio: cannot resolve the WFAS group")
            runCatching { socket.close() }
            running = false
            return
        }

        onEvent("audio: announcing to WFAS as \"$name\" on $port")

        var complained = false
        try {
            while (running) {
                val bytes = body().toByteArray(Charsets.US_ASCII)
                runCatching {
                    socket.send(DatagramPacket(bytes, bytes.size, group, BEACON_PORT))
                    socket.send(
                        DatagramPacket(
                            bytes,
                            bytes.size,
                            InetAddress.getByName("255.255.255.255"),
                            BEACON_PORT
                        )
                    )
                }.onFailure {
                    if (!complained) {
                        onEvent("audio: the WFAS announcement is not going out: ${it.message}")
                        complained = true
                    }
                }
                Thread.sleep(BEACON_EVERY_MS)
            }

            val bye = "$BEACON_MESSAGE;$name;UNICAST;$port;BYE".toByteArray(Charsets.US_ASCII)
            runCatching { socket.send(DatagramPacket(bye, bye.size, group, BEACON_PORT)) }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            runCatching { socket.close() }
        }
    }
}
