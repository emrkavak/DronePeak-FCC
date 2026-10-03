package com.dronepeak.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

private val Graphite = Color(0xFF101315)
private val Panel = Color(0xFF191E22)
private val Raised = Color(0xFF232A2F)
private val Line = Color(0xFF38434B)
private val Ink = Color(0xFFF2F5F6)
private val Muted = Color(0xFFADB9C1)
private val Link = Color(0xFF87CFEA)
private val Fcc = Color(0xFF8DDDB4)
private val Warning = Color(0xFFFFC67A)
private val Danger = Color(0xFFFF9A9E)
private val Radius = RoundedCornerShape(12.dp)

class MainActivity : ComponentActivity() {
    private val viewModel: FccViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.init()
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.rgb(16, 19, 21)),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.rgb(16, 19, 21))
        )
        setContent {
            DronePeakTheme { AppRoot(viewModel) }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.onAppResumed()
    }
}

@Composable
internal fun DronePeakTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(
        primary = Link, onPrimary = Graphite,
        background = Graphite, onBackground = Ink,
        surface = Panel, onSurface = Ink,
        surfaceVariant = Raised, onSurfaceVariant = Muted,
        outline = Line, error = Danger
    ), content = content)
}

/** UI callback adapter. A null delegate is used only by the debug visual harness. */
internal class ControllerActions(private val delegate: FccViewModel?) {
    fun connect() { delegate?.connect() }
    fun enableFcc() { delegate?.enableFcc() }
    fun disableFcc() { delegate?.disableFcc() }
    fun probeSerial() { delegate?.probeSerial() }
    fun send4gActivationFrames() { delegate?.send4gActivationFrames() }
    fun setLed(on: Boolean) { delegate?.setLed(on) }
    fun toggleAutoFcc() { delegate?.toggleAutoFcc() }
    fun startKeepalive() { delegate?.startKeepalive() }
    fun stopKeepalive() { delegate?.stopKeepalive() }
    fun launchDjiFly() { delegate?.launchDjiFly() }
    fun queryDeviceInfo() { delegate?.queryDeviceInfo() }
    fun setManualSerial(serial: String) { delegate?.setManualSerial(serial) }
    fun setLanguage(language: AppLanguage) { delegate?.setLanguage(language) }
    fun checkForUpdates(force: Boolean) { delegate?.checkForUpdates(force) }
    fun cancelUpdateDownload() { delegate?.cancelUpdateDownload() }
    fun installUpdate() { delegate?.installUpdate() }
    fun reDownloadUpdate() { delegate?.reDownloadUpdate() }
    fun downloadUpdate() { delegate?.downloadUpdate() }
    fun downloadProfileUpdate() { delegate?.downloadProfileUpdate() }
}

/**
 * RC 2 is normally landscape; RC Pro 2 can rotate. Both available dimensions
 * matter: a wide, short viewport needs a rail to conserve vertical space.
 * The screen never assumes a controller's Android density from its pixel count.
 */
@Composable
private fun AppRoot(viewModel: FccViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val actions = remember(viewModel) { ControllerActions(viewModel) }
    ControllerDashboard(state, actions)
}

@Composable
internal fun ControllerDashboard(state: AppState, viewModel: ControllerActions, initialPage: Int = 0) {
    val ui = TextCatalog.ui(state.language)
    val pager = rememberPagerState(initialPage = initialPage) { 4 }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().background(Graphite).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Header(state, viewModel)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val rail = maxWidth >= 600.dp || (maxWidth >= 560.dp && maxWidth > maxHeight)
            val select: (Int) -> Unit = { scope.launch { pager.animateScrollToPage(it) } }
            val pages: @Composable (Modifier) -> Unit = { modifier ->
                HorizontalPager(state = pager, modifier = modifier) { page ->
                    when (page) {
                        0 -> ControlPage(state, viewModel)
                        1 -> InfoPage(state, viewModel)
                        2 -> LogPage(state)
                        else -> UpdatePage(state, viewModel)
                    }
                }
            }
            if (rail) {
                Row(Modifier.fillMaxSize()) {
                    Navigation(ui, pager.currentPage, state.updateAvailable, true, select)
                    pages(Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    pages(Modifier.weight(1f).fillMaxWidth())
                    Navigation(ui, pager.currentPage, state.updateAvailable, false, select)
                }
            }
        }
    }
}

