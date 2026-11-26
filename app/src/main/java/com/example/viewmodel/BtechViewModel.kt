package com.example.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.BtechPreferencesRepository
import com.example.model.AllowedSymbolConfig
import com.example.model.BrokerCatalog
import com.example.model.BrokerSymbolResolver
import com.example.model.BubbleGlowStyle
import com.example.model.DiscoveredBrokerCatalog
import com.example.model.ExecutionStepLog
import com.example.model.NavStyle
import com.example.model.PlatformType
import com.example.model.SavedAccount
import com.example.model.SimulatedSignalRequest
import com.example.model.TradeChannelSignal
import com.example.model.TradeDirection
import com.example.service.BtechOverlayService
import com.example.service.FloatingOverlayMonitor
import com.example.service.OverlayActionEvent
import com.example.service.OverlayStateBus
import com.example.webterminal.WebTerminalEngine
import com.example.webterminal.WebTerminalStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class AppUiState(
    val hasRobot: Boolean = false,
    val isRunning: Boolean = false,
    val botName: String = "BTECH SCALPER V2.3",
    val botOwner: String = "Bee Blessings",
    val allowedSymbols: List<AllowedSymbolConfig> = emptyList(),
    val symbolTabIndex: Int = 1, // 0 = All Symbols, 1 = Allowed Symbols
    val editingSymbol: String? = null,
    val selectedBroker: String = "JustMarkets",
    val selectedPlatform: PlatformType = PlatformType.MT5,
    val loginInput: String = "",
    val passwordInput: String = "",
    val selectedServerId: String = "jm-live",
    val mt5ServerInput: String = "JustMarkets-Live",
    val mt4ServerInput: String = "",
    val customTerminalUrl: String = "",
    val savedAccount: SavedAccount? = null,
    val savedAccountsByBroker: Map<String, SavedAccount> = emptyMap(),
    val isAuthBusy: Boolean = false,
    val authDialogSuccess: Boolean = false,
    val authDialogMessage: String? = null,
    val isDarkTheme: Boolean = false,
    val accentHex: String = "#9138EE",
    val customColors: List<String> = List(16) { "" },
    val navStyle: NavStyle = NavStyle.INDICATOR,
    val bubbleGlowStyle: BubbleGlowStyle = BubbleGlowStyle.AUTO,
    val profileName: String = "BTECH Client",
    val showWebInspector: Boolean = false,
    val showOverlayPermissionPrompt: Boolean = false,
    val showSimulateDialog: Boolean = false,
    val isSimulatingTrade: Boolean = false,
    val logs: List<ExecutionStepLog> = emptyList(),
    val lastStatusDetail: String = "Ready — press Start or Simulate Trade to test.",
    val isBalanceMonitorRunning: Boolean = false,
    val showInAppFloatingOverlay: Boolean = false,
    val accountBalance: String = "—",
    val accountEquity: String = "—",
    val accountFloatingPl: String = "—",
    val balanceUpdatedAt: String = "",
    val balanceStatus: String = "Waiting for Web Terminal",
    val discoveredSymbols: List<String> = emptyList(),
    val isDiscoveringSymbols: Boolean = false,
    val discoveredCatalogsByKey: Map<String, DiscoveredBrokerCatalog> = emptyMap(),
    val toastMessage: String? = null,
    val toastIsError: Boolean = false,
)

private enum class PendingOverlayTarget {
    NONE,
    BOT,
    BALANCE_MONITOR,
}

class BtechViewModel(application: Application) : AndroidViewModel(application) {

    private var pendingOverlayTarget = PendingOverlayTarget.NONE

    private val repo = BtechPreferencesRepository(application)
    val webEngine = WebTerminalEngine.getInstance(application)

    private val _uiState = MutableStateFlow(AppUiState())
    val uiState = _uiState.asStateFlow()

    val webStatus: kotlinx.coroutines.flow.StateFlow<WebTerminalStatus> = webEngine.status

