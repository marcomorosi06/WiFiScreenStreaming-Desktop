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
