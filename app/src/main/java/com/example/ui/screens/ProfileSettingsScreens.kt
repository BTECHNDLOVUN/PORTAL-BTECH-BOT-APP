package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.core.graphics.toColorInt
import androidx.core.net.toUri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt
import com.example.model.BrokerCatalog
import com.example.model.NavStyle
import com.example.model.SimulatedSignalRequest
import com.example.model.TradeDirection
import com.example.service.OverlayStateBus
import com.example.ui.components.BtechCard
import com.example.ui.components.BtechPrimaryButton
import com.example.ui.components.BtechSecondaryButton
import com.example.ui.components.BtechStatusPill
import com.example.ui.components.PageHeader
import com.example.ui.components.TradeExecutionTrackerCard
import com.example.ui.theme.AccentSwatches
import com.example.ui.theme.BtechTerminalBlue
import com.example.ui.theme.BtechTerminalBlueBorder
import com.example.ui.theme.BtechTerminalBlueDeep
import com.example.ui.theme.JetBrainsMono
import com.example.ui.theme.LocalBtechColors
import com.example.viewmodel.AppUiState
import com.example.webterminal.WebTerminalEngine
import com.example.webterminal.WebTerminalStatus

@Composable
fun ProfileScreen(
    state: AppUiState,
    onEditName: (String) -> Unit,
    onToggleTheme: (Boolean) -> Unit,
    onSelectAccentHex: (String) -> Unit,
    onSaveCustomColors: (List<String>) -> Unit = {},
    onSelectNavStyle: (NavStyle) -> Unit,
) {
    val extra = LocalBtechColors.current
    var showEditName by remember { mutableStateOf(false) }
    var showCustomColor by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        PageHeader(
            title = "My profile",
            subtitle = "Client details and appearance preferences.",
        )

        BtechCard(padding = PaddingValues(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .background(extra.accentSoft),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = state.profileName.take(2).uppercase(),
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Black,
                        fontSize = 18.sp,
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(state.profileName, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text("Client profile", fontSize = 11.sp, color = extra.muted)
                }
                TextButton(onClick = { showEditName = true }) {
                    Text("EDIT", fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("App appearance", style = MaterialTheme.typography.titleMedium)
            BtechStatusPill("PERSONAL THEME", extra.accentSoft, MaterialTheme.colorScheme.primary)
        }
        Spacer(modifier = Modifier.height(10.dp))

        BtechCard {
            Text("Display mode", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Spacer(modifier = Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onToggleTheme(false) },
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .testTag("light_mode_button"),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (!state.isDarkTheme) MaterialTheme.colorScheme.primary else extra.surface2,
                        contentColor = if (!state.isDarkTheme) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    ),
                    border = BorderStroke(1.dp, if (!state.isDarkTheme) MaterialTheme.colorScheme.primary else extra.line),
                ) {
                    Icon(Icons.Filled.LightMode, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("LIGHT", fontWeight = FontWeight.ExtraBold, fontSize = 11.sp)
                }
                Button(
                    onClick = { onToggleTheme(true) },
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .testTag("dark_mode_button"),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (state.isDarkTheme) MaterialTheme.colorScheme.primary else extra.surface2,
                        contentColor = if (state.isDarkTheme) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    ),
                    border = BorderStroke(1.dp, if (state.isDarkTheme) MaterialTheme.colorScheme.primary else extra.line),
                ) {
                    Icon(Icons.Filled.DarkMode, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("DARK", fontWeight = FontWeight.ExtraBold, fontSize = 11.sp)
                }
            }

            Spacer(modifier = Modifier.height(18.dp))
            Text("Theme colour", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text(
                text = "Recolours main buttons, active navigation, tabs, and highlights across the app.",
                fontSize = 11.sp,
                color = extra.muted,
            )
            Spacer(modifier = Modifier.height(12.dp))

            AccentSwatches.chunked(4).forEach { rowColors ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    rowColors.forEach { color ->
                        val hex = String.format("#%06X", 0xFFFFFF and color.toArgb())
                        val active = state.accentHex.equals(hex, ignoreCase = true)
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(color)
                                .border(
                                    width = if (active) 3.dp else 1.dp,
                                    color = if (active) MaterialTheme.colorScheme.onSurface else extra.line,
                                    shape = CircleShape,
                                )
                                .clickable { onSelectAccentHex(hex) }
                                .testTag("swatch_$hex"),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { onSelectAccentHex("#9138EE") }) {
                    Text("Restore BTECH default")
                }
                Button(
                    onClick = { showCustomColor = true },
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = extra.surface2,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                ) {
                    Text("CUSTOM COLOUR", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Bottom navigation style", style = MaterialTheme.typography.titleMedium)
            BtechStatusPill("3 OPTIONS", extra.accentSoft, MaterialTheme.colorScheme.primary)
        }
        Spacer(modifier = Modifier.height(10.dp))

        NavStyle.entries.forEach { style ->
            val selected = state.navStyle == style
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 5.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onSelectNavStyle(style) }
                    .testTag("nav_style_${style.id}"),
                shape = RoundedCornerShape(10.dp),
                color = if (selected) extra.accentSoft else extra.surface2,
                border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else extra.line),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(style.title, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text(
                            text = if (selected) "✓ SELECTED" else "SELECT",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(style.description, fontSize = 11.sp, color = extra.muted)
                    Spacer(modifier = Modifier.height(8.dp))
                    NavStyleMiniPreview(style = style, hasRobot = state.hasRobot)
                }
            }
        }
    }

    if (showEditName) {
        var nameInput by remember { mutableStateOf(state.profileName) }
        AlertDialog(
            onDismissRequest = { showEditName = false },
            title = { Text("Client display name") },
            text = {
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    singleLine = true,
                    label = { Text("Display name") },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onEditName(nameInput)
                        showEditName = false
                    },
                ) { Text("SAVE") }
            },
            dismissButton = {
                TextButton(onClick = { showEditName = false }) { Text("CANCEL") }
            },
        )
    }

    if (showCustomColor) {
        CustomColorPickerDialog(
            initialHex = state.accentHex,
            savedCustomColors = state.customColors,
            onSaveCustomColors = onSaveCustomColors,
            onDismiss = { showCustomColor = false },
            onApply = { hex ->
                onSelectAccentHex(hex)
                showCustomColor = false
            },
        )
    }
}

@Composable
private fun NavStyleMiniPreview(style: NavStyle, hasRobot: Boolean) {
    val extra = LocalBtechColors.current
    val previewItems = if (hasRobot) {
        listOf(
            "Home" to Icons.Filled.Home,
            "Meta" to Icons.Filled.BarChart,
            "Profile" to Icons.Filled.Person,
            "Settings" to Icons.Filled.Settings,
        )
    } else {
        listOf(
            "Home" to Icons.Filled.Home,
            "Meta" to Icons.Filled.BarChart,
            "Add" to Icons.Filled.Add,
            "Profile" to Icons.Filled.Person,
            "Settings" to Icons.Filled.Settings,
        )
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(66.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, extra.line),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Top 25% strip
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(16.dp)
                    .align(Alignment.TopStart)
                    .background(extra.navStrip),
            ) {
                if (style == NavStyle.INDICATOR) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(1f / previewItems.size)
                            .height(2.5.dp)
                            .align(Alignment.BottomStart)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            }
            // Bottom 75% preview items
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 16.dp, start = 4.dp, end = 4.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                previewItems.forEachIndexed { idx, (label, icon) ->
                    val active = idx == 0
                    val isRaised = active && style != NavStyle.INDICATOR
                    val tint = if (active) MaterialTheme.colorScheme.primary else extra.muted
                    val fill = when {
                        !isRaised -> Color.Transparent
                        style == NavStyle.RAISED_TOP -> MaterialTheme.colorScheme.surface
                        else -> extra.surface2
                    }
                    val ring = when {
                        !isRaised -> Color.Transparent
                        style == NavStyle.RAISED_TOP -> extra.navStrip
                        else -> MaterialTheme.colorScheme.surface
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize(),
                    ) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 2.dp)
                                .offset(y = if (isRaised) (-8).dp else 0.dp)
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(fill)
                                .border(if (isRaised) 3.dp else 0.dp, ring, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = tint,
                                modifier = Modifier.size(15.dp),
                            )
                        }
                        Text(
                            text = label,
                            color = tint,
                            fontSize = 8.sp,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 1.dp),
                        )
                    }
                }
            }
        }
    }
}

