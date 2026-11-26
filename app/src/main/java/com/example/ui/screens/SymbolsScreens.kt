package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.AllowedSymbolConfig
import com.example.model.BrokerCatalog
import com.example.model.PlatformType
import com.example.model.TradeDirection
import com.example.ui.components.BtechCard
import com.example.ui.components.BtechPrimaryButton
import com.example.ui.components.BtechStatusPill
import com.example.ui.components.PageHeader
import com.example.ui.theme.LocalBtechColors
import com.example.viewmodel.AppUiState

@Composable
fun SymbolsScreen(
    state: AppUiState,
    onTabChange: (Int) -> Unit,
    onOpenSymbolConfig: (String) -> Unit,
    onBack: () -> Unit,
    onRefreshDiscoveredSymbols: (() -> Unit)? = null,
) {
    BackHandler(onBack = onBack)
    val extra = LocalBtechColors.current
    var searchQuery by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        TextButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Back to Home")
        }

        PageHeader(
            title = "Symbols",
            subtitle = "Choose which markets this robot is allowed to trade.",
        )

        // Tab Bar: ALL SYMBOLS | ALLOWED SYMBOLS (count)
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, extra.line),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val tabs = listOf("ALL SYMBOLS", "ALLOWED SYMBOLS (${state.allowedSymbols.size})")
                tabs.forEachIndexed { idx, label ->
                    val active = state.symbolTabIndex == idx
                    Button(
                        onClick = { onTabChange(idx) },
                        modifier = Modifier
                            .weight(1f)
                            .height(42.dp)
                            .testTag("symbol_tab_$idx"),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (active) MaterialTheme.colorScheme.primary else Color.Transparent,
                            contentColor = if (active) MaterialTheme.colorScheme.onPrimary else extra.muted,
                        ),
                        elevation = null,
                    ) {
                        Text(label, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        if (state.symbolTabIndex == 1) {
            if (state.allowedSymbols.isEmpty()) {
                BtechCard(padding = PaddingValues(24.dp)) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("No Symbol Allowed", style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "This robot cannot trade any market until you choose one from All Symbols.",
                            style = MaterialTheme.typography.bodySmall,
                            color = extra.muted,
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        BtechPrimaryButton(
                            text = "BROWSE ALL SYMBOLS",
                            onClick = { onTabChange(0) },
                        )
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BtechStatusPill("${state.allowedSymbols.size} ALLOWED", extra.greenContainer, extra.green)
                    Text("Tap a row to edit settings", fontSize = 10.sp, color = extra.muted)
                }
                Spacer(modifier = Modifier.height(10.dp))
                state.allowedSymbols.forEach { cfg ->
                    SymbolMarketRow(
                        symbol = cfg.symbol,
                        subtitle = "${cfg.platform.label} · Lot ${cfg.lot} · ${cfg.trades} trades · ${if (cfg.live) "Live" else "Paused"}",
                        isAllowed = true,
                        onClick = { onOpenSymbolConfig(cfg.symbol) },
                    )
                }
            }
        } else {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search or type exact symbol (e.g. XAUUSD.s, XAUUSD.m)") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("symbol_search_input"),
                shape = RoundedCornerShape(10.dp),
            )
            Spacer(modifier = Modifier.height(10.dp))

            val acct = state.savedAccount
            if (acct != null && onRefreshDiscoveredSymbols != null) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, extra.line),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${acct.broker} · ${acct.platform.label} (${acct.server.ifBlank { "Terminal" }})",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = if (state.discoveredSymbols.isNotEmpty()) {
                                    "${state.discoveredSymbols.size} broker instruments discovered dynamically"
                                } else {
                                    "Scan open ${acct.platform.label} terminal for live broker symbols"
                                },
                                fontSize = 10.sp,
                                color = extra.muted,
                            )
                        }
                        TextButton(
                            onClick = onRefreshDiscoveredSymbols,
                            enabled = !state.isDiscoveringSymbols,
                            modifier = Modifier.testTag("scan_broker_symbols_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = "Scan broker symbols",
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (state.isDiscoveringSymbols) "SCANNING..." else "SCAN TERMINAL",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.ExtraBold,
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
            }

            val trimmed = searchQuery.trim()
            val discoveredEntries = state.discoveredSymbols.map { sym ->
                com.example.model.MarketSymbol(
                    symbol = sym,
                    description = "Discovered on ${acct?.broker ?: "Broker"} ${acct?.platform?.label ?: ""}".trim(),
                )
            }
            val discoveredNames = state.discoveredSymbols.map { it.lowercase() }.toSet()
            val combinedMarkets = discoveredEntries + BrokerCatalog.markets.filterNot {
                discoveredNames.contains(it.symbol.lowercase())
            }
            val filtered = combinedMarkets.filter {
                trimmed.isEmpty() || it.symbol.contains(trimmed, ignoreCase = true) ||
                    it.description.contains(trimmed, ignoreCase = true)
            }

            if (trimmed.isNotEmpty() && filtered.none { it.symbol == trimmed }) {
                SymbolMarketRow(
                    symbol = trimmed,
                    subtitle = "Exact broker symbol · Tap to configure",
                    isAllowed = false,
                    onClick = { onOpenSymbolConfig(trimmed) },
                )
            }

            filtered.forEach { market ->
                val saved = state.allowedSymbols.firstOrNull { it.symbol == market.symbol }
                SymbolMarketRow(
                    symbol = market.symbol,
                    subtitle = if (saved != null) {
                        "${saved.platform.label} · Lot ${saved.lot} · ${saved.trades} trades · ${if (saved.live) "Live" else "Off"}"
                    } else {
                        market.description
                    },
                    isAllowed = false,
                    onClick = { onOpenSymbolConfig(market.symbol) },
                )
            }
        }
    }
}