@Composable
private fun Header(state: AppState, viewModel: ControllerActions) {
    val ui = TextCatalog.ui(state.language)
    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(painterResource(R.drawable.dronepeak_icon), null, Modifier.size(30.dp))
        Spacer(Modifier.width(10.dp))
        Text(ui.brand, color = Ink, fontWeight = FontWeight.Bold, fontSize = 19.sp, letterSpacing = (-0.5).sp)
        Spacer(Modifier.weight(1f))
        Text(state.controllerModel.ifEmpty { ui.rcPanel }, color = Muted,
            fontFamily = FontFamily.Monospace, fontSize = 11.sp,
            maxLines = 1, modifier = Modifier.weight(0.7f), overflow = TextOverflow.Ellipsis)
        TextButton(onClick = {
            viewModel.setLanguage(if (state.language == AppLanguage.TR) AppLanguage.EN else AppLanguage.TR)
        }, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = ui.language }) {
            Icon(Icons.Filled.Language, null, Modifier.size(18.dp), tint = Muted)
            Spacer(Modifier.width(6.dp))
            Text(state.language.shortName, color = Ink, fontSize = 12.sp)
        }
    }
    HorizontalDivider(color = Line, thickness = 1.dp)
}

@Composable
private fun Navigation(ui: UiText, current: Int, update: Boolean, rail: Boolean, select: (Int) -> Unit) {
    val labels = listOf(ui.control, ui.info, ui.log, ui.update)
    val icons = listOf(Icons.Filled.Tune, Icons.Filled.Info, Icons.Filled.Terminal, Icons.Filled.SystemUpdate)
    val item: @Composable (Int, Modifier) -> Unit = { index, modifier ->
        val selected = current == index
        Column(
            modifier.clip(Radius).background(if (selected) Raised else Color.Transparent)
                .clickable(role = Role.Tab, onClick = { select(index) })
                .semantics { this.selected = selected }.padding(vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box {
                Icon(icons[index], labels[index], Modifier.size(22.dp), tint = if (selected) Ink else Muted)
                if (index == 3 && update) Box(Modifier.align(Alignment.TopEnd).size(6.dp).background(Warning, Radius))
            }
            Spacer(Modifier.height(4.dp))
            Text(labels[index], color = if (selected) Ink else Muted, fontSize = 11.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, lineHeight = 14.sp, maxLines = 1)
        }
    }
    if (rail) {
        Row(Modifier.fillMaxHeight()) {
            BoxWithConstraints(Modifier.width(76.dp).fillMaxHeight()) {
                val itemHeight = ((maxHeight - 30.dp) / 4).coerceIn(48.dp, 64.dp)
                Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    repeat(4) { item(it, Modifier.fillMaxWidth().height(itemHeight)) }
                }
            }
            VerticalDivider(color = Line)
        }
    } else {
        Column {
            HorizontalDivider(color = Line)
            Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(4) { item(it, Modifier.weight(1f).fillMaxHeight()) }
            }
        }
    }
}

/**
 * The primary control page reserves space for actions before the illustration.
 * Short landscape panels use two columns, while portrait stacks the same
 * components. Only secondary feedback may scroll; no action is hidden in a fold.
 */
