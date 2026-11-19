package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CandlestickChart
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.components.BtechCard
import com.example.ui.components.BtechPrimaryButton
import com.example.ui.components.BtechSecondaryButton
import com.example.ui.components.BtechStatusPill
import com.example.ui.components.PageHeader
import com.example.ui.theme.LocalBtechColors
import com.example.viewmodel.AppUiState

@Composable
fun HomeScreen(
    state: AppUiState,
    onAddRobotClick: () -> Unit,
    onToggleBot: () -> Unit,
    onSimulateTradeClick: () -> Unit,
    onSymbolsClick: () -> Unit,
    onInfoClick: () -> Unit,
    onLogsClick: () -> Unit,
    onOpenWebTerminal: () -> Unit,
    onToggleBalanceMonitor: () -> Unit = {},
    onRefreshBalance: () -> Unit = {},
) {
    val extra = LocalBtechColors.current
    val scroll = rememberScrollState()

    if (!state.hasRobot) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            PageHeader(
                title = "My robot",
                subtitle = "Your trading assistant will appear here.",
            )

            BtechCard(padding = PaddingValues(24.dp)) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier
                            .size(86.dp)
                            .clip(CircleShape)
                            .background(extra.accentSoft)
                            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CandlestickChart,
                            contentDescription = "No robot connected",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(38.dp),
                        )
                    }
                    Spacer(modifier = Modifier.height(18.dp))
                    Text(
                        text = "No robot connected",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Add a licensed BTECH robot to receive signals, display the floating overlay, and test Web Terminal trades.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = extra.muted,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(22.dp))
                    BtechPrimaryButton(
                        text = "ADD YOUR FIRST ROBOT",
                        onClick = onAddRobotClick,
                        modifier = Modifier.testTag("add_first_robot_button"),
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Tip: Connect your MT4/MT5 account under Meta, pick symbols, then press Start or Simulate Trade.",
                        style = MaterialTheme.typography.bodySmall,
                        color = extra.muted,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            FloatingBalanceMonitorCard(
                state = state,
                onToggleBalanceMonitor = onToggleBalanceMonitor,
                onRefreshBalance = onRefreshBalance,
            )
        }
        return
    }

    // Active Robot Home Screen with Adaptive Half-Screen Hero Art
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val heroHeight = (maxHeight * 0.56f).coerceIn(285.dp, 460.dp)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(heroHeight),
            ) {
                Image(
                    painter = painterResource(id = R.drawable.astra_hero),
                    contentDescription = "BTECH EA Robot Artwork",
                    contentScale = ContentScale.Crop,
                    alignment = BiasAlignment(0f, -0.16f),
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0.0f to Color.Transparent,
                                0.62f to Color.Black.copy(alpha = 0.12f),
                                1.0f to MaterialTheme.colorScheme.background,
                            )
                        ),
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            // 3 Primary Robot Action Tiles: START/STOP | SYMBOLS | INFO
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                RobotActionTile(
                    icon = if (state.isRunning) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                    label = if (state.isRunning) "STOP" else "START",
                    onClick = onToggleBot,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("start_stop_robot_button"),
                )
                RobotActionTile(
                    icon = Icons.Filled.CandlestickChart,
                    label = "SYMBOLS",
                    onClick = onSymbolsClick,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("symbols_shortcut_button"),
                )
                RobotActionTile(
                    icon = Icons.Filled.Info,
                    label = "INFO",
                    onClick = onInfoClick,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("robot_info_button"),
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Simulate Trade Test Action (requested by user for testing WebView & Portal pipeline)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = onSimulateTradeClick,
                    enabled = !state.isSimulatingTrade,
                    modifier = Modifier
                        .weight(1.4f)
                        .height(48.dp)
                        .testTag("simulate_trade_button"),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Bolt,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (state.isSimulatingTrade) "EXECUTING..." else "SIMULATE TRADE",
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 12.sp,
                        maxLines = 1,
                    )
                }

                BtechSecondaryButton(
                    text = "WEB TERMINAL",
                    onClick = onOpenWebTerminal,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("open_web_terminal_button"),
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Robot Summary Card
            BtechCard(padding = PaddingValues(13.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painter = painterResource(id = R.drawable.astra_hero),
                        contentDescription = "Robot avatar",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .border(2.dp, extra.line, CircleShape),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = state.botName,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Licence plan: PRO",
                            fontSize = 11.sp,
                            color = extra.muted,
                        )
                        Text(
                            text = "Expires: 31 Dec 2026",
                            fontSize = 11.sp,
                            color = extra.amber,
                        )
                    }
                    BtechStatusPill(
                        text = if (state.isRunning) "RUNNING" else "STOPPED",
                        containerColor = if (state.isRunning) extra.greenContainer else extra.amberContainer,
                        contentColor = if (state.isRunning) extra.green else extra.amber,
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Allowed Symbols Shortcut Card
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onSymbolsClick)
                    .testTag("allowed_symbols_card"),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, extra.line),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(extra.accentSoft),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CandlestickChart,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Allowed Symbols",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        val count = state.allowedSymbols.size
                        Text(
                            text = "$count ${if (count == 1) "symbol" else "symbols"} saved · Tap to review or edit",
                            fontSize = 11.sp,
                            color = extra.muted,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "${state.allowedSymbols.size}",
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold,
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Filled.ChevronRight,
                        contentDescription = "Open symbols",
                        tint = extra.muted,
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Live Status & Execution Log Card
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onLogsClick)
                    .testTag("execution_logs_card"),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, extra.line),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(extra.surface2),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Terminal,
                            contentDescription = null,
                            tint = extra.green,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Execution Log & Broker Status",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = state.lastStatusDetail,
                            fontSize = 11.sp,
                            color = extra.muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Icon(
                        imageVector = Icons.Filled.ChevronRight,
                        contentDescription = "Open execution log",
                        tint = extra.muted,
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            FloatingBalanceMonitorCard(
                state = state,
                onToggleBalanceMonitor = onToggleBalanceMonitor,
                onRefreshBalance = onRefreshBalance,
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
    }
}

