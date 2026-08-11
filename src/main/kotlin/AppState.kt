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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicReference

private const val LOG_LIMIT = 800

class AppState(initial: Options, private val scope: CoroutineScope) {

    var options by mutableStateOf(initial)
        private set

    var running by mutableStateOf(false)
        private set

    var client by mutableStateOf<String?>(null)
        private set

    var stats by mutableStateOf<Stats?>(null)
        private set

    var addresses by mutableStateOf(localAddresses())
        private set

    var recommended by mutableStateOf<String?>(null)
        private set

    var identity by mutableStateOf<WssIdentity?>(null)
        private set

    var deviceList by mutableStateOf<List<Device>>(emptyList())
        private set

    var pairingPin by mutableStateOf<String?>(null)
        private set

    var pairingSas by mutableStateOf<String?>(null)
        private set

    var pairingSeconds by mutableStateOf(0L)
        private set

    var pairingAttempts by mutableStateOf(0)
        private set

    var approval by mutableStateOf<DeviceRequest?>(null)
        private set

    var showDevices by mutableStateOf(false)

    var showSettings by mutableStateOf(false)

    var standAside by mutableStateOf(false)

    val log = mutableStateListOf<String>()

    private var server: ScreenServer? = null
    private var devices: WssDevices? = null
    private var pairing: PairingWindow? = null
    private val approvalAnswer = AtomicReference<CompletableDeferred<Boolean>?>(null)

    val usb: LocalAddress? get() = addresses.firstOrNull { it.kind == LinkKind.USB }
    val wifi: LocalAddress? get() = addresses.firstOrNull { it.kind == LinkKind.NORMAL }

    val fingerprint: String? get() = identity?.fingerprint

    init {
        say("ready. press Start, then point the phone at one of the addresses above.")
        refreshLink()
    }

    fun update(next: Options) {
        options = next
        Config.save(next)
        refreshLink()
    }

    fun say(message: String) {
        log.add(message)
        while (log.size > LOG_LIMIT) log.removeAt(0)
    }

    fun tick() {
        addresses = localAddresses()
        refreshLink()
        val window = pairing
        if (window != null) {
            if (window.isOpen) {
                pairingSeconds = window.secondsLeft
                pairingAttempts = window.attemptsLeft
            } else if (pairingSas == null) {
                closePairing()
            }
        }
    }

    private fun refreshLink() {
        val target = when (options.linkMode) {
            LINK_USB -> usb
            LINK_WIFI -> wifi
            else -> usb ?: wifi
        }
        recommended = target?.let { "${it.address}:${options.port}" }
    }

    fun trust(): Pair<WssIdentity, WssDevices>? {
        val known = identity
        val store = devices
        if (known != null && store != null) return known to store
        return try {
            val created = WssIdentity.loadOrCreate(Config.identityFile())
            val list = WssDevices(Config.devicesFile())
            identity = created
            devices = list
            deviceList = list.all()
            created to list
        } catch (e: Exception) {
            say("remote control unavailable: ${e.message}")
            null
        }
    }

    fun refreshDevices() {
        deviceList = devices?.all() ?: emptyList()
    }

    fun forget(device: Device) {
        devices?.forget(device.publicKey)
        refreshDevices()
        say("device removed: ${device.name}")
    }

    fun rename(device: Device, name: String) {
        devices?.rename(device.publicKey, name)
        refreshDevices()
    }

    fun openPairing(): Boolean {
        val pair = trust() ?: return false
        if (!running) {
            say("pairing needs the server running, starting it now")
            start()
        }
        if (!running) return false
        closePairing()
        val window = PairingWindow()
        pairing = window
        pairingPin = window.display
        pairingSas = null
        pairingSeconds = window.secondsLeft
        pairingAttempts = window.attemptsLeft
        say("pairing open, PIN ${window.display}")
        say("PC fingerprint ${pair.first.fingerprint}")
        return true
    }

    fun closePairing() {
        pairing?.close()
        pairing = null
        pairingPin = null
        pairingSas = null
        pairingSeconds = 0
        pairingAttempts = 0
    }

    fun answerApproval(accept: Boolean) {
        approvalAnswer.getAndSet(null)?.complete(accept)
        approval = null
        if (accept) closePairing()
        refreshDevices()
    }

    fun start() {
        if (running) return
        val pair = if (options.input) trust() else null
        val instance = ScreenServer(
            options,
            onEvent = { message -> post { say(message) } },
            onStats = { value -> post { stats = value } },
            onClient = { who ->
                post {
                    client = who
                    if (who == null) stats = null
                }
            },
            identity = pair?.first,
            devices = pair?.second,
            pairing = { pairing?.takeIf { it.isOpen } },
            onSas = { sas ->
                post {
                    pairingSas = sas
                    say("verification code: $sas")
                }
            },
            onInput = { live -> post { standAside = live } },
            approve = { request -> ask(request) }
        )
        server = instance
        instance.start()
        running = instance.isRunning
        if (running) refreshLink()
    }

    fun stop() {
        server?.stop()
        server = null
        running = false
        client = null
        stats = null
        closePairing()
        refreshLink()
    }

    private fun ask(request: DeviceRequest): Boolean {
        val answer = CompletableDeferred<Boolean>()
        approvalAnswer.set(answer)
        post { approval = request }
        return runCatching { runBlocking { answer.await() } }.getOrDefault(false)
    }

    private fun post(block: () -> Unit) {
        scope.launch { block() }
    }
}