@Composable
private fun ControlPage(state: AppState, viewModel: ControllerActions) {
    BoxWithConstraints(Modifier.fillMaxSize().padding(12.dp)) {
        val landscape = maxWidth >= 540.dp || (maxWidth >= 460.dp && maxWidth > maxHeight)
        if (landscape) {
            Row(Modifier.widthIn(max = 1040.dp).heightIn(max = 520.dp).fillMaxSize()
                .align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                AircraftPanel(state, viewModel, Modifier.weight(0.43f).fillMaxHeight())
                Controls(state, viewModel, Modifier.weight(0.57f).heightIn(max = 380.dp).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AircraftPanel(state, viewModel, Modifier.weight(1f).fillMaxWidth())
                Controls(state, viewModel, Modifier.fillMaxWidth().height(310.dp))
            }
        }
    }
}

@Composable
private fun AircraftPanel(state: AppState, viewModel: ControllerActions, modifier: Modifier) {
    val ui = TextCatalog.ui(state.language)
    val connected = state.isConnected
    CardShell(modifier) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val short = maxHeight < 290.dp
            val horizontalHero = maxWidth > maxHeight * 1.3f
            Column(Modifier.fillMaxSize().padding(if (short) 12.dp else 16.dp)) {
                if (horizontalHero) {
                    Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            RegionStatus(state, ui, true)
                            ConnectIcon(state, viewModel, ui)
                        }
                        Image(painterResource(R.drawable.drone_hero), null,
                            Modifier.weight(1f).fillMaxHeight(), contentScale = ContentScale.Fit)
                    }
                } else {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { RegionStatus(state, ui, short) }
                        ConnectIcon(state, viewModel, ui)
                    }
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Image(painterResource(R.drawable.drone_hero), null, Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit)
                    }
                }
                if (!short) {
                    Text(ui.droneIllustration, color = Muted, fontSize = 10.sp, maxLines = 1)
                    Spacer(Modifier.height(8.dp))
                }
                HorizontalDivider(color = Line)
                Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        MicroLabel(ui.aircraftSerial)
                        Text(state.manualSerial.ifEmpty { state.aircraftSerial }.ifEmpty { ui.notDetected },
                            color = Ink, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { viewModel.probeSerial() },
                        enabled = !state.isHardwareBusy && !state.isProbingSerial && !state.isBusy,
                        modifier = Modifier.size(48.dp)) {
                        if (state.isProbingSerial) CircularProgressIndicator(Modifier.size(20.dp), color = Link, strokeWidth = 2.dp)
                        else Icon(Icons.Filled.Refresh, ui.refreshSerial, Modifier.size(20.dp), tint = Muted)
                    }
                }
                Text(if (state.isHardwareBusy) ui.blockedBusy else if (!connected) ui.connectHint else ui.fccWriteNote,
                    color = Muted, fontSize = 11.sp, lineHeight = 15.sp, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.heightIn(min = 30.dp))
            }
        }
    }
}

@Composable
private fun RegionStatus(state: AppState, ui: UiText, short: Boolean) {
    Column {
        MicroLabel(ui.region)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (state.isFccEnabled) ui.fcc else ui.regionUnknown,
                color = if (state.isFccEnabled) Fcc else Ink, fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold, fontSize = if (short) 26.sp else 34.sp, lineHeight = 38.sp)
            Spacer(Modifier.width(10.dp))
            Text(if (state.isConnected) ui.online else ui.offline,
                color = if (state.isConnected) Link else Muted, fontSize = 11.sp, lineHeight = 15.sp)
        }
    }
}

@Composable
private fun ConnectIcon(state: AppState, viewModel: ControllerActions, ui: UiText) {
    IconButton(onClick = { viewModel.connect() },
        enabled = !state.isHardwareBusy && !state.isBusy && !state.is4gBusy,
        modifier = Modifier.size(48.dp)) {
        Icon(Icons.Filled.Wifi, ui.connect, tint = if (state.isConnected) Link else Muted)
    }
}

