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
