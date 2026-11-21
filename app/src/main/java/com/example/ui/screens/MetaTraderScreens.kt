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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.BrokerCatalog
import com.example.model.BrokerInfo
import com.example.model.PlatformType
import com.example.ui.components.BtechCard
import com.example.ui.components.BtechPrimaryButton
import com.example.ui.components.BtechSecondaryButton
import com.example.ui.components.BtechStatusPill
import com.example.ui.components.PageHeader
import com.example.ui.theme.LocalBtechColors
import com.example.viewmodel.AppUiState
import com.example.webterminal.WebTerminalStatus

@Composable
fun MetaBrokerListScreen(
    onSelectBroker: (String) -> Unit,
    onOpenSavedDetails: () -> Unit,
) {
    val extra = LocalBtechColors.current
    var query by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        PageHeader(
            title = "MetaTrader",
            subtitle = "Choose your broker. BTECH connects via the broker's Web Terminal in the background.",
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("BROKER DIRECTORY", style = MaterialTheme.typography.labelMedium, color = extra.muted)
            TextButton(
                onClick = onOpenSavedDetails,
                modifier = Modifier.testTag("saved_details_button"),
            ) {
                Text("SAVED DETAILS", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search brokers") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("broker_search_input"),
            shape = RoundedCornerShape(10.dp),
        )
        Spacer(modifier = Modifier.height(10.dp))

        val filtered = BrokerCatalog.brokers.filter {
            query.isBlank() || it.name.contains(query, ignoreCase = true)
        }
        filtered.forEach { broker ->
            BrokerRow(broker = broker, onClick = { onSelectBroker(broker.name) })
        }
    }
}

@Composable
private fun BrokerRow(broker: BrokerInfo, onClick: () -> Unit) {
    val extra = LocalBtechColors.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .testTag("broker_row_${broker.id}"),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, extra.line),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(extra.surface3),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = broker.logo,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Black,
                    fontSize = 13.sp,
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(broker.name, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(broker.summary, fontSize = 11.sp, color = extra.muted)
            }
            Icon(Icons.Filled.ChevronRight, contentDescription = "Select ${broker.name}", tint = extra.muted)
        }
    }
}

