package com.dronepeak.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

// ═══════════════════════════════════════════════════════════════════════════
// Design tokens
//
// Cool dark monochrome with colour reserved strictly for meaning. Flat
// surfaces, 1dp hairline borders, one corner radius (12dp), no gradients and
// no glow — hierarchy comes from type scale and whitespace instead. The palette
// stays dark and high-contrast because this runs on controller screens in
// direct sun, where a light editorial theme would be unreadable.
// ═══════════════════════════════════════════════════════════════════════════

private val Canvas = Color(0xFF080B0E)
private val Surface1 = Color(0xFF0E1317)
private val Surface2 = Color(0xFF151C21)
private val Hairline = Color(0xFF232D34)
private val HairlineSoft = Color(0xFF1A2228)

private val Ink = Color(0xFFF1F5F7)
private val InkMuted = Color(0xFF93A4AF)
private val InkFaint = Color(0xFF5F6E78)

private val AccentLink = Color(0xFF4FB6DD)
private val AccentFcc = Color(0xFF45C98A)
private val AccentWarn = Color(0xFFDFA42B)
private val AccentAlert = Color(0xFFE85A5F)
private val AccentFourG = Color(0xFF9187DE)

/** Semantic colours Material 3's scheme has no slot for. */
private data class SemanticColors(
    val success: Color,
    val warning: Color,
    val danger: Color,
    val fourG: Color,
    val neutral: Color
)

private val LocalSemanticColors = staticCompositionLocalOf {
    SemanticColors(AccentFcc, AccentWarn, AccentAlert, AccentFourG, AccentLink)
}

private val Corner = RoundedCornerShape(12.dp)
private val CornerSmall = RoundedCornerShape(8.dp)
private val CornerTag = RoundedCornerShape(4.dp)

private val PagePadding = 16.dp
private val BarHeight = 64.dp
private val RailWidth = 92.dp
private val MinTouchTarget = 48.dp
private val PrimaryActionHeight = 56.dp

/**
 * Width buckets chosen for the controllers this ships on, in dp.
 *
 * DJI RC 2 is a 5.5" 1080x1920 panel at ~400ppi, so it lands at roughly
 * 432x768dp — the compact branch, where every action has to fit above the
 * fold. RC Pro 2 / RC Plus 2 are 11" 2160x2560, about 1130x1340dp, so they get
 * a navigation rail and a single-row utility grid instead.
 */
private enum class WidthClass { COMPACT, MEDIUM, EXPANDED }

private fun widthClassFor(width: Dp): WidthClass = when {
    width < 600.dp -> WidthClass.COMPACT
    width < 900.dp -> WidthClass.MEDIUM
    else -> WidthClass.EXPANDED
}

class MainActivity : ComponentActivity() {

    private val viewModel: FccViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.init()