@Composable
private fun Controls(state: AppState, viewModel: ControllerActions, modifier: Modifier) {
    val ui = TextCatalog.ui(state.language)
    val blocked = state.isHardwareBusy || state.isBusy || state.is4gBusy
    val ledEnabled = state.isConnected && !state.isLedBusy
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(modifier) {
        val short = maxHeight < 270.dp
        val toolHeight = if (maxHeight < 330.dp) 48.dp else 56.dp
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(
            if (short) 1.dp else if (fontScale > 1.15f) 2.dp else 4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    !state.isConnected -> Action(ui.connect, Icons.Filled.Wifi, Link, !blocked,
                        Modifier.weight(1f), onClick = { viewModel.connect() })
                    state.isFccEnabled -> {
                        Action(ui.stopShort, Icons.Filled.PowerSettingsNew, Danger, !blocked,
                            Modifier.weight(1f), outlined = true, onClick = { viewModel.disableFcc() })
                        Action(ui.reapply, Icons.Filled.Refresh, Fcc, !blocked,
                            Modifier.weight(1f), onClick = { viewModel.enableFcc() })
                    }
                    else -> Action(ui.enableFcc, Icons.Filled.Radio, Fcc, !blocked,
                        Modifier.weight(1f), onClick = { viewModel.enableFcc() })
                }
            }
            Box(Modifier.fillMaxWidth().height(((if (short) 20 else 32) * fontScale).dp)) {
                if (state.isBusy || state.is4gBusy) {
                    Column {
                        LinearProgressIndicator(progress = { state.busyProgress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(3.dp), color = Link, trackColor = Raised)
                        Spacer(Modifier.height(2.dp))
                        Text(if (state.is4gBusy) ui.sending4g else ui.working, color = Link,
                            fontSize = 11.sp, lineHeight = 14.sp, maxLines = 1)
                    }
                } else {
                    // Upstream messages have no severity field. Keep prose neutral;
                    // never infer outcomes or colors from translated text fragments.
                    Text(TextCatalog.operationMessage(state.message, state.language).ifEmpty {
                        if (state.isConnected) ui.readyApplyFcc else ui.connectHint
                    }, color = Muted, fontSize = 12.sp, lineHeight = 16.sp,
                        maxLines = if (short) 1 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.verticalScroll(rememberScrollState()))
                }
            }
            if (short) {
                // Dense controller settings can leave only ~200dp of height.
                // Keep 48dp touch targets by putting the four tools on one row;
                // labels carry the meaning when there is no room for icons.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Tool(ui.fourGShort, ui.send4g, Icons.Filled.CellTower, state.isConnected && !blocked,
                        state.is4gBusy, Modifier.weight(1f), toolHeight, true) { viewModel.send4gActivationFrames() }
                    Tool(ui.djiFly, ui.djiFly, Icons.Filled.FlightTakeoff, true,
                        false, Modifier.weight(1f), toolHeight, true) { viewModel.launchDjiFly() }
                    Tool(ui.ledOn, ui.ledOn, Icons.Filled.Lightbulb, ledEnabled,
                        state.isLedBusy, Modifier.weight(1f), toolHeight, true) { viewModel.setLed(true) }
                    Tool(ui.ledOff, ui.ledOff, Icons.Filled.PowerSettingsNew, ledEnabled,
                        false, Modifier.weight(1f), toolHeight, true) { viewModel.setLed(false) }
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Tool(ui.fourGShort, ui.send4g, Icons.Filled.CellTower, state.isConnected && !blocked,
                        state.is4gBusy, Modifier.weight(1f), toolHeight) { viewModel.send4gActivationFrames() }
                    Tool(ui.djiFly, ui.djiFly, Icons.Filled.FlightTakeoff, true,
                        false, Modifier.weight(1f), toolHeight) { viewModel.launchDjiFly() }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Tool(ui.ledOn, ui.ledOn, Icons.Filled.Lightbulb, ledEnabled,
                        state.isLedBusy, Modifier.weight(1f), toolHeight) { viewModel.setLed(true) }
                    Tool(ui.ledOff, ui.ledOff, Icons.Filled.PowerSettingsNew, ledEnabled,
                        false, Modifier.weight(1f), toolHeight) { viewModel.setLed(false) }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Toggle(ui.autoFcc, state.autoFcc, ui.autoFccDetail, Modifier.weight(1f)) { viewModel.toggleAutoFcc() }
                Toggle(ui.keepalive, state.isKeepaliveRunning, ui.keepaliveInactive, Modifier.weight(1f)) {
                    if (state.isKeepaliveRunning) viewModel.stopKeepalive() else viewModel.startKeepalive()
                }
            }
            val feedback = when {
                state.isHardwareBusy -> ui.blockedBusy
                !state.isConnected -> ui.blockedNeedLink
                state.isLedBusy -> ui.ledBusy
                state.fourGMessage.isNotEmpty() -> TextCatalog.operationMessage(state.fourGMessage, state.language)
                state.ledStatus.isNotEmpty() -> TextCatalog.operationMessage(state.ledStatus, state.language)
                else -> ui.fourGHint
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                // Reserve whole text lines so large text never paints half a
                // clipped line. Feedback can scroll without moving any control.
                val lineHeight = 15 * fontScale
                val lines = (maxHeight.value / lineHeight).toInt().coerceAtLeast(1)
                Text(feedback, color = Muted, fontSize = 11.sp, lineHeight = 15.sp,
                    modifier = Modifier.fillMaxWidth().heightIn(max = (lines * lineHeight).dp)
                        .verticalScroll(rememberScrollState()))
            }
        }
    }
}

