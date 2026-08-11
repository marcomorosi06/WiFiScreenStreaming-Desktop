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
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Mouse
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Tonality
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenStreamingApp(state: AppState) {
    LaunchedEffect(state.options.input) {
        if (state.options.input) {
            state.trust()
            state.refreshDevices()
        }
    }

    val density = LocalDensity.current
    val logo = remember(density) { AppIcon.logo(density) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = logo,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Text("WiFi Screen Streaming", fontWeight = FontWeight.SemiBold)
                    }
                },
                navigationIcon = {
                    if (state.showSettings) {
                        IconButton(onClick = { state.showSettings = false }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { state.showSettings = !state.showSettings }) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (state.showSettings) SettingsScreen(state) else HomeScreen(state)
        }
        Dialogs(state)
    }
}

@Composable
private fun HomeScreen(state: AppState) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= 880.dp) {
            Column(
                modifier = Modifier.fillMaxSize().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                SessionHeaderBar(state)
                Row(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        LinkCard(state)
                        RemoteControlCard(state)
                    }
                    Column(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        PerformanceCard(state)
                        LogCard(state, Modifier.weight(1f))
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                SessionHeaderBar(state)
                LinkCard(state)
                RemoteControlCard(state)
                PerformanceCard(state)
                LogCard(state, Modifier.height(260.dp))
            }
        }
    }
}

@Composable
private fun SessionHeaderBar(state: AppState) {
    val usb = state.usb
    val stats = state.stats
    ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
        val showAddress = maxWidth >= 700.dp
        val showLink = maxWidth >= 900.dp
        val showRate = maxWidth >= 1120.dp
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            if (state.running) LivePulse()

            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        state.client != null -> "Streaming"
                        state.running -> "Waiting for a phone"
                        else -> "Ready to stream"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
                Text(
                    state.client?.let { "client $it" }
                        ?: if (state.running) "nothing connected yet" else "press Start when the phone is ready",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (showAddress) {
                HeaderField("ADDRESS", state.recommended ?: "none", mono = true)
            }
            if (showLink) {
                HeaderField(
                    "LINK",
                    when (state.options.linkMode) {
                        LINK_USB -> "USB only"
                        LINK_WIFI -> "Wi-Fi only"
                        else -> if (usb != null) "Auto, on the cable" else "Auto, on Wi-Fi"
                    },
                    accent = usb != null && state.options.linkMode != LINK_WIFI
                )
            }
            if (showRate && stats != null) {
                HeaderField("THROUGHPUT", "%.0f fps  %.1f Mbps".format(stats.fps, stats.mbps), mono = true)
            }
            if (showAddress) VerticalDivider(Modifier.height(36.dp))

            if (state.running) {
                FilledTonalButton(
                    onClick = { state.stop() },
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) {
                    Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Stop", maxLines = 1)
                }
            } else {
                Button(onClick = { state.start() }, enabled = state.recommended != null) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Start", maxLines = 1)
                }
            }
        }
        }
    }
}