        setContent {
            CompositionLocalProvider(
                LocalSemanticColors provides SemanticColors(
                    success = AccentFcc,
                    warning = AccentWarn,
                    danger = AccentAlert,
                    fourG = AccentFourG,
                    neutral = AccentLink
                )
            ) {
                MaterialTheme(
                    colorScheme = darkColorScheme(
                        primary = AccentLink,
                        onPrimary = Canvas,
                        background = Canvas,
                        onBackground = Ink,
                        surface = Surface1,
                        onSurface = Ink,
                        surfaceVariant = Surface2,
                        onSurfaceVariant = InkMuted,
                        outline = Hairline,
                        outlineVariant = HairlineSoft,
                        error = AccentAlert
                    )
                ) {
                    AppRoot(viewModel)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.onAppResumed()
    }
}

@Composable
private fun AppRoot(viewModel: FccViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pagerState = rememberPagerState(initialPage = 0) { 4 }
    val scope = rememberCoroutineScope()

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Canvas)
    ) {
        when (widthClassFor(maxWidth)) {
            WidthClass.COMPACT, WidthClass.MEDIUM -> {
                Column(modifier = Modifier.fillMaxSize()) {
                    PageHost(state, viewModel, pagerState, Modifier.weight(1f))
                    NavBar(
                        currentPage = pagerState.currentPage,
                        onSelect = { target -> scope.launch { pagerState.animateScrollToPage(target) } }
                    )
                }
            }

            WidthClass.EXPANDED -> {
                Row(modifier = Modifier.fillMaxSize()) {
                    NavRail(
                        currentPage = pagerState.currentPage,
                        onSelect = { target -> scope.launch { pagerState.animateScrollToPage(target) } }
                    )
                    PageHost(state, viewModel, pagerState, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun PageHost(
    state: AppState,
    viewModel: FccViewModel,
    pagerState: androidx.compose.foundation.pager.PagerState,
    modifier: Modifier = Modifier
) {
    HorizontalPager(
        state = pagerState,
        modifier = modifier.fillMaxSize(),
        userScrollEnabled = true
    ) { page ->
        when (page) {
            0 -> ControlPage(state, viewModel)
            1 -> InfoPage(state, viewModel)
            2 -> LogPage(state)
            else -> UpdatePage(state, viewModel)
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// Control page
//
// Everything above the fold on a 432x768dp RC 2: status, one primary action,
// the link bar, and the utility grid. 4G and LED are no longer buried under an
// "expand tools" disclosure — they were unreachable without scrolling, which
// is what made the 4G button read as broken.
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun ControlPage(state: AppState, viewModel: FccViewModel) {
    val ui = TextCatalog.ui(state.language)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = PagePadding)
            .padding(top = 12.dp, bottom = BarHeight + 16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 900.dp)
                .align(Alignment.CenterHorizontally)
        ) {
            StatusStrip(state, viewModel)
            Spacer(Modifier.height(14.dp))
            PrimaryActions(state, viewModel)
            Spacer(Modifier.height(12.dp))
            LinkBar(state, viewModel)
            Spacer(Modifier.height(12.dp))
            UtilityGrid(state, viewModel)
            Spacer(Modifier.height(12.dp))
            FourGStatus(state)

            if (state.updateAvailable && state.updateInfo != null && !state.isCheckingUpdate) {
                Spacer(Modifier.height(12.dp))
                Notice(
                    title = ui.updateAvailableTitle(state.updateInfo.version),
                    detail = ui.updateAvailableDetail,
                    accent = AccentFcc,
                    icon = Icons.Filled.NewReleases
                )
            }
        }
    }
}

/**
 * The always-visible status header: link state, region, and whatever the app
 * currently has to say.
 *
 * This used to be a card with a gradient button inside it, and it was the
 * first thing below the fold. It is now the top of the page and it always
 * shows [AppState.message], which is where lock contention is reported — the
 * one piece of feedback that was previously only written to the Log tab.
 */
@Composable
private fun StatusStrip(state: AppState, viewModel: FccViewModel) {
    val ui = TextCatalog.ui(state.language)
    val accent = animateColorAsState(
        targetValue = toneColor(regionTone(state)),
        animationSpec = tween(220),
        label = "regionAccent"
    ).value
    val headline = when {
        state.isBusy -> if (state.language == AppLanguage.TR) "İşlem sürüyor" else "Operation running"
        state.is4gBusy -> if (state.language == AppLanguage.TR) "4G gönderiliyor" else "Sending 4G"
        state.isLedBusy -> if (state.language == AppLanguage.TR) "LED gönderiliyor" else "Sending LED"
        !state.isConnected -> if (state.language == AppLanguage.TR) "Kumandayı bağla" else "Connect your controller"
        state.isFccEnabled -> if (state.language == AppLanguage.TR) "FCC aktif" else "FCC is active"
        else -> if (state.language == AppLanguage.TR) "FCC'ye hazır" else "Ready for FCC"
    }
    val detail = state.message.ifEmpty {
        when {
            !state.isConnected -> ui.connectHint
            state.isFccEnabled -> ui.fccActiveHint
            else -> ui.readyApplyFcc
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Tag(
                text = connectionLabel(state, ui),
                accent = animateColorAsState(connectionColor(state), tween(220), label = "linkAccent").value
            )
            Tag(text = if (state.isFccEnabled) "FCC" else "CE", accent = accent)
            Spacer(Modifier.weight(1f))
            LanguageToggle(state.language, { viewModel.setLanguage(it) })
        }

        Spacer(Modifier.height(10.dp))

        Text(
            text = headline,
            color = Ink,
            fontSize = 30.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = (-0.6).sp,
            lineHeight = 34.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(Modifier.height(5.dp))

        Text(
            text = detail,
            color = animateColorAsState(toneColor(state.messageTone), tween(220), label = "msgAccent").value,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(Modifier.height(10.dp))

        // Tone is carried by a hairline rule rather than a coloured panel, so
        // the status change is felt without the whole block shifting hue.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(accent)
        )

        val progress = when {
            state.is4gBusy -> state.busyProgress
            state.isBusy -> state.busyProgress
            state.isHardwareBusy && state.message.isBlank() -> null
            else -> null
        }
        if (progress != null) {
            Spacer(Modifier.height(8.dp))
            MeterBar(progress)
        }
    }
}

/** The one action that matters right now, sized for a gloved thumb on a controller. */
@Composable
private fun PrimaryActions(state: AppState, viewModel: FccViewModel) {
    val ui = TextCatalog.ui(state.language)
    val blocked = hardwareBlockedReason(state)

    if (state.isBusy) {
        Column(modifier = Modifier.fillMaxWidth()) {
            MeterBar(state.busyProgress)
            Spacer(Modifier.height(8.dp))
            Text(
                text = state.message.ifEmpty { ui.working },
                color = AccentLink,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        return
    }

    when {
        !state.isConnected -> SolidButton(
            text = ui.connect,
            icon = Icons.Filled.Wifi,
            accent = AccentLink,
            onClick = { viewModel.connect() }
        )

        state.isFccEnabled -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SolidButton(
                text = ui.stopFcc,
                icon = Icons.Filled.PowerSettingsNew,
                accent = AccentAlert,
                enabled = blocked == null,
                modifier = Modifier.weight(1f),
                onClick = { viewModel.disableFcc() }
            )
            SolidButton(
                text = ui.reapplyFcc,
                icon = Icons.Filled.Refresh,
                accent = AccentLink,
                enabled = blocked == null,
                modifier = Modifier.weight(1f),
                onClick = { viewModel.enableFcc() }
            )
        }

        else -> SolidButton(
            text = ui.enableFcc,
            icon = Icons.Filled.Radio,
            accent = AccentFcc,
            enabled = blocked == null,
            onClick = { viewModel.enableFcc() }
        )
    }

    if (blocked != null) {
        Spacer(Modifier.height(6.dp))
        BlockedNote(blocked)
    }
}

/** Connect, serial readout, and the keepalive switch on one hairline row. */
@Composable
private fun LinkBar(state: AppState, viewModel: FccViewModel) {
    val ui = TextCatalog.ui(state.language)
    val blocked = hardwareBlockedReason(state)

    Surface(
        color = Surface1,
        shape = Corner,
        border = BorderStroke(1.dp, Hairline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlineAction(
                    text = if (state.isConnected) ui.connected else ui.connect,
                    icon = Icons.Filled.Wifi,
                    accent = animateColorAsState(connectionColor(state), tween(220), label = "connAccent").value,
                    enabled = !state.isConnected && blocked == null,
                    dense = true,
                    modifier = Modifier.weight(1f),
                    onClick = { viewModel.connect() }
                )

                SerialReadout(state, viewModel, ui, Modifier.weight(1f))
            }

            Spacer(Modifier.height(6.dp))
            HairlineDivider()
            Spacer(Modifier.height(2.dp))

            ToggleRow(
                title = "Keepalive",
                detail = if (state.isKeepaliveRunning) ui.keepaliveActive else ui.keepaliveInactive,
                checked = state.isKeepaliveRunning,
                enabled = state.isConnected && blocked == null,
                blockedReason = blocked ?: if (state.isConnected) null else linkBlockedReason(state),
                onChange = { if (it) viewModel.startKeepalive() else viewModel.stopKeepalive() }
            )
        }
    }
}

/** Serial value in monospace, with a rescan affordance when it is worth retrying. */
@Composable
private fun SerialReadout(state: AppState, viewModel: FccViewModel, ui: UiText, modifier: Modifier = Modifier) {
    val manual = state.manualSerial.isNotEmpty()
    val hasSerial = state.aircraftSerial.isNotEmpty()

    Row(
        modifier = modifier.heightIn(min = MinTouchTarget),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            MetaLabel(if (manual) ui.serialIsManual else ui.detectedSerial)
            Spacer(Modifier.height(1.dp))
            Text(
                text = when {
                    state.isProbingSerial -> ui.working
                    hasSerial -> state.aircraftSerial
                    else -> ui.notDetected
                },
                color = if (hasSerial) Ink else InkFaint,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (!state.isProbingSerial) {
            IconAction(
                icon = Icons.Filled.Refresh,
                description = ui.refreshSerial,
                enabled = !state.isHardwareBusy,
                onClick = { viewModel.probeSerial() }
            )
        } else {
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                color = AccentLink,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/**
 * Flat utility grid, hairline-divided rather than individually bordered.
 *
 * 4G sits in the top-left on purpose: it is the action that was hardest to
 * reach and easiest to misread as broken, so it now owns the first tile.
 */
@Composable
private fun UtilityGrid(state: AppState, viewModel: FccViewModel) {
    val busy = hardwareBlockedReason(state)
    val fourGBlocked = fourGBlockedReason(state)
    val ledBlocked = linkBlockedReason(state)

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val columns = when (widthClassFor(maxWidth)) {
            WidthClass.COMPACT -> 2
            WidthClass.MEDIUM -> 3
            WidthClass.EXPANDED -> 4
        }
        val tiles = listOf(
            GridTileSpec(
                label = TextCatalog.ui(state.language).send4g,
                icon = Icons.Filled.SystemUpdate,
                accent = AccentFourG,
                running = state.is4gBusy,
                enabled = fourGBlocked == null,
                onClick = { viewModel.send4gActivationFrames() }
            ),
            GridTileSpec(
                label = TextCatalog.ui(state.language).ledOn,
                icon = Icons.Filled.Lightbulb,
                accent = AccentFcc,
                running = state.isLedBusy,
                enabled = ledBlocked == null && !state.isLedBusy,
                onClick = { viewModel.setLed(true) }
            ),
            GridTileSpec(
                label = TextCatalog.ui(state.language).ledOff,
                icon = Icons.Filled.PowerSettingsNew,
                accent = InkMuted,
                running = false,
                enabled = ledBlocked == null && !state.isLedBusy,
                onClick = { viewModel.setLed(false) }
            ),
            GridTileSpec(
                label = "DJI Fly",
                icon = Icons.Filled.Flight,
                accent = AccentFcc,
                running = false,
                enabled = true,
                onClick = { viewModel.launchDjiFly() }
            )
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(Corner)
                .background(Surface1)
        ) {
            tiles.chunked(columns).forEachIndexed { rowIndex, row ->
                if (rowIndex > 0) HairlineDivider()
                Row(modifier = Modifier.fillMaxWidth()) {
                    row.forEachIndexed { columnIndex, tile ->
                        if (columnIndex > 0) {
                            Box(
                                modifier = Modifier
                                    .width(1.dp)
                                    .fillMaxHeight()
                                    .background(Hairline)
                            )
                        }
                        GridTile(
                            spec = tile,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                        )
                    }
                    // Keep the last row's cells the same width as a full row.
                    repeat(columns - row.size) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(TileHeight)
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TogglePill(
                label = "Auto-FCC",
                checked = state.autoFcc,
                onChange = { viewModel.toggleAutoFcc() }
            )
            Spacer(Modifier.weight(1f))
        }

        // A disabled tile is indistinguishable from a dead one, so the reason
        // is spelled out underneath instead of being left to inference.
        val blockedNote = fourGBlocked
            ?: ledBlocked
            ?: busy
        if (blockedNote != null) {
            Spacer(Modifier.height(8.dp))
            BlockedNote(blockedNote)
        }
    }
}

private data class GridTileSpec(
    val label: String,
    val icon: ImageVector,
    val accent: Color,
    val running: Boolean,
    val enabled: Boolean,
    val onClick: () -> Unit
)

private val TileHeight = 72.dp

@Composable
private fun GridTile(spec: GridTileSpec, modifier: Modifier = Modifier) {
    val accent = animateColorAsState(
        targetValue = if (spec.enabled) spec.accent else InkFaint,
        animationSpec = tween(200),
        label = "tileAccent"
    ).value

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .height(TileHeight)
            .semantics {
                role = Role.Button
                stateDescription = if (spec.running) "running" else "idle"
                contentDescription = spec.label
            }
            .clickable(enabled = spec.enabled, onClick = spec.onClick)
    ) {
        if (spec.running) {
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                color = spec.accent,
                modifier = Modifier.size(22.dp)
            )
        } else {
            Icon(
                spec.icon,
                null,
                tint = accent,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = spec.label,
            color = if (spec.enabled) Ink else InkFaint,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.3.sp,
            maxLines = 2,
            textAlign = TextAlign.Center,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
    }
}

/**
 * 4G status line plus the keepalive-conflict warning.
 *
 * The outcome is a typed [FourGOutcome], not a substring search over
 * translated prose — the old colour heuristic matched none of its keywords
 * for "4G error: …" or "4G needs the aircraft connected…", so genuine failures
 * rendered in neutral body colour.
 */
@Composable
private fun FourGStatus(state: AppState) {
    val ui = TextCatalog.ui(state.language)
    if (state.fourGOutcome == FourGOutcome.IDLE && state.fourGMessage.isBlank()) return

    val accent = animateColorAsState(
        targetValue = fourGTone(state.fourGOutcome),
        animationSpec = tween(220),
        label = "fourGTone"
    ).value

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite }
    ) {
        Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .padding(top = 5.dp)
                    .width(2.dp)
                    .height(14.dp)
                    .background(accent)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = state.fourGMessage,
                color = Ink,
                fontSize = 12.sp,
                lineHeight = 17.sp
            )
        }

        if (state.fourGOutcome == FourGOutcome.WRITTEN) {
            Spacer(Modifier.height(6.dp))
            MetaNote(text = ui.fourGUnverified, accent = AccentWarn)
        }

        if (state.isKeepaliveRunning && state.fourGOutcome != FourGOutcome.IDLE) {
            Spacer(Modifier.height(6.dp))
            MetaNote(text = ui.keepaliveConflicts4g, accent = AccentWarn)
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// Info page
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun InfoPage(state: AppState, viewModel: FccViewModel) {
    val ui = TextCatalog.ui(state.language)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = PagePadding)
            .padding(top = 16.dp, bottom = BarHeight + 16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 900.dp)
                .align(Alignment.CenterHorizontally)
        ) {
            SectionLabel(ui.deviceInfo)
            Spacer(Modifier.height(10.dp))

            DetailBlock {
                MetaRow(ui.controller, state.controllerModel.ifEmpty { ui.unknown })
                Spacer(Modifier.height(8.dp))
                MetaRow(
                    ui.status,
                    if (state.isConnected) ui.connected else ui.disconnected,
                    animateColorAsState(connectionColor(state), tween(200), label = "infoConn").value
                )
                Spacer(Modifier.height(8.dp))
                MetaRow(ui.aircraftSerial, state.aircraftSerial.ifEmpty { ui.notDetected })
                if (state.manualSerial.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    MetaRow(ui.serialIsManual, state.manualSerial, AccentLink)
                }
            }

            Spacer(Modifier.height(12.dp))
            SectionLabel(ui.connection)
            Spacer(Modifier.height(10.dp))

            DetailBlock {
                when {
                    state.isQueryingInfo -> InlineProgress(ui.working)
                    state.deviceInfo.isNotEmpty() -> Text(
                        state.deviceInfo,
                        color = Ink,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                    )
                    else -> Text(
                        if (state.isConnected) ui.queryVersionHint else ui.connectFirst,
                        color = InkFaint,
                        fontSize = 13.sp
                    )
                }
                Spacer(Modifier.height(12.dp))
                OutlineAction(
                    text = ui.query,
                    icon = Icons.Filled.Refresh,
                    accent = AccentLink,
                    enabled = state.isConnected && !state.isQueryingInfo && !state.isHardwareBusy,
                    blockedReason = hardwareBlockedReason(state)?.takeIf { state.isConnected },
                    onClick = { viewModel.queryDeviceInfo() }
                )
            }

            Spacer(Modifier.height(12.dp))
            SectionLabel(ui.manualSerial)
            Spacer(Modifier.height(10.dp))

            ManualSerialBlock(state, viewModel)

            Spacer(Modifier.height(12.dp))
            SectionLabel(ui.version)
            Spacer(Modifier.height(10.dp))

            DetailBlock {
                MetaRow(ui.currentVersion(BuildConfig.VERSION_NAME), state.controllerModel.ifEmpty { ui.unknown })
                if (state.isKeepaliveRunning) {
                    Spacer(Modifier.height(8.dp))
                    MetaRow("Keepalive", ui.on, AccentFcc)
                }
            }
        }
    }
}

/**
 * Manual serial entry.
 *
 * Detection can fail on a controller that is perfectly linked, and without
 * this there is no way to reach 4G: the serial is embedded in every 4G
 * payload, so the feature is simply unavailable. Upstream ships this field and
 * treats a manual value as the highest-priority source; it was dropped in the
 * DronePeak fork.
 */
@Composable
private fun ManualSerialBlock(state: AppState, viewModel: FccViewModel) {
    val ui = TextCatalog.ui(state.language)
    var field by rememberSaveable(state.manualSerial, state.aircraftSerial) {
        mutableStateOf(if (state.manualSerial.isNotEmpty()) state.manualSerial else state.aircraftSerial)
    }

    DetailBlock {
        Text(
            text = ui.manualSerialHint,
            color = InkMuted,
            fontSize = 12.sp,
            lineHeight = 17.sp
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = field,
            onValueChange = { field = it.trim() },
            singleLine = true,
            shape = CornerSmall,
            textStyle = androidx.compose.ui.text.TextStyle(
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                color = Ink
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = AccentLink,
                unfocusedBorderColor = Hairline,
                focusedContainerColor = Surface2,
                unfocusedContainerColor = Surface2,
                cursorColor = AccentLink
            ),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlineAction(
                text = ui.useManualSerial,
                icon = Icons.Filled.CheckCircle,
                accent = AccentFcc,
                dense = true,
                enabled = field.isNotBlank() && field != state.manualSerial,
                modifier = Modifier.weight(1f),
                onClick = { viewModel.setManualSerial(field) }
            )
            OutlineAction(
                text = ui.clearManualSerial,
                icon = Icons.Filled.Close,
                accent = InkMuted,
                dense = true,
                enabled = state.manualSerial.isNotEmpty(),
                modifier = Modifier.weight(1f),
                onClick = { viewModel.setManualSerial("") }
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// Log page
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun LogPage(state: AppState) {
    val ui = TextCatalog.ui(state.language)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = PagePadding)
            .padding(top = 16.dp, bottom = BarHeight + 16.dp)
    ) {
        SectionLabel(ui.activityLog)
        Spacer(Modifier.height(10.dp))

        if (state.logMessages.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(ui.noActivity, color = InkFaint, fontSize = 13.sp)
            }
            return@Column
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 900.dp)
                .fillMaxHeight()
        ) {
            items(state.logMessages) { entry ->
                LogLine(entry)
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// Update page
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun UpdatePage(state: AppState, viewModel: FccViewModel) {
    val ui = TextCatalog.ui(state.language)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = PagePadding)
            .padding(top = 16.dp, bottom = BarHeight + 16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 900.dp)
                .align(Alignment.CenterHorizontally)
        ) {
            SectionLabel(ui.updates)
            Spacer(Modifier.height(10.dp))

            when {
                state.isCheckingUpdate -> DetailBlock { InlineProgress(ui.checkingLatest) }

                state.updateInfo == null && state.updateChecked -> DetailBlock {
                    Text(ui.updateCheckFailed, color = Ink, fontSize = 13.sp, lineHeight = 18.sp)
                    Spacer(Modifier.height(12.dp))
                    OutlineAction(ui.retry, Icons.Filled.Refresh, AccentLink) {
                        viewModel.checkForUpdates(force = true)
                    }
                }

                state.updateInfo != null -> {
                    val info = state.updateInfo
                    DetailBlock {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (state.updateAvailable) ui.upstreamUpdateAvailable else ui.upToDate,
                                    color = if (state.updateAvailable) AccentFcc else Ink,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = (-0.2).sp
                                )
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    text = ui.currentVersion(BuildConfig.VERSION_NAME),
                                    color = InkMuted,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            Icon(
                                if (state.updateAvailable) Icons.Filled.NewReleases else Icons.Filled.CheckCircle,
                                null,
                                tint = if (state.updateAvailable) AccentFcc else InkFaint,
                                modifier = Modifier.size(26.dp)
                            )
                        }

                        Spacer(Modifier.height(12.dp))
                        HairlineDivider()
                        Spacer(Modifier.height(10.dp))

                        MetaRow(ui.latest, "v${info.version}", if (state.updateAvailable) AccentFcc else Ink)
                        Spacer(Modifier.height(6.dp))
                        MetaRow(ui.released, info.publishedAt.split("T").firstOrNull().orEmpty())
                        if (info.apkSize > 0) {
                            Spacer(Modifier.height(6.dp))
                            MetaRow(ui.size, "%.1f MB".format(info.apkSize / 1048576.0))
                        }

                        Spacer(Modifier.height(12.dp))
                        HairlineDivider()
                        Spacer(Modifier.height(10.dp))

                        MetaLabel(ui.changelog)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            info.changelog.ifEmpty { ui.noChangelog },
                            color = InkMuted,
                            fontSize = 13.sp,
                            lineHeight = 19.sp
                        )

                        if (state.profileUpdateMessage.isNotEmpty()) {
                            Spacer(Modifier.height(10.dp))
                            MetaNote(
                                text = state.profileUpdateMessage,
                                accent = when (state.updateStage) {
                                    UpdateStage.READY, UpdateStage.COMPLETED -> AccentFcc
                                    UpdateStage.FAILED -> AccentAlert
                                    UpdateStage.NEEDS_INSTALL_PERMISSION,
                                    UpdateStage.VERIFYING,
                                    UpdateStage.PREPARING_INSTALL,
                                    UpdateStage.WAITING_FOR_ANDROID -> AccentWarn
                                    else -> InkMuted
                                }
                            )
                        }

                        if (state.updateDiagnosticSummary.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            HairlineDivider()
                            Spacer(Modifier.height(10.dp))
                            MetaLabel(ui.updateDiagnostics)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                state.updateDiagnosticSummary,
                                color = if (state.updateDiagnosticFailure) AccentAlert else Ink,
                                fontSize = 13.sp,
                                lineHeight = 18.sp
                            )
                            if (state.updateDiagnosticDetails.isNotEmpty()) {
                                Spacer(Modifier.height(5.dp))
                                Text(
                                    state.updateDiagnosticDetails,
                                    color = InkFaint,
                                    fontSize = 11.sp,
                                    lineHeight = 16.sp,
                                    maxLines = 10,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Spacer(Modifier.height(14.dp))

                        when (state.updateStage) {
                            UpdateStage.DOWNLOADING -> {
                                MeterBar(state.updateDownloadProgress)
                                Spacer(Modifier.height(10.dp))
                                OutlineAction(ui.cancelDownload, Icons.Filled.Close, AccentWarn) {
                                    viewModel.cancelUpdateDownload()
                                }
                            }
                            UpdateStage.READY -> SolidButton(ui.installDronePeakUpdate, Icons.Filled.SystemUpdate, AccentFcc) {
                                viewModel.installUpdate()
                            }
                            UpdateStage.NEEDS_INSTALL_PERMISSION -> SolidButton(ui.openInstallSettings, Icons.Filled.Wifi, AccentWarn) {
                                viewModel.installUpdate()
                            }
                            UpdateStage.VERIFYING, UpdateStage.PREPARING_INSTALL ->
                                DetailBlock { InlineProgress(state.profileUpdateMessage.ifEmpty { ui.preparingInstallation }) }
                            UpdateStage.WAITING_FOR_ANDROID ->
                                DetailBlock { InlineProgress(state.profileUpdateMessage.ifEmpty { ui.waitingForAndroidApproval }) }
                            UpdateStage.COMPLETED ->
                                Notice(ui.installationCompleted, state.profileUpdateMessage, AccentFcc, Icons.Filled.CheckCircle)
                            UpdateStage.FAILED -> OutlineAction(ui.retry, Icons.Filled.Refresh, AccentLink) {
                                viewModel.reDownloadUpdate()
                            }
                            UpdateStage.NONE -> if (state.updateAvailable) {
                                SolidButton(ui.downloadDronePeakUpdate, Icons.Filled.SystemUpdate, AccentFcc) {
                                    viewModel.downloadUpdate()
                                }
                            }
                        }

                        Spacer(Modifier.height(10.dp))
                        OutlineAction(ui.checkAgain, Icons.Filled.Refresh, AccentLink) {
                            viewModel.checkForUpdates(force = true)
                        }
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// Components
// ═══════════════════════════════════════════════════════════════════════════

/**
 * Flat, solid primary action. No gradient and no glow: the accent is a solid
 * fill and the whole thing sits on the canvas.
 */
@Composable
private fun SolidButton(
    text: String,
    icon: ImageVector,
    accent: Color,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = Corner,
        contentPadding = PaddingValues(horizontal = 16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = accent,
            contentColor = Canvas,
            disabledContainerColor = Surface2,
            disabledContentColor = InkFaint
        ),
        modifier = modifier
            .fillMaxWidth()
            .height(PrimaryActionHeight)
            .semantics { role = Role.Button }
    ) {
        Icon(icon, null, modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(9.dp))
        Text(
            text = text,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Secondary action on a hairline outline.
 *
 * [blockedReason] exists because a disabled control that does not say why is
 * indistinguishable from a broken one. With keepalive re-applying FCC every
 * two seconds the hardware lock is contended a meaningful fraction of the
 * time, so the reason is shown next to the button rather than left implicit.
 */
@Composable
private fun OutlineAction(
    text: String,
    icon: ImageVector,
    accent: Color,
    enabled: Boolean = true,
    dense: Boolean = false,
    blockedReason: String? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = CornerSmall,
        contentPadding = PaddingValues(horizontal = 14.dp),
        border = BorderStroke(1.dp, if (enabled) accent.copy(alpha = 0.5f) else HairlineSoft),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = accent,
            disabledContentColor = InkFaint
        ),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = if (dense) MinTouchTarget else 48.dp)
            .semantics {
                role = Role.Button
                blockedReason?.let { stateDescription = it }
            }
    ) {
        Icon(icon, null, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            fontSize = if (dense) 13.sp else 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun IconAction(
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(MinTouchTarget)
            .semantics {
                role = Role.Button
                contentDescription = description
            }
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        Icon(
            icon,
            null,
            tint = if (enabled) InkMuted else InkFaint,
            modifier = Modifier.size(18.dp)
        )
    }
}

/** Editorial micro-label: uppercase, small, wide tracking. */
@Composable
private fun MetaLabel(text: String) {
    Text(
        text = text.uppercase(),
        color = InkFaint,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun SectionLabel(text: String) {
    MetaLabel(text)
}

/**
 * Small caption in an accent colour, marked by a short vertical rule.
 * Used for caveats that would otherwise be lost in body text.
 */
@Composable
private fun MetaNote(text: String, accent: Color) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .padding(top = 2.dp)
                .width(2.dp)
                .height(12.dp)
                .background(accent)
        )
        Spacer(Modifier.width(8.dp))
        Text(text, color = InkMuted, fontSize = 11.sp, lineHeight = 16.sp)
    }
}

@Composable
private fun BlockedNote(reason: String) {
    MetaNote(reason, AccentWarn)
}

@Composable
private fun DetailBlock(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = Surface1,
        shape = Corner,
        border = BorderStroke(1.dp, Hairline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), content = content)
    }
}

@Composable
private fun MetaRow(label: String, value: String, valueColor: Color = Ink) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = InkMuted, fontSize = 13.sp, maxLines = 1)
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            color = valueColor,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
    }
}

/** Accent-bordered notice. Used sparingly — only for things worth interrupting for. */
@Composable
private fun Notice(title: String, detail: String, accent: Color, icon: ImageVector) {
    Surface(
        color = Surface1,
        shape = CornerSmall,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.Top
        ) {
            Icon(icon, null, tint = accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                if (detail.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(detail, color = InkMuted, fontSize = 12.sp, lineHeight = 17.sp)
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    detail: String,
    checked: Boolean,
    enabled: Boolean,
    blockedReason: String? = null,
    onChange: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .semantics {
                stateDescription = if (checked) "on" else "off"
                blockedReason?.let { contentDescription = it }
            }
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(
                text = blockedReason ?: detail,
                color = when {
                    blockedReason != null -> AccentWarn
                    checked -> AccentFcc
                    else -> InkFaint
                },
                fontSize = 12.sp,
                lineHeight = 16.sp,
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
                checkedThumbColor = Canvas,
                checkedTrackColor = AccentFcc,
                uncheckedThumbColor = InkMuted,
                uncheckedTrackColor = Surface2,
                uncheckedBorderColor = Hairline,
                disabledCheckedThumbColor = InkFaint,
                disabledCheckedTrackColor = Surface2,
                disabledUncheckedThumbColor = InkFaint
            )
        )
    }
}

/** Compact checkbox-style pill for secondary settings that do not warrant a switch row. */
@Composable
private fun TogglePill(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val accent = animateColorAsState(
        targetValue = if (checked) AccentFcc else InkFaint,
        animationSpec = tween(200),
        label = "pillAccent"
    ).value

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .heightIn(min = MinTouchTarget)
            .clip(CornerTag)
            .background(if (checked) AccentFcc.copy(alpha = 0.12f) else Surface1)
            .semantics {
                role = Role.Checkbox
                this.selected = checked
                contentDescription = label
            }
            .clickable { onChange(!checked) }
            .padding(horizontal = 12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(14.dp)
                .clip(CornerTag)
                .background(if (checked) AccentFcc else Surface2)
        )
        Text(
            text = label,
            color = if (checked) Ink else InkMuted,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun MeterBar(progress: Float) {
    val fraction = progress.coerceIn(0f, 1f)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(3.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(Surface2)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .height(3.dp)
                .background(AccentLink)
        )
    }
}

@Composable
private fun InlineProgress(label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        CircularProgressIndicator(
            strokeWidth = 2.dp,
            color = AccentLink,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            color = InkMuted,
            fontSize = 13.sp,
            lineHeight = 18.sp
        )
    }
}

@Composable
private fun HairlineDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Hairline)
    )
}

/** Tiny uppercase tag. The only place a small rounded shape is allowed. */
@Composable
private fun Tag(text: String, accent: Color) {
    Surface(
        color = Surface2,
        shape = CornerTag,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.45f)),
        modifier = Modifier.semantics { contentDescription = text }
    ) {
        Text(
            text = text.uppercase(),
            color = accent,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
        )
    }
}

/**
 * Language switch. Flag emoji are gone on purpose: they render inconsistently
 * across Android builds, and this control sits in the most safety-relevant
 * part of the UI.
 */
@Composable
private fun LanguageToggle(
    selected: AppLanguage,
    onSelect: (AppLanguage) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        AppLanguage.entries.forEach { language ->
            val isSelected = language == selected
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .heightIn(min = 36.dp)
                    .clip(CornerTag)
                    .background(if (isSelected) Ink else Color.Transparent)
                    .semantics {
                        role = Role.RadioButton
                        this.selected = isSelected
                        contentDescription = language.name
                    }
                    .clickable { onSelect(language) }
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                Text(
                    text = language.shortName,
                    color = if (isSelected) Canvas else InkMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
            }
        }
    }
}

@Composable
private fun LogLine(entry: String) {
    val color = logToneColor(entry)
    Text(
        text = entry,
        color = color,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
    )
}

// ═══════════════════════════════════════════════════════════════════════════
// Navigation
// ═══════════════════════════════════════════════════════════════════════════

private val NavTabs = listOf(
    NavTab("Control", Icons.Filled.Wifi, Icons.Filled.Radio),
    NavTab("Info", Icons.Filled.Info, Icons.Filled.Info),
    NavTab("Log", Icons.Filled.History, Icons.Filled.History),
    NavTab("Update", Icons.Filled.SystemUpdate, Icons.Filled.SystemUpdate)
)

private data class NavTab(val key: String, val icon: ImageVector, val selectedIcon: ImageVector)

@Composable
private fun NavBar(currentPage: Int, onSelect: (Int) -> Unit) {
    Surface(color = Surface1, border = BorderStroke(1.dp, Hairline)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(BarHeight)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            NavTabs.forEachIndexed { index, tab ->
                NavCell(
                    tab = tab,
                    selected = currentPage == index,
                    onClick = { onSelect(index) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun NavRail(currentPage: Int, onSelect: (Int) -> Unit) {
    Surface(
        color = Surface1,
        border = BorderStroke(1.dp, Hairline),
        modifier = Modifier
            .width(RailWidth)
            .fillMaxHeight()
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .padding(vertical = 16.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            NavTabs.forEachIndexed { index, tab ->
                NavCell(
                    tab = tab,
                    selected = currentPage == index,
                    onClick = { onSelect(index) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                )
            }
        }
    }
}

@Composable
private fun NavCell(
    tab: NavTab,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tint = animateColorAsState(
        targetValue = if (selected) AccentLink else InkFaint,
        animationSpec = tween(200),
        label = "navTint"
    ).value

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .fillMaxHeight()
            .clip(CornerSmall)
            .background(if (selected) AccentLink.copy(alpha = 0.10f) else Color.Transparent)
            .semantics {
                role = Role.Tab
                this.selected = selected
                contentDescription = tab.key
            }
            .clickable(onClick = onClick)
    ) {
        Icon(
            if (selected) tab.selectedIcon else tab.icon,
            null,
            tint = tint,
            modifier = Modifier.size(21.dp)
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = tab.key.uppercase(),
            color = tint,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// Tone mapping
//
// All of this used to be substring matching over translated user-facing text
// ("does it contain 'failed' / 'başarısız' / 'not'?"). That silently
// mis-classified real failures, because those exact messages were the ones
// missing a keyword. Tone is now set where the outcome is known.
// ═══════════════════════════════════════════════════════════════════════════

private fun toneColor(tone: Tone): Color = when (tone) {
    Tone.NEUTRAL -> InkMuted
    Tone.INFO -> AccentLink
    Tone.SUCCESS -> AccentFcc
    Tone.WARNING -> AccentWarn
    Tone.DANGER -> AccentAlert
}

private fun regionTone(state: AppState): Tone = when {
    state.isFccEnabled -> Tone.SUCCESS
    state.isConnected -> Tone.INFO
    else -> Tone.NEUTRAL
}

private fun fourGTone(outcome: FourGOutcome): Color = when (outcome) {
    FourGOutcome.IDLE -> InkMuted
    FourGOutcome.RUNNING -> AccentLink
    // Written is not confirmed: the socket never acknowledges.
    FourGOutcome.WRITTEN -> AccentWarn
    FourGOutcome.NO_SERIAL, FourGOutcome.NO_DONGLE,
    FourGOutcome.WRITE_FAILED, FourGOutcome.ERROR -> AccentAlert
}

/**
 * Why a hardware action cannot run right now, or null if it can.
 *
 * Returns a *reason* rather than a bare boolean so the UI can explain a
 * disabled control. Keepalive holds the shared lock for a few hundred
 * milliseconds every two seconds, so this is a frequently-hit path.
 */
private fun hardwareBlockedReason(state: AppState): String? {
    if (state.isHardwareBusy) {
        return if (state.language == AppLanguage.TR) "Donanım meşgul" else "Hardware busy"
    }
    return null
}

private fun linkBlockedReason(state: AppState): String? {
    hardwareBlockedReason(state)?.let { return it }
    if (!state.isConnected) {
        return if (state.language == AppLanguage.TR) "Kumanda bağlanmadı" else "Controller not connected"
    }
    return null
}

private fun fourGBlockedReason(state: AppState): String? {
    linkBlockedReason(state)?.let { return it }
    if (state.aircraftSerial.isEmpty() && state.manualSerial.isEmpty()) {
        return if (state.language == AppLanguage.TR) {
            "Seri numarası yok — önce Bağlan'a bas ya da Bilgi sayfasından elle gir"
        } else {
            "No aircraft serial — tap Connect first, or type it on the Info tab"
        }
    }
    return null
}

private fun connectionLabel(state: AppState, ui: UiText): String = when {
    state.status == "connecting" -> ui.linking
    state.isConnected -> ui.online
    state.status == "error" -> ui.error
    else -> ui.offline
}

private fun connectionColor(state: AppState): Color = when {
    state.status == "connecting" -> AccentWarn
    state.isConnected -> AccentFcc
    state.status == "error" -> AccentAlert
    else -> InkFaint
}

private fun logToneColor(entry: String): Color = when {
    entry.contains("fail", true) || entry.contains("error", true) ||
        entry.contains("hata", true) || entry.contains("başarısız", true) ||
        entry.contains("algılanmadı", true) || entry.contains("iptal", true) -> AccentAlert

    entry.contains("enabled", true) || entry.contains("connected", true) ||
        entry.contains("restored", true) || entry.contains("written", true) ||
        entry.contains("açıldı", true) || entry.contains("bağlandı", true) ||
        entry.contains("yüklendi", true) || entry.contains("başlatıldı", true) -> AccentFcc

    entry.contains("busy", true) || entry.contains("Enabling", true) ||
        entry.contains("Loading", true) || entry.contains("meşgul", true) ||
        entry.contains("aranıyor", true) || entry.contains("uygulanıyor", true) ||
        entry.contains("Not:", true) -> AccentWarn

    else -> InkMuted
}
