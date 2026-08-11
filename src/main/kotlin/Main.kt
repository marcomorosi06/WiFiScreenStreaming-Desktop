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
import java.awt.GraphicsEnvironment
import java.util.concurrent.CountDownLatch

fun main(args: Array<String>) {
    val wantsCli = args.contains("--cli")
    val wantsGui = args.contains("--gui")
    val wantsPair = args.contains("--pair")
    val rest = args.filter { it != "--cli" && it != "--gui" && it != "--pair" }.toTypedArray()

    val options = try {
        parse(rest, Config.load())
    } catch (e: Exception) {
        System.err.println(e.message)
        usage()
        kotlin.system.exitProcess(1)
    }

    val headless = GraphicsEnvironment.isHeadless()
    val useGui = wantsGui || (!wantsCli && rest.isEmpty() && !headless)

    if (useGui) {
        if (headless) {
            System.err.println("no display available, falling back to text mode")
        } else {
            startGui(options)
            return
        }
    }

    runCli(options, wantsPair)
}

private fun askConsole(question: String): String? {
    print(question)
    System.out.flush()
    return readLine()?.trim()
}

fun runCli(options: Options, wantsPair: Boolean) {
    var identity: WssIdentity? = null
    var devices: WssDevices? = null
    var window: PairingWindow? = null

    if (options.input) {
        identity = WssIdentity.loadOrCreate(Config.identityFile())
        devices = WssDevices(Config.devicesFile())
        println("remote control on. fingerprint of this PC: ${identity.fingerprint}")
        if (wantsPair || devices.isEmpty()) {
            window = PairingWindow()
            println("pairing open for ${window.secondsLeft} s")
            println("  PIN to type on the phone: ${window.display}")
        } else {
            println("${devices.all().size} authorized devices (use --pair to add more)")
        }
    }

    val server = ScreenServer(
        options,
        identity = identity,
        devices = devices,
        pairing = { window?.takeIf { it.isOpen } },
        onSas = { sas ->
            println()
            println("  the PIN was accepted.")
            println("  verification code: ${sas.chunked(3).joinToString(" ")}")
            println("  the phone is showing a number: it must be the same one.")
        },
        approve = { request ->
            println()
            println("  device:       ${request.name}")
            println("  fingerprint:  ${request.fingerprint}")
            println("  code:         ${request.sas.chunked(3).joinToString(" ")}")
            val answer = askConsole("  authorize? [y/N] ")
            val ok = answer != null && (answer.equals("y", true) || answer.equals("yes", true))
            if (ok) window?.close()
            ok
        },
        onEvent = { println(it) },
        onStats = { s ->
            println("  %.1f fps   %.1f Mbps   %d frames   %d dropped".format(s.fps, s.mbps, s.frames, s.dropped))
            println("             avg     p95    max")
            println("    capture ${s.capture} ms")
            println("    encode  ${s.encode} ms")
            println("    send    ${s.send} ms")
            println("    pacing  ${s.cadence} ms")
        }
    )

    server.start()
    if (!server.isRunning) kotlin.system.exitProcess(1)

    Runtime.getRuntime().addShutdownHook(Thread { server.stop() })
    CountDownLatch(1).await()
}