private val BasicPaletteHexes = listOf(
    "#FF8080", "#FFFF80", "#80FF80", "#00FF80", "#80FFFF", "#0080FF", "#FF80C0", "#FF80FF",
    "#FF0000", "#FFFF00", "#80FF00", "#00FF40", "#00FFFF", "#0080C0", "#8080C0", "#FF00FF",
    "#804040", "#FF8040", "#00FF00", "#008080", "#004080", "#8080FF", "#800040", "#FF0080",
    "#800000", "#FF8000", "#008000", "#008040", "#0000FF", "#0000A0", "#800080", "#8000FF",
    "#400000", "#804000", "#004000", "#004040", "#000080", "#000040", "#400040", "#400080",
    "#000000", "#808000", "#808040", "#808080", "#408080", "#C0C0C0", "#400040", "#FFFFFF",
)

@Composable
private fun CustomColorPickerDialog(
    initialHex: String,
    savedCustomColors: List<String>,
    onSaveCustomColors: (List<String>) -> Unit,
    onDismiss: () -> Unit,
    onApply: (String) -> Unit,
) {
    val extra = LocalBtechColors.current
    val initialArgb = runCatching { initialHex.toColorInt() }.getOrDefault(0xFF3BBBC2.toInt())
    val initialHsl = remember(initialArgb) {
        FloatArray(3).also { ColorUtils.colorToHSL(initialArgb, it) }
    }

    var hueDeg by remember { mutableFloatStateOf(initialHsl[0]) } // 0..360
    var sat by remember { mutableFloatStateOf(initialHsl[1]) }    // 0..1
    var lum by remember { mutableFloatStateOf(initialHsl[2]) }    // 0..1

    var customSlots by remember(savedCustomColors) {
        mutableStateOf(List(16) { idx -> savedCustomColors.getOrNull(idx).orEmpty() })
    }
    var selectedCustomIndex by remember { mutableIntStateOf(0) }
    var selectedBasicHex by remember { mutableStateOf<String?>(null) }

    val currentArgb = ColorUtils.HSLToColor(floatArrayOf(hueDeg.coerceIn(0f, 359.9f), sat.coerceIn(0f, 1f), lum.coerceIn(0f, 1f)))
    val previewColor = Color(currentArgb)
    val red = android.graphics.Color.red(currentArgb)
    val green = android.graphics.Color.green(currentArgb)
    val blue = android.graphics.Color.blue(currentArgb)
    val hex = String.format("#%02X%02X%02X", red, green, blue)

    val hue240 = ((hueDeg / 360f) * 240f).roundToInt().coerceIn(0, 239)
    val sat240 = (sat * 240f).roundToInt().coerceIn(0, 240)
    val lum240 = (lum * 240f).roundToInt().coerceIn(0, 240)

    fun setFromRgb(newR: Int, newG: Int, newB: Int) {
        val out = FloatArray(3)
        ColorUtils.RGBToHSL(newR.coerceIn(0, 255), newG.coerceIn(0, 255), newB.coerceIn(0, 255), out)
        hueDeg = out[0]
        sat = out[1]
        lum = out[2]
        selectedBasicHex = null
    }

    fun setFromHex(hexStr: String) {
        val argb = runCatching { hexStr.toColorInt() }.getOrNull() ?: return
        val out = FloatArray(3)
        ColorUtils.colorToHSL(argb, out)
        hueDeg = out[0]
        sat = out[1]
        lum = out[2]
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .widthIn(max = 560.dp)
                .testTag("custom_color_dialog"),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, extra.line),
            shadowElevation = 16.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(14.dp),
            ) {
                // Top Header matching classic Color dialog
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "Color",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("color_picker_close"),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Close color picker",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val isWide = maxWidth >= 460.dp

                    val palettePane: @Composable (Modifier) -> Unit = { mod ->
                        Column(modifier = mod) {
                            Text(
                                text = "Basic colors:",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            BasicPaletteHexes.chunked(8).forEach { rowHexes ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    rowHexes.forEach { swatchHex ->
                                        val isSelected = selectedBasicHex.equals(swatchHex, ignoreCase = true)
                                        val swatchColor = Color(swatchHex.toColorInt())
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(22.dp)
                                                .background(swatchColor)
                                                .border(
                                                    width = if (isSelected) 2.dp else 1.dp,
                                                    color = if (isSelected) MaterialTheme.colorScheme.onSurface else Color(0xFF7A8594),
                                                )
                                                .clickable {
                                                    selectedBasicHex = swatchHex
                                                    setFromHex(swatchHex)
                                                },
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "Custom colors:",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            (0 until 16).chunked(8).forEach { rowIndices ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    rowIndices.forEach { slotIndex ->
                                        val slotHex = customSlots.getOrElse(slotIndex) { "" }
                                        val slotColor = runCatching {
                                            if (slotHex.isNotBlank()) Color(slotHex.toColorInt()) else Color.White
                                        }.getOrDefault(Color.White)
                                        val isSlotSelected = selectedCustomIndex == slotIndex
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(22.dp)
                                                .background(if (slotHex.isNotBlank()) slotColor else extra.surface2)
                                                .border(
                                                    width = if (isSlotSelected) 2.dp else 1.dp,
                                                    color = if (isSlotSelected) MaterialTheme.colorScheme.primary else Color(0xFF7A8594),
                                                )
                                                .clickable {
                                                    selectedCustomIndex = slotIndex
                                                    if (slotHex.isNotBlank()) {
                                                        selectedBasicHex = null
                                                        setFromHex(slotHex)
                                                    }
                                                },
                                        )
                                    }
                                }
                            }
                        }
                    }

                    val spectrumPane: @Composable (Modifier) -> Unit = { mod ->
                        Column(modifier = mod) {
                            // 2D Rainbow Spectrum + Vertical Luminance Bar
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(168.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                // Rainbow Hue x Saturation Spectrum
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .border(1.dp, Color(0xFF7A8594))
                                        .testTag("color_spectrum_canvas"),
                                ) {
                                    Canvas(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .pointerInput(Unit) {
                                                detectTapGestures { pos ->
                                                    hueDeg = ((pos.x / size.width).coerceIn(0f, 0.999f)) * 360f
                                                    sat = 1f - (pos.y / size.height).coerceIn(0f, 1f)
                                                    selectedBasicHex = null
                                                }
                                            }
                                            .pointerInput(Unit) {
                                                detectDragGestures { change, _ ->
                                                    change.consume()
                                                    hueDeg = ((change.position.x / size.width).coerceIn(0f, 0.999f)) * 360f
                                                    sat = 1f - (change.position.y / size.height).coerceIn(0f, 1f)
                                                    selectedBasicHex = null
                                                }
                                            },
                                    ) {
                                        drawRect(
                                            brush = Brush.horizontalGradient(
                                                colors = listOf(
                                                    Color(0xFFFF0000),
                                                    Color(0xFFFFFF00),
                                                    Color(0xFF00FF00),
                                                    Color(0xFF00FFFF),
                                                    Color(0xFF0000FF),
                                                    Color(0xFFFF00FF),
                                                    Color(0xFFFF0000),
                                                ),
                                            ),
                                        )
                                        drawRect(
                                            brush = Brush.verticalGradient(
                                                colors = listOf(
                                                    Color.Transparent,
                                                    Color(0xFF808080),
                                                ),
                                            ),
                                        )

                                        val cx = (hueDeg / 360f).coerceIn(0f, 1f) * size.width
                                        val cy = (1f - sat.coerceIn(0f, 1f)) * size.height
                                        val gap = 3.dp.toPx()
                                        val arm = 8.dp.toPx()
                                        val stroke = 2.dp.toPx()
                                        // Crosshair matching screenshot
                                        drawLine(Color.Black, Offset(cx - arm, cy), Offset(cx - gap, cy), strokeWidth = stroke)
                                        drawLine(Color.Black, Offset(cx + gap, cy), Offset(cx + arm, cy), strokeWidth = stroke)
                                        drawLine(Color.Black, Offset(cx, cy - arm), Offset(cx, cy - gap), strokeWidth = stroke)
                                        drawLine(Color.Black, Offset(cx, cy + gap), Offset(cx, cy + arm), strokeWidth = stroke)
                                    }
                                }

                                // Vertical Luminance Bar + Pointer Triangle
                                val midColor = Color(ColorUtils.HSLToColor(floatArrayOf(hueDeg.coerceIn(0f, 359.9f), sat.coerceIn(0f, 1f), 0.5f)))
                                Canvas(
                                    modifier = Modifier
                                        .width(34.dp)
                                        .fillMaxHeight()
                                        .testTag("color_lum_bar")
                                        .pointerInput(Unit) {
                                            detectTapGestures { pos ->
                                                lum = 1f - (pos.y / size.height).coerceIn(0f, 1f)
                                                selectedBasicHex = null
                                            }
                                        }
                                        .pointerInput(Unit) {
                                            detectDragGestures { change, _ ->
                                                change.consume()
                                                lum = 1f - (change.position.y / size.height).coerceIn(0f, 1f)
                                                selectedBasicHex = null
                                            }
                                        },
                                ) {
                                    val barW = 20.dp.toPx()
                                    drawRect(
                                        brush = Brush.verticalGradient(
                                            0.0f to Color.White,
                                            0.5f to midColor,
                                            1.0f to Color.Black,
                                        ),
                                        size = androidx.compose.ui.geometry.Size(barW, size.height),
                                    )
                                    drawRect(
                                        color = Color(0xFF7A8594),
                                        size = androidx.compose.ui.geometry.Size(barW, size.height),
                                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()),
                                    )

                                    val py = (1f - lum.coerceIn(0f, 1f)) * size.height
                                    val tipX = barW + 2.dp.toPx()
                                    val tri = Path().apply {
                                        moveTo(tipX, py)
                                        lineTo(size.width, py - 5.dp.toPx())
                                        lineTo(size.width, py + 5.dp.toPx())
                                        close()
                                    }
                                    drawPath(tri, color = Color.Black)
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            // Color|Solid Swatch + Hue/Sat/Lum + Red/Green/Blue
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.width(74.dp),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(48.dp)
                                            .background(previewColor)
                                            .border(1.dp, Color(0xFF7A8594)),
                                    )
                                    Spacer(modifier = Modifier.height(3.dp))
                                    Text(
                                        text = "Color|Solid",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Medium,
                                    )
                                    Text(
                                        text = hex,
                                        fontSize = 9.sp,
                                        fontFamily = JetBrainsMono,
                                        color = extra.muted,
                                    )
                                }

                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    ColorNumericField(
                                        label = "Hue:",
                                        value = hue240,
                                        maxValue = 239,
                                    ) { v ->
                                        hueDeg = (v / 240f) * 360f
                                        selectedBasicHex = null
                                    }
                                    ColorNumericField(
                                        label = "Sat:",
                                        value = sat240,
                                        maxValue = 240,
                                    ) { v ->
                                        sat = v / 240f
                                        selectedBasicHex = null
                                    }
                                    ColorNumericField(
                                        label = "Lum:",
                                        value = lum240,
                                        maxValue = 240,
                                    ) { v ->
                                        lum = v / 240f
                                        selectedBasicHex = null
                                    }
                                }

                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    ColorNumericField(
                                        label = "Red:",
                                        value = red,
                                        maxValue = 255,
                                    ) { v -> setFromRgb(v, green, blue) }
                                    ColorNumericField(
                                        label = "Green:",
                                        value = green,
                                        maxValue = 255,
                                    ) { v -> setFromRgb(red, v, blue) }
                                    ColorNumericField(
                                        label = "Blue:",
                                        value = blue,
                                        maxValue = 255,
                                    ) { v -> setFromRgb(red, green, v) }
                                }
                            }
                        }
                    }

                    if (isWide) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            palettePane(Modifier.weight(1f))
                            spectrumPane(Modifier.weight(1.1f))
                        }
                    } else {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            spectrumPane(Modifier.fillMaxWidth())
                            Spacer(modifier = Modifier.height(12.dp))
                            palettePane(Modifier.fillMaxWidth())
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Add to Custom Colors button matching screenshot
                Button(
                    onClick = {
                        val updated = customSlots.toMutableList()
                        updated[selectedCustomIndex] = hex
                        customSlots = updated
                        onSaveCustomColors(updated)
                        selectedCustomIndex = (selectedCustomIndex + 1) % 16
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(38.dp)
                        .testTag("add_to_custom_colors_button"),
                    shape = RoundedCornerShape(6.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = extra.surface2,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                    border = BorderStroke(1.dp, extra.line),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                ) {
                    Text("Add to Custom Colors", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }

                Spacer(modifier = Modifier.height(10.dp))

                // OK / Cancel Footer
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    BtechSecondaryButton(
                        text = "Cancel",
                        onClick = onDismiss,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("cancel_custom_color_button"),
                    )
                    BtechPrimaryButton(
                        text = "OK",
                        onClick = { onApply(hex) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("apply_custom_color_button"),
                    )
                }
            }
        }
    }
}

