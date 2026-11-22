package com.example.webterminal

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.net.http.SslError
import android.os.Message
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.example.BuildConfig
import com.example.data.BtechPreferencesRepository
import com.example.model.BrokerCatalog
import com.example.model.BrokerResultStatus
import com.example.model.BrokerSymbolResolver
import com.example.model.BrokerTradeResult
import com.example.model.PlatformType
import com.example.model.SavedAccount
import com.example.model.SimulatedSignalRequest
import com.example.model.SymbolDiscoveryResult
import com.example.model.TradeDirection
import com.example.service.ExecutionStepId
import com.example.service.ExecutionStepStatus
import com.example.service.OverlayStateBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import android.os.SystemClock
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class WatchdogFailureCategory {
    NONE,
    WEBVIEW_NOT_ATTACHED,
    PAGE_STILL_LOADING,
    AUTH_INCOMPLETE,
    JS_NOT_RESPONDING,
    DOM_NOT_READY,
    SYMBOL_SEARCH_RUNNING,
    SYMBOL_GENUINELY_NOT_FOUND,
    ORDER_TICKET_NOT_FOUND,
    BROKER_RESPONSE_PENDING,
    EXECUTION_TIMED_OUT,
}

data class WebTerminalHeartbeat(
    val webviewAlive: Boolean = false,
    val javascriptRunning: Boolean = false,
    val terminalLoaded: Boolean = false,
    val authenticated: Boolean = false,
    val domReady: Boolean = false,
    val symbolDiscoveryRunning: Boolean = false,
    val orderTicketOpen: Boolean = false,
    val currentStage: String = "IDLE",
    val runId: Long = 0L,
    val lastHeartbeat: Long = 0L,
    val platform: String = "",
    val currentUrl: String = "",
) {
    fun toStructuredJson(): JSONObject = JSONObject().apply {
        put("webview_alive", webviewAlive)
        put("javascript_running", javascriptRunning)
        put("terminal_loaded", terminalLoaded)
        put("authenticated", authenticated)
        put("dom_ready", domReady)
        put("symbol_discovery_running", symbolDiscoveryRunning)
        put("order_ticket_open", orderTicketOpen)
        put("current_stage", currentStage)
        put("run_id", runId)
        put("last_heartbeat", lastHeartbeat)
    }
}

data class DesktopViewportDiagnostics(
    val innerWidth: Int = 0,
    val clientWidth: Int = 0,
    val visualViewportWidth: Int = 0,
    val innerHeight: Int = 0,
    val metaViewport: String = "",
    val userAgent: String = "",
    val url: String = "",
    val targetWidth: Int = WebTerminalEngine.DEFAULT_DESKTOP_VIEWPORT_WIDTH,
    val viewportEnforced: Boolean = false,
    val layoutIsDesktopWidth: Boolean = false,
    val documentStartSupported: Boolean = false,
    val fallbackApplied: Boolean = false,
    val measuredAtMs: Long = 0L,
)

data class WebTerminalStatus(
    val currentUrl: String = "",
    val pageTitle: String = "",
    val progress: Int = 0,
    val isLoading: Boolean = false,
    val isPageReady: Boolean = false,
    val isDesktopMode: Boolean = true,
    val desktopViewportWidth: Int = WebTerminalEngine.DEFAULT_DESKTOP_VIEWPORT_WIDTH,
    val viewportDiagnostics: DesktopViewportDiagnostics? = null,
    val lastError: String? = null,
)

class WebTerminalEngine private constructor(private val appContext: Context) {

    companion object {
        const val DEFAULT_DESKTOP_VIEWPORT_WIDTH = 1366
        const val MIN_DESKTOP_VIEWPORT_WIDTH = 1024
        const val MAX_DESKTOP_VIEWPORT_WIDTH = 2560
        const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

        private const val AUTH_TIMEOUT_MS = 60_000L
        private const val DOM_FALLBACK_MS = 8_000L
        private const val TRADE_TIMEOUT_MS = 60_000L
        private const val PHASE_STALL_TIMEOUT_MS = 28_000L
        private const val SYMBOL_GRACE_EXTENSION_MS = 10_000L
        private const val BALANCE_POLL_INTERVAL_MS = 2_500L
        private const val HEARTBEAT_INTERVAL_MS = 4_000L
        private const val HEARTBEAT_PROBE_TIMEOUT_MS = 2_000L

        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var INSTANCE: WebTerminalEngine? = null

        fun getInstance(context: Context): WebTerminalEngine {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: WebTerminalEngine(context.applicationContext).also { INSTANCE = it }
            }
        }