@Composable
private fun HeaderField(label: String, value: String, mono: Boolean = false, accent: Boolean = false) {
    Column(Modifier.widthIn(max = 220.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            fontWeight = FontWeight.Medium,
            color = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun LivePulse() {
    val transition = rememberInfiniteTransition(label = "live")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        label = "alpha",
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse)
    )
    Box(
        Modifier.size(10.dp).background(MaterialTheme.colorScheme.error.copy(alpha = alpha), CircleShape)
    )
}

@Composable
private fun LinkCard(state: AppState) {
    val clipboard = LocalClipboardManager.current
    val usb = state.usb
    SectionCard("Link", Icons.Outlined.Link) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = state.options.linkMode == LINK_AUTO,
                onClick = { state.update(state.options.copy(linkMode = LINK_AUTO)) },
                enabled = !state.running,
                shape = SegmentedButtonDefaults.itemShape(0, 3),
                icon = { Icon(Icons.Outlined.Tune, contentDescription = null, Modifier.size(ButtonDefaults.IconSize)) }
            ) { Text("Auto") }
            SegmentedButton(
                selected = state.options.linkMode == LINK_USB,
                onClick = { state.update(state.options.copy(linkMode = LINK_USB)) },
                enabled = !state.running && usb != null,
                shape = SegmentedButtonDefaults.itemShape(1, 3),
                icon = { Icon(Icons.Outlined.Usb, contentDescription = null, Modifier.size(ButtonDefaults.IconSize)) }
            ) { Text("USB") }
            SegmentedButton(
                selected = state.options.linkMode == LINK_WIFI,
                onClick = { state.update(state.options.copy(linkMode = LINK_WIFI)) },
                enabled = !state.running,
                shape = SegmentedButtonDefaults.itemShape(2, 3),
                icon = { Icon(Icons.Outlined.Wifi, contentDescription = null, Modifier.size(ButtonDefaults.IconSize)) }
            ) { Text("Wi-Fi") }
        }

        StatusRow(
            icon = Icons.Outlined.Usb,
            accent = usb != null,
            title = if (usb != null) "USB cable connected" else "No USB cable detected",
            body = usb?.via
                ?: "plug the phone in, then Settings > Hotspot and tethering > USB tethering"
        )

        val target = state.recommended
        if (target == null) {
            StatusRow(
                icon = Icons.Outlined.Router,
                accent = false,
                title = "No usable address",
                body = "check the network interfaces, then reopen this panel"
            )
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "On the phone type",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        target,
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        when {
                            state.options.linkMode == LINK_AUTO && usb != null ->
                                "the cable is the fastest, use this one"
                            state.options.linkMode == LINK_AUTO -> "Wi-Fi"
                            state.options.linkMode == LINK_USB -> "USB cable"
                            else -> "Wi-Fi"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = {
                    clipboard.setText(AnnotatedString(target))
                    state.say("copied to the clipboard: $target")
                }) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy")
                }
            }
        }
    }
}

@Composable
private fun RemoteControlCard(state: AppState) {
    val enabled = state.options.input
    SectionCard("Remote control", Icons.Outlined.Mouse) {
        SwitchSetting(
            title = "Mouse and keyboard from the phone",
            description = "end-to-end encrypted, needs a PIN pairing",
            icon = Icons.Outlined.Mouse,
            checked = enabled,
            enabled = !state.running,
            onCheckedChange = {
                state.update(state.options.copy(input = it))
                if (!it) state.closePairing()
            }
        )

        if (enabled) {
            val count = state.deviceList.size
            StatusRow(
                icon = Icons.Outlined.Info,
                accent = count > 0,
                title = when {
                    state.pairingPin != null -> "Pairing open: ${state.pairingPin}"
                    count == 0 -> "No authorized device"
                    count == 1 -> "1 authorized device"
                    else -> "$count authorized devices"
                },
                body = when {
                    state.pairingPin != null -> "${state.pairingSeconds} s left, ${state.pairingAttempts} attempts"
                    count == 0 -> "press Pair device to add one"
                    else -> "PC fingerprint ${state.fingerprint ?: "unknown"}"
                }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { state.openPairing() }, modifier = Modifier.weight(1f)) {
                    Text("Pair device")
                }
                OutlinedButton(
                    onClick = { state.refreshDevices(); state.showDevices = true },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Devices")
                }
            }
        } else {
            StatusRow(
                icon = Icons.Outlined.Info,
                accent = false,
                title = "The phone can only watch",
                body = "turn the switch on to let it click and type"
            )
        }
    }
}