@Composable
private fun SymbolMarketRow(
    symbol: String,
    subtitle: String,
    isAllowed: Boolean,
    onClick: () -> Unit,
) {
    val extra = LocalBtechColors.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .testTag("symbol_row_$symbol"),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, extra.line),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(extra.surface3),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = symbol.take(2),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Black,
                    fontSize = 13.sp,
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(symbol, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(subtitle, fontSize = 11.sp, color = extra.muted)
            }
            Icon(
                imageVector = if (isAllowed) Icons.Filled.ChevronRight else Icons.Filled.Add,
                contentDescription = if (isAllowed) "Edit $symbol" else "Add $symbol",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
fun SymbolConfigScreen(
    symbol: String,
    existingConfig: AllowedSymbolConfig?,
    onSave: (AllowedSymbolConfig) -> Unit,
    onDelete: (String) -> Unit,
    onCancel: () -> Unit,
) {
    BackHandler(onBack = onCancel)
    val extra = LocalBtechColors.current

    var activeSymbol by remember(symbol) { mutableStateOf(symbol) }
    var platform by remember(symbol) { mutableStateOf(existingConfig?.platform ?: PlatformType.MT5) }
    var lot by remember(symbol) { mutableStateOf(existingConfig?.lot ?: "0.01") }
    var trades by remember(symbol) { mutableStateOf(existingConfig?.trades ?: "4") }
    var direction by remember(symbol) { mutableStateOf(existingConfig?.direction ?: TradeDirection.BOTH) }
    var live by remember(symbol) { mutableStateOf(existingConfig?.live ?: true) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        PageHeader(
            title = "$activeSymbol settings",
            subtitle = "Set the exact broker symbol suffix and execution rules for this market.",
        )

        BtechCard(padding = PaddingValues(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(extra.accentSoft),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = activeSymbol.take(2),
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Black,
                        fontSize = 17.sp,
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(activeSymbol, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text("Exact broker symbol (suffixes stay lowercase)", fontSize = 11.sp, color = extra.muted)
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = activeSymbol,
                onValueChange = { activeSymbol = it },
                label = { Text("EXACT BROKER SYMBOL") },
                placeholder = { Text("e.g. XAUUSD.s, XAUUSD.m") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
            )
            Spacer(modifier = Modifier.height(8.dp))
            val baseSym = activeSymbol.replace(Regex("(\\.[a-zA-Z]+|[mc])$"), "")
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                listOf("" to "Std", ".m" to ".m", ".s" to ".s", ".c" to ".c", ".ecn" to ".ecn", "m" to "m").forEach { (sfx, label) ->
                    val target = baseSym + sfx
                    val selected = activeSymbol == target
                    Button(
                        onClick = { activeSymbol = target },
                        modifier = Modifier.weight(1f).height(34.dp),
                        contentPadding = PaddingValues(0.dp),
                        shape = RoundedCornerShape(6.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (selected) MaterialTheme.colorScheme.primary else extra.surface2,
                            contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                        ),
                    ) {
                        Text(label, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))
        Text("PLATFORM", style = MaterialTheme.typography.labelMedium, color = extra.muted)
        Spacer(modifier = Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PlatformType.entries.forEach { p ->
                val active = platform == p
                Button(
                    onClick = { platform = p },
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (active) extra.accentSoft else extra.surface2,
                        contentColor = if (active) MaterialTheme.colorScheme.primary else extra.muted,
                    ),
                    border = BorderStroke(1.dp, if (active) MaterialTheme.colorScheme.primary else extra.line),
                ) {
                    Text(p.label, fontWeight = FontWeight.ExtraBold)
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = lot,
                onValueChange = { lot = it },
                label = { Text("LOT VOLUME") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier
                    .weight(1f)
                    .testTag("lot_volume_input"),
                shape = RoundedCornerShape(10.dp),
            )
            OutlinedTextField(
                value = trades,
                onValueChange = { trades = it },
                label = { Text("NUMBER OF TRADES") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier
                    .weight(1f)
                    .testTag("trade_count_input"),
                shape = RoundedCornerShape(10.dp),
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Make sure you enter your broker's minimum lot for this symbol to avoid errors.",
            fontSize = 11.sp,
            color = extra.muted,
            modifier = Modifier.testTag("lot_minimum_hint"),
        )

        Spacer(modifier = Modifier.height(14.dp))
        Text("DIRECTION", style = MaterialTheme.typography.labelMedium, color = extra.muted)
        Spacer(modifier = Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TradeDirection.entries.forEach { dir ->
                val active = direction == dir
                Button(
                    onClick = { direction = dir },
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (active) extra.accentSoft else extra.surface2,
                        contentColor = if (active) MaterialTheme.colorScheme.primary else extra.muted,
                    ),
                    border = BorderStroke(1.dp, if (active) MaterialTheme.colorScheme.primary else extra.line),
                ) {
                    Text(dir.label, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))
        BtechCard(padding = PaddingValues(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Enable live trades", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text(
                        "Allow this symbol to execute on the connected Web Terminal.",
                        fontSize = 11.sp,
                        color = extra.muted,
                    )
                }
                Switch(checked = live, onCheckedChange = { live = it })
            }
        }

        Spacer(modifier = Modifier.height(18.dp))
        BtechPrimaryButton(
            text = "SAVE SYMBOL",
            onClick = {
                val cleanSymbol = activeSymbol.trim().ifEmpty { symbol }
                onSave(
                    AllowedSymbolConfig(
                        symbol = cleanSymbol,
                        platform = platform,
                        lot = lot.trim(),
                        direction = direction,
                        trades = trades.trim(),
                        live = live,
                    )
                )
            },
            modifier = Modifier.testTag("save_symbol_button"),
        )

        if (existingConfig != null) {
            Spacer(modifier = Modifier.height(10.dp))
            TextButton(
                onClick = { onDelete(symbol) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.DeleteOutline, contentDescription = null, tint = extra.red)
                Spacer(modifier = Modifier.width(6.dp))
                Text("Remove from Allowed Symbols", color = extra.red)
            }
        }

        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
            Text("Cancel")
        }
    }
}