@Composable
private fun ColorNumericField(
    label: String,
    value: Int,
    maxValue: Int,
    onValueChanged: (Int) -> Unit,
) {
    val extra = LocalBtechColors.current
    var text by remember(value) { mutableStateOf(value.toString()) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            fontSize = 10.sp,
            color = extra.muted,
            modifier = Modifier.width(38.dp),
            textAlign = TextAlign.End,
        )
        Spacer(modifier = Modifier.width(4.dp))
        BasicTextField(
            value = text,
            onValueChange = { raw ->
                val digits = raw.filter { it.isDigit() }.take(3)
                text = digits
                val parsed = digits.toIntOrNull()
                if (parsed != null) {
                    onValueChanged(parsed.coerceIn(0, maxValue))
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            textStyle = TextStyle(
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 11.sp,
                fontFamily = JetBrainsMono,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier
                .weight(1f)
                .height(26.dp)
                .background(extra.surface2, RoundedCornerShape(4.dp))
                .border(1.dp, extra.line, RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 4.dp),
        )
    }
}

@Composable
fun SettingsScreen(
    onOpenLogs: () -> Unit,
    onOpenWebTerminal: () -> Unit,
    onShowMessage: (String) -> Unit,
) {
    val extra = LocalBtechColors.current
    val items = listOf(
        Triple(Icons.Filled.Terminal, "Execution Log & Simulator", "View real-time signal & WebView automation steps") to onOpenLogs,
        Triple(Icons.Filled.Info, "Inspect Live Web Terminal", "View the embedded MT4/MT5 WebView directly") to onOpenWebTerminal,
        Triple(Icons.AutoMirrored.Filled.HelpOutline, "Help & Support", "Overlay & Web Terminal setup tips") to {
            onShowMessage("Grant 'Display over other apps' permission when prompted so the floating robot stays on top of MT5.")
        },
        Triple(Icons.Filled.Description, "Terms & Conditions", "Review BTECH Portal client usage terms") to {
            onShowMessage("BTECH FINANCE standalone test build — Portal terms apply in production.")
        },
        Triple(Icons.Filled.PrivacyTip, "Privacy Policy", "Credentials stay on-device in this test build") to {
            onShowMessage("Your broker credentials are stored only on this device for WebView testing.")
        },
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        PageHeader(
            title = "Settings",
            subtitle = "Diagnostics, live WebView inspection, and help for your BTECH app.",
        )

        BtechCard(padding = PaddingValues(6.dp)) {
            items.forEachIndexed { idx, (meta, action) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = action)
                        .padding(horizontal = 10.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(extra.surface3),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = meta.first,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(meta.second, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text(meta.third, fontSize = 11.sp, color = extra.muted)
                    }
                    Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = extra.muted)
                }
                if (idx < items.lastIndex) {
                    HorizontalDivider(color = extra.line)
                }
            }
        }
    }
}