@Composable
private fun PerformanceCard(state: AppState) {
    SectionCard("Performance", Icons.Outlined.Speed) {
        val stats = state.stats
        if (stats == null) {
            StatusRow(
                icon = Icons.Outlined.Info,
                accent = false,
                title = if (state.running) "Waiting for a client" else "Not streaming",
                body = "the numbers appear as soon as a phone connects"
            )
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Metric("fps", "%.1f".format(stats.fps), Modifier.weight(1f))
                Metric("Mbps", "%.1f".format(stats.mbps), Modifier.weight(1f))
                Metric("frames", stats.frames.toString(), Modifier.weight(1f))
                Metric("dropped", stats.dropped.toString(), Modifier.weight(1f))
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                LatencyRow("", "avg", "p95", "max", header = true)
                LatencyRow("capture", stats.capture)
                LatencyRow("encode", stats.encode)
                LatencyRow("send", stats.send)
                LatencyRow("pacing", stats.cadence)
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            maxLines = 1
        )
    }
}

@Composable
private fun LatencyRow(name: String, sample: Sample) {
    LatencyRow(name, "%.1f".format(sample.avg), "%.1f".format(sample.p95), "%.1f".format(sample.max))
}

@Composable
private fun LatencyRow(name: String, avg: String, p95: String, max: String, header: Boolean = false) {
    val color = if (header) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    val style = if (header) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(name, style = style, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        listOf(avg, p95, max).forEach {
            Text(
                it,
                style = style,
                color = color,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.width(64.dp),
                maxLines = 1
            )
        }
    }
}

@Composable
private fun LogCard(state: AppState, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.log.size) {
        if (state.log.isNotEmpty()) listState.scrollToItem(state.log.size - 1)
    }
    SectionCard("Log", Icons.Outlined.Terminal, modifier, fill = true) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                itemsIndexed(state.log) { _, line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(listState),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 4.dp)
            )
        }
    }
}

@Composable
fun SectionCard(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    fill: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    ElevatedCard(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(20.dp).then(if (fill) Modifier.fillMaxHeight() else Modifier)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.width(14.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Column(
                modifier = if (fill) Modifier.weight(1f) else Modifier,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                content = content
            )
        }
    }
}

@Composable
private fun StatusRow(icon: ImageVector, accent: Boolean, title: String, body: String) {
    val container = if (accent) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.surfaceContainerHigh
    val onContainer = if (accent) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(container)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Icon(icon, contentDescription = null, tint = onContainer, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = onContainer
            )
            Text(body, style = MaterialTheme.typography.bodySmall, color = onContainer)
        }
    }
}

@Composable
fun SwitchSetting(
    title: String,
    description: String,
    icon: ImageVector,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> Dropdown(
    label: String,
    value: String,
    options: List<Pair<String, T>>,
    enabled: Boolean = true,
    onSelected: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (text, option) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = { onSelected(option); expanded = false }
                )
            }
        }
    }
}

@Composable
fun IntField(
    label: String,
    value: Int,
    range: IntRange,
    enabled: Boolean = true,
    onValue: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var text by remember { mutableStateOf(value.toString()) }
    LaunchedEffect(value) { if (text.toIntOrNull() != value) text = value.toString() }
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            text = raw.filter { it.isDigit() }.take(9)
            text.toIntOrNull()?.let { if (it in range) onValue(it) }
        },
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        isError = text.toIntOrNull()?.let { it !in range } ?: true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier
    )
}

@Composable
fun DoubleField(
    label: String,
    value: Double,
    range: ClosedFloatingPointRange<Double>,
    enabled: Boolean = true,
    onValue: (Double) -> Unit,
    modifier: Modifier = Modifier
) {
    var text by remember { mutableStateOf(trim(value)) }
    LaunchedEffect(value) { if (text.toDoubleOrNull() != value) text = trim(value) }
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            text = raw.filter { it.isDigit() || it == '.' }.take(9)
            text.toDoubleOrNull()?.let { if (it in range) onValue(it) }
        },
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        isError = text.toDoubleOrNull()?.let { it !in range } ?: true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier
    )
}

@Composable
fun TextSetting(
    label: String,
    value: String,
    placeholder: String,
    enabled: Boolean = true,
    onValue: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        singleLine = true,
        enabled = enabled,
        modifier = modifier
    )
}

