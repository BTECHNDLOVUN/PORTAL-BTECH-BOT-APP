package com.example.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.graphics.toColorInt
import com.example.R
import com.example.model.BubbleGlowStyle
import com.example.service.OverlaySnapshot
import com.example.ui.theme.BtechGreenLight
import com.example.ui.theme.BtechLightBg
import com.example.ui.theme.BtechLightLine
import com.example.ui.theme.BtechLightMuted
import com.example.ui.theme.BtechLightSurface
import com.example.ui.theme.BtechLightSurface2
import com.example.ui.theme.BtechLightText
import com.example.ui.theme.LocalBtechColors
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * In-app draggable floating bubble and expandable monitor card faithfully matching
 * the HTML prototype (#floatBubble + #floatPanel), adapting the dark outer/card
 * backgrounds to light surfaces when Light Mode is active.
 */
@Composable
fun BtechFloatingOverlay(
    snapshot: OverlaySnapshot,
    onToggleSkipTrades: () -> Unit,
    onRefreshBalance: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    var logsVisible by remember { mutableStateOf(true) }

    val extra = LocalBtechColors.current
    val isDark = extra.isDark || snapshot.isDarkTheme

    val bubbleBg = if (isDark) Color(0xFF102743) else BtechLightSurface
    val bubbleBorder = if (isDark) Color.White else BtechLightLine

    val panelBg = if (isDark) Color(0xFF070B14) else BtechLightSurface
    val panelBorder = if (isDark) Color(0xFF526071) else BtechLightLine

    val controlsBg = if (isDark) Color(0xFF0B111C) else BtechLightSurface2
    val logsBtnBg = if (isDark) Color(0xFF202C3C) else BtechLightSurface
    val logsBtnBorder = if (isDark) Color(0xFF45546A) else BtechLightLine
    val logsBtnText = if (isDark) Color(0xFFE9F2FF) else BtechLightText

    val dividerColor = if (isDark) Color(0xFF263447) else BtechLightLine
    val bodyBg = if (isDark) Color(0xFF080E19) else BtechLightBg

    val statusCardBg = if (isDark) Color(0xFF101B2B) else BtechLightSurface
    val statusCardBorder = if (isDark) Color(0xFF25384E) else BtechLightLine
    val statusTitleColor = if (isDark) Color(0xFFF1F5FB) else BtechLightText
    val balanceColor = if (isDark) Color(0xFF41D39A) else BtechGreenLight
    val mutedTextColor = if (isDark) Color(0xFF9FB1C8) else BtechLightMuted
    val refreshTint = if (isDark) Color(0xFF68EDA6) else BtechGreenLight
    val footerCopyColor = if (isDark) Color(0xFF8397AF) else BtechLightMuted

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .zIndex(50f),
    ) {
        val density = LocalDensity.current
        val maxW = with(density) { maxWidth.toPx() }
        val maxH = with(density) { maxHeight.toPx() }

        val bubbleOuterSizePx = with(density) { 78.dp.toPx() }
        var bubbleX by remember(maxW) { mutableFloatStateOf((maxW - bubbleOuterSizePx - with(density) { 8.dp.toPx() }).coerceAtLeast(8f)) }
        var bubbleY by remember(maxH) { mutableFloatStateOf((maxH * 0.62f).coerceIn(80f, (maxH - bubbleOuterSizePx - 120f).coerceAtLeast(80f))) }

        val panelWidthDp = 340.dp.coerceAtMost(maxWidth - 24.dp)
        val panelWidthPx = with(density) { panelWidthDp.toPx() }
        var panelX by remember(maxW) { mutableFloatStateOf(((maxW - panelWidthPx) / 2f).coerceAtLeast(12f)) }
        var panelY by remember(maxH) { mutableFloatStateOf((maxH * 0.14f).coerceAtLeast(48f)) }

        if (!expanded) {
            Box(
                modifier = Modifier
                    .offset { IntOffset(bubbleX.roundToInt(), bubbleY.roundToInt()) }
                    .size(78.dp)
                    .pointerInput(maxW, maxH) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            bubbleX = (bubbleX + dragAmount.x).coerceIn(4f, (maxW - bubbleOuterSizePx - 4f).coerceAtLeast(4f))
                            bubbleY = (bubbleY + dragAmount.y).coerceIn(32f, (maxH - bubbleOuterSizePx - 88f).coerceAtLeast(32f))
                        }
                    }
                    .clickable { expanded = true }
                    .testTag("in_app_floating_bubble"),
                contentAlignment = Alignment.Center,
            ) {
                GlowingBubbleAvatar(
                    glowEnabled = snapshot.isRunning,
                    glowStyle = snapshot.bubbleGlowStyle,
                    primeColor = Color(runCatching { snapshot.accentHex.toColorInt() }.getOrDefault(0xFF9138EE.toInt())),
                    bubbleBg = bubbleBg,
                    idleBorderColor = bubbleBorder,
                    coreSize = 56.dp,
                    outerSize = 78.dp,
                )
            }
        } else {
            Surface(
                modifier = Modifier
                    .offset { IntOffset(panelX.roundToInt(), panelY.roundToInt()) }
                    .width(panelWidthDp)
                    .widthIn(max = 360.dp)
                    .testTag("in_app_floating_panel"),
                shape = RoundedCornerShape(8.dp),
                color = panelBg,
                border = BorderStroke(1.dp, panelBorder),
                shadowElevation = 18.dp,
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Draggable Hero Header matching .float-panel-hero (170.dp for comfortable height)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(170.dp)
                            .background(Color(0xFF0B0E18))
                            .pointerInput(maxW, maxH) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    panelX = (panelX + dragAmount.x).coerceIn(8f, (maxW - panelWidthPx - 8f).coerceAtLeast(8f))
                                    panelY = (panelY + dragAmount.y).coerceIn(32f, (maxH - 220f).coerceAtLeast(32f))
                                }
                            },
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.astra_hero),
                            contentDescription = "EA Artwork",
                            contentScale = ContentScale.Crop,
                            alignment = BiasAlignment(0f, -0.16f),
                            modifier = Modifier.fillMaxSize(),
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.horizontalGradient(
                                        0.0f to Color(0xB8000000),
                                        0.85f to Color(0x1E000000),
                                    ),
                                ),
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        0.35f to Color(0x10000000),
                                        1.0f to Color(0xB3000000),
                                    ),
                                ),
                        )

                        Column(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(start = 15.dp, top = 11.dp, end = 48.dp),
                        ) {
                            Text(
                                text = snapshot.botName,
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.ExtraBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = snapshot.botOwner,
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 3.dp),
                            )
                        }

                        val connStatus = if (snapshot.isConnected) "Connected" else "Ready"
                        Row(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(start = 15.dp, end = 15.dp, bottom = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF4CE28C)),
                            )
                            Text(
                                text = "Server: $connStatus · ${snapshot.serverName}",
                                color = Color(0xFF57E69D),
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        Surface(
                            onClick = { expanded = false },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(9.dp)
                                .size(28.dp)
                                .testTag("in_app_floating_minimize"),
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xBB07101D),
                            border = BorderStroke(1.dp, Color(0x55FFFFFF)),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = "Minimize floating monitor",
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }

                    // Controls row matching .float-panel-controls (exact 3 items)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(controlsBg)
                            .padding(horizontal = 10.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        Surface(
                            onClick = onToggleSkipTrades,
                            modifier = Modifier.height(31.dp),
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFFFFB04A),
                        ) {
                            Box(
                                modifier = Modifier.padding(horizontal = 10.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = if (snapshot.skipTrading) "RESUME TRADING" else "SKIP TRADES",
                                    color = Color(0xFF211305),
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                )
                            }
                        }

                        Surface(
                            onClick = { logsVisible = !logsVisible },
                            modifier = Modifier.height(31.dp),
                            shape = RoundedCornerShape(8.dp),
                            color = logsBtnBg,
                            border = BorderStroke(1.dp, logsBtnBorder),
                        ) {
                            Box(
                                modifier = Modifier.padding(horizontal = 10.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = if (logsVisible) "HIDE LOGS" else "SHOW LOGS",
                                    color = logsBtnText,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                )
                            }
                        }

                        Spacer(modifier = Modifier.weight(1f))

                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = Color(0xFF102E22),
                            border = BorderStroke(1.dp, Color(0xFF2D8054)),
                        ) {
                            Text(
                                text = "● LIVE",
                                color = Color(0xFF68EDA6),
                                fontSize = 8.sp,
                                fontWeight = FontWeight.ExtraBold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(dividerColor),
                    )

                    // Body: Compact Status & Balance Card + Trade Execution Card
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(bodyBg)
                            .padding(10.dp),
                    ) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            color = statusCardBg,
                            border = BorderStroke(1.dp, statusCardBorder),
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        text = snapshot.statusTitle,
                                        color = statusTitleColor,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = Color(0xFF12372D),
                                    ) {
                                        Text(
                                            text = snapshot.statusPill,
                                            color = Color(0xFF41D39A),
                                            fontSize = 8.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(5.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Balance: ${snapshot.accountBalance}  |  Equity: ${snapshot.accountEquity}",
                                            color = balanceColor,
                                            fontSize = 10.sp,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = "P/L: ${snapshot.accountFloatingPl}  ${if (snapshot.balanceUpdatedAt.isNotBlank()) "· ${snapshot.balanceUpdatedAt}" else "· ${snapshot.balanceStatus}"}",
                                            color = mutedTextColor,
                                            fontSize = 9.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    IconButton(
                                        onClick = onRefreshBalance,
                                        modifier = Modifier.size(28.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Refresh,
                                            contentDescription = "Refresh balance",
                                            tint = refreshTint,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                }
                            }
                        }

                        if (logsVisible) {
                            Spacer(modifier = Modifier.height(8.dp))
                            TradeExecutionTrackerCard(
                                snapshot = snapshot,
                                blueTerminalStyle = true,
                            )
                        }
                    }

                    // Footer matching .float-panel-footer
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(bodyBg)
                            .padding(horizontal = 10.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "Powered by BTECH FINANCE",
                            color = footerCopyColor,
                            fontSize = 8.sp,
                        )
                        Surface(
                            onClick = onStop,
                            modifier = Modifier
                                .height(28.dp)
                                .testTag("in_app_floating_stop"),
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFFD92C40),
                        ) {
                            Box(
                                modifier = Modifier.padding(horizontal = 12.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "STOP ROBOT",
                                    color = Color.White,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun GlowingBubbleAvatar(
    glowEnabled: Boolean,
    glowStyle: BubbleGlowStyle,
    primeColor: Color = Color(0xFF9138EE),
    bubbleBg: Color,
    idleBorderColor: Color,
    coreSize: Dp = 56.dp,
    outerSize: Dp = 78.dp,
    contentDescription: String = "Open BTECH floating monitor",
    modifier: Modifier = Modifier,
) {
    val cycleColors = remember {
        BubbleGlowStyle.CYCLE_HEX_COLORS.map { Color(it.toColorInt()) }
    }
    val transition = rememberInfiniteTransition(label = "bubble_glow_transition")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 7200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "bubble_glow_phase",
    )

    val pulse = 0.5f + 0.5f * sin(phase * 2.0 * PI * 6.0).toFloat()
    val glowColor = when (glowStyle) {
        BubbleGlowStyle.AUTO -> {
            val scaled = phase * cycleColors.size
            val idx = scaled.toInt().coerceIn(0, cycleColors.lastIndex)
            val nextIdx = (idx + 1) % cycleColors.size
            val fraction = scaled - scaled.toInt()
            lerp(cycleColors[idx], cycleColors[nextIdx], fraction)
        }
        BubbleGlowStyle.PRIME -> primeColor
        else -> Color(glowStyle.hex.toColorInt())
    }

    val isDarkStyle = glowColor.luminance() < 0.08f
    val haloColor = if (isDarkStyle) Color(0xFF0F172A) else glowColor
    val rimColor = when {
        !glowEnabled -> idleBorderColor
        isDarkStyle -> Color(0xFF64748B)
        else -> lerp(glowColor, Color.White, 0.25f)
    }

    Box(
        modifier = modifier.size(outerSize),
        contentAlignment = Alignment.Center,
    ) {
        if (glowEnabled) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val radius = (size.minDimension / 2f) * (0.93f + 0.07f * pulse)
                drawCircle(
                    brush = Brush.radialGradient(
                        0.0f to haloColor.copy(alpha = 0.92f),
                        0.58f to haloColor.copy(alpha = 0.72f + 0.18f * pulse),
                        0.82f to haloColor.copy(alpha = 0.34f + 0.16f * pulse),
                        1.0f to Color.Transparent,
                        center = center,
                        radius = radius,
                    ),
                    radius = radius,
                )
            }
        }
        Surface(
            modifier = Modifier.size(coreSize),
            shape = CircleShape,
            color = bubbleBg,
            border = BorderStroke(if (glowEnabled) 2.5.dp else 3.dp, rimColor),
            shadowElevation = if (glowEnabled) 10.dp else 12.dp,
        ) {
            Image(
                painter = painterResource(id = R.drawable.astra_hero),
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                alignment = BiasAlignment(0f, -0.25f),
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape),
            )
        }
    }
}
