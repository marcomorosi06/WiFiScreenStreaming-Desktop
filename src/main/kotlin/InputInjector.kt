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
import java.awt.Robot
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.event.InputEvent
import java.awt.event.KeyEvent

class InputInjector(private val onEvent: (String) -> Unit) {

    private val robot: Robot? = runCatching {
        if (GraphicsEnvironment.isHeadless()) null else Robot().also { it.isAutoWaitForIdle = false }
    }.getOrElse {
        onEvent("input: cannot create Robot: ${it.message}")
        null
    }

    private val screen = runCatching {
        Toolkit.getDefaultToolkit().screenSize
    }.getOrNull()

    private val pressedButtons = LinkedHashSet<Int>()
    private val pressedKeys = LinkedHashSet<Int>()
    private val held = java.lang.Object()

    @Volatile
    private var lastEvent = System.currentTimeMillis()

    @Volatile
    private var alive = true

    private var windowStart = System.currentTimeMillis()
    private var windowCount = 0

    init {
        Thread {
            while (alive) {
                Thread.sleep(500)
                val idle = System.currentTimeMillis() - lastEvent
                val stuck = synchronized(held) { pressedButtons.isNotEmpty() || pressedKeys.isNotEmpty() }
                if (stuck && idle > 3000) {
                    onEvent("input: nothing for ${idle / 1000}s with something held down, releasing it")
                    releaseEverything()
                }
            }
        }.also {
            it.isDaemon = true
            it.name = "input-watchdog"
            it.start()
        }
    }

    val available: Boolean get() = robot != null && screen != null

    val screenWidth: Int get() = screen?.width ?: 0
    val screenHeight: Int get() = screen?.height ?: 0

    fun apply(message: InputMessage) {
        val active = robot ?: return
        rateLimit()
        lastEvent = System.currentTimeMillis()

        when (message) {
            is InputMessage.Video -> Unit

            is InputMessage.MoveAbsolute -> {
                val size = screen ?: return
                active.mouseMove(
                    (message.x * (size.width - 1) / 1000),
                    (message.y * (size.height - 1) / 1000)
                )
            }

            is InputMessage.MoveRelative -> {
                val size = screen ?: return
                val at = java.awt.MouseInfo.getPointerInfo()?.location ?: return
                active.mouseMove(
                    (at.x + message.dx).coerceIn(0, size.width - 1),
                    (at.y + message.dy).coerceIn(0, size.height - 1)
                )
            }

            is InputMessage.ButtonDown -> {
                val mask = maskOf(message.button)
                active.mousePress(mask)
                synchronized(held) { pressedButtons.add(mask) }
            }

            is InputMessage.ButtonUp -> {
                val mask = maskOf(message.button)
                active.mouseRelease(mask)
                synchronized(held) { pressedButtons.remove(mask) }
            }

            is InputMessage.Scroll -> active.mouseWheel(message.amount)

            is InputMessage.KeyDown -> {
                if (!allowedKey(message.keyCode)) return
                active.keyPress(message.keyCode)
                synchronized(held) { pressedKeys.add(message.keyCode) }
            }

            is InputMessage.KeyUp -> {
                if (!allowedKey(message.keyCode)) return
                active.keyRelease(message.keyCode)
                synchronized(held) { pressedKeys.remove(message.keyCode) }
            }

            is InputMessage.Text -> type(active, message.value)

            InputMessage.Ping -> Unit
        }
    }

    fun releaseEverything() {
        val active = robot ?: return
        val keys: List<Int>
        val buttons: List<Int>
        synchronized(held) {
            keys = pressedKeys.toList()
            buttons = pressedButtons.toList()
            pressedKeys.clear()
            pressedButtons.clear()
        }
        keys.forEach { runCatching { active.keyRelease(it) } }
        buttons.forEach { runCatching { active.mouseRelease(it) } }
        listOf(KeyEvent.VK_SHIFT, KeyEvent.VK_CONTROL, KeyEvent.VK_ALT, KeyEvent.VK_META)
            .forEach { runCatching { active.keyRelease(it) } }
        if (keys.isNotEmpty() || buttons.isNotEmpty()) {
            onEvent("input: released ${keys.size} keys and ${buttons.size} buttons")
        }
    }

    fun shutdown() {
        alive = false
        releaseEverything()
    }

    fun forgetRateWindow() {
        windowStart = System.currentTimeMillis()
        windowCount = 0
        lastEvent = System.currentTimeMillis()
    }

    private fun rateLimit() {
        val now = System.currentTimeMillis()
        if (now - windowStart >= 1000) {
            windowStart = now
            windowCount = 0
        }
        windowCount++
        if (windowCount > 1000) {
            throw WssProtocolException("too many input events per second")
        }
    }

    private fun maskOf(button: Int): Int = when (button) {
        BUTTON_LEFT -> InputEvent.BUTTON1_DOWN_MASK
        BUTTON_MIDDLE -> InputEvent.BUTTON2_DOWN_MASK
        BUTTON_RIGHT -> InputEvent.BUTTON3_DOWN_MASK
        else -> throw WssProtocolException("invalid button: $button")
    }

    private fun allowedKey(code: Int): Boolean {
        if (code <= 0 || code > 0xFFFF) return false
        return true
    }

    private fun plainAscii(character: Char): Boolean =
        character in 'a'..'z' || character in 'A'..'Z' ||
            character in '0'..'9' || character == ' '

    private fun type(active: Robot, text: String) {
        val fallback = StringBuilder()

        for (character in text) {
            if (character == '\n') {
                tap(active, KeyEvent.VK_ENTER)
                continue
            }
            if (character == '\t') {
                tap(active, KeyEvent.VK_TAB)
                continue
            }

            if (!plainAscii(character)) {
                fallback.append(character)
                continue
            }

            val direct = KeyEvent.getExtendedKeyCodeForChar(character.code)
            if (direct == KeyEvent.VK_UNDEFINED) {
                fallback.append(character)
                continue
            }

            val needsShift = character.isUpperCase()
            runCatching {
                if (needsShift) active.keyPress(KeyEvent.VK_SHIFT)
                active.keyPress(direct)
                active.keyRelease(direct)
                if (needsShift) active.keyRelease(KeyEvent.VK_SHIFT)
            }.onFailure {
                runCatching { active.keyRelease(KeyEvent.VK_SHIFT) }
                fallback.append(character)
            }
        }

        if (fallback.isNotEmpty()) paste(active, fallback.toString())

        listOf(KeyEvent.VK_SHIFT, KeyEvent.VK_CONTROL, KeyEvent.VK_ALT)
            .forEach { runCatching { active.keyRelease(it) } }
    }

    private fun tap(active: Robot, code: Int) {
        active.keyPress(code)
        active.keyRelease(code)
    }

    private fun paste(active: Robot, text: String) {
        val clipboard = runCatching { Toolkit.getDefaultToolkit().systemClipboard }.getOrNull() ?: return
        val previous = runCatching {
            if (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
                clipboard.getData(DataFlavor.stringFlavor) as? String
            } else null
        }.getOrNull()

        runCatching {
            clipboard.setContents(StringSelection(text), null)
            active.keyPress(KeyEvent.VK_CONTROL)
            tap(active, KeyEvent.VK_V)
            active.keyRelease(KeyEvent.VK_CONTROL)
            Thread.sleep(60)
        }.onFailure {
            runCatching { active.keyRelease(KeyEvent.VK_CONTROL) }
        }

        if (previous != null) runCatching { clipboard.setContents(StringSelection(previous), null) }
    }
}