private fun trim(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

@Composable
private fun Dialogs(state: AppState) {
    val pin = state.pairingPin
    val sas = state.pairingSas
    if (pin != null) {
        AlertDialog(
            onDismissRequest = { state.closePairing() },
            title = { Text(if (sas == null) "Pair device" else "Check the number") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        sas?.chunked(3)?.joinToString(" ") ?: pin,
                        style = MaterialTheme.typography.displaySmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        if (sas == null)
                            "On the phone type ${state.recommended ?: "the address in the Link panel"}, " +
                                "tick Mouse and keyboard and press Connect. It will ask for the PIN above."
                        else
                            "The PIN was accepted. The phone is now showing a number: it must be the same " +
                                "one above. If it differs, refuse on both sides.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        if (sas == null)
                            "expires in ${state.pairingSeconds} s   attempts left ${state.pairingAttempts}"
                        else
                            "fingerprint of this PC: ${state.fingerprint ?: "unknown"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = { TextButton(onClick = { state.closePairing() }) { Text("Close") } }
        )
    }

    state.approval?.let { request ->
        AlertDialog(
            onDismissRequest = { state.answerApproval(false) },
            title = { Text("New device") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        request.sas.chunked(3).joinToString(" "),
                        style = MaterialTheme.typography.displaySmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "${request.name} is asking to control this PC. The phone must show the same " +
                            "number as above. If it differs, someone is in the middle: refuse.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "device fingerprint ${request.fingerprint}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(onClick = { state.answerApproval(true) }) { Text("The number matches") }
            },
            dismissButton = {
                TextButton(onClick = { state.answerApproval(false) }) { Text("Refuse") }
            }
        )
    }

    if (state.showDevices) DevicesDialog(state)
}

@Composable
private fun DevicesDialog(state: AppState) {
    var renaming by remember { mutableStateOf<Device?>(null) }
    var draft by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { state.showDevices = false },
        title = { Text("Authorized devices") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    "fingerprint of this PC: ${state.fingerprint ?: "unknown"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                if (state.deviceList.isEmpty()) {
                    Text("No device has been paired yet.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(state.deviceList, key = { it.fingerprint }) { device ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(device.name, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        device.fingerprint,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(onClick = { renaming = device; draft = device.name }) {
                                    Icon(Icons.Outlined.Edit, contentDescription = "Rename")
                                }
                                IconButton(onClick = { state.forget(device) }) {
                                    Icon(
                                        Icons.Outlined.Delete,
                                        contentDescription = "Remove",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { state.showDevices = false }) { Text("Close") } }
    )

    renaming?.let { device ->
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename device") },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (draft.isNotBlank()) state.rename(device, draft.trim())
                    renaming = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } }
        )
    }
}

private enum class SettingsTab(val label: String, val icon: ImageVector) {
    Video("Video", Icons.Outlined.Videocam),
    Audio("Audio", Icons.Outlined.VolumeUp),
    Network("Network", Icons.Outlined.Router),
    Control("Remote control", Icons.Outlined.Mouse),
    Appearance("Appearance", Icons.Outlined.Palette)
}

