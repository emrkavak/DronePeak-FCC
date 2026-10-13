package com.dronepeak.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Six visual cells are one editable field, preserving paste, deletion and accessibility. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun ActivationScreen(state: AppState, activate: (String) -> Unit, clearError: () -> Unit,
    setLanguage: (AppLanguage) -> Unit, exit: () -> Unit) {
    val ui = TextCatalog.ui(state.language)
    val background = Color(0xFF101315)
    val panel = Color(0xFF191E22)
    val line = Color(0xFF38434B)
    val ink = Color(0xFFF2F5F6)
    val muted = Color(0xFFADB9C1)
    val accent = Color(0xFF87CFEA)
    val danger = Color(0xFFFF9A9E)
    val radius = RoundedCornerShape(12.dp)
    var code by rememberSaveable { mutableStateOf("") }
    var focused by remember { mutableStateOf(false) }
    val bringIntoView = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(imeBottom, focused, scrollState.maxValue) {
        if (focused && imeBottom > 0) {
            delay(150)
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }
    BackHandler(onBack = exit)
    val controls: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = exit, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(ui.exit) }
            AppLanguage.entries.forEach { language ->
                TextButton(onClick = { setLanguage(language) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(language.shortName, color = if (state.language == language) ink else muted)
                }
            }
        }
    }
    val brand: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.dronepeak_icon), null, Modifier.size(48.dp).clip(CircleShape))
                Spacer(Modifier.width(12.dp))
                Text("DronePeak", color = ink, fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
            }
            Text(BuildConfig.VERSION_NAME, color = muted, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            Text(ui.activationHint, color = muted, fontSize = 14.sp, lineHeight = 21.sp)
        }
    }
    val card: @Composable () -> Unit = {
        Surface(color = panel, shape = radius, border = BorderStroke(1.dp, line)) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(ui.activationTitle, color = ink, fontWeight = FontWeight.SemiBold,
                        fontSize = 20.sp, modifier = Modifier.weight(1f))
                    ShortcutSettings(ui)
                }
                Text(if (state.activationFailed) ui.activationError else ui.activationTiming,
                    color = if (state.activationFailed) danger else muted, fontSize = 12.sp, lineHeight = 18.sp,
                    modifier = Modifier.heightIn(min = 36.dp))
                Column(Modifier.bringIntoViewRequester(bringIntoView), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(ui.activationCode.uppercase(), color = muted, fontSize = 10.sp, letterSpacing = 1.sp)
                    BasicTextField(value = code, onValueChange = { value ->
                        val next = value.filter { it in '0'..'9' }.take(6)
                        if (next != code) { code = next; clearError() }
                    }, singleLine = true,
                        textStyle = TextStyle(color = Color.Transparent), cursorBrush = SolidColor(Color.Transparent),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (code.length == 6) activate(code) }),
                        modifier = Modifier.fillMaxWidth().height(56.dp).semantics { contentDescription = ui.activationCode }
                            .onFocusChanged { focus ->
                                focused = focus.isFocused
                                if (focus.isFocused) scope.launch { delay(300); bringIntoView.bringIntoView() }
                            },
                        decorationBox = { inner ->
                            Box(Modifier.fillMaxSize()) {
                                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    repeat(6) { index ->
                                        val active = focused && index == code.length.coerceAtMost(5)
                                        Box(Modifier.weight(1f).fillMaxHeight().background(background, radius)
                                            .border(1.dp, if (state.activationFailed) danger else if (active) accent else line, radius),
                                            contentAlignment = Alignment.Center) {
                                            Text(code.getOrNull(index)?.toString().orEmpty(), color = ink,
                                                fontFamily = FontFamily.Monospace, fontSize = 24.sp)
                                        }
                                    }
                                }
                                Box(Modifier.matchParentSize().alpha(0f)) { inner() }
                            }
                        })
                    Text(if (code.length < 6) ui.activationIncomplete else "",
                        color = muted, fontSize = 12.sp, lineHeight = 18.sp,
                        modifier = Modifier.heightIn(min = 18.dp))
                    Button(onClick = { activate(code) }, enabled = code.length == 6,
                        modifier = Modifier.fillMaxWidth().height(56.dp), shape = radius) { Text(ui.activate) }
                }
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(background)
        .windowInsetsPadding(WindowInsets.safeDrawing).imePadding().padding(16.dp)) {
        val wide = maxWidth >= 600.dp && maxWidth > maxHeight && maxHeight >= 260.dp
        Column(Modifier.fillMaxSize().verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (wide) {
                Row(Modifier.widthIn(max = 1000.dp).fillMaxWidth().align(Alignment.CenterHorizontally),
                    horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(0.4f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        brand()
                        if (imeBottom == 0) controls()
                    }
                    Box(Modifier.weight(0.6f)) { card() }
                }
            } else {
                Column(Modifier.widthIn(max = 480.dp).fillMaxWidth().align(Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    if (imeBottom == 0) controls()
                    brand()
                    card()
                }
            }
        }
    }
}