    init {
        viewModelScope.launch {
            repo.dataFlow.collectLatest { saved ->
                _uiState.update { state ->
                    state.copy(
                        hasRobot = saved.hasRobot,
                        botName = saved.botName,
                        botOwner = saved.botOwner,
                        allowedSymbols = saved.allowedSymbols,
                        savedAccount = saved.savedAccount,
                        savedAccountsByBroker = saved.savedAccountsByBroker,
                        discoveredCatalogsByKey = saved.discoveredCatalogsByKey,
                        discoveredSymbols = saved.savedAccount?.let { acc ->
                            val key = BrokerSymbolResolver.cacheKey(acc.broker, acc.platform, acc.server)
                            saved.discoveredCatalogsByKey[key]?.symbols.orEmpty()
                        }.orEmpty(),
                        accentHex = saved.accentHex,
                        customColors = saved.customColors,
                        isDarkTheme = saved.isDarkTheme,
                        navStyle = saved.navStyle,
                        bubbleGlowStyle = saved.bubbleGlowStyle,
                        profileName = saved.profileName,
                        loginInput = state.loginInput.ifEmpty {
                            saved.savedAccountsByBroker[state.selectedBroker]?.login ?: ""
                        },
                        customTerminalUrl = state.customTerminalUrl.ifEmpty {
                            saved.savedAccountsByBroker[state.selectedBroker]?.terminalUrl ?: ""
                        },
                    )
                }
                syncOverlayMetadata()
            }
        }

        viewModelScope.launch {
            OverlayStateBus.snapshot.collectLatest { snap ->
                _uiState.update {
                    it.copy(
                        isRunning = snap.isRunning,
                        isBalanceMonitorRunning = snap.isBalanceMonitorRunning,
                        logs = snap.logs,
                        isSimulatingTrade = snap.isExecuting,
                        lastStatusDetail = snap.statusDetail,
                        accountBalance = snap.accountBalance,
                        accountEquity = snap.accountEquity,
                        accountFloatingPl = snap.accountFloatingPl,
                        balanceUpdatedAt = snap.balanceUpdatedAt,
                        balanceStatus = snap.balanceStatus,
                    )
                }
            }
        }

        viewModelScope.launch {
            OverlayStateBus.actions.collect { event ->
                when (event) {
                    is OverlayActionEvent.StopBotRequested -> stopBot()
                    is OverlayActionEvent.StopBalanceMonitorRequested -> stopFloatingBalanceMonitor()
                    is OverlayActionEvent.RefreshBalanceRequested -> webEngine.pollBalanceOnce()
                    is OverlayActionEvent.SkipTradingChanged -> {
                        if (event.skipTrading) {
                            webEngine.cancelActiveTradeRun(
                                reason = "Skip Trades activated — pending discovery and queued trades cancelled.",
                                markAsStopped = false,
                            )
                        }
                    }
                    is OverlayActionEvent.SimulateTradeRequested -> {
                        val firstSymbol = _uiState.value.allowedSymbols.firstOrNull { it.live }
                            ?: _uiState.value.allowedSymbols.firstOrNull()
                        if (firstSymbol == null) {
                            showToast("Choose an allowed symbol first", isError = true)
                            OverlayStateBus.appendLog("Simulation skipped: no allowed symbol configured.", isError = true)
                        } else {
                            val dir = if (firstSymbol.direction == TradeDirection.SELL) TradeDirection.SELL else TradeDirection.BUY
                            executeSimulatedTrade(
                                SimulatedSignalRequest(
                                    symbol = firstSymbol.symbol,
                                    direction = dir,
                                    lot = firstSymbol.lot,
                                    stopLoss = "",
                                    takeProfit = "",
                                    comment = "BTECH-EA",
                                    tradeCount = firstSymbol.trades.toIntOrNull()?.coerceIn(1, 10) ?: 1,
                                    platform = _uiState.value.savedAccount?.platform ?: firstSymbol.platform,
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    private fun syncOverlayMetadata() {
        val state = _uiState.value
        val account = state.savedAccount
        OverlayStateBus.updateSnapshot {
            it.copy(
                botName = state.botName,
                botOwner = state.botOwner,
                serverName = account?.server ?: "Not connected",
                accentHex = state.accentHex,
                isConnected = account?.authenticated == true,
                isDarkTheme = state.isDarkTheme,
                bubbleGlowStyle = state.bubbleGlowStyle,
            )
        }
    }

    fun showToast(message: String, isError: Boolean = false) {
        _uiState.update { it.copy(toastMessage = message, toastIsError = isError) }
    }

    fun dismissToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }

    fun authenticateRobot(key: String, onSuccess: () -> Unit) {
        val trimmed = key.trim()
        if (trimmed.length < 8) {
            showToast("Please enter a valid licence key (8+ characters)", isError = true)
            return
        }
        viewModelScope.launch {
            repo.setRobotConnected(true, trimmed)
            OverlayStateBus.appendLog("EA Licence authenticated: ${trimmed.take(4)}****", isSuccess = true)
            onSuccess()
        }
    }

    fun removeRobot(onRemoved: () -> Unit) {
        stopBot()
        viewModelScope.launch {
            repo.setRobotConnected(false)
            showToast("Robot disconnected and removed")
            onRemoved()
        }
    }

    fun setSymbolTab(index: Int) {
        _uiState.update { it.copy(symbolTabIndex = index) }
    }

    fun startEditingSymbol(symbol: String) {
        _uiState.update { it.copy(editingSymbol = symbol) }
    }

    fun saveSymbolConfig(config: AllowedSymbolConfig, onDone: () -> Unit) {
        val lotVal = config.lot.toDoubleOrNull()
        val tradesVal = config.trades.toIntOrNull()
        if (lotVal == null || lotVal <= 0.0 || tradesVal == null || tradesVal < 1) {
            showToast("Check lot volume (>0) and number of trades (>=1)", isError = true)
            return
        }
        viewModelScope.launch {
            val current = _uiState.value.allowedSymbols.toMutableList()
            val idx = current.indexOfFirst { it.symbol == config.symbol }
            val updated = if (idx >= 0) {
                current[idx] = config
                true
            } else {
                current.add(config)
                false
            }
            repo.saveAllowedSymbols(current)
            _uiState.update { it.copy(symbolTabIndex = 1) }
            showToast("${config.symbol} ${if (updated) "updated" else "added to Allowed Symbols"}")
            onDone()
        }
    }

    fun removeAllowedSymbol(symbol: String) {
        viewModelScope.launch {
            val updated = _uiState.value.allowedSymbols.filterNot { it.symbol == symbol }
            repo.saveAllowedSymbols(updated)
            showToast("$symbol removed from Allowed Symbols")
        }
    }

    fun selectBroker(brokerName: String) {
        val broker = BrokerCatalog.findBroker(brokerName)
        val savedForBroker = _uiState.value.savedAccountsByBroker[brokerName]
        val defaultServer = broker?.mt5Servers?.firstOrNull()
        val chosenPlatform = savedForBroker?.platform ?: PlatformType.MT5
        val prefilledUrl = savedForBroker?.terminalUrl?.takeIf { it.isNotBlank() }
            ?: if (chosenPlatform == PlatformType.MT5) {
                defaultServer?.terminalUrl.orEmpty()
            } else {
                broker?.defaultMt4TerminalUrl.orEmpty()
            }
        _uiState.update {
            it.copy(
                selectedBroker = brokerName,
                selectedPlatform = chosenPlatform,
                selectedServerId = savedForBroker?.serverId?.ifEmpty { defaultServer?.id ?: "" }
                    ?: (defaultServer?.id ?: ""),
                mt5ServerInput = if (chosenPlatform == PlatformType.MT5) {
                    savedForBroker?.server?.ifEmpty { defaultServer?.name ?: "" } ?: (defaultServer?.name ?: "")
                } else {
                    defaultServer?.name ?: ""
                },
                mt4ServerInput = if (chosenPlatform == PlatformType.MT4) {
                    savedForBroker?.server.orEmpty()
                } else {
                    ""
                },
                loginInput = savedForBroker?.login.orEmpty(),
                passwordInput = savedForBroker?.password.orEmpty(),
                customTerminalUrl = prefilledUrl,
            )
        }
    }

    fun setPlatform(platform: PlatformType) {
        _uiState.update { state ->
            val broker = BrokerCatalog.findBroker(state.selectedBroker)
            val savedForBroker = state.savedAccountsByBroker[state.selectedBroker]
            val firstMt5 = broker?.mt5Servers?.firstOrNull()
            val serverId = if (platform == PlatformType.MT5) {
                firstMt5?.id ?: ""
            } else {
                ""
            }
            val nextUrl = if (savedForBroker != null && savedForBroker.platform == platform && savedForBroker.terminalUrl.isNotBlank()) {
                savedForBroker.terminalUrl
            } else if (platform == PlatformType.MT5) {
                firstMt5?.terminalUrl.orEmpty()
            } else {
                broker?.defaultMt4TerminalUrl.orEmpty()
            }
            state.copy(
                selectedPlatform = platform,
                selectedServerId = serverId,
                mt5ServerInput = if (platform == PlatformType.MT5) firstMt5?.name ?: "" else state.mt5ServerInput,
                customTerminalUrl = nextUrl,
            )
        }
    }

    fun setLoginInput(v: String) = _uiState.update { it.copy(loginInput = v) }
    fun setPasswordInput(v: String) = _uiState.update { it.copy(passwordInput = v) }
    fun setSelectedServerId(id: String) {
        _uiState.update { state ->
            val broker = BrokerCatalog.findBroker(state.selectedBroker)
            val srv = broker?.mt5Servers?.firstOrNull { it.id == id }
            val nextUrl = state.customTerminalUrl.ifBlank { srv?.terminalUrl.orEmpty() }
            state.copy(
                selectedServerId = id,
                mt5ServerInput = srv?.name ?: state.mt5ServerInput,
                customTerminalUrl = nextUrl,
            )
        }
    }
    fun setMt5ServerInput(v: String) = _uiState.update { it.copy(mt5ServerInput = v) }
    fun setMt4ServerInput(v: String) = _uiState.update { it.copy(mt4ServerInput = v) }
    fun setCustomTerminalUrl(v: String) = _uiState.update { it.copy(customTerminalUrl = v) }

    fun resolvedTerminalInfo(): Triple<String, String, String>? {
        val s = _uiState.value
        val broker = BrokerCatalog.findBroker(s.selectedBroker)
        return if (s.selectedPlatform == PlatformType.MT5) {
            val srv = broker?.mt5Servers?.firstOrNull { it.id == s.selectedServerId }
                ?: broker?.mt5Servers?.firstOrNull()
                ?: return null
            val url = s.customTerminalUrl.trim().ifEmpty { srv.terminalUrl }
            val resolvedServerName = s.mt5ServerInput.trim().ifEmpty { srv.name }
            Triple(srv.id, resolvedServerName, url)
        } else {
            val mt4Srv = s.mt4ServerInput.trim()
            if (mt4Srv.isEmpty()) return null
            val url = s.customTerminalUrl.trim().ifEmpty { broker?.defaultMt4TerminalUrl.orEmpty() }
            Triple("mt4-custom", mt4Srv, url)
        }
    }

    fun authenticateBrokerAccount() {
        val s = _uiState.value
        if (s.isAuthBusy) return
        val login = s.loginInput.trim()
        val password = s.passwordInput
        if (login.isEmpty() || password.isEmpty()) {
            showToast("Enter your account login and trading password", isError = true)
            return
        }
        val target = resolvedTerminalInfo()
        if (target == null) {
            showToast(
                if (s.selectedPlatform == PlatformType.MT5) "Select an MT5 server first"
                else "Enter the exact MT4 server name",
                isError = true,
            )
            return
        }
        val (serverId, serverName, rawUrl) = target
        val terminalUrl = rawUrl.trim()
        if (terminalUrl.isEmpty()) {
            showToast("Use your broker's own WebTrader URL", isError = true)
            return
        }
        if (BrokerCatalog.isUrlPlatformMismatch(terminalUrl, s.selectedPlatform)) {
            val mismatchHint = if (s.selectedPlatform == PlatformType.MT4) {
                "$terminalUrl is an MT5 terminal. Select MT5 or use https://metatraderweb.app/trade for MT4."
            } else {
                "$terminalUrl is an MT4 terminal. Select MT4 or use your broker's MT5 URL (e.g. https://live.justmarkets.com)."
            }
            showToast(mismatchHint, isError = true)
            _uiState.update {
                it.copy(authDialogSuccess = false, authDialogMessage = mismatchHint)
            }
            return
        }
        val normalizedUrl = BrokerCatalog.buildLaunchUrl(
            rawUrl = terminalUrl,
            platform = s.selectedPlatform,
            login = login,
            server = serverName,
        )

        val existing = s.savedAccount
        if (existing != null) {
            if (_uiState.value.isRunning) stopBot()
            if (_uiState.value.isBalanceMonitorRunning) stopFloatingBalanceMonitor()
            viewModelScope.launch {
                repo.saveAccount(null)
            }
            OverlayStateBus.updateSnapshot {
                it.copy(
                    isConnected = false,
                    serverName = "Not connected",
                    accountBalance = "—",
                    accountEquity = "—",
                    accountFloatingPl = "—",
                    accountMargin = "—",
                    balanceUpdatedAt = "",
                    balanceStatus = "Switching to new account...",
                )
            }
            OverlayStateBus.appendLog("Removed previous saved account (${existing.broker} · ${existing.login}) before new login")
        }

        _uiState.update { it.copy(isAuthBusy = true, authDialogMessage = null) }

        webEngine.authenticateInBackground(
            terminalUrl = normalizedUrl,
            login = login,
            password = password,
            server = serverName,
            platform = s.selectedPlatform,
            clearPreviousSession = true,
        ) { success, message ->
            val now = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            val account = SavedAccount(
                broker = s.selectedBroker,
                platform = s.selectedPlatform,
                login = login,
                password = if (success) password else "",
                server = serverName,
                serverId = serverId,
                terminalUrl = normalizedUrl,
                authenticated = success,
                connectionStatus = if (success) "Authenticated on Web Terminal" else "Authentication Failed",
                lastCheckedAt = now,
            )
            viewModelScope.launch {
                if (success) {
                    repo.saveAccount(account)
                }
                _uiState.update {
                    it.copy(
                        isAuthBusy = false,
                        authDialogSuccess = success,
                        authDialogMessage = message,
                    )
                }
                syncOverlayMetadata()
            }
        }
    }

    fun removeSavedAccount(clearInputs: Boolean = true, showFeedback: Boolean = true) {
        val current = _uiState.value
        if (current.isRunning) stopBot()
        if (current.isBalanceMonitorRunning) stopFloatingBalanceMonitor()
        viewModelScope.launch {
            repo.saveAccount(null)
        }
        webEngine.resetSession()
        if (clearInputs) {
            val broker = BrokerCatalog.findBroker(current.selectedBroker)
            val defaultServer = broker?.mt5Servers?.firstOrNull()
            val defaultUrl = if (current.selectedPlatform == PlatformType.MT5) {
                defaultServer?.terminalUrl.orEmpty()
            } else {
                broker?.defaultMt4TerminalUrl.orEmpty()
            }
            _uiState.update {
                it.copy(
                    savedAccount = null,
                    savedAccountsByBroker = emptyMap(),
                    loginInput = "",
                    passwordInput = "",
                    mt4ServerInput = "",
                    mt5ServerInput = if (it.selectedPlatform == PlatformType.MT5) defaultServer?.name ?: "" else it.mt5ServerInput,
                    customTerminalUrl = defaultUrl,
                )
            }
        }
        syncOverlayMetadata()
        if (showFeedback) {
            showToast("Previous saved account removed — ready for a new login")
        }
    }

    fun dismissAuthDialog() {
        _uiState.update { it.copy(authDialogMessage = null) }
    }

    fun cancelAuthBusy() {
        webEngine.cancelAuthentication()
        _uiState.update { it.copy(isAuthBusy = false) }
    }

    fun confirmManualWebSession() {
        val s = _uiState.value
        val target = resolvedTerminalInfo()
        val sameBrokerSaved = s.savedAccount?.takeIf { it.broker == s.selectedBroker }
        val login = s.loginInput.trim().ifEmpty { sameBrokerSaved?.login ?: "WebSession" }
        val serverName = target?.second ?: sameBrokerSaved?.server ?: s.selectedBroker
        val serverId = target?.first ?: sameBrokerSaved?.serverId ?: "web-session"
        val url = webEngine.status.value.currentUrl.ifBlank {
            target?.third?.ifBlank { null } ?: sameBrokerSaved?.terminalUrl.orEmpty()
        }
        val now = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val account = SavedAccount(
            broker = s.selectedBroker,
            platform = s.selectedPlatform,
            login = login,
            password = s.passwordInput,
            server = serverName,
            serverId = serverId,
            terminalUrl = url,
            authenticated = true,
            connectionStatus = "Active WebView Session",
            lastCheckedAt = now,
        )
        viewModelScope.launch {
            repo.saveAccount(account)
            val safeUrl = BrokerCatalog.sanitizeTerminalUrlForDisplay(url)
            OverlayStateBus.appendLog("Saved active WebView session for $serverName ($safeUrl)", isSuccess = true)
            _uiState.update {
                it.copy(
                    isAuthBusy = false,
                    authDialogMessage = null,
                    showWebInspector = false,
                    customTerminalUrl = url,
                )
            }
            webEngine.setInspectMode(false)
            syncOverlayMetadata()
            showToast("Web Terminal session saved ($serverName)")
        }
    }

    fun openWebInspector(customUrl: String? = null) {
        val explicitUrl = customUrl?.trim()?.takeIf { it.isNotBlank() }
        val curStatus = webEngine.status.value
        val hasExistingPage = webEngine.hasWebView() &&
            curStatus.currentUrl.isNotBlank() &&
            !curStatus.currentUrl.equals("about:blank", ignoreCase = true) &&
            curStatus.lastError == null

        val targetUrl = explicitUrl ?: if (hasExistingPage) {
            null
        } else {
            _uiState.value.customTerminalUrl.takeIf { it.isNotBlank() }
                ?: _uiState.value.savedAccount?.terminalUrl?.takeIf { it.isNotBlank() }
                ?: resolvedTerminalInfo()?.third?.takeIf { it.isNotBlank() }
        }

        webEngine.setInspectMode(true)
        webEngine.loadUrlForInspection(targetUrl, forceReload = false)
        _uiState.update { it.copy(showWebInspector = true) }
    }

    fun closeWebInspector() {
        webEngine.setInspectMode(false)
        _uiState.update { it.copy(showWebInspector = false) }
    }

    fun toggleBot() {
        val state = _uiState.value
        if (state.isRunning) {
            stopBot()
            return
        }

        if (state.allowedSymbols.isEmpty()) {
            showToast("Choose at least one Allowed Symbol first", isError = true)
            return
        }
        val account = state.savedAccount
        if (account == null || account.login.isBlank()) {
            showToast("Please enter and authenticate your broker login details first", isError = true)
            return
        }

        val context = getApplication<Application>()
        if (!BtechOverlayService.canDrawOverlays(context)) {
            pendingOverlayTarget = PendingOverlayTarget.BOT
            _uiState.update { it.copy(showOverlayPermissionPrompt = true) }
            return
        }

        startOverlayServiceInternal()
    }

    fun toggleFloatingBalanceMonitor() {
        val state = _uiState.value
        if (state.isBalanceMonitorRunning) {
            stopFloatingBalanceMonitor()
            return
        }
        val context = getApplication<Application>()
        if (!FloatingOverlayMonitor.canDrawOverlays(context)) {
            pendingOverlayTarget = PendingOverlayTarget.BALANCE_MONITOR
            _uiState.update { it.copy(showOverlayPermissionPrompt = true) }
            return
        }
        startFloatingBalanceMonitorInternal()
    }

    fun dismissOverlayPermissionPrompt() {
        pendingOverlayTarget = PendingOverlayTarget.NONE
        _uiState.update { it.copy(showOverlayPermissionPrompt = false) }
    }

    fun enableInAppFloatingFallback() {
        val target = pendingOverlayTarget
        pendingOverlayTarget = PendingOverlayTarget.NONE
        val account = _uiState.value.savedAccount
        OverlayStateBus.updateSnapshot {
            it.copy(
                isRunning = if (target == PendingOverlayTarget.BOT) true else it.isRunning,
                botName = _uiState.value.botName,
                botOwner = _uiState.value.botOwner,
                serverName = account?.server ?: "Demo",
                isConnected = account?.authenticated == true,
                statusTitle = "Trade Channel monitor",
                statusDetail = "In-app floating monitor enabled",
                statusPill = "LIVE",
            )
        }
        webEngine.startBalancePolling(account)
        _uiState.update {
            it.copy(
                showOverlayPermissionPrompt = false,
                showInAppFloatingOverlay = true,
            )
        }
        showToast("Floating bubble enabled inside app")
    }

    fun dismissInAppFloatingOverlay() {
        val snap = OverlayStateBus.snapshot.value
        if (!snap.isRunning && !snap.isBalanceMonitorRunning) {
            webEngine.stopBalancePolling()
        }
        _uiState.update { it.copy(showInAppFloatingOverlay = false) }
    }

    fun onOverlayPermissionReturned() {
        val target = pendingOverlayTarget
        pendingOverlayTarget = PendingOverlayTarget.NONE
        _uiState.update { it.copy(showOverlayPermissionPrompt = false) }
        val context = getApplication<Application>()
        if (BtechOverlayService.canDrawOverlays(context)) {
            if (target == PendingOverlayTarget.BALANCE_MONITOR) {
                startFloatingBalanceMonitorInternal()
            } else {
                startOverlayServiceInternal()
            }
        } else {
            showToast("Display over other apps permission is required for overlay over other apps", isError = true)
        }
    }

    private fun startFloatingBalanceMonitorInternal() {
        val account = _uiState.value.savedAccount
        OverlayStateBus.updateSnapshot {
            it.copy(
                isBalanceMonitorRunning = true,
                botName = _uiState.value.botName,
                botOwner = _uiState.value.botOwner,
                serverName = account?.server ?: it.serverName,
                isConnected = account?.authenticated == true || it.isConnected,
            )
        }
        OverlayStateBus.appendLog("Floating balance monitor started", isSuccess = true)
        FloatingOverlayMonitor.start(getApplication())
        webEngine.startBalancePolling(account)
        showToast("Floating balance monitor enabled")
    }

    fun stopFloatingBalanceMonitor() {
        FloatingOverlayMonitor.stop(getApplication())
        val stillRunningBot = OverlayStateBus.snapshot.value.isRunning
        if (!stillRunningBot) {
            webEngine.stopBalancePolling()
        }
        OverlayStateBus.updateSnapshot {
            it.copy(isBalanceMonitorRunning = false)
        }
        OverlayStateBus.appendLog("Floating balance monitor stopped")
        showToast("Balance monitor stopped")
    }

    private fun startOverlayServiceInternal() {
        val account = _uiState.value.savedAccount
        OverlayStateBus.updateSnapshot {
            it.copy(
                isRunning = true,
                botName = _uiState.value.botName,
                botOwner = _uiState.value.botOwner,
                serverName = account?.server ?: "Demo",
                isConnected = account?.authenticated == true,
                statusTitle = "Trade Channel monitor",
                statusDetail = "Robot active — floating bubble enabled over other apps.",
                statusPill = "LIVE",
            )
        }
        OverlayStateBus.appendLog("Robot started — floating overlay active", isSuccess = true)
        BtechOverlayService.start(getApplication())
        webEngine.startBalancePolling(account)
        _uiState.update { it.copy(showInAppFloatingOverlay = false) }
        showToast("Robot started — floating monitor enabled")
    }

    fun stopBot() {
        webEngine.cancelActiveTradeRun(
            reason = "Robot stopped (queued/pending executions cancelled; already submitted broker orders are unaffected).",
            markAsStopped = true,
        )
        BtechOverlayService.stop(getApplication())
        val keepPolling = OverlayStateBus.snapshot.value.isBalanceMonitorRunning
        if (!keepPolling) {
            webEngine.stopBalancePolling()
        }
        OverlayStateBus.updateSnapshot {
            it.copy(
                isRunning = false,
                isExecuting = false,
                statusPill = "STOPPED",
                statusDetail = "Robot stopped.",
            )
        }
        _uiState.update { it.copy(showInAppFloatingOverlay = false) }
        OverlayStateBus.appendLog("Robot stopped — pending discovery and queued trades cancelled")
        showToast("Robot stopped")
    }

    fun openSimulateDialog() {
        val state = _uiState.value
        if (state.allowedSymbols.isEmpty()) {
            showToast("Add at least one symbol in Symbols first", isError = true)
            return
        }
        val account = state.savedAccount
        if (account == null || account.login.isBlank()) {
            showToast("Set and authenticate your broker login in Meta first", isError = true)
            return
        }
        _uiState.update { it.copy(showSimulateDialog = true) }
    }

    fun closeSimulateDialog() {
        _uiState.update { it.copy(showSimulateDialog = false) }
    }

    fun executeSimulatedTrade(request: SimulatedSignalRequest) {
        val state = _uiState.value
        val account = state.savedAccount
        if (account == null || account.login.isBlank()) {
            showToast("No broker login details saved", isError = true)
            return
        }
        if (OverlayStateBus.snapshot.value.skipTrading) {
            OverlayStateBus.appendLog("Signal ignored because SKIP TRADES is active", isError = true)
            showToast("Resume trading in the floating monitor first", isError = true)
            return
        }

        _uiState.update { it.copy(showSimulateDialog = false) }
        webEngine.executeSimulatedTrade(
            account = account,
            signal = request.copy(platform = request.platform ?: account.platform),
        ) { success, summary ->
            showToast(summary, isError = !success)
        }
    }

    fun executeTradeChannelSignal(rawJson: String): Boolean {
        val signal = TradeChannelSignal.fromJson(rawJson) ?: run {
            OverlayStateBus.appendLog("Rejected malformed Trade Channel payload", isError = true)
            return false
        }
        val state = _uiState.value
        val account = state.savedAccount
        if (account == null || !account.authenticated || account.login.isBlank()) {
            OverlayStateBus.appendLog("Signal ${signal.signalId} rejected: no authenticated broker account", isError = true)
            return false
        }
        if (!state.isRunning || OverlayStateBus.snapshot.value.skipTrading) {
            OverlayStateBus.appendLog("Signal ${signal.signalId} skipped: robot stopped or Skip Trades active", isError = true)
            return false
        }
        if (signal.platform != account.platform) {
            OverlayStateBus.appendLog(
                "Signal ${signal.signalId} skipped: expects ${signal.platform.label}, active account is ${account.platform.label}",
                isError = true,
            )
            return false
        }
        val authorizedConfig = state.allowedSymbols.firstOrNull { cfg ->
            cfg.platform == signal.platform &&
                cfg.live &&
                BrokerSymbolResolver.matchRank(cfg.symbol, signal.symbol) > 0 &&
                (cfg.direction == TradeDirection.BOTH || cfg.direction == signal.direction)
        }
        if (authorizedConfig == null) {
            OverlayStateBus.appendLog(
                "Signal ${signal.signalId} skipped: ${signal.symbol} (${signal.platform.label} ${signal.direction.label}) is not enabled in Allowed Symbols.",
                isError = true,
            )
            return false
        }
        executeSimulatedTrade(signal.toSignalRequest())
        return true
    }

    fun refreshBrokerSymbols(targetSymbol: String = "XAUUSD") {
        val account = _uiState.value.savedAccount
        if (account == null || account.login.isBlank()) {
            showToast("Connect a broker account in Meta first", isError = true)
            return
        }
        _uiState.update { it.copy(isDiscoveringSymbols = true) }
        webEngine.discoverSymbolsOnTerminal(account, targetSymbol) { res ->
            _uiState.update { cur ->
                val merged = if (res.discoveredSymbols.isNotEmpty()) res.discoveredSymbols else cur.discoveredSymbols
                cur.copy(isDiscoveringSymbols = false, discoveredSymbols = merged)
            }
            if (res.discoveredSymbols.isNotEmpty()) {
                showToast("Discovered ${res.discoveredSymbols.size} symbols on ${account.platform.label}")
            } else {
                showToast(res.message.ifBlank { "No symbols read yet — open Web Terminal workspace first" }, isError = !res.success)
            }
        }
    }

    fun setDarkTheme(isDark: Boolean) {
        viewModelScope.launch { repo.setTheme(isDark) }
    }

    fun setAccentHex(hex: String) {
        viewModelScope.launch { repo.setAccent(hex) }
    }

    fun saveCustomColors(colors: List<String>) {
        viewModelScope.launch { repo.saveCustomColors(colors) }
    }

    fun setNavStyle(style: NavStyle) {
        viewModelScope.launch { repo.setNavStyle(style) }
        showToast("Navigation style updated")
    }

    fun setBubbleGlowStyle(style: BubbleGlowStyle) {
        viewModelScope.launch { repo.setBubbleGlowStyle(style) }
        OverlayStateBus.updateSnapshot { it.copy(bubbleGlowStyle = style) }
        showToast("Bubble glow: ${style.label}")
    }

    fun setProfileName(name: String) {
        val trimmed = name.trim()
        if (trimmed.isNotEmpty()) {
            viewModelScope.launch { repo.setProfileName(trimmed) }
        }
    }
}