@Composable
private fun Action(label: String, icon: ImageVector, accent: Color, enabled: Boolean,
                   modifier: Modifier = Modifier, outlined: Boolean = false, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier.height(56.dp), shape = Radius,
        contentPadding = PaddingValues(horizontal = 12.dp),
        border = if (outlined) BorderStroke(1.dp, if (enabled) accent else Line) else null,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (outlined) Panel else accent,
            contentColor = if (outlined) accent else Graphite,
            disabledContainerColor = Raised, disabledContentColor = Muted
        )) {
        Icon(icon, null, Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, fontWeight = FontWeight.Bold, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 2)
    }
}

@Composable
private fun Tool(label: String, description: String, icon: ImageVector, enabled: Boolean,
                 running: Boolean, modifier: Modifier, height: Dp, compact: Boolean = false, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = enabled, modifier = modifier.height(height)
        .semantics { contentDescription = description }, shape = Radius, color = Panel,
        contentColor = if (enabled) Ink else Muted, border = BorderStroke(1.dp, Line)) {
        if (compact) {
            Box(Modifier.padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
                Text(label, fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium,
                    maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        } else {
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (running) CircularProgressIndicator(Modifier.size(20.dp), color = Link, strokeWidth = 2.dp)
                else Icon(icon, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(label, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, maxLines = 2)
            }
        }
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, description: String, modifier: Modifier,
                   change: () -> Unit) {
    Row(modifier.heightIn(min = 48.dp).clip(Radius).background(Panel).border(1.dp, Line, Radius)
        .toggleable(value = checked, role = Role.Switch, onValueChange = { change() })
        .semantics { contentDescription = description }
        .padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Ink, fontSize = 11.sp, modifier = Modifier.weight(1f), maxLines = 1)
        Switch(checked = checked, onCheckedChange = null,
            colors = SwitchDefaults.colors(checkedThumbColor = Graphite, checkedTrackColor = Fcc,
                uncheckedThumbColor = Muted, uncheckedTrackColor = Raised))
    }
}

@Composable
private fun InfoPage(state: AppState, viewModel: ControllerActions) {
    val ui = TextCatalog.ui(state.language)
    ScrollPage(ui.deviceInfo, ui.infoSubtitle) {
        CardShell(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Readout(ui.controller, state.controllerModel.ifEmpty { ui.unknown })
                Readout(ui.connection, if (state.isConnected) ui.connected else ui.disconnected)
                Readout(ui.version, BuildConfig.VERSION_NAME)
                Readout(ui.aircraftSerial, state.manualSerial.ifEmpty { state.aircraftSerial }.ifEmpty { ui.notDetected })
                Note(ui.fccWriteNote)
                Note(ui.fourGHint)
                Action(if (state.isQueryingInfo) ui.working else ui.query, Icons.Filled.Memory, Link,
                    state.isConnected && !state.isHardwareBusy && !state.isQueryingInfo,
                    Modifier.fillMaxWidth(), outlined = true) { viewModel.queryDeviceInfo() }
                if (!state.isConnected || state.isHardwareBusy) {
                    Note(if (state.isHardwareBusy) ui.blockedBusy else ui.blockedNeedLink)
                }
                if (state.deviceInfo.isNotEmpty()) {
                    HorizontalDivider(color = Line)
                    Text(TextCatalog.operationMessage(state.deviceInfo, state.language), color = Ink,
                        fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 19.sp)
                }
            }
        }
        ManualSerial(state, viewModel)
        CardShell(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                MicroLabel(ui.sourceAndLicense)
                LinkButton(ui.sourceGithub, "https://github.com/emrkavak/DronePeak-FCC", Icons.Filled.Code)
                Readout(ui.license, "AGPL-3.0")
            }
        }
    }
}

