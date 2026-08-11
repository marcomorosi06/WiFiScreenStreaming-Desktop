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
import java.net.InetAddress
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

const val SERVICE_TYPE = "_wfss._tcp.local."

class Advertiser(
    private val opt: Options,
    private val onEvent: (String) -> Unit
) {

    private val instances = ArrayList<JmDNS>()

    fun start(addresses: List<LocalAddress>) {
        if (addresses.isEmpty()) return

        val host = runCatching { InetAddress.getLocalHost().hostName }.getOrNull()
            ?.substringBefore('.')
            ?.ifBlank { null }
            ?: "PC"

        val props = mapOf(
            "v" to "2",
            "codec" to if (opt.hevc) "h265" else "h264",
            "fps" to opt.fps.toString(),
            "audio" to opt.audioPort.toString(),
            "name" to host
        )

        for (local in addresses) {
            runCatching {
                val jmdns = JmDNS.create(InetAddress.getByName(local.address), host)
                val suffix = if (local.kind == LinkKind.USB) " USB" else ""
                val info = ServiceInfo.create(SERVICE_TYPE, host + suffix, opt.port, 0, 0, props)
                jmdns.registerService(info)
                instances.add(jmdns)
                onEvent("mDNS: advertising on ${local.address} as \"$host$suffix\"")
            }.onFailure {
                onEvent("mDNS: advertising failed on ${local.address}: ${it.message}")
            }
        }
    }

    fun stop() {
        for (jmdns in instances) {
            runCatching { jmdns.unregisterAllServices() }
            runCatching { jmdns.close() }
        }
        instances.clear()
    }
}
