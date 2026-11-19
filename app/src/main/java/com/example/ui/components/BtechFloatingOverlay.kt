package com.example.ui.components

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.example.R
import com.example.service.OverlaySnapshot
import kotlin.math.roundToInt

/**
 * In-app draggable floating bubble and expandable monitor card faithfully matching
 * the HTML prototype (#floatBubble + #floatPanel) with real-time account balance updates.
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

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .zIndex(50f),
    ) {
        val density = LocalDensity.current
        val maxW = with(density) { maxWidth.toPx() }
        val maxH = with(density) { maxHeight.toPx() }

        val bubbleSizePx = with(density) { 58.dp.toPx() }
        var bubbleX by remember(maxW) { mutableFloatStateOf((maxW - bubbleSizePx - with(density) { 18.dp.toPx() }).coerceAtLeast(16f)) }
        var bubbleY by remember(maxH) { mutableFloatStateOf((maxH * 0.62f).coerceIn(80f, (maxH - bubbleSizePx - 120f).coerceAtLeast(80f))) }

        val panelWidthDp = 340.dp.coerceAtMost(maxWidth - 24.dp)
        val panelWidthPx = with(density) { panelWidthDp.toPx() }
        var panelX by remember(maxW) { mutableFloatStateOf(((maxW - panelWidthPx) / 2f).coerceAtLeast(12f)) }
        var panelY by remember(maxH) { mutableFloatStateOf((maxH * 0.14f).coerceAtLeast(48f)) }

        if (!expanded) {
            Surface(
                modifier = Modifier
                    .offset { IntOffset(bubbleX.roundToInt(), bubbleY.roundToInt()) }
                    .size(58.dp)
                    .pointerInput(maxW, maxH) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            bubbleX = (bubbleX + dragAmount.x).coerceIn(8f, (maxW - bubbleSizePx - 8f).coerceAtLeast(8f))
                            bubbleY = (bubbleY + dragAmount.y).coerceIn(40f, (maxH - bubbleSizePx - 96f).coerceAtLeast(40f))
                        }
                    }
                    .clickable { expanded = true }
                    .testTag("in_app_floating_bubble"),
                shape = CircleShape,
                color = Color(0xFF102743),
                border = BorderStroke(3.dp, Color.White),
                shadowElevation = 12.dp,
            ) {
                Image(
                    painter = painterResource(id = R.drawable.astra_hero),
                    contentDescription = "Open BTECH floating monitor",
                    contentScale = ContentScale.Crop,
                    alignment = BiasAlignment(0f, -0.25f),
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape),
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
                color = Color(0xFF070B14),
                border = BorderStroke(1.dp, Color(0xFF526071)),
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
                            .background(Color(0xFF0B111C))
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
                            color = Color(0xFF202C3C),
                            border = BorderStroke(1.dp, Color(0xFF45546A)),
                        ) {
                            Box(
                                modifier = Modifier.padding(horizontal = 10.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = if (logsVisible) "HIDE LOGS" else "SHOW LOGS",
                                    color = Color(0xFFE9F2FF),
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
                            .background(Color(0xFF263447)),
                    )

                    // Body: Compact Status & Balance Card + Blue Trade Execution Card
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF080E19))
                            .padding(10.dp),
                    ) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF101B2B),
                            border = BorderStroke(1.dp, Color(0xFF25384E)),
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        text = snapshot.statusTitle,
                                        color = Color(0xFFF1F5FB),
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
                                            color = Color(0xFF41D39A),
                                            fontSize = 10.sp,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = "P/L: ${snapshot.accountFloatingPl}  ${if (snapshot.balanceUpdatedAt.isNotBlank()) "· ${snapshot.balanceUpdatedAt}" else "· ${snapshot.balanceStatus}"}",
                                            color = Color(0xFF9FB1C8),
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
                                            tint = Color(0xFF68EDA6),
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
                            .background(Color(0xFF080E19))
                            .padding(horizontal = 10.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "Powered by BTECH FINANCE",
                            color = Color(0xFF8397AF),
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