@Composable
fun ExecutionLogsScreen(
    state: AppUiState,
    onSimulateClick: () -> Unit,
    onInspectWebTerminal: () -> Unit,
    onClearLogs: () -> Unit,
    onShowMessage: (String) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val extra = LocalBtechColors.current
    val context = LocalContext.current
    val overlaySnap by OverlayStateBus.snapshot.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        TextButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Back")
        }

        PageHeader(
            title = "Execution log",
            subtitle = "Live WebView DOM pipeline from signal reception to order submission.",
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BtechPrimaryButton(
                text = "SIMULATE TRADE",
                onClick = onSimulateClick,
                modifier = Modifier.weight(1f),
            )
            BtechSecondaryButton(
                text = "WEB TERMINAL",
                onClick = onInspectWebTerminal,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        BtechSecondaryButton(
            text = "COPY DIAGNOSTICS (${state.logs.takeLast(100).size} LINES)",
            onClick = {
                val recent = state.logs.takeLast(100)
                val payload = if (recent.isEmpty()) {
                    "No diagnostic logs recorded yet."
                } else {
                    recent.joinToString("\n") { "[${it.timestamp}] ${it.message}" }
                }
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                clipboard?.setPrimaryClip(ClipData.newPlainText("BTECH Diagnostics", payload))
                onShowMessage("Copied ${recent.size} diagnostic log lines")
            },
            modifier = Modifier.testTag("copy_diagnostics_button"),
        )

        Spacer(modifier = Modifier.height(14.dp))
        TradeExecutionTrackerCard(
            snapshot = overlaySnap,
            darkSurface = false,
        )

        Spacer(modifier = Modifier.height(14.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Brush.linearGradient(listOf(BtechTerminalBlue, BtechTerminalBlueDeep)))
                .border(1.dp, BtechTerminalBlueBorder, RoundedCornerShape(12.dp))
                .padding(14.dp),
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "WEBVIEW AUTOMATION CONSOLE",
                        color = Color(0xFFBBD7FF),
                        fontFamily = JetBrainsMono,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    TextButton(onClick = onClearLogs) {
                        Text("CLEAR", color = Color.White, fontSize = 10.sp)
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                if (state.logs.isEmpty()) {
                    Text(
                        text = ">> No events yet.\n>> 1. Authenticate broker login under Meta.\n>> 2. Add a symbol under Symbols.\n>> 3. Tap SIMULATE TRADE.",
                        color = Color(0xFFE9F3FF),
                        fontFamily = JetBrainsMono,
                        fontSize = 11.sp,
                        lineHeight = 17.sp,
                    )
                } else {
                    state.logs.forEach { entry ->
                        val color = when {
                            entry.isError -> Color(0xFFFFB0B8)
                            entry.isSuccess -> Color(0xFF80FFB0)
                            else -> Color(0xFFE9F3FF)
                        }
                        Text(
                            text = "[${entry.timestamp}] ${entry.message}",
                            color = color,
                            fontFamily = JetBrainsMono,
                            fontSize = 11.sp,
                            lineHeight = 17.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SimulateTradeDialog(
    state: AppUiState,
    onDismiss: () -> Unit,
    onConfirmSimulate: (SimulatedSignalRequest) -> Unit,
) {
    val extra = LocalBtechColors.current
    val first = state.allowedSymbols.firstOrNull { it.live } ?: state.allowedSymbols.firstOrNull()
    var symbol by remember { mutableStateOf(first?.symbol ?: "XAUUSD") }
    var direction by remember {
        mutableStateOf(
            if (first?.direction == TradeDirection.SELL) TradeDirection.SELL else TradeDirection.BUY
        )
    }
    var lot by remember { mutableStateOf(first?.lot ?: "0.01") }
    var stopLoss by remember { mutableStateOf("") }
    var takeProfit by remember { mutableStateOf("") }
    var comment by remember { mutableStateOf("BTECH-EA") }
    var tradeCount by remember { mutableStateOf(first?.trades ?: "1") }

    val activePlatform = state.savedAccount?.platform ?: first?.platform ?: com.example.model.PlatformType.MT5
    val cleanSym = symbol.trim().ifEmpty { "XAUUSD" }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = MaterialTheme.colorScheme.primary,
        unfocusedBorderColor = extra.line,
        focusedLabelColor = MaterialTheme.colorScheme.primary,
        unfocusedLabelColor = extra.muted,
        cursorColor = MaterialTheme.colorScheme.primary,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurface,
        icon = {
            Icon(
                imageVector = Icons.Filled.Bolt,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = {
            Text(
                text = "Simulate Signal Trade (${activePlatform.label})",
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = "Simulates receiving a BTECH Portal trade signal and automates ${activePlatform.label} Symbol Discovery → Verify Order Form → Set Volume/SL/TP/Comment → Submit on your connected Web Terminal.",
                    fontSize = 11.sp,
                    color = extra.muted,
                )
                Spacer(modifier = Modifier.height(12.dp))

                if (state.allowedSymbols.size > 1) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        state.allowedSymbols.take(4).forEach { cfg ->
                            val selected = symbol == cfg.symbol
                            Button(
                                onClick = {
                                    symbol = cfg.symbol
                                    lot = cfg.lot
                                    tradeCount = cfg.trades
                                },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 4.dp),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (selected) MaterialTheme.colorScheme.primary else extra.surface2,
                                    contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                ),
                            ) {
                                Text(cfg.symbol, fontSize = 10.sp, maxLines = 1)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                OutlinedTextField(
                    value = symbol,
                    onValueChange = { symbol = it },
                    label = { Text("SYMBOL (EXACT CASE)") },
                    placeholder = { Text("e.g. XAUUSD.s or XAUUSD.m") },
                    singleLine = true,
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    val baseSym = symbol.replace(Regex("(\\.[a-zA-Z]+|[mc])$"), "")
                    listOf("" to "Std", ".m" to ".m", ".s" to ".s", ".c" to ".c", ".ecn" to ".ecn").forEach { (sfx, label) ->
                        val target = baseSym + sfx
                        val active = symbol == target
                        Button(
                            onClick = { symbol = target },
                            modifier = Modifier.weight(1f).height(34.dp),
                            contentPadding = PaddingValues(0.dp),
                            shape = RoundedCornerShape(6.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (active) MaterialTheme.colorScheme.primary else extra.surface2,
                                contentColor = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                            ),
                        ) {
                            Text(label, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(TradeDirection.BUY, TradeDirection.SELL).forEach { dir ->
                        val active = direction == dir
                        Button(
                            onClick = { direction = dir },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (active) {
                                    if (dir == TradeDirection.BUY) extra.green else extra.red
                                } else extra.surface2,
                                contentColor = if (active) Color.White else MaterialTheme.colorScheme.onSurface,
                            ),
                        ) {
                            Text(dir.label, fontWeight = FontWeight.ExtraBold)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = lot,
                        onValueChange = { lot = it },
                        label = { Text("LOT") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        colors = fieldColors,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = tradeCount,
                        onValueChange = { tradeCount = it },
                        label = { Text("TRADES") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        colors = fieldColors,
                        modifier = Modifier.weight(1f),
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = stopLoss,
                        onValueChange = { stopLoss = it },
                        label = { Text("STOP LOSS") },
                        placeholder = { Text("Optional") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        colors = fieldColors,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = takeProfit,
                        onValueChange = { takeProfit = it },
                        label = { Text("TAKE PROFIT") },
                        placeholder = { Text("Optional") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        colors = fieldColors,
                        modifier = Modifier.weight(1f),
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = comment,
                    onValueChange = { comment = it },
                    label = { Text("ORDER COMMENT") },
                    singleLine = true,
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            BtechPrimaryButton(
                text = "SEND TO WEB TERMINAL",
                onClick = {
                    onConfirmSimulate(
                        SimulatedSignalRequest(
                            symbol = cleanSym,
                            direction = direction,
                            lot = lot.trim().ifEmpty { "0.01" },
                            stopLoss = stopLoss.trim(),
                            takeProfit = takeProfit.trim(),
                            comment = comment.trim().ifEmpty { "BTECH-EA" },
                            tradeCount = tradeCount.toIntOrNull()?.coerceIn(1, 10) ?: 1,
                            platform = activePlatform,
                        )
                    )
                },
                modifier = Modifier.testTag("confirm_simulate_trade_button"),
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", color = MaterialTheme.colorScheme.primary)
            }
        },
    )
}

@Composable
fun WebTerminalInspectorDialog(
    webEngine: WebTerminalEngine,
    webStatus: WebTerminalStatus,
    onConfirmSession: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val extra = LocalBtechColors.current
    var hostContainer by remember { mutableStateOf<FrameLayout?>(null) }
    var urlBarText by remember { mutableStateOf(webStatus.currentUrl) }

    LaunchedEffect(webStatus.currentUrl) {
        if (webStatus.currentUrl.isNotBlank()) {
            urlBarText = webStatus.currentUrl
        }
    }

    DisposableEffect(hostContainer) {
        val container = hostContainer
        onDispose {
            if (container != null) {
                webEngine.detachIfParent(container)
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(1.dp, extra.line),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(extra.surface2)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    IconButton(onClick = { webEngine.goBack() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    IconButton(onClick = { webEngine.reload() }) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = "Reload page",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    IconButton(onClick = { webEngine.toggleDesktopMode() }) {
                        Icon(
                            imageVector = Icons.Filled.DesktopWindows,
                            contentDescription = "Toggle Desktop Mode",
                            tint = if (webStatus.isDesktopMode) MaterialTheme.colorScheme.primary else extra.muted,
                        )
                    }
                    IconButton(
                        onClick = {
                            val target = urlBarText.ifBlank { webStatus.currentUrl }
                            if (target.isNotBlank()) {
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, target.toUri()))
                                }
                            }
                        },
                    ) {
                        Icon(
                            Icons.Filled.OpenInBrowser,
                            contentDescription = "Open in Chrome",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    IconButton(
                        onClick = {
                            val target = urlBarText.ifBlank { webStatus.currentUrl }
                            webEngine.resetSession {
                                if (target.isNotBlank()) {
                                    webEngine.navigateToUrl(target)
                                }
                            }
                        },
                        modifier = Modifier.testTag("clear_web_session_button"),
                    ) {
                        Icon(
                            Icons.Filled.DeleteSweep,
                            contentDescription = "Clear Web Session",
                            tint = extra.red,
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = if (webStatus.isDesktopMode) "DESKTOP UA" else "CHROME UA",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Close Web Terminal",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                HorizontalDivider(color = extra.line)
                Spacer(modifier = Modifier.height(8.dp))

                // Editable URL bar + Go button
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    OutlinedTextField(
                        value = urlBarText,
                        onValueChange = { urlBarText = it },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        textStyle = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface),
                        placeholder = { Text("Enter terminal URL...", fontSize = 11.sp, color = extra.muted) },
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = extra.line,
                            cursorColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                    Button(
                        onClick = { webEngine.navigateToUrl(urlBarText) },
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    ) {
                        Text("GO", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                // Quick terminal shortcuts
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf(
                        "JM MT5" to BrokerCatalog.JUSTMARKETS_MT5_URL,
                        "MT4 Web" to BrokerCatalog.METAQUOTES_MT4_URL,
                        "MQ MT5" to BrokerCatalog.METAQUOTES_MT5_URL,
                    ).forEach { (label, endpoint) ->
                        val selected = webStatus.currentUrl.startsWith(endpoint)
                        Button(
                            onClick = {
                                urlBarText = endpoint
                                webEngine.navigateToUrl(endpoint)
                            },
                            modifier = Modifier.height(30.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(
                                width = 1.dp,
                                color = if (selected) MaterialTheme.colorScheme.primary else extra.line,
                            ),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (selected) extra.accentSoft else extra.surface2,
                                contentColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            ),
                        ) {
                            Text(label, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                webStatus.lastError?.let { err ->
                    Text(
                        text = "WebView Notice: $err",
                        fontSize = 10.sp,
                        color = extra.red,
                        maxLines = 2,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(extra.redContainer)
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }

                if (webStatus.isLoading) {
                    LinearProgressIndicator(
                        progress = { (webStatus.progress / 100f).coerceIn(0.05f, 1f) },
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = extra.surface2,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                AndroidView(
                    factory = { ctx ->
                        FrameLayout(ctx).also { container ->
                            hostContainer = container
                            webEngine.attachToContainer(container, ctx)
                        }
                    },
                    update = { container ->
                        webEngine.attachToContainer(container, container.context)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )

                HorizontalDivider(color = extra.line)
                Surface(
                    color = extra.surface2,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BtechPrimaryButton(
                            text = "I'M LOGGED IN — SAVE SESSION",
                            onClick = onConfirmSession,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("confirm_manual_session_button"),
                        )
                    }
                }
            }
        }
    }
}
