import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.delay

fun startGui(initial: Options) = application {
    val scope = rememberCoroutineScope()
    val state = remember { AppState(initial, scope) }
    val options = state.options

    val windowState = rememberWindowState(size = DpSize(1180.dp, 760.dp))
    var windowVisible by remember { mutableStateOf(!initial.startMinimized) }
    val trayState = rememberTrayState()

    LaunchedEffect(Unit) {
        while (true) {
            delay(2000)
            state.tick()
        }
    }

    LaunchedEffect(state.standAside) {
        if (state.standAside) {
            state.say("the phone is in control: stepping aside so the clicks land on what is behind")
            windowState.isMinimized = true
        } else if (windowState.isMinimized) {
            windowState.isMinimized = false
        }
    }

    val quit: () -> Unit = {
        state.stop()
        exitApplication()
    }

    val raise: () -> Unit = {
        windowVisible = true
        windowState.isMinimized = false
    }

    val closeRequest: () -> Unit = {
        if (options.closeToTray) {
            windowVisible = false
            runCatching {
                trayState.sendNotification(
                    Notification(
                        title = "WiFi Screen Streaming",
                        message = "still running in the background",
                        type = Notification.Type.Info
                    )
                )
            }
        } else {
            quit()
        }
    }

    Tray(
        icon = AppIcon.painter,
        state = trayState,
        tooltip = "WiFi Screen Streaming",
        onAction = raise,
        menu = {
            Item(
                text = if (windowVisible) "Hide window" else "Show window",
                onClick = { if (windowVisible) windowVisible = false else raise() }
            )
            Item(
                text = if (state.running) "Stop the server" else "Start the server",
                onClick = { if (state.running) state.stop() else state.start() }
            )
            Separator()
            Item(text = "Quit", onClick = quit)
        }
    )

    Window(
        onCloseRequest = closeRequest,
        state = windowState,
        visible = windowVisible,
        title = "WiFi Screen Streaming",
        icon = AppIcon.painter
    ) {
        LaunchedEffect(Unit) {
            window.minimumSize = java.awt.Dimension(720, 560)
            runCatching { window.iconImages = ArrayList(AppIcon.awtImages()) }
        }

        val dark = when (themeOf(options.theme)) {
            Theme.Light -> false
            Theme.Dark -> true
            Theme.System -> isSystemInDarkTheme()
        }
        val scheme = if (options.accent != 0L) {
            MaterialYou.scheme(Color(options.accent), dark)
        } else {
            if (dark) darkColorScheme() else lightColorScheme()
        }

        MaterialTheme(colorScheme = scheme) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                ScreenStreamingApp(state)
            }
        }
    }
}