@Composable
private fun SettingsScreen(state: AppState) {
    var tab by remember { mutableStateOf(SettingsTab.Video) }
    val locked = state.running

    Row(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .width(200.dp)
                .fillMaxSize()
                .padding(start = 16.dp, top = 20.dp, end = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            SettingsTab.entries.forEach { entry ->
                NavItem(entry.label, entry.icon, tab == entry) { tab = entry }
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (locked) {
                StatusRow(
                    icon = Icons.Outlined.Info,
                    accent = true,
                    title = "The server is running",
                    body = "stop it to change the capture and network settings"
                )
            }
            when (tab) {
                SettingsTab.Video -> VideoSettings(state, !locked)
                SettingsTab.Audio -> AudioSettings(state, !locked)
                SettingsTab.Network -> NetworkSettings(state, !locked)
                SettingsTab.Control -> ControlSettings(state, !locked)
                SettingsTab.Appearance -> AppearanceSettings(state)
            }
            Text(
                "Settings live in ${Config.file().path}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun NavItem(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    val tint = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
    else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(20.dp), tint = tint)
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
            else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun VideoSettings(state: AppState, enabled: Boolean) {
    val o = state.options

    SectionCard("Preset", Icons.Outlined.Tune) {
        Dropdown(
            label = "Tune for",
            value = "choose a preset",
            options = TUNE_NAMES.map { it.replaceFirstChar { c -> c.uppercase() } to it },
            enabled = enabled,
            onSelected = { state.update(applyTune(state.options, it)) },
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            "game 60 fps 20 Mbps intra-refresh, video 30 fps 10 Mbps, text 30 fps 25 Mbps for reading",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    SectionCard("Picture", Icons.Outlined.Videocam) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IntField("Frames per second", o.fps, 5..240, enabled, {
                state.update(state.options.copy(fps = it))
            }, Modifier.weight(1f))
            DoubleField("Bitrate (Mbps)", o.bitrate / 1_000_000.0, 1.0..200.0, enabled, {
                state.update(state.options.copy(bitrate = (it * 1_000_000).toInt()))
            }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IntField("Quality (0 sharp, 51 mushy)", o.quality, 0..51, enabled, {
                state.update(state.options.copy(quality = it))
            }, Modifier.weight(1f))
            IntField("Keyframe every (s)", o.gopSeconds, 1..60, enabled, {
                state.update(state.options.copy(gopSeconds = it))
            }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Dropdown(
                label = "Codec",
                value = if (o.hevc) "H.265" else "H.264",
                options = listOf("H.264" to false, "H.265" to true),
                enabled = enabled,
                onSelected = { state.update(state.options.copy(hevc = it)) },
                modifier = Modifier.weight(1f)
            )
            Dropdown(
                label = "Scale",
                value = when {
                    o.scale > 0.9 -> "100%"
                    o.scale > 0.6 -> "75%"
                    else -> "50%"
                },
                options = listOf("100%" to 1.0, "75%" to 0.75, "50%" to 0.5),
                enabled = enabled,
                onSelected = { state.update(state.options.copy(scale = it)) },
                modifier = Modifier.weight(1f)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Dropdown(
                label = "nvenc preset",
                value = o.speed,
                options = (1..7).map { "p$it" to "p$it" },
                enabled = enabled,
                onSelected = { state.update(state.options.copy(speed = it)) },
                modifier = Modifier.weight(1f)
            )
            IntField("Display", o.display, 0..8, enabled, {
                state.update(state.options.copy(display = it))
            }, Modifier.weight(1f))
        }
        SwitchSetting(
            title = "Send the picture",
            description = "off means the PC only takes mouse and keyboard",
            icon = Icons.Outlined.DesktopWindows,
            checked = o.video,
            enabled = enabled,
            onCheckedChange = { state.update(state.options.copy(video = it)) }
        )
        SwitchSetting(
            title = "Intra-refresh",
            description = "instead of periodic keyframes, steadier bitrate",
            icon = Icons.Outlined.GraphicEq,
            checked = o.intraRefresh,
            enabled = enabled,
            onCheckedChange = { state.update(state.options.copy(intraRefresh = it)) }
        )
    }

    SectionCard("Pipeline", Icons.Outlined.Memory) {
        SwitchSetting(
            title = "DXGI capture",
            description = "faster and steadier than GDI, Windows only",
            icon = Icons.Outlined.DesktopWindows,
            checked = o.dda,
            enabled = enabled,
            onCheckedChange = {
                state.update(
                    if (it) state.options.copy(dda = true)
                    else state.options.copy(dda = false, gpu = false)
                )
            }
        )
        SwitchSetting(
            title = "Keep the frames on the graphics card",
            description = "no readback to RAM, needs DXGI capture",
            icon = Icons.Outlined.Memory,
            checked = o.gpu,
            enabled = enabled,
            onCheckedChange = {
                state.update(
                    if (it) state.options.copy(gpu = true, dda = true)
                    else state.options.copy(gpu = false)
                )
            }
        )
        SwitchSetting(
            title = "Build the pipeline in here",
            description = "no external ffmpeg process, exact frame boundaries",
            icon = Icons.Outlined.Memory,
            checked = o.gpuNative,
            enabled = enabled && o.gpu,
            onCheckedChange = { state.update(state.options.copy(gpuNative = it)) }
        )
        SwitchSetting(
            title = "Capture and encoding on separate threads",
            description = "off only to compare the pacing",
            icon = Icons.Outlined.Speed,
            checked = o.pipeline,
            enabled = enabled,
            onCheckedChange = { state.update(state.options.copy(pipeline = it)) }
        )
        IntField(
            "Oversample (with the in-process pipeline)",
            o.oversample,
            1..4,
            enabled && o.gpu && o.gpuNative,
            { state.update(state.options.copy(oversample = it)) },
            Modifier.fillMaxWidth()
        )
        TextSetting(
            label = "External ffmpeg",
            value = o.ffmpeg ?: "",
            placeholder = "empty: the bundled one, then the one on PATH",
            enabled = enabled && o.gpu && !o.gpuNative,
            onValue = { state.update(state.options.copy(ffmpeg = it.trim().takeIf { t -> t.isNotEmpty() })) },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun AudioSettings(state: AppState, enabled: Boolean) {
    val o = state.options
    SectionCard("Audio over WFAS v2", Icons.Outlined.VolumeUp) {
        SwitchSetting(
            title = "Send the audio",
            description = "the WFAS v2 protocol, unicast and unchanged",
            icon = Icons.Outlined.VolumeUp,
            checked = o.audioPort > 0,
            enabled = enabled,
            onCheckedChange = {
                state.update(state.options.copy(audioPort = if (it) 9090 else 0))
            }
        )
        SwitchSetting(
            title = "Serve it from here",
            description = "off means a separate wfas --server handles the port",
            icon = Icons.Outlined.Router,
            checked = o.audioInternal,
            enabled = enabled && o.audioPort > 0,
            onCheckedChange = { state.update(state.options.copy(audioInternal = it)) }
        )
        SwitchSetting(
            title = "Mute the PC while streaming",
            description = "the sound only comes out of the phone",
            icon = Icons.Outlined.VolumeUp,
            checked = o.audioMuteLocal,
            enabled = enabled && o.audioPort > 0,
            onCheckedChange = { state.update(state.options.copy(audioMuteLocal = it)) }
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IntField(
                "Audio port",
                if (o.audioPort > 0) o.audioPort else 9090,
                1..65535,
                enabled && o.audioPort > 0,
                { state.update(state.options.copy(audioPort = it)) },
                Modifier.weight(1f)
            )
            IntField(
                "Sample rate",
                o.audioRate,
                8000..192000,
                enabled && o.audioPort > 0,
                { state.update(state.options.copy(audioRate = it)) },
                Modifier.weight(1f)
            )
            IntField(
                "Channels",
                o.audioChannels,
                1..2,
                enabled && o.audioPort > 0,
                { state.update(state.options.copy(audioChannels = it)) },
                Modifier.weight(1f)
            )
        }
        StatusRow(
            icon = Icons.Outlined.Info,
            accent = false,
            title = "Unauthenticated by design",
            body = "this build rides WFAS v2 as it is: no handshake and no encryption on the audio"
        )
    }
}

@Composable
private fun NetworkSettings(state: AppState, enabled: Boolean) {
    val o = state.options
    SectionCard("Network", Icons.Outlined.Router) {
        IntField("TCP port", o.port, 1..65535, enabled, {
            state.update(state.options.copy(port = it))
        }, Modifier.fillMaxWidth())
        IntField("Socket send buffer (KB)", o.sendBuffer / 1024, 8..8192, enabled, {
            state.update(state.options.copy(sendBuffer = it * 1024))
        }, Modifier.fillMaxWidth())
        SwitchSetting(
            title = "Advertise over mDNS",
            description = "the Search button on the phone finds this PC",
            icon = Icons.Outlined.Wifi,
            checked = o.mdns,
            enabled = enabled,
            onCheckedChange = { state.update(state.options.copy(mdns = it)) }
        )
        HorizontalDivider()
        Text(
            "Interfaces seen right now",
            style = MaterialTheme.typography.labelLarge
        )
        if (state.addresses.isEmpty()) {
            Text(
                "none",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            state.addresses.forEach { address ->
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        when (address.kind) {
                            LinkKind.USB -> Icons.Outlined.Usb
                            LinkKind.VIRTUAL -> Icons.Outlined.Memory
                            LinkKind.NORMAL -> Icons.Outlined.Wifi
                        },
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        address.address,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        address.via,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun ControlSettings(state: AppState, enabled: Boolean) {
    val o = state.options
    SectionCard("Remote control", Icons.Outlined.Mouse) {
        SwitchSetting(
            title = "Mouse and keyboard from the phone",
            description = "end-to-end encrypted, PIN pairing on first use",
            icon = Icons.Outlined.Mouse,
            checked = o.input,
            enabled = enabled,
            onCheckedChange = {
                state.update(state.options.copy(input = it))
                if (!it) state.closePairing()
            }
        )
        StatusRow(
            icon = Icons.Outlined.Info,
            accent = o.input,
            title = if (o.input) "PC fingerprint ${state.fingerprint ?: "not loaded"}" else "Disabled",
            body = "pair from the home screen, then check the number on both sides"
        )
        Text(
            "The window steps aside while the phone is in control, so the clicks land on what is behind.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AppearanceSettings(state: AppState) {
    val o = state.options
    SectionCard("Appearance", Icons.Outlined.Palette) {
        Text("Theme", style = MaterialTheme.typography.labelLarge)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = o.theme == THEME_LIGHT,
                onClick = { state.update(state.options.copy(theme = THEME_LIGHT)) },
                shape = SegmentedButtonDefaults.itemShape(0, 3),
                icon = { Icon(Icons.Outlined.LightMode, contentDescription = null, Modifier.size(ButtonDefaults.IconSize)) }
            ) { Text("Light") }
            SegmentedButton(
                selected = o.theme == THEME_DARK,
                onClick = { state.update(state.options.copy(theme = THEME_DARK)) },
                shape = SegmentedButtonDefaults.itemShape(1, 3),
                icon = { Icon(Icons.Outlined.DarkMode, contentDescription = null, Modifier.size(ButtonDefaults.IconSize)) }
            ) { Text("Dark") }
            SegmentedButton(
                selected = o.theme == THEME_SYSTEM,
                onClick = { state.update(state.options.copy(theme = THEME_SYSTEM)) },
                shape = SegmentedButtonDefaults.itemShape(2, 3),
                icon = { Icon(Icons.Outlined.Tonality, contentDescription = null, Modifier.size(ButtonDefaults.IconSize)) }
            ) { Text("System") }
        }

        Text("Accent", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ACCENT_SWATCHES.forEach { (name, argb) ->
                val selected = o.accent == argb
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(
                            if (argb == 0L) MaterialTheme.colorScheme.surfaceContainerHighest
                            else Color(argb)
                        )
                        .border(
                            width = if (selected) 3.dp else 1.dp,
                            color = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant,
                            shape = CircleShape
                        )
                        .clickable { state.update(state.options.copy(accent = argb)) },
                    contentAlignment = Alignment.Center
                ) {
                    if (argb == 0L) {
                        Icon(
                            Icons.Outlined.Palette,
                            contentDescription = name,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }

        HorizontalDivider()

        SwitchSetting(
            title = "Close to the tray",
            description = "the X hides the window instead of quitting",
            icon = Icons.Outlined.DesktopWindows,
            checked = o.closeToTray,
            onCheckedChange = { state.update(state.options.copy(closeToTray = it)) }
        )
        SwitchSetting(
            title = "Start minimized",
            description = "open straight into the tray",
            icon = Icons.Outlined.DesktopWindows,
            checked = o.startMinimized,
            onCheckedChange = { state.update(state.options.copy(startMinimized = it)) }
        )
    }
}