@Composable
private fun ManualSerial(state: AppState, viewModel: ControllerActions) {
    val ui = TextCatalog.ui(state.language)
    var serial by rememberSaveable(state.manualSerial, state.aircraftSerial) {
        mutableStateOf(state.manualSerial.ifEmpty { state.aircraftSerial })
    }
    CardShell(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            MicroLabel(ui.manualSerial)
            Note(ui.manualSerialHint)
            OutlinedTextField(value = serial, onValueChange = { serial = it },
                label = { Text(ui.aircraftSerial) }, singleLine = true,
                enabled = !state.isHardwareBusy, shape = Radius, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Action(ui.useManualSerial, Icons.Filled.Check, Link, !state.isHardwareBusy && serial.isNotBlank(),
                    Modifier.weight(1f)) { viewModel.setManualSerial(serial) }
                IconButton(onClick = { serial = ""; viewModel.setManualSerial("") },
                    enabled = !state.isHardwareBusy && state.manualSerial.isNotEmpty(),
                    modifier = Modifier.size(56.dp).border(1.dp, Line, Radius)) {
                    Icon(Icons.Filled.Close, ui.clearManualSerial, tint = Muted)
                }
            }
            Action(if (state.isProbingSerial) ui.working else ui.refreshSerial, Icons.Filled.Refresh, Link,
                !state.isHardwareBusy && !state.isProbingSerial, Modifier.fillMaxWidth(), outlined = true) {
                viewModel.probeSerial()
            }
            if (state.isHardwareBusy) Note(ui.blockedBusy)
        }
    }
}

@Composable
private fun LogPage(state: AppState) {
    val ui = TextCatalog.ui(state.language)
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        PageTitle(ui.activityLog, ui.logSubtitle)
        Spacer(Modifier.height(16.dp))
        if (state.logMessages.isEmpty()) {
            CardShell(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(24.dp)) {
                    Icon(Icons.Filled.Terminal, null, Modifier.size(28.dp), tint = Muted)
                    Spacer(Modifier.height(12.dp))
                    Note(ui.noActivity)
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                items(state.logMessages) { entry ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                        Text(TextCatalog.operationMessage(entry, state.language), color = Ink,
                            fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 19.sp)
                        Spacer(Modifier.height(12.dp))
                        HorizontalDivider(color = Line)
                    }
                }
            }
        }
    }
}