@Composable
fun BrokerLoginScreen(
    state: AppUiState,
    webStatus: WebTerminalStatus,
    resolvedTerminalUrl: String?,
    onPlatformChange: (PlatformType) -> Unit,
    onLoginChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onServerIdChange: (String) -> Unit,
    onMt5ServerChange: (String) -> Unit,
    onMt4ServerChange: (String) -> Unit,
    onCustomUrlChange: (String) -> Unit,
    onAuthenticateClick: () -> Unit,
    onCancelAuth: () -> Unit,
    onInspectWebTerminal: (String?) -> Unit,
    onDismissAuthResult: () -> Unit,
    onAuthSuccessContinue: () -> Unit,
    onRemoveSavedAccount: () -> Unit = {},
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val extra = LocalBtechColors.current
    val broker = BrokerCatalog.findBroker(state.selectedBroker)
    val mt5Servers = broker?.mt5Servers.orEmpty()
    val selectedServer = mt5Servers.firstOrNull { it.id == state.selectedServerId } ?: mt5Servers.firstOrNull()

    var passwordVisible by remember { mutableStateOf(false) }
    var serverMenuExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        TextButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Back to Brokers")
        }

        PageHeader(
            title = state.selectedBroker,
            subtitle = "Connect your account once. BTECH loads your broker Web Terminal in the background to verify credentials.",
        )

        // Platform Toggle: MT5 ACCOUNT | MT4 ACCOUNT
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(PlatformType.MT5 to "MT5 ACCOUNT", PlatformType.MT4 to "MT4 ACCOUNT").forEach { (p, label) ->
                val active = state.selectedPlatform == p
                Button(
                    onClick = { onPlatformChange(p) },
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .testTag("platform_${p.name}"),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (active) extra.accentSoft else extra.surface2,
                        contentColor = if (active) MaterialTheme.colorScheme.primary else extra.muted,
                    ),
                    border = BorderStroke(1.dp, if (active) MaterialTheme.colorScheme.primary else extra.line),
                ) {
                    Text(label, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        state.savedAccount?.let { existing ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                shape = RoundedCornerShape(12.dp),
                color = extra.surface2,
                border = BorderStroke(1.dp, extra.line),
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "SAVED: ${existing.broker} · ${existing.login}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "Authenticating a new account removes this saved account and resets the Web Terminal session.",
                            fontSize = 10.sp,
                            color = extra.muted,
                            lineHeight = 14.sp,
                        )
                    }
                    TextButton(
                        onClick = onRemoveSavedAccount,
                        modifier = Modifier.testTag("clear_recent_account_button"),
                    ) {
                        Text(
                            text = "REMOVE",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = extra.red,
                        )
                    }
                }
            }
        }

        BtechCard(padding = PaddingValues(18.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(extra.accentSoft),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.AccountBalance,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "${state.selectedPlatform.label} ACCOUNT AUTHENTICATION",
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 14.sp,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Invalid logins fail safely and ask for valid details. Authenticated sessions are reused for simulated trades.",
                    style = MaterialTheme.typography.bodySmall,
                    color = extra.muted,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = state.loginInput,
                    onValueChange = onLoginChange,
                    label = { Text("LOGIN") },
                    placeholder = { Text("Enter account login number") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("broker_login_input"),
                    shape = RoundedCornerShape(10.dp),
                )
                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = state.passwordInput,
                    onValueChange = onPasswordChange,
                    label = { Text("MASTER / TRADING PASSWORD") },
                    placeholder = { Text("Enter trading password") },
                    singleLine = true,
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (passwordVisible) "Hide password" else "Show password",
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("broker_password_input"),
                    shape = RoundedCornerShape(10.dp),
                )
                Spacer(modifier = Modifier.height(12.dp))

                if (state.selectedPlatform == PlatformType.MT5) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "MT5 SERVER — ${state.selectedBroker.uppercase()}",
                            style = MaterialTheme.typography.labelMedium,
                            color = extra.muted,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Box {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { serverMenuExpanded = true }
                                    .testTag("mt5_server_dropdown"),
                                shape = RoundedCornerShape(10.dp),
                                color = extra.surface2,
                                border = BorderStroke(1.dp, extra.line),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        text = selectedServer?.name ?: "Select server",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Choose server")
                                }
                            }
                            DropdownMenu(
                                expanded = serverMenuExpanded,
                                onDismissRequest = { serverMenuExpanded = false },
                            ) {
                                mt5Servers.forEach { srv ->
                                    val tag = if (srv.isUrlVerified) "verified" else "unverified URL"
                                    DropdownMenuItem(
                                        text = { Text("${srv.name} (${srv.environment} · $tag)") },
                                        onClick = {
                                            onServerIdChange(srv.id)
                                            serverMenuExpanded = false
                                        },
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedTextField(
                            value = state.mt5ServerInput,
                            onValueChange = onMt5ServerChange,
                            label = { Text("MT5 SERVER NAME (EDITABLE)") },
                            placeholder = { Text("e.g. JustMarkets-Demo or JMarkets-Demo") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("mt5_server_input"),
                            shape = RoundedCornerShape(10.dp),
                        )
                    }
                } else {
                    OutlinedTextField(
                        value = state.mt4ServerInput,
                        onValueChange = onMt4ServerChange,
                        label = { Text("MT4 SERVER") },
                        placeholder = { Text("Enter exact MT4 server name") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("mt4_server_input"),
                        shape = RoundedCornerShape(10.dp),
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                val effectiveUrl = state.customTerminalUrl.ifBlank { resolvedTerminalUrl.orEmpty() }
                val urlMismatch = BrokerCatalog.isUrlPlatformMismatch(effectiveUrl, state.selectedPlatform)
                val showGenericOrEmptyWarning = BrokerCatalog.requiresBrokerOwnUrlWarning(
                    state.selectedBroker,
                    effectiveUrl,
                    state.selectedPlatform,
                )

                OutlinedTextField(
                    value = state.customTerminalUrl,
                    onValueChange = onCustomUrlChange,
                    label = { Text("TERMINAL URL (REQUIRED)") },
                    placeholder = {
                        Text(
                            when {
                                state.selectedBroker == "JustMarkets" && state.selectedPlatform == PlatformType.MT5 ->
                                    "https://live.justmarkets.com"
                                state.selectedPlatform == PlatformType.MT4 ->
                                    "https://metatraderweb.app/trade"
                                else -> "https://webtrader.yourbroker.com"
                            }
                        )
                    },
                    singleLine = true,
                    isError = showGenericOrEmptyWarning,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("terminal_url_input"),
                    shape = RoundedCornerShape(10.dp),
                )

                if (state.selectedBroker == "JustMarkets") {
                    Spacer(modifier = Modifier.height(6.dp))
                    val jmTargetUrl = if (state.selectedPlatform == PlatformType.MT5) {
                        BrokerCatalog.JUSTMARKETS_MT5_URL
                    } else {
                        BrokerCatalog.METAQUOTES_MT4_URL
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = { onCustomUrlChange(jmTargetUrl) },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (state.customTerminalUrl == jmTargetUrl) extra.accentSoft else extra.surface2,
                                contentColor = MaterialTheme.colorScheme.onSurface,
                            ),
                        ) {
                            Text(
                                if (state.selectedPlatform == PlatformType.MT5) {
                                    "Use JustMarkets MT5 (live.justmarkets.com)"
                                } else {
                                    "Use JustMarkets MT4 (metatraderweb.app)"
                                },
                                fontSize = 10.sp,
                            )
                        }
                    }
                } else if (state.selectedBroker == "MetaQuotes") {
                    Spacer(modifier = Modifier.height(6.dp))
                    val mqUrl = if (state.selectedPlatform == PlatformType.MT5) {
                        BrokerCatalog.METAQUOTES_MT5_URL
                    } else {
                        BrokerCatalog.METAQUOTES_MT4_URL
                    }
                    Button(
                        onClick = { onCustomUrlChange(mqUrl) },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (state.customTerminalUrl == mqUrl) extra.accentSoft else extra.surface2,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                    ) {
                        Text("Fill Official MetaQuotes ${state.selectedPlatform.label} URL", fontSize = 10.sp)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                if (showGenericOrEmptyWarning) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = extra.amberContainer,
                        border = BorderStroke(1.dp, extra.amber),
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = if (urlMismatch) {
                                    "Terminal URL does not match ${state.selectedPlatform.label}"
                                } else {
                                    "Use your broker's own WebTrader URL"
                                },
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = extra.amber,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = when {
                                    effectiveUrl.isBlank() ->
                                        "Enter the exact WebTrader URL from your broker's client area. It is saved per broker for next time."
                                    urlMismatch && state.selectedPlatform == PlatformType.MT4 ->
                                        "https://live.justmarkets.com and trade.metatrader5.com are MT5 terminals. For MT4 use https://metatraderweb.app/trade."
                                    urlMismatch ->
                                        "metatraderweb.app is an MT4 terminal. Switch platform to MT4 or use your broker's MT5 URL (e.g. https://live.justmarkets.com)."
                                    else ->
                                        "Generic MetaQuotes URLs often fail with Network/Authorization errors for third-party brokers. Replace it with your broker's own WebTrader URL."
                                },
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                lineHeight = 14.sp,
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                }

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = extra.surface2,
                    border = BorderStroke(1.dp, extra.line),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "Target Web Terminal:",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = extra.muted,
                        )
                        Text(
                            text = effectiveUrl.ifBlank { "Not set — enter your broker's WebTrader URL above" },
                            fontSize = 11.sp,
                            color = if (effectiveUrl.isBlank()) extra.amber else MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                BtechPrimaryButton(
                    text = if (state.isAuthBusy) "AUTHENTICATING IN WEBVIEW..." else "AUTHENTICATE & SAVE ACCOUNT",
                    onClick = onAuthenticateClick,
                    enabled = !state.isAuthBusy,
                    modifier = Modifier.testTag("authenticate_broker_button"),
                )

                Spacer(modifier = Modifier.height(10.dp))
                BtechSecondaryButton(
                    text = "INSPECT LIVE WEB TERMINAL",
                    onClick = { onInspectWebTerminal(resolvedTerminalUrl) },
                    modifier = Modifier.testTag("inspect_web_terminal_button"),
                )
            }
        }
    }

    if (state.isAuthBusy) {
        val pct = webStatus.progress.coerceIn(0, 100)
        AlertDialog(
            onDismissRequest = onCancelAuth,
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurface,
            title = { Text("Connecting in background...") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(14.dp))
                    LinearProgressIndicator(
                        progress = { (pct / 100f).coerceIn(0.05f, 1f) },
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = extra.surface2,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Loading ($pct%) ${resolvedTerminalUrl ?: "Web Terminal"}...",
                        textAlign = TextAlign.Center,
                        color = extra.muted,
                        fontSize = 12.sp,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = state.lastStatusDetail,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 11.sp,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { onInspectWebTerminal(resolvedTerminalUrl) }) {
                    Text("VIEW LIVE WEBVIEW", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = onCancelAuth,
                    modifier = Modifier.testTag("cancel_auth_button"),
                ) {
                    Text("CANCEL", color = MaterialTheme.colorScheme.primary)
                }
            },
        )
    }

    state.authDialogMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = onDismissAuthResult,
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurface,
            icon = {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(if (state.authDialogSuccess) extra.greenContainer else extra.redContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (state.authDialogSuccess) Icons.Filled.Check else Icons.Filled.Close,
                        contentDescription = null,
                        tint = if (state.authDialogSuccess) extra.green else extra.red,
                    )
                }
            },
            title = {
                Text(
                    text = if (state.authDialogSuccess) "Account Authenticated" else "Authentication Failed",
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(msg, color = extra.muted, fontSize = 12.sp)
                    if (!state.authDialogSuccess && webStatus.currentUrl.isNotBlank() && !msg.contains(webStatus.currentUrl)) {
                        Text(
                            text = "Current URL: ${webStatus.currentUrl}",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 11.sp,
                        )
                    }
                }
            },
            confirmButton = {
                BtechPrimaryButton(
                    text = if (state.authDialogSuccess) "CONTINUE" else "PUT VALID LOGIN DETAILS",
                    onClick = {
                        onDismissAuthResult()
                        if (state.authDialogSuccess) onAuthSuccessContinue()
                    },
                )
            },
            dismissButton = {
                if (!state.authDialogSuccess) {
                    TextButton(
                        onClick = {
                            onDismissAuthResult()
                            onInspectWebTerminal(resolvedTerminalUrl)
                        },
                    ) {
                        Text("Inspect Web Terminal")
                    }
                }
            },
        )
    }
}