@Composable
private fun FloatingBalanceMonitorCard(
    state: AppUiState,
    onToggleBalanceMonitor: () -> Unit,
    onRefreshBalance: () -> Unit,
) {
    val extra = LocalBtechColors.current
    BtechCard(padding = PaddingValues(14.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Floating Balance Monitor",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = if (state.balanceUpdatedAt.isNotBlank()) {
                        "${state.balanceStatus} · ${state.balanceUpdatedAt}"
                    } else {
                        state.balanceStatus
                    },
                    fontSize = 10.sp,
                    color = extra.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BtechStatusPill(
                text = if (state.isBalanceMonitorRunning) "OVERLAY ON" else "READY",
                containerColor = if (state.isBalanceMonitorRunning) extra.greenContainer else extra.surface2,
                contentColor = if (state.isBalanceMonitorRunning) extra.green else extra.muted,
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp),
                color = extra.surface2,
                border = BorderStroke(1.dp, extra.line),
            ) {
                Column(modifier = Modifier.padding(9.dp)) {
                    Text("BALANCE", fontSize = 9.sp, color = extra.muted, fontWeight = FontWeight.Bold)
                    Text(
                        text = state.accountBalance,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = extra.green,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Surface(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp),
                color = extra.surface2,
                border = BorderStroke(1.dp, extra.line),
            ) {
                Column(modifier = Modifier.padding(9.dp)) {
                    Text("EQUITY", fontSize = 9.sp, color = extra.muted, fontWeight = FontWeight.Bold)
                    Text(
                        text = state.accountEquity,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Surface(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp),
                color = extra.surface2,
                border = BorderStroke(1.dp, extra.line),
            ) {
                Column(modifier = Modifier.padding(9.dp)) {
                    Text("FLOAT P/L", fontSize = 9.sp, color = extra.muted, fontWeight = FontWeight.Bold)
                    Text(
                        text = state.accountFloatingPl,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (state.accountFloatingPl.startsWith("-")) extra.red else extra.green,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BtechPrimaryButton(
                text = if (state.isBalanceMonitorRunning) "STOP OVERLAY" else "OPEN BALANCE OVERLAY",
                onClick = onToggleBalanceMonitor,
                containerColor = if (state.isBalanceMonitorRunning) extra.red else MaterialTheme.colorScheme.primary,
                contentColor = if (state.isBalanceMonitorRunning) Color.White else MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .weight(1.6f)
                    .testTag("floating_balance_monitor_button"),
            )
            BtechSecondaryButton(
                text = "REFRESH",
                onClick = onRefreshBalance,
                modifier = Modifier
                    .weight(1f)
                    .testTag("refresh_balance_button"),
            )
        }
    }
}

@Composable
private fun RobotActionTile(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(56.dp),
        shape = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(vertical = 6.dp, horizontal = 4.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(imageVector = icon, contentDescription = label, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.height(2.dp))
            Text(text = label, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
        }
    }
}

@Composable
fun AddRobotScreen(
    onAuthenticate: (String, onSuccess: () -> Unit) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val extra = LocalBtechColors.current
    var licenseKey by remember { mutableStateOf("") }
    var showSuccessModal by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        PageHeader(
            title = "Add a robot",
            subtitle = "Enter the licence key issued for your BTECH EA.",
        )

        BtechCard(padding = PaddingValues(20.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(extra.accentSoft),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Key,
                        contentDescription = "Licence key",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp),
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "ROBOT AUTHENTICATION",
                    style = MaterialTheme.typography.labelMedium,
                    color = extra.muted,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Authenticate your EA",
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Paste your licence key below. Enter any 8+ character key in this standalone build to unlock the robot controls.",
                    style = MaterialTheme.typography.bodySmall,
                    color = extra.muted,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(18.dp))

                OutlinedTextField(
                    value = licenseKey,
                    onValueChange = { licenseKey = it },
                    label = { Text("LICENCE KEY") },
                    placeholder = { Text("e.g. BTECH-PRO-2026") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("license_key_input"),
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = extra.line,
                    ),
                )
                Spacer(modifier = Modifier.height(16.dp))
                BtechPrimaryButton(
                    text = "AUTHENTICATE",
                    onClick = {
                        onAuthenticate(licenseKey) {
                            showSuccessModal = true
                        }
                    },
                    modifier = Modifier.testTag("authenticate_robot_button"),
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        TextButton(onClick = onBack, modifier = Modifier.testTag("back_to_home_button")) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Back to Home")
        }
    }

    if (showSuccessModal) {
        AlertDialog(
            onDismissRequest = {
                showSuccessModal = false
                onBack()
            },
            icon = {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(extra.greenContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = extra.green)
                }
            },
            title = { Text("Authentication Successful", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Your robot has been added. Next, open Meta to connect your MT4/MT5 Web Terminal login and Symbols to pick your trading pair.",
                    color = extra.muted,
                )
            },
            confirmButton = {
                BtechPrimaryButton(
                    text = "CONTINUE TO HOME",
                    onClick = {
                        showSuccessModal = false
                        onBack()
                    },
                )
            },
        )
    }
}

@Composable
fun RobotInfoScreen(
    state: AppUiState,
    onRemoveRobot: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val extra = LocalBtechColors.current
    var showConfirm by remember { mutableStateOf(false) }

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
            title = "Robot information",
            subtitle = "Review the owner and licence details linked to this EA.",
        )

        BtechCard {
            Image(
                painter = painterResource(id = R.drawable.astra_hero),
                contentDescription = "Robot artwork",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(170.dp)
                    .clip(RoundedCornerShape(12.dp)),
            )
            Spacer(modifier = Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(state.botName, style = MaterialTheme.typography.titleLarge)
                BtechStatusPill("ACTIVE", extra.greenContainer, extra.green)
            }
            Spacer(modifier = Modifier.height(14.dp))

            val cells = listOf(
                "OWNER" to state.botOwner,
                "OWNER ROLE" to "Expertise Mentor",
                "EA DETAILS" to "Forex & indices scalper",
                "LICENCE PLAN" to "PRO",
                "EXPIRY DATE" to "31 Dec 2026",
                "STATUS" to "Active",
            )
            cells.chunked(2).forEach { pair ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    pair.forEach { (k, v) ->
                        Surface(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            color = extra.surface2,
                            border = BorderStroke(1.dp, extra.line),
                        ) {
                            Column(modifier = Modifier.padding(11.dp)) {
                                Text(k, fontSize = 9.sp, color = extra.muted, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(v, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        BtechPrimaryButton(
            text = "REMOVE ROBOT",
            onClick = { showConfirm = true },
            containerColor = extra.red,
            contentColor = Color.White,
            modifier = Modifier.testTag("remove_robot_button"),
        )
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            icon = { Icon(Icons.Filled.Warning, contentDescription = null, tint = extra.red) },
            title = { Text("Remove this robot?") },
            text = { Text("This will disconnect the EA from this app and stop the floating overlay.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showConfirm = false
                        onRemoveRobot()
                    },
                ) {
                    Text("CONFIRM REMOVE", color = extra.red, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text("CANCEL") }
            },
        )
    }
}