@Composable
private fun UpdatePage(state: AppState, viewModel: ControllerActions) {
    val ui = TextCatalog.ui(state.language)
    ScrollPage(ui.updates, ui.updateSubtitle) {
        CardShell(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                MicroLabel(ui.currentVersion(BuildConfig.VERSION_NAME))
                when {
                    state.isCheckingUpdate -> {
                        CircularProgressIndicator(Modifier.size(24.dp), color = Link, strokeWidth = 2.dp)
                        Note(ui.checkingLatest)
                    }
                    state.updateInfo == null -> {
                        Text(if (state.updateChecked) ui.updateCheckFailed else ui.checkingLatest,
                            color = Ink, fontSize = 16.sp)
                        Action(ui.retry, Icons.Filled.Refresh, Link, true, Modifier.fillMaxWidth()) {
                            viewModel.checkForUpdates(force = true)
                        }
                    }
                    else -> {
                        val info = state.updateInfo
                        Text(if (state.updateAvailable) ui.updateAvailableTitle(info.version) else ui.upToDate,
                            color = if (state.updateAvailable) Warning else Fcc,
                            fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                        Readout(ui.latest, info.version)
                        Readout(ui.released, info.publishedAt.take(10))
                        if (info.apkSize > 0) Readout(ui.size, "%.1f MB".format(info.apkSize / 1048576.0))
                        if (state.isDownloadingUpdate) {
                            LinearProgressIndicator(progress = { state.updateDownloadProgress.coerceIn(0f, 1f) },
                                color = Link, trackColor = Raised, modifier = Modifier.fillMaxWidth())
                            Note(ui.downloadingDronePeakUpdate)
                            if (state.updateStage == UpdateStage.DOWNLOADING) {
                                Action(ui.cancelDownload, Icons.Filled.Close, Danger, true,
                                    Modifier.fillMaxWidth(), outlined = true) { viewModel.cancelUpdateDownload() }
                            }
                        } else if (state.isUpdateDownloaded) {
                            val installing = state.updateStage in setOf(UpdateStage.VERIFYING,
                                UpdateStage.PREPARING_INSTALL, UpdateStage.WAITING_FOR_ANDROID)
                            Action(if (state.updateStage == UpdateStage.NEEDS_INSTALL_PERMISSION) ui.openInstallSettings
                                else ui.installDronePeakUpdate, Icons.Filled.InstallMobile, Link, !installing,
                                Modifier.fillMaxWidth()) { viewModel.installUpdate() }
                            Action(ui.redownload, Icons.Filled.Refresh, Link, !installing,
                                Modifier.fillMaxWidth(), outlined = true) { viewModel.reDownloadUpdate() }
                        } else if (state.updateAvailable && info.downloadUrl.isNotBlank()) {
                            Action(ui.downloadDronePeakUpdate, Icons.Filled.Download, Link, true,
                                Modifier.fillMaxWidth()) { viewModel.downloadUpdate() }
                        }
                        if (state.profileUpdateMessage.isNotEmpty()) Note(state.profileUpdateMessage)
                        if (state.updateStage == UpdateStage.FAILED && !state.isDownloadingUpdate && !state.isUpdateDownloaded) {
                            Action(ui.redownload, Icons.Filled.Refresh, Link, true,
                                Modifier.fillMaxWidth(), outlined = true) { viewModel.reDownloadUpdate() }
                        }
                        Action(ui.applyProfileUpdate, Icons.Filled.Sync, Link, !state.isDownloadingUpdate,
                            Modifier.fillMaxWidth(), outlined = true) { viewModel.downloadProfileUpdate() }
                        Action(ui.checkAgain, Icons.Filled.Refresh, Link, !state.isDownloadingUpdate,
                            Modifier.fillMaxWidth(), outlined = true) { viewModel.checkForUpdates(force = true) }
                    }
                }
            }
        }
        state.updateInfo?.let { info ->
            CardShell(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    MicroLabel(ui.changelog)
                    Note(info.changelog.ifEmpty { ui.noChangelog })
                }
            }
        }
        if (state.updateDiagnosticSummary.isNotEmpty() || state.updateDiagnosticDetails.isNotEmpty()) {
            CardShell(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    MicroLabel(ui.updateDiagnostics)
                    Text(state.updateDiagnosticSummary, color = if (state.updateDiagnosticFailure) Danger else Muted,
                        fontSize = 12.sp)
                    Text(state.updateDiagnosticDetails, color = Muted, fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp, lineHeight = 16.sp)
                }
            }
        }
    }
}

@Composable
private fun ScrollPage(title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = 900.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            PageTitle(title, subtitle)
            content()
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun PageTitle(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(title, color = Ink, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp)
        Note(subtitle)
    }
}

@Composable
private fun CardShell(modifier: Modifier, content: @Composable () -> Unit) {
    Surface(modifier = modifier, color = Panel, shape = Radius, border = BorderStroke(1.dp, Line), content = content)
}

@Composable
private fun MicroLabel(text: String) {
    Text(text.uppercase(), color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Medium,
        letterSpacing = 1.2.sp, lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun Note(text: String) {
    Text(text, color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
}

@Composable
private fun Readout(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, color = Muted, fontSize = 12.sp, modifier = Modifier.weight(0.4f))
        Text(value, color = Ink, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(0.6f))
    }
}

@Composable
private fun LinkButton(label: String, url: String, icon: ImageVector) {
    val context = LocalContext.current
    val ui = TextCatalog.ui(AppLanguage.fromPref(
        context.getSharedPreferences("dronepeak", android.content.Context.MODE_PRIVATE).getString("language", null)))
    var unavailable by remember { mutableStateOf(false) }
    Action(label, icon, Link, true, Modifier.fillMaxWidth(), outlined = true) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { unavailable = true }
    }
    if (unavailable) Note(ui.browserUnavailable)
}