        internal fun extractOriginRule(rawUrl: String?): String? {
            val trimmed = rawUrl?.trim().orEmpty()
            if (trimmed.isEmpty() || trimmed.equals("about:blank", ignoreCase = true) || trimmed.contains("*")) {
                return null
            }
            val normalized = if (
                trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true)
            ) {
                trimmed
            } else {
                "https://$trimmed"
            }
            return runCatching {
                val uri = java.net.URI(normalized)
                val scheme = uri.scheme?.lowercase(Locale.US) ?: return@runCatching null
                if (scheme != "http" && scheme != "https") return@runCatching null
                val host = uri.host?.lowercase(Locale.US)?.takeIf { it.isNotBlank() && !it.contains("*") }
                    ?: return@runCatching null
                val port = if (uri.port != -1) ":${uri.port}" else ""
                "$scheme://$host$port"
            }.getOrNull()
        }

        internal fun buildAllowedTerminalOriginRules(extraUrls: Collection<String> = emptyList()): Set<String> {
            val seeds = buildList {
                add(BrokerCatalog.METAQUOTES_MT5_URL)
                add(BrokerCatalog.METAQUOTES_MT4_URL)
                add(BrokerCatalog.JUSTMARKETS_MT5_URL)
                add(BrokerCatalog.JUSTMARKETS_MT4_WEB_URL)
                add("https://trade.mql5.com")
                add("https://mt5trial9.exwebterm.com")
                BrokerCatalog.brokers.forEach { broker ->
                    broker.defaultMt4TerminalUrl.takeIf { it.isNotBlank() }?.let { add(it) }
                    broker.mt5Servers.forEach { srv ->
                        srv.terminalUrl.takeIf { it.isNotBlank() }?.let { add(it) }
                    }
                }
                addAll(extraUrls)
            }
            return seeds.mapNotNull { extractOriginRule(it) }
                .filter { it != "*" && !it.contains("*") }
                .toCollection(linkedSetOf())
        }

        internal fun normalizeTerminalUrl(rawUrl: String?): String {
            val trimmed = rawUrl?.trim().orEmpty()
            if (trimmed.isEmpty()) return ""
            return runCatching {
                val uri = android.net.Uri.parse(trimmed)
                val scheme = (uri.scheme ?: "https").lowercase(Locale.US)
                val host = (uri.host ?: "").lowercase(Locale.US)
                val port = if (uri.port != -1) ":${uri.port}" else ""
                val rawPath = uri.path ?: ""
                val cleanPath = if (rawPath == "/") "" else rawPath.trimEnd('/')
                val query = uri.query?.takeIf { it.isNotBlank() }?.let { "?$it" } ?: ""
                if (host.isNotEmpty()) "$scheme://$host$port$cleanPath$query" else trimmed.trimEnd('/')
            }.getOrElse { trimmed.trimEnd('/') }
        }

        internal fun shouldNavigateForInspection(
            currentWebViewUrl: String?,
            lastRequestedUrl: String?,
            targetUrl: String?,
            lastError: String?,
            forceReload: Boolean,
        ): Boolean {
            val target = targetUrl?.trim().orEmpty()
            if (forceReload) return target.isNotEmpty()
            val cur = currentWebViewUrl?.trim().orEmpty()
            if (cur.isEmpty() || cur.equals("about:blank", ignoreCase = true)) {
                return target.isNotEmpty()
            }
            if (!lastError.isNullOrBlank()) {
                return target.isNotEmpty()
            }
            if (target.isEmpty()) {
                return false
            }
            val normCur = normalizeTerminalUrl(cur)
            val normTarget = normalizeTerminalUrl(target)
            if (normCur == normTarget) return false
            val normLast = normalizeTerminalUrl(lastRequestedUrl)
            if (normLast.isNotEmpty() && normLast == normTarget) {
                // Already navigated from targetUrl into redirected live terminal URL (e.g. /terminal)
                return false
            }
            return true
        }

        internal fun classifyWatchdogStall(
            activeStepId: ExecutionStepId,
            webViewPresent: Boolean,
            webViewAttached: Boolean,
            isLoading: Boolean,
            currentUrl: String?,
            jsResponded: Boolean,
            heartbeat: WebTerminalHeartbeat,
            symbolName: String,
            platformLabel: String,
        ): Pair<WatchdogFailureCategory, String> {
            if (!webViewPresent || !webViewAttached) {
                return WatchdogFailureCategory.WEBVIEW_NOT_ATTACHED to
                    "WebView is not attached to an active Android window ($platformLabel) during ${activeStepId.label}."
            }
            if (isLoading || currentUrl.isNullOrBlank() || currentUrl.equals("about:blank", ignoreCase = true)) {
                return WatchdogFailureCategory.PAGE_STILL_LOADING to
                    "$platformLabel Web Terminal page is still loading (${currentUrl ?: "blank"})."
            }
            if (!jsResponded || !heartbeat.javascriptRunning) {
                return WatchdogFailureCategory.JS_NOT_RESPONDING to
                    "JavaScript stopped responding in $platformLabel Web Terminal during ${activeStepId.label}."
            }
            if (!heartbeat.domReady || !heartbeat.terminalLoaded) {
                return WatchdogFailureCategory.DOM_NOT_READY to
                    "$platformLabel Web Terminal DOM is not ready (stage=${heartbeat.currentStage})."
            }
            if (!heartbeat.authenticated && activeStepId != ExecutionStepId.EXECUTING_TRADE) {
                return WatchdogFailureCategory.AUTH_INCOMPLETE to
                    "Authentication incomplete in $platformLabel Web Terminal — account session not confirmed."
            }
            if (activeStepId == ExecutionStepId.SELECTING_SYMBOL) {
                return if (heartbeat.symbolDiscoveryRunning) {
                    WatchdogFailureCategory.SYMBOL_SEARCH_RUNNING to
                        "Symbol search for $symbolName is still running inside $platformLabel Web Terminal without completing."
                } else {
                    WatchdogFailureCategory.SYMBOL_GENUINELY_NOT_FOUND to
                        "error:Symbol $symbolName not found inside $platformLabel Web Terminal."
                }
            }
            if (activeStepId == ExecutionStepId.PREPARING_TRADE && !heartbeat.orderTicketOpen) {
                return WatchdogFailureCategory.ORDER_TICKET_NOT_FOUND to
                    "Order ticket not found / could not open for $symbolName inside $platformLabel Web Terminal."
            }
            if (activeStepId == ExecutionStepId.EXECUTING_TRADE) {
                return WatchdogFailureCategory.BROKER_RESPONSE_PENDING to
                    "Broker response pending without confirmation — error:Unable to confirm trade submission. The trade was not retried."
            }
            return WatchdogFailureCategory.EXECUTION_TIMED_OUT to
                "Execution timed out at ${activeStepId.label} (stage=${heartbeat.currentStage}) for $symbolName."
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val repo by lazy { BtechPreferencesRepository(appContext) }
    private val contextWrapper = MutableContextWrapper(appContext)
    private var webView: WebView? = null
    private var backgroundHostRef: WeakReference<ViewGroup>? = null
    private var isInspectMode: Boolean = false
    private var useSoftwareLayer = false

    private val _status = MutableStateFlow(WebTerminalStatus())
    val status = _status.asStateFlow()

    private val _heartbeat = MutableStateFlow(WebTerminalHeartbeat())
    val heartbeat = _heartbeat.asStateFlow()

    @Volatile
    private var currentRunId: Long = 0L
    @Volatile
    private var lastRequestedTerminalUrl: String = ""
    @Volatile
    private var lastWatchdogFailureCategory: WatchdogFailureCategory = WatchdogFailureCategory.NONE

    private var pendingAuthCallback: ((Boolean, String) -> Unit)? = null
    private var pendingTradeDoneCallback: ((Boolean, String) -> Unit)? = null
    private var pendingDiscoveryCallback: ((SymbolDiscoveryResult) -> Unit)? = null
    private var pendingOnPageFinishedAction: (() -> Unit)? = null
    private var activeTradeAccount: SavedAccount? = null
    private var activeTradeSignal: SimulatedSignalRequest? = null
    private var tradeReauthAttempted: Boolean = false
    private var symbolGraceUsed: Boolean = false

    private var domFallbackRunnable: Runnable? = null
    private var authTimeoutRunnable: Runnable? = null
    private var tradeTimeoutRunnable: Runnable? = null
    private var phaseStallRunnable: Runnable? = null
    private var balancePollRunnable: Runnable? = null
    private var heartbeatPollRunnable: Runnable? = null
    private var isBalancePollingActive: Boolean = false
    private var isHeartbeatMonitorActive: Boolean = false
    private val balanceTimeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Volatile
    private var cachedSavedTerminalUrls: List<String> = emptyList()
    private var desktopViewportScriptHandler: ScriptHandler? = null
    @Volatile
    private var registeredViewportOrigins: Set<String> = emptySet()
    @Volatile
    private var registeredViewportWidth: Int = 0
    @Volatile
    private var registeredViewportEnabled: Boolean = false
    @Volatile
    private var documentStartSupported: Boolean = false
    @Volatile
    private var documentStartScriptActive: Boolean = false
    @Volatile
    private var fallbackViewportApplied: Boolean = false

    init {
        engineScope.launch {
            runCatching {
                repo.dataFlow.collect { saved ->
                    val urls = buildList {
                        saved.savedAccount?.terminalUrl?.takeIf { it.isNotBlank() }?.let { add(it) }
                        saved.savedAccountsByBroker.values.forEach { acc ->
                            acc.terminalUrl.takeIf { it.isNotBlank() }?.let { add(it) }
                        }
                    }.distinct()
                    if (urls != cachedSavedTerminalUrls) {
                        cachedSavedTerminalUrls = urls
                        mainHandler.post {
                            webView?.let { wv ->
                                configureDesktopViewportForWebView(wv, urls)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun nextRunId(): Long {
        currentRunId += 1L
        return currentRunId
    }

    fun getCurrentRunId(): Long = currentRunId

    fun getLastWatchdogFailureCategory(): WatchdogFailureCategory = lastWatchdogFailureCategory

    fun hasWebView(): Boolean = webView != null

    fun isWebViewAttached(): Boolean = webView?.parent != null

    private fun clearAuthTimers() {
        domFallbackRunnable?.let { mainHandler.removeCallbacks(it) }
        domFallbackRunnable = null
        authTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        authTimeoutRunnable = null
    }

    private fun clearTradeTimers() {
        domFallbackRunnable?.let { mainHandler.removeCallbacks(it) }
        domFallbackRunnable = null
        tradeTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        tradeTimeoutRunnable = null
        phaseStallRunnable?.let { mainHandler.removeCallbacks(it) }
        phaseStallRunnable = null
    }

    private fun finishAuthWithError(message: String) {
        clearAuthTimers()
        pendingOnPageFinishedAction = null
        val cb = pendingAuthCallback
        pendingAuthCallback = null
        if (cb != null) {
            val curUrl = webView?.url ?: _status.value.currentUrl
            val formatted = if (curUrl.isNotBlank() && !message.contains(curUrl)) {
                "$message\nURL: $curUrl"
            } else {
                message
            }
            OverlayStateBus.appendLog(">> AUTH FAILED: $formatted", isError = true)
            cb(false, formatted)
        }
    }

    private fun finishTradeWithError(message: String, failedStep: ExecutionStepId? = null) {
        clearTradeTimers()
        pendingOnPageFinishedAction = null
        val cb = pendingTradeDoneCallback
        pendingTradeDoneCallback = null
        activeTradeAccount = null
        activeTradeSignal = null
        if (cb != null) {
            OverlayStateBus.appendLog(">> $message", isError = true)
            OverlayStateBus.failExecution(message, failedStep = failedStep)
            cb(false, message)
        }
    }

    private fun finishTradeWithUnknown(message: String) {
        clearTradeTimers()
        pendingOnPageFinishedAction = null
        val cb = pendingTradeDoneCallback
        pendingTradeDoneCallback = null
        activeTradeAccount = null
        activeTradeSignal = null
        if (cb != null) {
            OverlayStateBus.appendLog(">> $message", isError = true)
            OverlayStateBus.markExecutionUnknown(message)
            cb(false, message)
        }
    }

    private fun parseHeartbeatJson(rawJson: String?, wv: WebView?): WebTerminalHeartbeat? {
        val cleaned = rawJson?.trim().orEmpty()
        if (cleaned.isEmpty() || cleaned == "null") return null
        val unquoted = if (cleaned.startsWith("\"") && cleaned.endsWith("\"")) {
            runCatching { JSONObject("{\"v\":$cleaned}").getString("v") }.getOrDefault(cleaned)
        } else {
            cleaned
        }
        return runCatching {
            val obj = JSONObject(unquoted)
            WebTerminalHeartbeat(
                webviewAlive = wv != null && obj.optBoolean("webview_alive", true),
                javascriptRunning = obj.optBoolean("javascript_running", true),
                terminalLoaded = obj.optBoolean("terminal_loaded", false),
                authenticated = obj.optBoolean("authenticated", false),
                domReady = obj.optBoolean("dom_ready", false),
                symbolDiscoveryRunning = obj.optBoolean("symbol_discovery_running", false),
                orderTicketOpen = obj.optBoolean("order_ticket_open", false),
                currentStage = obj.optString("current_stage", "IDLE"),
                runId = obj.optLong("run_id", currentRunId),
                lastHeartbeat = System.currentTimeMillis(),
                platform = obj.optString("platform", ""),
                currentUrl = obj.optString("url", wv?.url ?: _status.value.currentUrl),
            )
        }.getOrNull()
    }

    fun probeHeartbeatNow(
        expectedPlatform: String = activeTradeSignal?.platform?.label ?: activeTradeAccount?.platform?.label ?: "",
        onComplete: ((Boolean, WebTerminalHeartbeat) -> Unit)? = null,
    ) {
        mainHandler.post {
            val wv = webView
            if (wv == null) {
                val snap = WebTerminalHeartbeat(
                    webviewAlive = false,
                    javascriptRunning = false,
                    currentStage = "NO_WEBVIEW",
                    runId = currentRunId,
                    lastHeartbeat = _heartbeat.value.lastHeartbeat,
                )
                _heartbeat.value = snap
                onComplete?.invoke(false, snap)
                return@post
            }

            if (wv.parent == null) {
                val snap = _heartbeat.value.copy(
                    webviewAlive = false,
                    javascriptRunning = false,
                    currentStage = "WEBVIEW_DETACHED",
                    currentUrl = wv.url ?: _status.value.currentUrl,
                )
                _heartbeat.value = snap
                onComplete?.invoke(false, snap)
                return@post
            }

            var settled = false
            val timeoutGuard = Runnable {
                if (settled) return@Runnable
                settled = true
                val stale = _heartbeat.value.copy(
                    webviewAlive = webView != null && webView?.parent != null,
                    javascriptRunning = false,
                    currentStage = "JS_UNRESPONSIVE",
                    currentUrl = wv.url ?: _status.value.currentUrl,
                )
                _heartbeat.value = stale
                onComplete?.invoke(false, stale)
            }
            mainHandler.postDelayed(timeoutGuard, HEARTBEAT_PROBE_TIMEOUT_MS)

            runCatching {
                wv.evaluateJavascript(WebTerminalScripts.buildHeartbeatProbeScript(expectedPlatform)) { result ->
                    if (settled) return@evaluateJavascript
                    settled = true
                    mainHandler.removeCallbacks(timeoutGuard)
                    val parsed = parseHeartbeatJson(result, wv)
                    if (parsed != null) {
                        _heartbeat.value = parsed
                        if (isBalancePollingActive && !parsed.authenticated && pendingAuthCallback == null && parsed.terminalLoaded) {
                            OverlayStateBus.updateSnapshot { cur ->
                                if (cur.isConnected) {
                                    cur.copy(
                                        isConnected = false,
                                        balanceStatus = "Session disconnected — waiting for reconnect",
                                    )
                                } else cur
                            }
                        }
                        onComplete?.invoke(true, parsed)
                    } else {
                        val fallback = _heartbeat.value.copy(
                            webviewAlive = true,
                            javascriptRunning = false,
                            currentStage = "PROBE_PARSE_FAILED",
                            currentUrl = wv.url ?: _status.value.currentUrl,
                        )
                        _heartbeat.value = fallback
                        onComplete?.invoke(false, fallback)
                    }
                }
            }.onFailure {
                if (!settled) {
                    settled = true
                    mainHandler.removeCallbacks(timeoutGuard)
                    val errSnap = _heartbeat.value.copy(
                        webviewAlive = false,
                        javascriptRunning = false,
                        currentStage = "EVALUATE_ERROR",
                    )
                    _heartbeat.value = errSnap
                    onComplete?.invoke(false, errSnap)
                }
            }
        }
    }

    private fun startHeartbeatMonitor() {
        if (isHeartbeatMonitorActive) return
        isHeartbeatMonitorActive = true
        val runner = object : Runnable {
            override fun run() {
                if (!isHeartbeatMonitorActive) return
                val needMonitor = isBalancePollingActive || pendingTradeDoneCallback != null || pendingAuthCallback != null
                if (!needMonitor) {
                    isHeartbeatMonitorActive = false
                    heartbeatPollRunnable = null
                    return
                }
                if (webView != null && webView?.url != null && webView?.url != "about:blank") {
                    probeHeartbeatNow()
                }
                mainHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
            }
        }
        heartbeatPollRunnable = runner
        mainHandler.postDelayed(runner, HEARTBEAT_INTERVAL_MS)
    }

    private fun stopHeartbeatMonitorIfIdle() {
        if (!isBalancePollingActive && pendingTradeDoneCallback == null && pendingAuthCallback == null) {
            isHeartbeatMonitorActive = false
            heartbeatPollRunnable?.let { mainHandler.removeCallbacks(it) }
            heartbeatPollRunnable = null
        }
    }

    private fun resetPhaseStallWatchdog(runId: Long = currentRunId) {
        phaseStallRunnable?.let { mainHandler.removeCallbacks(it) }
        phaseStallRunnable = null
        if (pendingTradeDoneCallback == null || pendingAuthCallback != null || runId != currentRunId) return

        val runner = Runnable {
            if (pendingTradeDoneCallback == null || pendingAuthCallback != null || runId != currentRunId) return@Runnable
            val account = activeTradeAccount
            val signal = activeTradeSignal
            val activeStepId = OverlayStateBus.snapshot.value.executionSteps
                .lastOrNull { it.status == ExecutionStepStatus.ACTIVE }
                ?.id
                ?: ExecutionStepId.OPENING_TERMINAL
            val platformLabel = (signal?.platform ?: account?.platform)?.label ?: "Web"
            val symbolName = signal?.symbol ?: "symbol"

            probeHeartbeatNow(platformLabel) { jsResponded, hb ->
                if (pendingTradeDoneCallback == null || pendingAuthCallback != null || runId != currentRunId) {
                    return@probeHeartbeatNow
                }
                // If JS is alive & actively running symbol search on first stall check, grant one bounded grace extension
                if (
                    jsResponded &&
                    hb.javascriptRunning &&
                    hb.authenticated &&
                    activeStepId == ExecutionStepId.SELECTING_SYMBOL &&
                    hb.symbolDiscoveryRunning &&
                    !symbolGraceUsed
                ) {
                    symbolGraceUsed = true
                    OverlayStateBus.appendLog("Heartbeat healthy — symbol search still active for $symbolName (extended wait)...")
                    val graceRunner = Runnable {
                        if (pendingTradeDoneCallback == null || pendingAuthCallback != null || runId != currentRunId) return@Runnable
                        probeHeartbeatNow(platformLabel) { r2, hb2 ->
                            if (pendingTradeDoneCallback == null || pendingAuthCallback != null || runId != currentRunId) return@probeHeartbeatNow
                            handleWatchdogExpiry(runId, activeStepId, r2, hb2, symbolName, platformLabel)
                        }
                    }
                    phaseStallRunnable = graceRunner
                    mainHandler.postDelayed(graceRunner, SYMBOL_GRACE_EXTENSION_MS)
                    return@probeHeartbeatNow
                }
                handleWatchdogExpiry(runId, activeStepId, jsResponded, hb, symbolName, platformLabel)
            }
        }
        phaseStallRunnable = runner
        mainHandler.postDelayed(runner, PHASE_STALL_TIMEOUT_MS)
    }

    private fun handleWatchdogExpiry(
        runId: Long,
        activeStepId: ExecutionStepId,
        jsResponded: Boolean,
        hb: WebTerminalHeartbeat,
        symbolName: String,
        platformLabel: String,
    ) {
        if (pendingTradeDoneCallback == null || runId != currentRunId) return
        val curUrl = webView?.url ?: _status.value.currentUrl
        val (category, msg) = classifyWatchdogStall(
            activeStepId = activeStepId,
            webViewPresent = webView != null,
            webViewAttached = webView?.parent != null,
            isLoading = _status.value.isLoading,
            currentUrl = curUrl,
            jsResponded = jsResponded,
            heartbeat = hb,
            symbolName = symbolName,
            platformLabel = platformLabel,
        )
        lastWatchdogFailureCategory = category
        runCatching { webView?.evaluateJavascript(WebTerminalScripts.buildCancelTradeScript(runId), null) }
        if (category == WatchdogFailureCategory.BROKER_RESPONSE_PENDING || activeStepId == ExecutionStepId.EXECUTING_TRADE) {
            finishTradeWithUnknown(msg)
        } else {
            finishTradeWithError("[$category] $msg", failedStep = activeStepId)
        }
    }

    @androidx.annotation.VisibleForTesting
    internal fun processHeartbeatJsonForTest(rawJson: String, webViewPresent: Boolean = true, webViewAttached: Boolean = true): WebTerminalHeartbeat? {
        val parsed = parseHeartbeatJson(rawJson, if (webViewPresent) webView else null) ?: return null
        val adjusted = parsed.copy(webviewAlive = webViewPresent && webViewAttached)
        _heartbeat.value = adjusted
        return adjusted
    }

    @androidx.annotation.VisibleForTesting
    internal fun evaluateWatchdogForTest(
        runId: Long,
        activeStepId: ExecutionStepId,
        jsResponded: Boolean,
        heartbeat: WebTerminalHeartbeat,
        symbolName: String,
        platformLabel: String,
    ): Pair<WatchdogFailureCategory, String> {
        val result = classifyWatchdogStall(
            activeStepId = activeStepId,
            webViewPresent = true,
            webViewAttached = heartbeat.webviewAlive,
            isLoading = _status.value.isLoading,
            currentUrl = heartbeat.currentUrl.ifBlank { _status.value.currentUrl },
            jsResponded = jsResponded,
            heartbeat = heartbeat,
            symbolName = symbolName,
            platformLabel = platformLabel,
        )
        lastWatchdogFailureCategory = result.first
        return result
    }

    @androidx.annotation.VisibleForTesting
    internal fun startSyntheticTradeRunForTest(
        account: SavedAccount,
        signal: SimulatedSignalRequest,
        onFinished: (Boolean, String) -> Unit = { _, _ -> },
    ): Long {
        clearTradeTimers()
        val runId = nextRunId()
        pendingTradeDoneCallback = onFinished
        activeTradeAccount = account
        activeTradeSignal = signal
        symbolGraceUsed = false
        lastWatchdogFailureCategory = WatchdogFailureCategory.NONE
        OverlayStateBus.beginNewSignalExecution(
            statusTitle = "Signal: ${signal.symbol} ${signal.direction.label}",
            detail = "Test signal run #$runId",
        )
        return runId
    }

    @androidx.annotation.VisibleForTesting
    internal fun dispatchTradePhaseForTest(runId: Long, phaseKey: String, detail: String) {
        handleTradePhase(runId, phaseKey, detail)
    }

    @androidx.annotation.VisibleForTesting
    internal fun dispatchTradeResultForTest(resultJson: String) {
        handleTradeResult(resultJson)
    }

    @androidx.annotation.VisibleForTesting
    internal fun dispatchTradeLogForTest(runId: Long, step: String, isError: Boolean, isDone: Boolean) {
        handleTradeLog(runId, step, isError, isDone)
    }

    private var defaultChromeMobileUa: String = ""

    private fun collectKnownTerminalUrls(extraUrls: Collection<String> = emptyList()): List<String> = buildList {
        addAll(cachedSavedTerminalUrls)
        lastRequestedTerminalUrl.takeIf { it.isNotBlank() }?.let { add(it) }
        activeTradeAccount?.terminalUrl?.takeIf { it.isNotBlank() }?.let { add(it) }
        webView?.url?.takeIf { it.isNotBlank() && !it.equals("about:blank", ignoreCase = true) }?.let { add(it) }
        addAll(extraUrls)
    }.distinct()

    internal fun parseDesktopViewportDiagnosticsJson(rawJson: String?): DesktopViewportDiagnostics? {
        val cleaned = rawJson?.trim().orEmpty()
        if (cleaned.isEmpty() || cleaned == "null") return null
        val unquoted = if (cleaned.startsWith("\"") && cleaned.endsWith("\"")) {
            runCatching { JSONObject("{\"v\":$cleaned}").getString("v") }.getOrDefault(cleaned)
        } else {
            cleaned
        }
        return runCatching {
            val obj = JSONObject(unquoted)
            val targetW = obj.optInt("targetWidth", _status.value.desktopViewportWidth)
                .coerceIn(MIN_DESKTOP_VIEWPORT_WIDTH, MAX_DESKTOP_VIEWPORT_WIDTH)
            val innerW = obj.optInt("innerWidth", 0)
            val clientW = obj.optInt("clientWidth", 0)
            DesktopViewportDiagnostics(
                innerWidth = innerW,
                clientWidth = clientW,
                visualViewportWidth = obj.optInt("visualViewportWidth", 0),
                innerHeight = obj.optInt("innerHeight", 0),
                metaViewport = obj.optString("metaViewport", ""),
                userAgent = obj.optString("userAgent", ""),
                url = obj.optString("url", webView?.url ?: _status.value.currentUrl),
                targetWidth = targetW,
                viewportEnforced = obj.optBoolean("viewportEnforced", false),
                layoutIsDesktopWidth = obj.optBoolean("layoutIsDesktopWidth", innerW >= MIN_DESKTOP_VIEWPORT_WIDTH || clientW >= MIN_DESKTOP_VIEWPORT_WIDTH),
                documentStartSupported = obj.optBoolean("documentStartSupported", documentStartSupported),
                fallbackApplied = obj.optBoolean("fallbackApplied", fallbackViewportApplied),
                measuredAtMs = System.currentTimeMillis(),
            )
        }.getOrNull()
    }

    @androidx.annotation.VisibleForTesting
    internal fun processViewportDiagnosticsJsonForTest(rawJson: String?): DesktopViewportDiagnostics? {
        val parsed = parseDesktopViewportDiagnosticsJson(rawJson) ?: return null
        _status.value = _status.value.copy(viewportDiagnostics = parsed)
        return parsed
    }

    @androidx.annotation.VisibleForTesting
    internal fun getRegisteredViewportOriginsForTest(extraUrls: Collection<String> = emptyList()): Set<String> {
        return buildAllowedTerminalOriginRules(collectKnownTerminalUrls(extraUrls))
    }

    private fun configureDesktopViewportForWebView(
        wv: WebView,
        extraUrls: Collection<String> = emptyList(),
    ) {
        val desktopMode = _status.value.isDesktopMode
        val targetWidth = _status.value.desktopViewportWidth.coerceIn(MIN_DESKTOP_VIEWPORT_WIDTH, MAX_DESKTOP_VIEWPORT_WIDTH)
        applyCleanUserAgent(wv.settings, desktopMode)
        wv.settings.useWideViewPort = true
        wv.settings.loadWithOverviewMode = desktopMode
        wv.settings.textZoom = 100
        wv.settings.builtInZoomControls = true
        wv.settings.displayZoomControls = false

        val origins = buildAllowedTerminalOriginRules(collectKnownTerminalUrls(extraUrls))
        val docStartSupported = runCatching {
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        }.getOrDefault(false)
        documentStartSupported = docStartSupported

        if (!desktopMode) {
            runCatching { desktopViewportScriptHandler?.remove() }
            desktopViewportScriptHandler = null
            documentStartScriptActive = false
            registeredViewportOrigins = origins
            registeredViewportWidth = targetWidth
            registeredViewportEnabled = false
            return
        }

        if (docStartSupported) {
            val needsRegister = desktopViewportScriptHandler == null ||
                registeredViewportOrigins != origins ||
                registeredViewportWidth != targetWidth ||
                !registeredViewportEnabled
            if (needsRegister && origins.isNotEmpty()) {
                runCatching { desktopViewportScriptHandler?.remove() }
                desktopViewportScriptHandler = null
                val script = WebTerminalScripts.buildDesktopViewportEnforcementScript(
                    targetWidthPx = targetWidth,
                    enabled = true,
                    isFallback = false,
                )
                runCatching {
                    desktopViewportScriptHandler = WebViewCompat.addDocumentStartJavaScript(wv, script, origins)
                    registeredViewportOrigins = origins
                    registeredViewportWidth = targetWidth
                    registeredViewportEnabled = true
                    documentStartScriptActive = true
                    fallbackViewportApplied = false
                }.onFailure { err ->
                    documentStartScriptActive = false
                    android.util.Log.w("WebTerminalEngine", "DOCUMENT_START_SCRIPT registration failed; using fallback: ${err.message}")
                }
            }
        } else {
            documentStartScriptActive = false
            registeredViewportOrigins = origins
            registeredViewportWidth = targetWidth
            registeredViewportEnabled = true
        }
    }

    private fun applyFallbackViewportOverride(wv: WebView, url: String?, stage: String) {
        if (url.isNullOrBlank() || url.equals("about:blank", ignoreCase = true)) return
        val desktopMode = _status.value.isDesktopMode
        val origin = extractOriginRule(url)
        if (origin != null && !registeredViewportOrigins.contains(origin)) {
            configureDesktopViewportForWebView(wv, listOf(url))
        }
        val targetWidth = _status.value.desktopViewportWidth.coerceIn(MIN_DESKTOP_VIEWPORT_WIDTH, MAX_DESKTOP_VIEWPORT_WIDTH)
        val isFallback = desktopMode && !documentStartScriptActive
        if (isFallback && stage == "onPageStarted" && !fallbackViewportApplied) {
            OverlayStateBus.appendLog(
                "WebView DOCUMENT_START_SCRIPT unavailable on this device — applying $stage desktop viewport fallback (${targetWidth}px)."
            )
        }
        if (isFallback) {
            fallbackViewportApplied = true
        }
        val js = WebTerminalScripts.buildDesktopViewportEnforcementScript(
            targetWidthPx = targetWidth,
            enabled = desktopMode,
            isFallback = isFallback,
        )
        runCatching { wv.evaluateJavascript(js, null) }
    }

    fun collectDesktopViewportDiagnostics(onComplete: ((DesktopViewportDiagnostics) -> Unit)? = null) {
        val action: () -> Unit = {
            val wv = webView
            val targetWidth = _status.value.desktopViewportWidth.coerceIn(MIN_DESKTOP_VIEWPORT_WIDTH, MAX_DESKTOP_VIEWPORT_WIDTH)
            val desktopMode = _status.value.isDesktopMode
            if (wv == null) {
                val empty = DesktopViewportDiagnostics(
                    targetWidth = targetWidth,
                    url = _status.value.currentUrl,
                    viewportEnforced = false,
                    documentStartSupported = documentStartSupported,
                    fallbackApplied = fallbackViewportApplied,
                    measuredAtMs = System.currentTimeMillis(),
                )
                _status.value = _status.value.copy(viewportDiagnostics = empty)
                onComplete?.invoke(empty)
            } else {
                val diagJs = WebTerminalScripts.buildDesktopViewportDiagnosticsScript(
                    targetWidthPx = targetWidth,
                    enforcementActive = desktopMode,
                    documentStartSupported = documentStartScriptActive,
                    fallbackApplied = desktopMode && !documentStartScriptActive && fallbackViewportApplied,
                )
                runCatching {
                    wv.evaluateJavascript(diagJs) { raw ->
                        val parsed = parseDesktopViewportDiagnosticsJson(raw)
                        if (parsed != null) {
                            _status.value = _status.value.copy(viewportDiagnostics = parsed)
                            if (desktopMode && !parsed.layoutIsDesktopWidth && parsed.innerWidth > 0) {
                                OverlayStateBus.appendLog(
                                    "Viewport notice: CSS width=${parsed.innerWidth}px (client=${parsed.clientWidth}px, meta='${parsed.metaViewport}', docStart=${parsed.documentStartSupported}, fallback=${parsed.fallbackApplied})",
                                )
                            }
                            onComplete?.invoke(parsed)
                        }
                    }
                }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post(action)
        }
    }

    internal fun applyCleanUserAgent(settings: WebSettings, desktopMode: Boolean) {
        if (defaultChromeMobileUa.isEmpty()) {
            val base = WebSettings.getDefaultUserAgent(appContext)
            // Removing "; wv" & "Version/4.0" prevents Cloudflare & broker gateways from blocking WebView as an embedded wrapper.
            defaultChromeMobileUa = base
                .replace("; wv)", ")")
                .replace("; wv;", ";")
                .replace("Version/4.0 ", "")
        }
        settings.userAgentString = if (desktopMode) {
            DESKTOP_USER_AGENT
        } else {
            defaultChromeMobileUa
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun obtainWebView(hostContext: Context? = null): WebView {
        if (hostContext != null) {
            contextWrapper.baseContext = hostContext
        }
        val existing = webView
        if (existing != null) return existing

        if (BuildConfig.DEBUG) {
            runCatching { WebView.setWebContentsDebuggingEnabled(true) }
        }

        val created = WebView(contextWrapper).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setLayerType(
                if (useSoftwareLayer) View.LAYER_TYPE_SOFTWARE else View.LAYER_TYPE_HARDWARE,
                null,
            )
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                @Suppress("DEPRECATION")
                databaseEnabled = true
                cacheMode = WebSettings.LOAD_DEFAULT
                javaScriptCanOpenWindowsAutomatically = true
                allowFileAccess = true
                allowContentAccess = true
                loadsImagesAutomatically = true
                mediaPlaybackRequiresUserGesture = false
                loadWithOverviewMode = true
                useWideViewPort = true
                textZoom = 100
                builtInZoomControls = true
                displayZoomControls = false
                setSupportMultipleWindows(true)
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                offscreenPreRaster = true
                applyCleanUserAgent(this, _status.value.isDesktopMode)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                runCatching {
                    setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
                }
            }

            addOnAttachStateChangeListener(
                object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) {
                        runCatching {
                            (v as? WebView)?.onResume()
                            (v as? WebView)?.resumeTimers()
                        }
                    }
                    override fun onViewDetachedFromWindow(v: View) {
                        contextWrapper.baseContext = appContext
                    }
                },
            )

            val webViewRef = this
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(webViewRef, true)
            }

            addJavascriptInterface(Bridge(), "BtechBridge")

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    _status.value = _status.value.copy(progress = newProgress)
                }

                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                    val cm = consoleMessage ?: return false
                    if (cm.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                        val msg = cm.message()?.take(220) ?: return false
                        val src = cm.sourceId()?.takeLast(60) ?: ""
                        android.util.Log.w("BtechWebView", "Console [$src:${cm.lineNumber()}]: $msg")
                    }
                    return super.onConsoleMessage(consoleMessage)
                }

                override fun onCreateWindow(
                    view: WebView?,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: Message?,
                ): Boolean {
                    val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                    val tempPopup = WebView(contextWrapper).apply {
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                popupView: WebView?,
                                request: WebResourceRequest?,
                            ): Boolean {
                                val targetUrl = request?.url?.toString()
                                if (!targetUrl.isNullOrBlank()) {
                                    webViewRef.loadUrl(targetUrl)
                                }
                                runCatching { popupView?.destroy() }
                                return true
                            }
                        }
                    }
                    transport.webView = tempPopup
                    resultMsg.sendToTarget()
                    return true
                }
            }

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val target = request?.url?.toString()
                    if (!target.isNullOrBlank() && view != null && request?.isForMainFrame == true) {
                        configureDesktopViewportForWebView(view, listOf(target))
                    }
                    return false
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    if (url == "about:blank") return
                    _status.value = _status.value.copy(
                        currentUrl = url ?: "",
                        progress = 5,
                        isLoading = true,
                        isPageReady = false,
                        lastError = null,
                    )
                    if (view != null) {
                        applyFallbackViewportOverride(view, url, "onPageStarted")
                    }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    if (url == "about:blank") {
                        _status.value = _status.value.copy(
                            currentUrl = "",
                            pageTitle = "",
                            progress = 0,
                            isLoading = false,
                            isPageReady = false,
                        )
                        return
                    }
                    runCatching { CookieManager.getInstance().flush() }
                    _status.value = _status.value.copy(
                        currentUrl = url ?: "",
                        pageTitle = view?.title ?: "",
                        progress = 100,
                        isLoading = false,
                        isPageReady = true,
                    )
                    if (view != null) {
                        applyFallbackViewportOverride(view, url, "onPageFinished")
                        collectDesktopViewportDiagnostics()
                    }
                    domFallbackRunnable?.let { mainHandler.removeCallbacks(it) }
                    domFallbackRunnable = null
                    val action = pendingOnPageFinishedAction
                    pendingOnPageFinishedAction = null
                    action?.invoke()
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?,
                ) {
                    val desc = error?.description?.toString() ?: "Failed to load web terminal"
                    val failingUrl = request?.url?.toString() ?: (view?.url ?: _status.value.currentUrl)
                    val isMain = request?.isForMainFrame == true
                    if (isMain) {
                        OverlayStateBus.appendLog(
                            "WebView error (url=$failingUrl): $desc",
                            isError = true,
                        )
                        _status.value = _status.value.copy(
                            isLoading = false,
                            lastError = "$desc ($failingUrl)",
                        )
                        finishAuthWithError("Cannot reach web terminal ($desc).\nURL: $failingUrl")
                        finishTradeWithError("Cannot reach web terminal ($desc) at $failingUrl.")
                    } else {
                        android.util.Log.d("BtechWebView", "Subresource error ($failingUrl): $desc")
                    }
                }

                override fun onReceivedHttpError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    errorResponse: WebResourceResponse?,
                ) {
                    if (errorResponse != null && errorResponse.statusCode >= 400) {
                        val code = errorResponse.statusCode
                        val failingUrl = request?.url?.toString() ?: (view?.url ?: _status.value.currentUrl)
                        val isMain = request?.isForMainFrame == true
                        if (isMain) {
                            val msg = "Broker terminal returned HTTP $code (${errorResponse.reasonPhrase.ifBlank { "Error" }}).\nURL: $failingUrl"
                            OverlayStateBus.appendLog(
                                "WebView HTTP $code (url=$failingUrl)",
                                isError = true,
                            )
                            _status.value = _status.value.copy(
                                isLoading = false,
                                lastError = msg,
                            )
                            finishAuthWithError("$msg\nTap 'Inspect Live Web Terminal' to verify the endpoint.")
                        } else {
                            android.util.Log.d("BtechWebView", "Subresource HTTP $code ($failingUrl)")
                        }
                    }
                }

                override fun onReceivedSslError(
                    view: WebView?,
                    handler: SslErrorHandler?,
                    error: SslError?,
                ) {
                    handler?.cancel()
                    val mainUrl = view?.url ?: _status.value.currentUrl
                    val failingUrl = error?.url ?: mainUrl
                    val mainHost = runCatching { android.net.Uri.parse(mainUrl).host }.getOrNull()
                    val failHost = runCatching { android.net.Uri.parse(failingUrl).host }.getOrNull()
                    val isMainHost = failingUrl == mainUrl || (mainHost != null && mainHost.equals(failHost, ignoreCase = true))
                    if (isMainHost) {
                        val msg = "SSL certificate error (primaryError=${error?.primaryError}) connecting to $failingUrl"
                        OverlayStateBus.appendLog("WebView SSL error ($failingUrl): primaryError=${error?.primaryError}", isError = true)
                        _status.value = _status.value.copy(isLoading = false, lastError = msg)
                        finishAuthWithError(msg)
                        finishTradeWithError(msg)
                    } else {
                        android.util.Log.d("BtechWebView", "Ignored subresource SSL error ($failingUrl)")
                    }
                }

                override fun onRenderProcessGone(
                    view: WebView?,
                    detail: RenderProcessGoneDetail?,
                ): Boolean {
                    useSoftwareLayer = true
                    (view?.parent as? ViewGroup)?.removeView(view)
                    runCatching { view?.destroy() }
                    if (webView === view) {
                        webView = null
                    }
                    OverlayStateBus.appendLog(
                        "WebView renderer restarted with software fallback",
                        isError = true,
                    )
                    finishAuthWithError("Web Terminal renderer restarted; please try connecting again.")
                    finishTradeWithError("Web Terminal renderer restarted; retry simulation.")
                    return true
                }
            }
        }
        webView = created
        configureDesktopViewportForWebView(created)
        if (!isInspectMode) {
            val bg = backgroundHostRef?.get()
            if (bg != null && bg.isAttachedToWindow) {
                attachToContainer(bg, bg.context)
            }
        }
        return created
    }

    fun navigateToUrl(url: String) {
        val clean = url.trim().let {
            if (it.startsWith("http://") || it.startsWith("https://")) it else "https://$it"
        }
        lastRequestedTerminalUrl = clean
        mainHandler.post {
            val wv = obtainWebView()
            configureDesktopViewportForWebView(wv, listOf(clean))
            wv.loadUrl(clean)
        }
    }

    fun reload() {
        mainHandler.post {
            webView?.let { wv ->
                configureDesktopViewportForWebView(wv)
                wv.reload()
            }
        }
    }

    fun goBack(): Boolean {
        val wv = webView ?: return false
        return if (wv.canGoBack()) {
            wv.goBack()
            true
        } else {
            false
        }
    }

    fun toggleDesktopMode() {
        mainHandler.post {
            val next = !_status.value.isDesktopMode
            _status.value = _status.value.copy(isDesktopMode = next)
            webView?.let { wv ->
                configureDesktopViewportForWebView(wv)
                if (!next) {
                    val disableJs = WebTerminalScripts.buildDesktopViewportEnforcementScript(
                        targetWidthPx = _status.value.desktopViewportWidth,
                        enabled = false,
                    )
                    runCatching { wv.evaluateJavascript(disableJs, null) }
                }
                wv.reload()
            }
        }
    }

    fun setDesktopViewportWidth(widthPx: Int) {
        val clamped = widthPx.coerceIn(MIN_DESKTOP_VIEWPORT_WIDTH, MAX_DESKTOP_VIEWPORT_WIDTH)
        mainHandler.post {
            _status.value = _status.value.copy(desktopViewportWidth = clamped)
            webView?.let { wv ->
                configureDesktopViewportForWebView(wv)
                if (!wv.url.isNullOrBlank() && wv.url != "about:blank") {
                    wv.reload()
                }
            }
        }
    }

    fun setInspectMode(inspecting: Boolean) {
        val action = {
            isInspectMode = inspecting
            if (!inspecting) {
                ensureAttachedToBackgroundHost()
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post(action)
        }
    }

    fun registerBackgroundHost(container: ViewGroup, hostContext: Context) {
        backgroundHostRef = WeakReference(container)
        val wv = webView ?: return
        if (!isInspectMode && wv.parent !== container) {
            attachToContainer(container, hostContext)
        } else if (wv.parent === container) {
            runCatching {
                wv.onResume()
                wv.resumeTimers()
            }
        }
    }

    fun unregisterBackgroundHost(expectedParent: ViewGroup) {
        if (backgroundHostRef?.get() === expectedParent) {
            backgroundHostRef = null
        }
        val wv = webView ?: return
        if (wv.parent === expectedParent) {
            expectedParent.removeView(wv)
            contextWrapper.baseContext = appContext
        }
    }

    fun ensureAttachedToBackgroundHost() {
        val action = {
            if (!isInspectMode) {
                val bg = backgroundHostRef?.get()
                val wv = webView
                if (wv != null && bg != null && bg.isAttachedToWindow && wv.parent !== bg) {
                    attachToContainer(bg, bg.context)
                }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post(action)
        }
    }

    fun attachToContainer(container: ViewGroup, hostContext: Context) {
        val wv = obtainWebView(hostContext)
        val oldParent = wv.parent as? ViewGroup
        if (oldParent != null && oldParent !== container) {
            oldParent.removeView(wv)
        }
        if (wv.parent !== container) {
            container.addView(
                wv,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        runCatching {
            wv.visibility = View.VISIBLE
            wv.onResume()
            wv.resumeTimers()
            wv.requestLayout()
            wv.invalidate()
        }
        _heartbeat.value = _heartbeat.value.copy(webviewAlive = true)
    }

    fun detachIfParent(expectedParent: ViewGroup) {
        val wv = webView ?: return
        if (wv.parent === expectedParent) {
            val bg = backgroundHostRef?.get()
            if (!isInspectMode && bg != null && bg !== expectedParent && bg.isAttachedToWindow) {
                // Atomic reparent straight back to persistent background host — never orphan WebView
                attachToContainer(bg, bg.context)
            } else {
                expectedParent.removeView(wv)
                contextWrapper.baseContext = appContext
            }
        }
    }

    fun detachFromParent() {
        val detachAction = {
            if (!isInspectMode) {
                val bg = backgroundHostRef?.get()
                val wv = webView
                if (wv != null && bg != null && bg.isAttachedToWindow) {
                    attachToContainer(bg, bg.context)
                } else if (wv != null) {
                    contextWrapper.baseContext = appContext
                    (wv.parent as? ViewGroup)?.removeView(wv)
                }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            detachAction()
        } else {
            mainHandler.post(detachAction)
        }
    }

    private fun ensureDesktopLayoutIfDetached(wv: WebView) {
        val bg = backgroundHostRef?.get()
        if (!isInspectMode && wv.parent == null && bg != null && bg.isAttachedToWindow) {
            attachToContainer(bg, bg.context)
        }
        if (wv.parent == null && (wv.width <= 0 || wv.height <= 0)) {
            runCatching {
                val targetW = if (_status.value.isDesktopMode) {
                    _status.value.desktopViewportWidth.coerceIn(MIN_DESKTOP_VIEWPORT_WIDTH, MAX_DESKTOP_VIEWPORT_WIDTH)
                } else {
                    412
                }
                val targetH = if (_status.value.isDesktopMode) 768 else 732
                val widthSpec = View.MeasureSpec.makeMeasureSpec(targetW, View.MeasureSpec.EXACTLY)
                val heightSpec = View.MeasureSpec.makeMeasureSpec(targetH, View.MeasureSpec.EXACTLY)
                wv.measure(widthSpec, heightSpec)
                wv.layout(0, 0, targetW, targetH)
            }
        }
    }

    private fun wipeBrowserSession(wv: WebView?, resetPageToBlank: Boolean) {
        runCatching {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        }
        runCatching {
            WebStorage.getInstance().deleteAllData()
        }
        wv?.let { view ->
            runCatching {
                view.stopLoading()
                view.clearCache(true)
                view.clearFormData()
                view.clearHistory()
                view.evaluateJavascript(
                    "try{if(window.__btechAuthTimer){clearTimeout(window.__btechAuthTimer);window.__btechAuthTimer=null;}window.__btechTradeRunId=(window.__btechTradeRunId||0)+1;if(Array.isArray(window.__btechTradeTimers)){window.__btechTradeTimers.forEach(function(id){clearTimeout(id);});window.__btechTradeTimers=[];}localStorage.clear();sessionStorage.clear();}catch(e){}",
                    null,
                )
                if (resetPageToBlank) {
                    view.loadUrl("about:blank")
                }
            }
        }
    }

    fun resetSession(onCleared: (() -> Unit)? = null) {
        mainHandler.post {
            clearAuthTimers()
            clearTradeTimers()
            pendingAuthCallback = null
            pendingTradeDoneCallback = null
            pendingOnPageFinishedAction = null
            activeTradeAccount = null
            activeTradeSignal = null
            tradeReauthAttempted = false

            webView?.let { wv ->
                runCatching {
                    wv.evaluateJavascript(WebTerminalScripts.buildMt5RemoveSessionScript(), null)
                }
            }
            wipeBrowserSession(webView, resetPageToBlank = true)
            _status.value = WebTerminalStatus(isDesktopMode = _status.value.isDesktopMode)
            OverlayStateBus.updateSnapshot {
                it.copy(
                    isConnected = false,
                    serverName = "Not connected",
                    accountBalance = "—",
                    accountEquity = "—",
                    accountFloatingPl = "—",
                    accountMargin = "—",
                    balanceUpdatedAt = "",
                    balanceStatus = "Waiting for Web Terminal",
                )
            }
            OverlayStateBus.appendLog("Cleared previous Web Terminal session and cookies")
            onCleared?.invoke()
        }
    }

    fun authenticateInBackground(
        terminalUrl: String,
        login: String,
        password: String,
        server: String,
        platform: PlatformType,
        clearPreviousSession: Boolean = true,
        onResult: (Boolean, String) -> Unit,
    ) {
        mainHandler.post {
            clearAuthTimers()
            pendingOnPageFinishedAction = null

            val wv = obtainWebView()
            configureDesktopViewportForWebView(wv, listOf(terminalUrl))
            runCatching { wv.onResume() }
            ensureDesktopLayoutIfDetached(wv)
            if (clearPreviousSession) {
                wipeBrowserSession(wv, resetPageToBlank = false)
                _status.value = _status.value.copy(
                    isPageReady = false,
                    lastError = null,
                )
                OverlayStateBus.appendLog("Cleared previous session cookies & storage before login")
            }

            pendingAuthCallback = onResult
            OverlayStateBus.updateSnapshot {
                it.copy(statusDetail = "Connecting to $terminalUrl...")
            }
            OverlayStateBus.appendLog("Loading ${platform.label} Web Terminal: $terminalUrl")

            val runScript = {
                if (pendingAuthCallback != null) {
                    val js = WebTerminalScripts.buildAuthScript(
                        login = login,
                        password = password,
                        server = server,
                        platform = platform.label,
                    )
                    wv.evaluateJavascript(js, null)
                }
            }

            // Fallback in case WebSockets/analytics prevent onPageFinished from firing promptly
            val fallback = Runnable {
                val pending = pendingOnPageFinishedAction
                if (pending != null && pendingAuthCallback != null) {
                    wv.evaluateJavascript("document.readyState") { state ->
                        val clean = state?.trim('"') ?: ""
                        if (clean == "interactive" || clean == "complete" || _status.value.progress >= 70) {
                            OverlayStateBus.appendLog("DOM ready ($clean, ${_status.value.progress}%) — scanning terminal...")
                            pendingOnPageFinishedAction = null
                            pending.invoke()
                        }
                    }
                }
            }
            domFallbackRunnable = fallback
            mainHandler.postDelayed(fallback, DOM_FALLBACK_MS)

            val timeout = Runnable {
                if (pendingAuthCallback != null) {
                    runCatching {
                        wv.evaluateJavascript(
                            "if(window.__btechAuthTimer){clearTimeout(window.__btechAuthTimer);window.__btechAuthTimer=null;}",
                            null,
                        )
                        wv.stopLoading()
                    }
                    val pct = _status.value.progress
                    val errHint = _status.value.lastError?.let { " ($it)" } ?: ""
                    finishAuthWithError(
                        "Connection timed out after ${AUTH_TIMEOUT_MS / 1000}s at $pct% on $terminalUrl$errHint. Check internet/VPN or tap 'View Live WebView' to sign in directly."
                    )
                }
            }
            authTimeoutRunnable = timeout
            mainHandler.postDelayed(timeout, AUTH_TIMEOUT_MS)

            if (!clearPreviousSession && normalizeTerminalUrl(wv.url) == normalizeTerminalUrl(terminalUrl) && _status.value.isPageReady) {
                clearAuthTimers()
                authTimeoutRunnable = timeout
                mainHandler.postDelayed(timeout, AUTH_TIMEOUT_MS)
                runScript()
            } else {
                lastRequestedTerminalUrl = terminalUrl
                pendingOnPageFinishedAction = runScript
                wv.loadUrl(terminalUrl)
            }
            startHeartbeatMonitor()
        }
    }

    fun cancelAuthentication() {
        mainHandler.post {
            clearAuthTimers()
            pendingAuthCallback = null
            pendingOnPageFinishedAction = null
            webView?.let { wv ->
                runCatching {
                    wv.evaluateJavascript(
                        "if(window.__btechAuthTimer){clearTimeout(window.__btechAuthTimer);window.__btechAuthTimer=null;}",
                        null,
                    )
                    wv.stopLoading()
                }
            }
            _status.value = _status.value.copy(isLoading = false)
            OverlayStateBus.appendLog("Authentication cancelled by user")
            stopHeartbeatMonitorIfIdle()
        }
    }

    fun loadUrlForInspection(url: String?, forceReload: Boolean = false) {
        mainHandler.post {
            val wv = obtainWebView()
            val curUrl = wv.url
            val hasLoadedSession = !curUrl.isNullOrBlank() &&
                !curUrl.equals("about:blank", ignoreCase = true) &&
                _status.value.lastError == null
            // Pure inspection when customUrl is omitted and WebView is already on an active session (or executing a trade)
            if (!forceReload && hasLoadedSession && (url.isNullOrBlank() || pendingTradeDoneCallback != null || isBalancePollingActive)) {
                runCatching {
                    wv.onResume()
                    wv.resumeTimers()
                }
                probeHeartbeatNow()
                return@post
            }
            if (shouldNavigateForInspection(curUrl, lastRequestedTerminalUrl, url, _status.value.lastError, forceReload)) {
                val target = url!!.trim()
                lastRequestedTerminalUrl = target
                configureDesktopViewportForWebView(wv, listOf(target))
                wv.loadUrl(target)
            } else {
                configureDesktopViewportForWebView(wv)
                runCatching {
                    wv.onResume()
                    wv.resumeTimers()
                }
                collectDesktopViewportDiagnostics()
                probeHeartbeatNow()
            }
        }
    }

    fun startBalancePolling(account: SavedAccount? = null) {
        mainHandler.post {
            if (account != null && account.terminalUrl.isNotBlank()) {
                val wv = obtainWebView()
                configureDesktopViewportForWebView(wv, listOf(account.terminalUrl))
                if (wv.url.isNullOrBlank() || wv.url.equals("about:blank", ignoreCase = true)) {
                    val target = account.terminalUrl.trim()
                    lastRequestedTerminalUrl = target
                    OverlayStateBus.updateSnapshot {
                        it.copy(balanceStatus = "Loading ${account.broker} Web Terminal...")
                    }
                    wv.loadUrl(target)
                }
            }
            if (isBalancePollingActive) {
                pollBalanceOnce()
                startHeartbeatMonitor()
                return@post
            }
            isBalancePollingActive = true
            val ticker = object : Runnable {
                override fun run() {
                    if (!isBalancePollingActive) return
                    pollBalanceOnce()
                    mainHandler.postDelayed(this, BALANCE_POLL_INTERVAL_MS)
                }
            }
            balancePollRunnable = ticker
            mainHandler.post(ticker)
            startHeartbeatMonitor()
        }
    }

    fun stopBalancePolling() {
        mainHandler.post {
            isBalancePollingActive = false
            balancePollRunnable?.let { mainHandler.removeCallbacks(it) }
            balancePollRunnable = null
            stopHeartbeatMonitorIfIdle()
        }
    }

    fun pollBalanceOnce() {
        mainHandler.post {
            if (pendingAuthCallback != null) return@post
            val wv = webView ?: return@post
            if (wv.url.isNullOrBlank()) {
                OverlayStateBus.updateSnapshot {
                    it.copy(balanceStatus = "Open or connect Web Terminal for live balance")
                }
                return@post
            }
            runCatching {
                wv.onResume()
                wv.resumeTimers()
            }
            ensureDesktopLayoutIfDetached(wv)
            wv.evaluateJavascript(WebTerminalScripts.buildBalanceTelemetryScript(), null)
        }
    }

    fun cancelActiveTradeRun(reason: String, markAsStopped: Boolean = false) {
        mainHandler.post {
            val cancelledRunId = currentRunId
            nextRunId() // Invalidate all in-flight callbacks belonging to previous runId
            clearTradeTimers()
            val hadPending = pendingTradeDoneCallback != null || activeTradeSignal != null || OverlayStateBus.snapshot.value.isExecuting
            val cb = pendingTradeDoneCallback
            pendingTradeDoneCallback = null
            pendingDiscoveryCallback = null
            pendingOnPageFinishedAction = null
            activeTradeAccount = null
            activeTradeSignal = null
            tradeReauthAttempted = false
            symbolGraceUsed = false

            webView?.let { wv ->
                runCatching {
                    wv.evaluateJavascript(WebTerminalScripts.buildCancelTradeScript(cancelledRunId), null)
                }
            }

            if (hadPending) {
                OverlayStateBus.cancelActiveExecution(reason, markAsStopped = markAsStopped)
                OverlayStateBus.appendLog(">> CANCELLED: $reason", isError = false)
                cb?.invoke(false, reason)
            } else if (markAsStopped) {
                OverlayStateBus.updateSnapshot {
                    it.copy(isRunning = false, isExecuting = false, statusPill = "STOPPED", statusDetail = reason)
                }
            }
            stopHeartbeatMonitorIfIdle()
        }
    }

    private fun buildVerifiedAliasesJson(account: SavedAccount, platform: PlatformType): String {
        val cached = runCatching {
            runBlocking(Dispatchers.IO) {
                repo.getDiscoveredCatalog(account.broker, platform, account.server)
            }
        }.getOrNull()
        return if (cached != null && cached.verifiedAliases.isNotEmpty()) {
            JSONObject(cached.verifiedAliases).toString()
        } else {
            "{}"
        }
    }

    fun executeSimulatedTrade(
        account: SavedAccount,
        signal: SimulatedSignalRequest,
        onFinished: (Boolean, String) -> Unit,
    ) {
        mainHandler.post {
            clearTradeTimers()
            if (OverlayStateBus.snapshot.value.skipTrading) {
                val msg = "Skipped ${signal.symbol}: Skip Trades is active."
                OverlayStateBus.appendLog(">> $msg")
                onFinished(false, msg)
                return@post
            }

            val runId = nextRunId()
            symbolGraceUsed = false
            lastWatchdogFailureCategory = WatchdogFailureCategory.NONE
            val effectivePlatform = signal.platform ?: account.platform
            val wv = obtainWebView()
            val terminalUrl = account.terminalUrl.trim()
            configureDesktopViewportForWebView(wv, listOfNotNull(terminalUrl.takeIf { it.isNotBlank() }))
            runCatching {
                wv.onResume()
                wv.resumeTimers()
            }
            ensureDesktopLayoutIfDetached(wv)
            pendingTradeDoneCallback = onFinished
            activeTradeAccount = account
            activeTradeSignal = signal
            tradeReauthAttempted = false

            if (terminalUrl.isBlank()) {
                finishTradeWithError("No broker Terminal URL configured for ${account.broker}. Enter your broker's WebTrader URL in Meta.")
                return@post
            }
            val directionLabel = when (signal.direction) {
                TradeDirection.SELL -> "SELL"
                else -> "BUY"
            }
            val effectiveTimeoutMs = TRADE_TIMEOUT_MS.coerceAtLeast(30_000L + signal.tradeCount * 15_000L)
            val aliasesJson = buildVerifiedAliasesJson(account, effectivePlatform)

            // Clear all previous execution logs for a clean run on every new signal
            OverlayStateBus.beginNewSignalExecution(
                statusTitle = "Signal: ${signal.symbol} $directionLabel",
                detail = "Signal received: ${signal.tradeCount}x ${signal.lot} lots ${signal.symbol} $directionLabel (${effectivePlatform.label})",
            )
            OverlayStateBus.appendLog(
                ">> SIGNAL #$runId: ${signal.symbol} $directionLabel · LOT ${signal.lot} · COUNT ${signal.tradeCount} · ${effectivePlatform.label}"
            )
            startHeartbeatMonitor()

            val runTrade = {
                if (pendingTradeDoneCallback != null && runId == currentRunId && !OverlayStateBus.snapshot.value.skipTrading) {
                    runCatching {
                        wv.onResume()
                        wv.resumeTimers()
                    }
                    ensureDesktopLayoutIfDetached(wv)
                    // Keep OPENING_TERMINAL ACTIVE until JS explicitly verifies the terminal DOM opened (TERMINAL_OK)
                    OverlayStateBus.updateDynamicStep(
                        key = ExecutionStepId.OPENING_TERMINAL.name,
                        stepId = ExecutionStepId.OPENING_TERMINAL,
                        status = ExecutionStepStatus.ACTIVE,
                        label = "Opening terminal",
                        progress = ExecutionStepId.OPENING_TERMINAL.progress,
                        headerStatus = "Opening terminal",
                        detail = "Verifying ${effectivePlatform.label} Web Terminal is open and ready...",
                    )
                    resetPhaseStallWatchdog(runId)
                    val js = WebTerminalScripts.buildTradeScript(
                        symbol = signal.symbol,
                        direction = directionLabel,
                        lot = signal.lot,
                        stopLoss = signal.stopLoss,
                        takeProfit = signal.takeProfit,
                        comment = signal.comment,
                        tradeCount = signal.tradeCount,
                        platform = effectivePlatform.label,
                        broker = account.broker,
                        server = account.server,
                        verifiedAliasesJson = aliasesJson,
                        signalId = signal.signalId,
                        accountLogin = account.login,
                        kotlinRunId = runId,
                    )
                    wv.evaluateJavascript(js, null)
                }
            }

            val fallback = Runnable {
                val pending = pendingOnPageFinishedAction
                if (pending != null && pendingTradeDoneCallback != null && runId == currentRunId) {
                    wv.evaluateJavascript("document.readyState") { state ->
                        val clean = state?.trim('"') ?: ""
                        if (clean == "complete" || (clean == "interactive" && _status.value.progress >= 90)) {
                            pendingOnPageFinishedAction = null
                            pending.invoke()
                        }
                    }
                }
            }
            domFallbackRunnable = fallback
            mainHandler.postDelayed(fallback, DOM_FALLBACK_MS)

            val timeout = Runnable {
                if (pendingTradeDoneCallback != null && runId == currentRunId) {
                    val activeStep = OverlayStateBus.snapshot.value.executionSteps
                        .lastOrNull { it.status == ExecutionStepStatus.ACTIVE }
                        ?.id
                        ?: ExecutionStepId.OPENING_TERMINAL
                    probeHeartbeatNow(effectivePlatform.label) { jsResponded, hb ->
                        if (pendingTradeDoneCallback != null && runId == currentRunId) {
                            handleWatchdogExpiry(runId, activeStep, jsResponded, hb, signal.symbol, effectivePlatform.label)
                        }
                    }
                }
            }
            tradeTimeoutRunnable = timeout
            mainHandler.postDelayed(timeout, effectiveTimeoutMs)

            val curUrl = wv.url
            val curHost = curUrl?.let { runCatching { android.net.Uri.parse(it).host }.getOrNull() }
            val targetHost = runCatching { android.net.Uri.parse(terminalUrl).host }.getOrNull()
            val sameHost = !curHost.isNullOrBlank() && curHost.equals(targetHost, ignoreCase = true)
            val sameSessionOrigin = lastRequestedTerminalUrl.isNotBlank() &&
                normalizeTerminalUrl(lastRequestedTerminalUrl) == normalizeTerminalUrl(terminalUrl)
            val activeTerminalLoaded = !curUrl.isNullOrBlank() && !curUrl.equals("about:blank", ignoreCase = true) &&
                _status.value.lastError == null &&
                (_status.value.isPageReady || _heartbeat.value.terminalLoaded || _heartbeat.value.authenticated)
            if (activeTerminalLoaded && (sameHost || sameSessionOrigin || _heartbeat.value.authenticated)) {
                runTrade()
            } else {
                lastRequestedTerminalUrl = terminalUrl
                OverlayStateBus.updateDynamicStep(
                    key = ExecutionStepId.OPENING_TERMINAL.name,
                    stepId = ExecutionStepId.OPENING_TERMINAL,
                    status = ExecutionStepStatus.ACTIVE,
                    label = "Opening terminal",
                    progress = ExecutionStepId.OPENING_TERMINAL.progress,
                    headerStatus = "Opening terminal",
                    detail = "Loading ${effectivePlatform.label} Web Terminal...",
                )
                resetPhaseStallWatchdog(runId)
                OverlayStateBus.appendLog("Loading Web Terminal before trade placement...")
                pendingOnPageFinishedAction = runTrade
                wv.loadUrl(terminalUrl)
            }
        }
    }

    fun discoverSymbolsOnTerminal(
        account: SavedAccount,
        requestedSymbol: String,
        onResult: (SymbolDiscoveryResult) -> Unit,
    ) {
        mainHandler.post {
            val wv = obtainWebView()
            val curUrl = wv.url
            if (curUrl.isNullOrBlank() || curUrl == "about:blank") {
                onResult(
                    SymbolDiscoveryResult(
                        success = false,
                        requestedSymbol = requestedSymbol,
                        actualBrokerSymbol = null,
                        broker = account.broker,
                        platform = account.platform,
                        server = account.server,
                        matchType = "TERMINAL_NOT_LOADED",
                        message = "Open or authenticate Web Terminal before running symbol discovery.",
                    )
                )
                return@post
            }
            pendingDiscoveryCallback = onResult
            val aliasesJson = buildVerifiedAliasesJson(account, account.platform)
            val js = WebTerminalScripts.buildSymbolDiscoveryScript(
                platform = account.platform.label,
                requestedSymbol = requestedSymbol,
                broker = account.broker,
                server = account.server,
                verifiedAliasesJson = aliasesJson,
            )
            wv.evaluateJavascript(js, null)
        }
    }

    private fun handleTradePhase(runId: Long, phaseKey: String, detail: String) {
        if (runId != currentRunId || pendingTradeDoneCallback == null) {
            android.util.Log.d("WebTerminalEngine", "Ignored stale onTradePhase($phaseKey) for runId=$runId (active=$currentRunId)")
            return
        }
        val upper = phaseKey.uppercase(Locale.US)
        _heartbeat.value = _heartbeat.value.copy(
            webviewAlive = webView != null && webView?.parent != null,
            javascriptRunning = true,
            currentStage = upper,
            runId = runId,
            lastHeartbeat = System.currentTimeMillis(),
            symbolDiscoveryRunning = upper == "SYMBOL_SEARCHING" || upper == "SYMBOL" || upper == "SYMBOL_SELECTING",
            orderTicketOpen = upper == "PREPARE" || upper == "PREPARED" || upper.startsWith("TRADE_") || upper == "SUBMIT" || upper == "CONFIRM",
        )
        if (upper != "DONE" && upper != "UNKNOWN" && upper != "ERROR") {
            resetPhaseStallWatchdog(runId)
        }
        when {
            upper == "SIGNAL" -> OverlayStateBus.updateDynamicStep(
                key = ExecutionStepId.SIGNAL_RECEIVED.name,
                stepId = ExecutionStepId.SIGNAL_RECEIVED,
                status = ExecutionStepStatus.COMPLETED,
                label = "Signal received",
                progress = ExecutionStepId.SIGNAL_RECEIVED.progress,
                detail = detail,
            )
            upper == "TERMINAL" -> OverlayStateBus.updateDynamicStep(
                key = ExecutionStepId.OPENING_TERMINAL.name,
                stepId = ExecutionStepId.OPENING_TERMINAL,
                status = ExecutionStepStatus.ACTIVE,
                label = "Opening terminal",
                progress = ExecutionStepId.OPENING_TERMINAL.progress,
                detail = detail,
            )
            upper == "TERMINAL_OK" -> {
                OverlayStateBus.updateDynamicStep(
                    key = ExecutionStepId.OPENING_TERMINAL.name,
                    stepId = ExecutionStepId.OPENING_TERMINAL,
                    status = ExecutionStepStatus.COMPLETED,
                    label = "Opening terminal",
                    progress = 0.32f,
                    detail = detail,
                )
                OverlayStateBus.updateDynamicStep(
                    key = ExecutionStepId.AUTHENTICATING_ACCOUNT.name,
                    stepId = ExecutionStepId.AUTHENTICATING_ACCOUNT,
                    status = ExecutionStepStatus.ACTIVE,
                    label = "Authenticating account",
                    progress = ExecutionStepId.AUTHENTICATING_ACCOUNT.progress,
                    headerStatus = "Authenticating account",
                    detail = detail,
                )
            }
            upper == "AUTH" -> OverlayStateBus.updateDynamicStep(
                key = ExecutionStepId.AUTHENTICATING_ACCOUNT.name,
                stepId = ExecutionStepId.AUTHENTICATING_ACCOUNT,
                status = ExecutionStepStatus.ACTIVE,
                label = "Authenticating account",
                progress = ExecutionStepId.AUTHENTICATING_ACCOUNT.progress,
                detail = detail,
            )
            upper == "AUTH_OK" -> OverlayStateBus.updateDynamicStep(
                key = ExecutionStepId.AUTHENTICATING_ACCOUNT.name,
                stepId = ExecutionStepId.AUTHENTICATING_ACCOUNT,
                status = ExecutionStepStatus.COMPLETED,
                label = "Authenticating account",
                progress = 0.48f,
                headerStatus = "Account authenticated",
                detail = detail,
            )
            upper == "SYMBOL_SEARCHING" || upper == "SYMBOL" -> OverlayStateBus.updateDynamicStep(
                key = "SYMBOL",
                stepId = ExecutionStepId.SELECTING_SYMBOL,
                status = ExecutionStepStatus.ACTIVE,
                label = "Searching symbol",
                progress = 0.54f,
                detail = detail,
            )
            upper == "SYMBOL_FOUND" -> OverlayStateBus.updateDynamicStep(
                key = "SYMBOL",
                stepId = ExecutionStepId.SELECTING_SYMBOL,
                status = ExecutionStepStatus.COMPLETED,
                label = "Symbol found",
                progress = 0.58f,
                detail = detail,
            )
            upper == "SYMBOL_SELECTING" -> OverlayStateBus.updateDynamicStep(
                key = "SYMBOL",
                stepId = ExecutionStepId.SELECTING_SYMBOL,
                status = ExecutionStepStatus.ACTIVE,
                label = "Selecting symbol",
                progress = 0.64f,
                detail = detail,
            )
            upper == "SYMBOL_SELECTED" -> OverlayStateBus.updateDynamicStep(
                key = "SYMBOL",
                stepId = ExecutionStepId.SELECTING_SYMBOL,
                status = ExecutionStepStatus.COMPLETED,
                label = "Symbol selected",
                progress = 0.70f,
                detail = detail,
            )
            upper == "PREPARE" -> OverlayStateBus.updateDynamicStep(
                key = "PREPARE",
                stepId = ExecutionStepId.PREPARING_TRADE,
                status = ExecutionStepStatus.ACTIVE,
                label = "Preparing trade",
                progress = 0.76f,
                detail = detail,
            )
            upper == "PREPARED" -> OverlayStateBus.updateDynamicStep(
                key = "PREPARE",
                stepId = ExecutionStepId.PREPARING_TRADE,
                status = ExecutionStepStatus.COMPLETED,
                label = "Preparing trade",
                progress = 0.80f,
                detail = detail,
            )
            upper.startsWith("TRADE_SUBMIT:") -> {
                val parts = upper.split(":")
                val idx = parts.getOrNull(1)?.toIntOrNull() ?: 1
                val total = parts.getOrNull(2)?.toIntOrNull() ?: 1
                val lbl = if (total > 1) "Executing trade $idx/$total" else "Executing trade"
                val frac = ((idx - 1).toFloat() / total.coerceAtLeast(1).toFloat()) * 0.16f
                OverlayStateBus.updateDynamicStep(
                    key = "TRADE",
                    stepId = ExecutionStepId.EXECUTING_TRADE,
                    status = ExecutionStepStatus.ACTIVE,
                    label = lbl,
                    progress = 0.82f + frac,
                    detail = detail,
                )
            }
            upper.startsWith("TRADE_CONFIRM:") -> {
                val parts = upper.split(":")
                val idx = parts.getOrNull(1)?.toIntOrNull() ?: 1
                val total = parts.getOrNull(2)?.toIntOrNull() ?: 1
                val lbl = if (total > 1) "Waiting for broker confirmation ($idx/$total)" else "Waiting for broker confirmation"
                val frac = ((idx - 0.5f) / total.coerceAtLeast(1).toFloat()) * 0.16f
                OverlayStateBus.updateDynamicStep(
                    key = "TRADE",
                    stepId = ExecutionStepId.EXECUTING_TRADE,
                    status = ExecutionStepStatus.ACTIVE,
                    label = lbl,
                    progress = (0.82f + frac).coerceAtMost(0.98f),
                    detail = detail,
                )
            }
            upper.startsWith("TRADE_CONFIRMED:") -> {
                val parts = upper.split(":")
                val idx = parts.getOrNull(1)?.toIntOrNull() ?: 1
                val total = parts.getOrNull(2)?.toIntOrNull() ?: 1
                val lbl = if (total > 1) "Trade $idx/$total confirmed" else "Trade confirmed"
                val frac = (idx.toFloat() / total.coerceAtLeast(1).toFloat()) * 0.16f
                OverlayStateBus.updateDynamicStep(
                    key = "TRADE",
                    stepId = ExecutionStepId.EXECUTING_TRADE,
                    status = ExecutionStepStatus.COMPLETED,
                    label = lbl,
                    progress = (0.82f + frac).coerceAtMost(0.98f),
                    detail = detail,
                )
            }
            upper.startsWith("TERMINAL_CLEAR:") -> {
                val parts = upper.split(":")
                val idx = parts.getOrNull(1)?.toIntOrNull() ?: 1
                val total = parts.getOrNull(2)?.toIntOrNull() ?: 1
                val lbl = if (total > 1) "Trade $idx/$total dialog cleared" else "Terminal dialog cleared"
                OverlayStateBus.updateDynamicStep(
                    key = "TRADE",
                    stepId = ExecutionStepId.EXECUTING_TRADE,
                    status = ExecutionStepStatus.ACTIVE,
                    label = lbl,
                    progress = 0.90f,
                    detail = detail,
                )
            }
            upper == "SUBMIT" -> OverlayStateBus.updateDynamicStep(
                key = "TRADE",
                stepId = ExecutionStepId.EXECUTING_TRADE,
                status = ExecutionStepStatus.ACTIVE,
                label = "Executing trade",
                progress = 0.86f,
                detail = detail,
            )
            upper == "CONFIRM" -> OverlayStateBus.updateDynamicStep(
                key = "TRADE",
                stepId = ExecutionStepId.EXECUTING_TRADE,
                status = ExecutionStepStatus.ACTIVE,
                label = "Waiting for broker confirmation",
                progress = 0.94f,
                detail = detail,
            )
            upper == "DONE" -> OverlayStateBus.completeExecutionSuccess(detail)
            upper == "UNKNOWN" -> OverlayStateBus.markExecutionUnknown(detail)
            upper == "ERROR" -> OverlayStateBus.failExecution(detail)
        }
    }

    private fun handleTradeResult(resultJson: String) {
        runCatching {
            val obj = JSONObject(resultJson)
            if (obj.has("runId") && obj.optLong("runId") != currentRunId) {
                android.util.Log.d("WebTerminalEngine", "Ignored stale onTradeResult for runId=${obj.optLong("runId")} (active=$currentRunId)")
                return
            }
            resetPhaseStallWatchdog(currentRunId)
            val status = BrokerResultStatus.fromWire(obj.optString("status", "UNKNOWN"))
            val platformStr = obj.optString("platform", "MT5").trim().uppercase(Locale.US)
            val platform = if (platformStr == "MT4") PlatformType.MT4 else PlatformType.MT5
            val parsed = BrokerTradeResult(
                status = status,
                platform = platform,
                requestedSymbol = obj.optString("requestedSymbol", ""),
                actualSymbol = obj.optString("actualSymbol", ""),
                direction = obj.optString("direction", ""),
                lot = obj.optString("lot", ""),
                tradeIndex = obj.optInt("tradeIndex", 1),
                tradeCount = obj.optInt("tradeCount", 1),
                confirmedCount = obj.optInt("confirmedCount", 0),
                ticket = obj.optString("ticket", ""),
                rawMessage = obj.optString("rawMessage", ""),
                retried = obj.optBoolean("retried", false),
            )
            OverlayStateBus.recordTradeResult(parsed)
            val summaryLine = when (status) {
                BrokerResultStatus.ACCEPTED ->
                    "Broker ACCEPTED (${parsed.tradeIndex}/${parsed.tradeCount}): ${parsed.actualSymbol.ifBlank { parsed.requestedSymbol }} ${parsed.direction} ${parsed.lot}" +
                        (if (parsed.ticket.isNotBlank()) " [${parsed.ticket}]" else "")
                BrokerResultStatus.REJECTED ->
                    "Broker REJECTED (${parsed.tradeIndex}/${parsed.tradeCount}): ${parsed.rawMessage.ifBlank { "Order rejected" }}"
                BrokerResultStatus.UNKNOWN ->
                    "Broker UNKNOWN (${parsed.tradeIndex}/${parsed.tradeCount}): ${parsed.rawMessage.ifBlank { "Unable to confirm trade submission. The trade was not retried." }}"
            }
            OverlayStateBus.appendLog(
                message = ">> $summaryLine",
                isError = status != BrokerResultStatus.ACCEPTED,
                isSuccess = status == BrokerResultStatus.ACCEPTED,
            )
        }
    }

    private fun handleTradeLog(runId: Long, step: String, isError: Boolean, isDone: Boolean) {
        if (runId != currentRunId || pendingTradeDoneCallback == null) {
            android.util.Log.d("WebTerminalEngine", "Ignored stale onTradeLog for runId=$runId (active=$currentRunId)")
            return
        }
        if (isDone) {
            clearTradeTimers()
        } else {
            resetPhaseStallWatchdog(runId)
        }
        OverlayStateBus.appendLog(
            message = ">> $step",
            isError = isError,
            isSuccess = isDone && !isError,
        )
        val isUnknown = isDone && isError && step.contains("UNKNOWN", ignoreCase = true)
        when {
            isUnknown -> OverlayStateBus.markExecutionUnknown(step)
            isDone && isError -> OverlayStateBus.failExecution(step)
            isDone && !isError -> OverlayStateBus.completeExecutionSuccess(step)
            else -> OverlayStateBus.updateSnapshot {
                it.copy(
                    statusPill = if (isError) "ERROR" else if (it.isExecuting) "EXECUTING" else it.statusPill,
                    statusDetail = step,
                )
            }
        }
        if (isDone) {
            val cb = pendingTradeDoneCallback
            pendingTradeDoneCallback = null
            activeTradeAccount = null
            activeTradeSignal = null
            stopHeartbeatMonitorIfIdle()
            cb?.invoke(!isError, step)
        }
    }

    private fun handleTradeRequiresAuth(runId: Long) {
        if (runId != currentRunId || pendingTradeDoneCallback == null) {
            android.util.Log.d("WebTerminalEngine", "Ignored stale onTradeRequiresAuth for runId=$runId (active=$currentRunId)")
            return
        }
        if (OverlayStateBus.snapshot.value.skipTrading) {
            cancelActiveTradeRun("Skip Trades active — aborted re-authentication.", markAsStopped = false)
            return
        }
        val account = activeTradeAccount
        val signal = activeTradeSignal
        val cb = pendingTradeDoneCallback
        if (account != null && signal != null && account.login.isNotBlank() && account.password.isNotBlank() && !tradeReauthAttempted && cb != null) {
            tradeReauthAttempted = true
            clearTradeTimers()
            OverlayStateBus.updateExecutionPhase(
                stepId = ExecutionStepId.AUTHENTICATING_ACCOUNT,
                headerStatus = ExecutionStepId.AUTHENTICATING_ACCOUNT.label,
                detail = "Logging in to ${account.platform.label} Account (${account.login})...",
            )
            OverlayStateBus.appendLog("Web Terminal session needs login — auto-signing in as ${account.login}...")
            val url = account.terminalUrl.trim()
            if (url.isBlank()) {
                finishTradeWithError("No broker Terminal URL configured for ${account.broker}.")
                return
            }
            authenticateInBackground(
                terminalUrl = url,
                login = account.login,
                password = account.password,
                server = account.server,
                platform = account.platform,
            ) { ok, msg ->
                if (runId != currentRunId) return@authenticateInBackground
                if (OverlayStateBus.snapshot.value.skipTrading) {
                    cancelActiveTradeRun("Skip Trades active — cancelled pending signal.", markAsStopped = false)
                    return@authenticateInBackground
                }
                if (ok) {
                    clearTradeTimers()
                    val effectiveTimeoutMs = TRADE_TIMEOUT_MS.coerceAtLeast(30_000L + signal.tradeCount * 15_000L)
                    val effectivePlatform = signal.platform ?: account.platform
                    val timeout = Runnable {
                        if (pendingTradeDoneCallback != null && runId == currentRunId) {
                            val activeStep = OverlayStateBus.snapshot.value.executionSteps
                                .lastOrNull { it.status == ExecutionStepStatus.ACTIVE }
                                ?.id
                                ?: ExecutionStepId.EXECUTING_TRADE
                            probeHeartbeatNow(effectivePlatform.label) { jsResponded, hb ->
                                if (pendingTradeDoneCallback != null && runId == currentRunId) {
                                    handleWatchdogExpiry(runId, activeStep, jsResponded, hb, signal.symbol, effectivePlatform.label)
                                }
                            }
                        }
                    }
                    tradeTimeoutRunnable = timeout
                    mainHandler.postDelayed(timeout, effectiveTimeoutMs)

                    OverlayStateBus.updateDynamicStep(
                        key = ExecutionStepId.AUTHENTICATING_ACCOUNT.name,
                        stepId = ExecutionStepId.AUTHENTICATING_ACCOUNT,
                        status = ExecutionStepStatus.ACTIVE,
                        label = "Authenticating account",
                        progress = ExecutionStepId.AUTHENTICATING_ACCOUNT.progress,
                        headerStatus = "Authenticating account",
                        detail = "Confirming live session telemetry for ${signal.symbol}...",
                    )
                    resetPhaseStallWatchdog(runId)
                    val dirLabel = if (signal.direction == TradeDirection.SELL) "SELL" else "BUY"
                    val aliasesJson = buildVerifiedAliasesJson(account, effectivePlatform)
                    val js = WebTerminalScripts.buildTradeScript(
                        symbol = signal.symbol,
                        direction = dirLabel,
                        lot = signal.lot,
                        stopLoss = signal.stopLoss,
                        takeProfit = signal.takeProfit,
                        comment = signal.comment,
                        tradeCount = signal.tradeCount,
                        platform = effectivePlatform.label,
                        broker = account.broker,
                        server = account.server,
                        verifiedAliasesJson = aliasesJson,
                        signalId = signal.signalId,
                        accountLogin = account.login,
                        kotlinRunId = runId,
                    )
                    webView?.let { wv ->
                        runCatching {
                            wv.onResume()
                            wv.resumeTimers()
                        }
                        ensureDesktopLayoutIfDetached(wv)
                        wv.evaluateJavascript(js, null)
                    }
                } else {
                    finishTradeWithError(
                        "Auto-login failed: $msg",
                        failedStep = ExecutionStepId.AUTHENTICATING_ACCOUNT,
                    )
                }
            }
        } else {
            finishTradeWithError(
                "Web Terminal requires an authenticated session (open Inspect Live Web Terminal to log in, then tap 'I\\'M LOGGED IN — SAVE SESSION').",
                failedStep = ExecutionStepId.AUTHENTICATING_ACCOUNT,
            )
        }
    }

    private inner class Bridge {
        @JavascriptInterface
        fun onAuthResult(success: Boolean, message: String) {
            mainHandler.post {
                clearAuthTimers()
                if (success) {
                    runCatching { CookieManager.getInstance().flush() }
                }
                OverlayStateBus.appendLog(
                    message = if (success) ">> AUTH OK: $message" else ">> AUTH FAILED: $message",
                    isError = !success,
                    isSuccess = success,
                )
                val cb = pendingAuthCallback
                pendingAuthCallback = null
                cb?.invoke(success, message)
            }
        }

        @JavascriptInterface
        fun onTradePhaseForRun(runId: Long, phaseKey: String, detail: String) {
            mainHandler.post {
                handleTradePhase(runId, phaseKey, detail)
            }
        }

        @JavascriptInterface
        fun onTradePhase(phaseKey: String, detail: String) {
            mainHandler.post {
                handleTradePhase(currentRunId, phaseKey, detail)
            }
        }

        @JavascriptInterface
        fun onTradeResult(resultJson: String) {
            mainHandler.post {
                handleTradeResult(resultJson)
            }
        }

        @JavascriptInterface
        fun onTradeLogForRun(runId: Long, step: String, isError: Boolean, isDone: Boolean) {
            mainHandler.post {
                handleTradeLog(runId, step, isError, isDone)
            }
        }

        @JavascriptInterface
        fun onTradeLog(step: String, isError: Boolean, isDone: Boolean) {
            mainHandler.post {
                handleTradeLog(currentRunId, step, isError, isDone)
            }
        }

        @JavascriptInterface
        fun onSymbolDiscovered(resultJson: String) {
            mainHandler.post {
                runCatching {
                    val obj = JSONObject(resultJson)
                    if (obj.has("runId") && activeTradeSignal != null && obj.optLong("runId") != currentRunId) {
                        android.util.Log.d("WebTerminalEngine", "Ignored stale onSymbolDiscovered for runId=${obj.optLong("runId")}")
                        return@post
                    }
                    val success = obj.optBoolean("success", false)
                    val requested = obj.optString("requestedSymbol", "").trim()
                    val actual = obj.optString("actualSymbol", "").trim().ifEmpty { null }
                    val acct = activeTradeAccount
                    val broker = obj.optString("broker", "").trim().ifEmpty { acct?.broker ?: "" }
                    val platformStr = obj.optString("platform", "").trim().uppercase()
                    val platform = if (platformStr == "MT4") PlatformType.MT4 else (acct?.platform ?: PlatformType.MT5)
                    val server = obj.optString("server", "").trim().ifEmpty { acct?.server ?: "" }
                    val matchType = obj.optString("matchType", if (success) "MATCHED" else "NOT_FOUND")
                    val message = obj.optString("message", "")
                    val arr = obj.optJSONArray("discoveredSymbols")
                    val discovered = buildList {
                        if (arr != null) {
                            for (i in 0 until arr.length()) {
                                val s = arr.optString(i, "").trim()
                                if (s.isNotEmpty()) add(s)
                            }
                        }
                    }

                    val parsed = SymbolDiscoveryResult(
                        success = success,
                        requestedSymbol = requested,
                        actualBrokerSymbol = actual,
                        broker = broker,
                        platform = platform,
                        server = server,
                        matchType = matchType,
                        message = message,
                        discoveredSymbols = discovered,
                    )

                    if (broker.isNotBlank() && (discovered.isNotEmpty() || actual != null)) {
                        engineScope.launch {
                            repo.recordDiscoveredSymbols(
                                broker = broker,
                                platform = platform,
                                server = server,
                                symbols = discovered,
                                requestedSymbol = requested.takeIf { it.isNotBlank() && actual != null && it.equals(actual, ignoreCase = true) },
                                actualBrokerSymbol = actual,
                            )
                        }
                    }

                    val cb = pendingDiscoveryCallback
                    pendingDiscoveryCallback = null
                    cb?.invoke(parsed)
                }
            }
        }

        @JavascriptInterface
        fun onTradeRequiresAuthForRun(runId: Long) {
            mainHandler.post {
                handleTradeRequiresAuth(runId)
            }
        }

        @JavascriptInterface
        fun onTradeRequiresAuth() {
            mainHandler.post {
                handleTradeRequiresAuth(currentRunId)
            }
        }

        @JavascriptInterface
        fun onHeartbeat(payloadJson: String) {
            mainHandler.post {
                parseHeartbeatJson(payloadJson, webView)?.let { parsed ->
                    _heartbeat.value = parsed
                }
            }
        }

        @JavascriptInterface
        fun onBalanceUpdate(payloadJson: String) {
            mainHandler.post {
                runCatching {
                    val obj = JSONObject(payloadJson)
                    val connected = obj.optBoolean("connected", false)
                    val balance = obj.optString("balance", "").trim()
                    val equity = obj.optString("equity", "").trim()
                    val profit = obj.optString("profit", "").trim()
                    val margin = obj.optString("margin", "").trim()
                    val status = obj.optString("status", "").trim().ifEmpty {
                        if (connected) "Live Web Terminal telemetry" else "Waiting for Web Terminal"
                    }
                    val now = balanceTimeFormat.format(Date())
                    _heartbeat.value = _heartbeat.value.copy(
                        webviewAlive = webView != null && webView?.parent != null,
                        javascriptRunning = true,
                        authenticated = connected || _heartbeat.value.authenticated,
                        lastHeartbeat = System.currentTimeMillis(),
                    )
                    OverlayStateBus.updateSnapshot { cur ->
                        cur.copy(
                            isConnected = connected || cur.isConnected,
                            accountBalance = if (balance.isNotEmpty()) balance else cur.accountBalance,
                            accountEquity = if (equity.isNotEmpty()) equity else cur.accountEquity,
                            accountFloatingPl = if (profit.isNotEmpty()) profit else cur.accountFloatingPl,
                            accountMargin = if (margin.isNotEmpty()) margin else cur.accountMargin,
                            balanceUpdatedAt = if (balance.isNotEmpty() || equity.isNotEmpty()) now else cur.balanceUpdatedAt,
                            balanceStatus = status,
                        )
                    }
                }
            }
        }
    }
}