@Composable
fun SavedAccountScreen(
    state: AppUiState,
    onChooseBroker: () -> Unit,
    onEditCredentials: () -> Unit,
    onInspectWebTerminal: () -> Unit,
    onToggleBalanceMonitor: () -> Unit = {},
    onRemoveSavedAccount: () -> Unit = {},
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val extra = LocalBtechColors.current
    val account = state.savedAccount
    var showRemoveConfirm by remember { mutableStateOf(false) }

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
            title = "Saved trading account",
            subtitle = "This Web Terminal session is reused automatically when a trade is simulated or received.",
        )

        if (account == null) {
            BtechCard(padding = PaddingValues(24.dp)) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("No saved accounts", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Your authenticated broker account will appear here.",
                        color = extra.muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    BtechPrimaryButton(text = "CHOOSE A BROKER", onClick = onChooseBroker)
                }
            }
        } else {
            BtechCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(extra.surface3),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = account.broker.take(2).uppercase(),
                            fontWeight = FontWeight.Black,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(account.broker, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text("${account.platform.label} trading account", fontSize = 11.sp, color = extra.muted)
                    }
                    BtechStatusPill(
                        text = if (account.authenticated) "AUTHENTICATED" else "UNVERIFIED",
                        containerColor = if (account.authenticated) extra.greenContainer else extra.amberContainer,
                        contentColor = if (account.authenticated) extra.green else extra.amber,
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 14.dp), color = extra.line)

                val items = listOf(
                    "LOGIN" to "••••${account.login.takeLast(4)}",
                    "SERVER" to account.server,
                    "PLATFORM" to account.platform.label,
                    "STATUS" to account.connectionStatus,
                )
                items.chunked(2).forEach { pair ->
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
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text(k, fontSize = 9.sp, color = extra.muted, fontWeight = FontWeight.Bold)
                                    Spacer(modifier = Modifier.height(3.dp))
                                    Text(v, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                BtechPrimaryButton(
                    text = if (state.isBalanceMonitorRunning) "STOP FLOATING BALANCE OVERLAY" else "OPEN FLOATING BALANCE OVERLAY",
                    onClick = onToggleBalanceMonitor,
                    containerColor = if (state.isBalanceMonitorRunning) extra.red else MaterialTheme.colorScheme.primary,
                    contentColor = if (state.isBalanceMonitorRunning) Color.White else MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.testTag("saved_account_balance_overlay_button"),
                )
                Spacer(modifier = Modifier.height(8.dp))
                BtechSecondaryButton(
                    text = "OPEN LIVE WEB TERMINAL",
                    onClick = onInspectWebTerminal,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onEditCredentials) {
                        Icon(Icons.Filled.OpenInBrowser, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Change credentials")
                    }
                    TextButton(
                        onClick = { showRemoveConfirm = true },
                        modifier = Modifier.testTag("remove_saved_account_button"),
                    ) {
                        Icon(
                            Icons.Filled.DeleteOutline,
                            contentDescription = null,
                            tint = extra.red,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Remove account", color = extra.red, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    if (showRemoveConfirm) {
        AlertDialog(
            onDismissRequest = { showRemoveConfirm = false },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurface,
            icon = {
                Icon(
                    imageVector = Icons.Filled.DeleteOutline,
                    contentDescription = null,
                    tint = extra.red,
                )
            },
            title = { Text("Remove saved trading account?", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "This removes your current saved broker login and clears its Web Terminal cookies/session so you can cleanly connect a new account.",
                    color = extra.muted,
                )
            },
            confirmButton = {
                BtechPrimaryButton(
                    text = "REMOVE & USE NEW ACCOUNT",
                    onClick = {
                        showRemoveConfirm = false
                        onRemoveSavedAccount()
                    },
                    containerColor = extra.red,
                    contentColor = Color.White,
                    modifier = Modifier.testTag("confirm_remove_saved_account_button"),
                )
            },
            dismissButton = {
                TextButton(onClick = { showRemoveConfirm = false }) {
                    Text("CANCEL", color = MaterialTheme.colorScheme.primary)
                }
            },
        )
    }
}
