package com.dronepeak.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

private val Ink = Color(0xFF080B10)
private val Panel = Color(0xFF10151D)
private val PanelAlt = Color(0xFF151B25)
private val Stroke = Color(0xFF283241)
private val MutedStroke = Color(0xFF1C2430)
private val Primary = Color(0xFF5CC8FF)
private val Success = Color(0xFF3ED598)
private val Warning = Color(0xFFFFB020)
private val Danger = Color(0xFFFF5A5F)
private val TextStrong = Color(0xFFEAF0F7)
private val TextBody = Color(0xFFA8B3C2)
private val TextMuted = Color(0xFF6E7A89)

private val BottomNavHeight = 60.dp
private val PageHorizontalPadding = 12.dp

class MainActivity : ComponentActivity() {

    private val viewModel: FccViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.init()

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Primary,
                    onPrimary = Ink,
                    background = Ink,
                    onBackground = TextStrong,
                    surface = Panel,
                    onSurface = TextStrong,
                    secondary = Success,
                    tertiary = Warning,
                    error = Danger
                )
            ) {
                AppRoot(viewModel)
            }
        }
    }
}

@Composable
private fun AppRoot(viewModel: FccViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pagerState = rememberPagerState(initialPage = 0) { 4 }
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink)
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = true
        ) { page ->
            when (page) {
                0 -> FccPage(state, viewModel)
                1 -> InfoPage(state, viewModel)
                2 -> LogPage(state)
                3 -> UpdatePage(state, viewModel)
            }
        }

        BottomNavBar(
            currentPage = pagerState.currentPage,
            language = state.language,
            onPageSelected = { index -> scope.launch { pagerState.animateScrollToPage(index) } },
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@Composable
private fun FccPage(state: AppState, viewModel: FccViewModel) {
    val ui = TextCatalog.ui(state.language)
    PageScaffold {
        AppHeader(state, viewModel, ui)

        if (state.updateAvailable && state.updateInfo != null && !state.isCheckingUpdate) {
            Spacer(Modifier.height(10.dp))
            NoticeRow(
                title = ui.updateAvailableTitle(state.updateInfo.version),
                detail = ui.updateAvailableDetail,
                color = Success,
                icon = Icons.Filled.NewReleases
            )
        }

        Spacer(Modifier.height(8.dp))
        FlightStatusPanel(state, viewModel)

        Spacer(Modifier.height(8.dp))
        PrimaryActionPanel(state, viewModel)

        Spacer(Modifier.height(8.dp))
        UtilitiesPanel(state, viewModel)

        Spacer(Modifier.height(8.dp))
        AutoFccPanel(state, viewModel)
    }
}

@Composable
private fun FlightStatusPanel(state: AppState, viewModel: FccViewModel) {
    val ui = TextCatalog.ui(state.language)
    PanelCard {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusMetric(
                label = ui.link,
                value = connectionLabel(state, ui),
                color = connectionColor(state),
                modifier = Modifier.weight(1f)
            )
            StatusMetric(
                label = ui.operation,
                value = operationLabel(state, ui),
                color = operationColor(state),
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusMetric(
                label = ui.region,
                value = if (state.isFccEnabled) "FCC" else "CE",
                color = if (state.isFccEnabled) Success else TextBody,
                modifier = Modifier.weight(1f)
            )
            StatusMetric(
                label = ui.keep,
                value = if (state.isKeepaliveRunning) ui.on else ui.off,
                color = if (state.isKeepaliveRunning) Success else TextMuted,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(10.dp))
        DividerLine()
        Spacer(Modifier.height(10.dp))

        InfoRowCompact(ui.controller, state.controllerModel.ifEmpty { ui.unknown })
        Spacer(Modifier.height(6.dp))
        InfoRowCompact(ui.aircraftSerial, state.aircraftSerial.ifEmpty { ui.notDetected })

        if (state.aircraftSerial.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            CompactTextButton(
                text = ui.refreshSerial,
                icon = Icons.Filled.Refresh,
                enabled = !state.isHardwareBusy,
                onClick = { viewModel.probeSerial() }
            )
        }
    }
}

@Composable
private fun PrimaryActionPanel(state: AppState, viewModel: FccViewModel) {
    val ui = TextCatalog.ui(state.language)
    PanelCard {
        SectionHeader(ui.fccControl, Icons.Filled.Radio)

        if (state.isBusy) {
            Spacer(Modifier.height(12.dp))
            ProgressBlock(state.busyProgress, state.message.ifEmpty { ui.working })
            return@PanelCard
        }

        Spacer(Modifier.height(10.dp))
        when {
            !state.isConnected -> {
                BodyText(ui.connectHint)
                Spacer(Modifier.height(10.dp))
                CommandButton(
                    text = ui.connect,
                    icon = Icons.Filled.Wifi,
                    color = Primary,
                    enabled = !state.isHardwareBusy,
                    onClick = { viewModel.connect() }
                )
            }
            state.isFccEnabled -> {
                BodyText(ui.fccActiveHint, Success)
                Spacer(Modifier.height(10.dp))
                CommandButton(
                    text = ui.stopFcc,
                    icon = Icons.Filled.PowerSettingsNew,
                    color = Danger,
                    enabled = !state.isHardwareBusy,
                    onClick = { viewModel.disableFcc() }
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    SecondaryButton(
                        text = ui.reapply,
                        icon = Icons.Filled.Refresh,
                        color = Primary,
                        enabled = !state.isHardwareBusy,
                        modifier = Modifier.weight(1f),
                        onClick = { viewModel.enableFcc() }
                    )
                    SecondaryButton(
                        text = "DJI Fly",
                        icon = Icons.Filled.Flight,
                        color = Success,
                        enabled = !state.isHardwareBusy,
                        modifier = Modifier.weight(1f),
                        onClick = { viewModel.launchDjiFly() }
                    )
                }
            }
            else -> {
                BodyText(state.message.ifEmpty { ui.readyApplyFcc })
                Spacer(Modifier.height(10.dp))
                CommandButton(
                    text = ui.enableFcc,
                    icon = Icons.Filled.Radio,
                    color = Primary,
                    enabled = !state.isHardwareBusy,
                    onClick = { viewModel.enableFcc() }
                )
            }
        }

        AnimatedVisibility(
            visible = state.isConnected,
            enter = fadeIn(tween(180)) + expandVertically(tween(180)),
            exit = fadeOut(tween(120)) + shrinkVertically(tween(120))
        ) {
            Column {
                Spacer(Modifier.height(10.dp))
                DividerLine()
                Spacer(Modifier.height(8.dp))
                ToggleRow(
                    title = "Keepalive",
                    detail = if (state.isKeepaliveRunning) ui.keepaliveActive else ui.keepaliveInactive,
                    checked = state.isKeepaliveRunning,
                    enabled = !state.isHardwareBusy,
                    onChange = { enabled ->
                        if (enabled) viewModel.startKeepalive() else viewModel.stopKeepalive()
                    }
                )
            }
        }
    }
}

@Composable
private fun UtilitiesPanel(state: AppState, viewModel: FccViewModel) {
    val ui = TextCatalog.ui(state.language)
    PanelCard {
        SectionHeader(ui.aircraftUtilities, Icons.Filled.Settings)
        Spacer(Modifier.height(10.dp))

        BodyText(
            if (state.fourGMessage.isNotEmpty()) state.fourGMessage
            else ui.fourGHint,
            if (
                state.fourGMessage.contains("failed", true) ||
                state.fourGMessage.contains("not", true) ||
                state.fourGMessage.contains("başarısız", true) ||
                state.fourGMessage.contains("desteklenmiyor", true) ||
                state.fourGMessage.contains("algılanmadı", true) ||
                state.fourGMessage.contains("yok", true)
            ) Warning else TextBody
        )
        Spacer(Modifier.height(8.dp))

        if (state.is4gBusy) {
            ProgressBlock(state.busyProgress, ui.sending4g)
        } else {
            SecondaryButton(
                text = ui.send4g,
                icon = Icons.Filled.SystemUpdate,
                color = Warning,
                enabled = state.isConnected && !state.isHardwareBusy,
                onClick = { viewModel.send4gActivationFrames() }
            )
        }

        Spacer(Modifier.height(10.dp))
        DividerLine()
        Spacer(Modifier.height(10.dp))

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(ui.externalLed, color = TextStrong, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    if (state.ledStatus.isNotEmpty()) "${ui.statusPrefix}: ${state.ledStatus}" else ui.ledRequiresDji,
                    color = ledStatusColor(state.ledStatus),
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            SecondaryButton(
                text = ui.ledOn,
                icon = Icons.Filled.CheckCircle,
                color = Success,
                enabled = state.isConnected && !state.isLedBusy && !state.isHardwareBusy,
                modifier = Modifier.weight(1f),
                onClick = { viewModel.setLed(true) }
            )
            SecondaryButton(
                text = ui.ledOff,
                icon = Icons.Filled.PowerSettingsNew,
                color = TextBody,
                enabled = state.isConnected && !state.isLedBusy && !state.isHardwareBusy,
                modifier = Modifier.weight(1f),
                onClick = { viewModel.setLed(false) }
            )
        }
    }
}

@Composable
private fun AutoFccPanel(state: AppState, viewModel: FccViewModel) {
    val ui = TextCatalog.ui(state.language)
    PanelCard {
        ToggleRow(
            title = "Auto-FCC",
            detail = ui.autoFccDetail,
            checked = state.autoFcc,
            enabled = true,
            onChange = { viewModel.toggleAutoFcc() }
        )
    }
}

@Composable
private fun InfoPage(state: AppState, viewModel: FccViewModel) {
    val ui = TextCatalog.ui(state.language)
    PageScaffold {
        PageTitle(ui.deviceInfo, Icons.Outlined.Info)
        Spacer(Modifier.height(12.dp))

        PanelCard {
            SectionHeader(ui.connection, Icons.Filled.Info)
            Spacer(Modifier.height(12.dp))
            InfoRowCompact(ui.controller, state.controllerModel.ifEmpty { ui.unknown })
            Spacer(Modifier.height(8.dp))
            InfoRowCompact(ui.status, if (state.isConnected) ui.connected else ui.disconnected, connectionColor(state))
            Spacer(Modifier.height(8.dp))
            InfoRowCompact(ui.aircraftSerial, state.aircraftSerial.ifEmpty { ui.notDetected })
        }

        Spacer(Modifier.height(10.dp))
        PanelCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                SectionHeader(ui.version, Icons.Filled.SystemUpdate)
                IconButton(
                    onClick = { viewModel.queryDeviceInfo() },
                    enabled = state.isConnected && !state.isQueryingInfo && !state.isHardwareBusy,
                    modifier = Modifier.size(38.dp)
                ) {
                    if (state.isQueryingInfo) {
                        CircularProgressIndicator(strokeWidth = 2.dp, color = Primary, modifier = Modifier.size(20.dp))
                    } else {
                        Icon(Icons.Filled.Refresh, ui.query, tint = Primary, modifier = Modifier.size(20.dp))
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            when {
                state.deviceInfo.isNotEmpty() -> {
                    Text(
                        state.deviceInfo,
                        color = TextBody,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 17.sp,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                !state.isConnected -> BodyText(ui.connectFirst, TextMuted)
                else -> BodyText(ui.queryVersionHint)
            }
        }
    }
}

@Composable
private fun LogPage(state: AppState) {
    val ui = TextCatalog.ui(state.language)
    PageScaffold {
        PageTitle(ui.activityLog, Icons.Filled.History)
        Spacer(Modifier.height(12.dp))

        PanelCard {
            if (state.logMessages.isEmpty()) {
                EmptyState(ui.noActivity)
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 620.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    state.logMessages.forEachIndexed { index, entry ->
                        if (index > 0) {
                            Spacer(Modifier.height(6.dp))
                            DividerLine(0.45f)
                            Spacer(Modifier.height(6.dp))
                        }
                        LogLine(entry)
                    }
                }
            }
        }
    }
}

@Composable
private fun UpdatePage(state: AppState, viewModel: FccViewModel) {
    val ui = TextCatalog.ui(state.language)
    PageScaffold {
        PageTitle(ui.updates, Icons.Outlined.SystemUpdate)
        Spacer(Modifier.height(10.dp))

        when {
            state.isCheckingUpdate -> {
                PanelCard {
                    ProgressSpinner(ui.checkingLatest)
                }
            }
            state.updateInfo == null && state.updateChecked -> {
                PanelCard {
                    Icon(Icons.Filled.CloudOff, null, tint = TextMuted, modifier = Modifier.size(34.dp))
                    Spacer(Modifier.height(10.dp))
                    BodyText(ui.updateCheckFailed, TextBody)
                    Spacer(Modifier.height(12.dp))
                    SecondaryButton(ui.retry, Icons.Filled.Refresh, Primary) {
                        viewModel.checkForUpdates(force = true)
                    }
                }
            }
            state.updateInfo != null -> {
                val info = state.updateInfo
                PanelCard {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                if (state.updateAvailable) ui.upstreamUpdateAvailable else ui.upToDate,
                                color = if (state.updateAvailable) Success else TextBody,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(ui.currentVersion(FccViewModel.APP_VERSION), color = TextMuted, fontSize = 11.sp)
                        }
                        Icon(
                            if (state.updateAvailable) Icons.Filled.NewReleases else Icons.Filled.CheckCircle,
                            null,
                            tint = if (state.updateAvailable) Success else TextMuted,
                            modifier = Modifier.size(30.dp)
                        )
                    }

                    Spacer(Modifier.height(10.dp))
                    DividerLine()
                    Spacer(Modifier.height(10.dp))

                    InfoRowCompact(ui.latest, "v${info.version}", if (state.updateAvailable) Success else TextStrong)
                    Spacer(Modifier.height(6.dp))
                    InfoRowCompact(ui.released, info.publishedAt.split("T").firstOrNull().orEmpty())
                    if (info.apkSize > 0) {
                        Spacer(Modifier.height(6.dp))
                        InfoRowCompact(ui.size, "%.1f MB".format(info.apkSize / 1048576.0))
                    }

                    Spacer(Modifier.height(10.dp))
                    DividerLine()
                    Spacer(Modifier.height(10.dp))

                    Text(ui.changelog, color = TextStrong, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    BodyText(info.changelog.ifEmpty { ui.noChangelog })

                    if (state.profileUpdateMessage.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        BodyText(state.profileUpdateMessage, if (state.isUpdateDownloaded) Success else TextBody)
                    }
                    Spacer(Modifier.height(12.dp))
                    if (state.isDownloadingUpdate) {
                        ProgressBlock(state.updateDownloadProgress, ui.downloadingDronePeakUpdate)
                    } else if (state.isUpdateDownloaded) {
                        CommandButton(ui.installDronePeakUpdate, Icons.Filled.SystemUpdate, Success) {
                            viewModel.installUpdate()
                        }
                    } else if (state.updateAvailable) {
                        CommandButton(ui.downloadDronePeakUpdate, Icons.Filled.SystemUpdate, Success) {
                            viewModel.downloadUpdate()
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    SecondaryButton(ui.checkAgain, Icons.Filled.Refresh, Primary) {
                        viewModel.checkForUpdates(force = true)
                    }
                }
            }
        }
    }
}

@Composable
private fun PageScaffold(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = PageHorizontalPadding)
            .padding(top = 18.dp, bottom = BottomNavHeight + 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content
    )
}

@Composable
private fun AppHeader(state: AppState, viewModel: FccViewModel, ui: UiText) {
    PanelCard(padding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "DronePeak-FCC",
                    color = TextStrong,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    if (state.controllerModel.isNotEmpty()) "v${FccViewModel.APP_VERSION} / ${state.controllerModel}" else "v${FccViewModel.APP_VERSION}",
                    color = TextMuted,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                LanguageSelector(
                    selected = state.language,
                    onSelected = { viewModel.setLanguage(it) }
                )
                Spacer(Modifier.height(6.dp))
                StatusChip(ui.rcPanel, Primary)
            }
        }
    }
}

@Composable
private fun LanguageSelector(selected: AppLanguage, onSelected: (AppLanguage) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        AppLanguage.entries.forEach { language ->
            val isSelected = language == selected
            Surface(
                color = if (isSelected) Primary.copy(0.18f) else PanelAlt,
                shape = RoundedCornerShape(6.dp),
                border = BorderStroke(1.dp, if (isSelected) Primary.copy(0.65f) else MutedStroke),
                modifier = Modifier.clickable { onSelected(language) }
            ) {
                Text(
                    "${language.flag} ${language.shortName}",
                    color = if (isSelected) TextStrong else TextBody,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun PageTitle(title: String, icon: ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Icon(icon, null, tint = Primary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Text(title, color = TextStrong, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun PanelCard(
    modifier: Modifier = Modifier,
    padding: androidx.compose.ui.unit.Dp = 14.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        color = Panel,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, Stroke),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(padding),
            content = content
        )
    }
}

@Composable
private fun SectionHeader(title: String, icon: ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Primary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(title, color = TextStrong, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun StatusMetric(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Surface(
        color = PanelAlt,
        shape = RoundedCornerShape(7.dp),
        border = BorderStroke(1.dp, MutedStroke),
        modifier = modifier.height(62.dp)
    ) {
        Column(
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 10.dp)
        ) {
            Text(label, color = TextMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(color)
                Spacer(Modifier.width(6.dp))
                Text(
                    value,
                    color = color,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun InfoRowCompact(label: String, value: String, valueColor: Color = TextStrong) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = TextMuted, fontSize = 12.sp, maxLines = 1)
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            color = valueColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
    }
}

@Composable
private fun ToggleRow(
    title: String,
    detail: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = TextStrong, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(
                detail,
                color = if (checked) Success else TextMuted,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Success,
                checkedTrackColor = Success.copy(0.28f),
                uncheckedThumbColor = TextBody,
                uncheckedTrackColor = PanelAlt,
                disabledCheckedThumbColor = TextMuted,
                disabledUncheckedThumbColor = TextMuted
            )
        )
    }
}

@Composable
private fun CommandButton(
    text: String,
    icon: ImageVector,
    color: Color,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = color,
            contentColor = Ink,
            disabledContainerColor = color.copy(0.18f),
            disabledContentColor = color.copy(0.45f)
        ),
        shape = RoundedCornerShape(7.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
    ) {
        Icon(icon, null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SecondaryButton(
    text: String,
    icon: ImageVector,
    color: Color,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = color,
            disabledContentColor = TextMuted
        ),
        border = BorderStroke(1.dp, if (enabled) color.copy(0.55f) else MutedStroke),
        shape = RoundedCornerShape(7.dp),
        modifier = modifier
            .fillMaxWidth()
            .height(42.dp)
    ) {
        Icon(icon, null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(7.dp))
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun CompactTextButton(text: String, icon: ImageVector, enabled: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 6.dp)
    ) {
        Icon(icon, null, tint = if (enabled) Primary else TextMuted, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, color = if (enabled) Primary else TextMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun NoticeRow(title: String, detail: String, color: Color, icon: ImageVector) {
    Surface(
        color = color.copy(0.10f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, color.copy(0.35f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(12.dp)) {
            Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(detail, color = TextBody, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun BodyText(
    text: String,
    color: Color = TextBody,
    textAlign: TextAlign = TextAlign.Start,
    modifier: Modifier = Modifier
) {
    Text(
        text,
        color = color,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        textAlign = textAlign,
        modifier = modifier
    )
}

@Composable
private fun ProgressBlock(progress: Float, label: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(label, color = Primary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("${(progress * 100).toInt()}%", color = TextMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(PanelAlt)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Primary)
            )
        }
    }
}

@Composable
private fun ProgressSpinner(label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        CircularProgressIndicator(strokeWidth = 2.dp, color = Primary, modifier = Modifier.size(30.dp))
        Spacer(Modifier.height(12.dp))
        BodyText(label, Primary, textAlign = TextAlign.Center)
    }
}

@Composable
private fun EmptyState(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 42.dp),
        contentAlignment = Alignment.Center
    ) {
        BodyText(text, TextMuted, textAlign = TextAlign.Center)
    }
}

@Composable
private fun LogLine(entry: String) {
    val color = when {
        entry.contains("fail", true) ||
            entry.contains("error", true) ||
            entry.contains("hata", true) ||
            entry.contains("başarısız", true) -> Danger
        entry.contains("enabled", true) ||
            entry.contains("connected", true) ||
            entry.contains("restored", true) ||
            entry.contains("received", true) ||
            entry.contains("açıldı", true) ||
            entry.contains("bağlandı", true) ||
            entry.contains("alındı", true) ||
            entry.contains("yüklendi", true) -> Success
        entry.contains("Enabling", true) ||
            entry.contains("Disabling", true) ||
            entry.contains("Probing", true) ||
            entry.contains("Querying", true) ||
            entry.contains("Loaded", true) ||
            entry.contains("uygulanıyor", true) ||
            entry.contains("aranıyor", true) ||
            entry.contains("sorgulanıyor", true) ||
            entry.contains("meşgul", true) -> Warning
        else -> TextBody
    }

    Text(
        entry,
        color = color,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun DividerLine(alpha: Float = 1f) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MutedStroke.copy(alpha = alpha))
    )
}

@Composable
private fun StatusDot(color: Color) {
    val transition = rememberInfiniteTransition(label = "statusDot")
    val alpha by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "statusDotAlpha"
    )
    Box(
        modifier = Modifier
            .size(7.dp)
            .background(color.copy(alpha), CircleShape)
    )
}

@Composable
private fun StatusChip(text: String, color: Color) {
    Surface(
        color = color.copy(0.12f),
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, color.copy(0.35f))
    ) {
        Text(
            text,
            color = color,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
        )
    }
}

@Composable
private fun BottomNavBar(
    currentPage: Int,
    language: AppLanguage,
    onPageSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val ui = TextCatalog.ui(language)
    val tabs = listOf(
        NavItem(ui.fcc, Icons.Filled.Wifi, Primary),
        NavItem(ui.info, Icons.Filled.Info, Success),
        NavItem(ui.log, Icons.Filled.History, Warning),
        NavItem(ui.update, Icons.Filled.SystemUpdate, Color(0xFF9AA7FF))
    )

    Surface(
        color = Panel,
        border = BorderStroke(1.dp, MutedStroke),
        shadowElevation = 6.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(BottomNavHeight)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            tabs.forEachIndexed { index, item ->
                val selected = currentPage == index
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .weight(1f)
                        .height(BottomNavHeight)
                        .clip(RoundedCornerShape(7.dp))
                        .clickable { onPageSelected(index) }
                ) {
                    Icon(
                        item.icon,
                        item.label,
                        tint = if (selected) item.color else TextMuted,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        item.label,
                        color = if (selected) item.color else TextMuted,
                        fontSize = 9.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

private data class NavItem(
    val label: String,
    val icon: ImageVector,
    val color: Color
)

private fun connectionLabel(state: AppState, ui: UiText): String = when {
    state.status == "connecting" -> ui.linking
    state.isConnected -> ui.online
    state.status == "error" -> ui.error
    else -> ui.offline
}

private fun connectionColor(state: AppState): Color = when {
    state.status == "connecting" -> Warning
    state.isConnected -> Success
    state.status == "error" -> Danger
    else -> TextMuted
}

private fun operationLabel(state: AppState, ui: UiText): String = when {
    state.isHardwareBusy -> ui.hardwareBusy
    state.isBusy -> state.message.ifEmpty { ui.working }
    state.is4gBusy -> ui.fourGBusy
    state.isLedBusy -> ui.ledBusy
    state.message.isNotEmpty() -> state.message
    else -> ui.ready
}

private fun operationColor(state: AppState): Color = when {
    state.isHardwareBusy || state.isBusy || state.is4gBusy || state.isLedBusy -> Warning
    state.message.contains("fail", true) ||
        state.message.contains("error", true) ||
        state.message.contains("hata", true) ||
        state.message.contains("başarısız", true) -> Danger
    state.isConnected -> Success
    else -> TextMuted
}

private fun ledStatusColor(status: String): Color = when {
    status == "ON" -> Success
    status == "OFF" -> TextMuted
    status.contains("fail", true) ||
        status.contains("error", true) ||
        status.contains("hata", true) ||
        status.contains("başarısız", true) -> Warning
    status.isNotEmpty() -> Warning
    else -> TextMuted
}
