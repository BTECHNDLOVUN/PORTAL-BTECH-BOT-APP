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
import com.example.BuildConfig
import com.example.data.BtechPreferencesRepository
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class WebTerminalStatus(
    val currentUrl: String = "",
    val pageTitle: String = "",
    val progress: Int = 0,
    val isLoading: Boolean = false,
    val isPageReady: Boolean = false,
    val isDesktopMode: Boolean = true,
    val lastError: String? = null,
)

class WebTerminalEngine private constructor(private val appContext: Context) {

    companion object {
        private const val AUTH_TIMEOUT_MS = 60_000L
        private const val DOM_FALLBACK_MS = 8_000L
        private const val TRADE_TIMEOUT_MS = 60_000L
        private const val PHASE_STALL_TIMEOUT_MS = 28_000L
        private const val BALANCE_POLL_INTERVAL_MS = 2_500L

        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var INSTANCE: WebTerminalEngine? = null

        fun getInstance(context: Context): WebTerminalEngine {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: WebTerminalEngine(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val repo by lazy { BtechPreferencesRepository(appContext) }
    private val contextWrapper = MutableContextWrapper(appContext)
    private var webView: WebView? = null
    private var useSoftwareLayer = false

    private val _status = MutableStateFlow(WebTerminalStatus())
    val status = _status.asStateFlow()

    private var pendingAuthCallback: ((Boolean, String) -> Unit)? = null
    private var pendingTradeDoneCallback: ((Boolean, String) -> Unit)? = null
    private var pendingDiscoveryCallback: ((SymbolDiscoveryResult) -> Unit)? = null
    private var pendingOnPageFinishedAction: (() -> Unit)? = null
    private var activeTradeAccount: SavedAccount? = null
    private var activeTradeSignal: SimulatedSignalRequest? = null
    private var tradeReauthAttempted: Boolean = false

    private var domFallbackRunnable: Runnable? = null
    private var authTimeoutRunnable: Runnable? = null
    private var tradeTimeoutRunnable: Runnable? = null
    private var phaseStallRunnable: Runnable? = null
    private var balancePollRunnable: Runnable? = null
    private var isBalancePollingActive: Boolean = false
    private val balanceTimeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

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

    private fun resetPhaseStallWatchdog() {
        phaseStallRunnable?.let { mainHandler.removeCallbacks(it) }
        phaseStallRunnable = null
        if (pendingTradeDoneCallback == null || pendingAuthCallback != null) return

        val runner = Runnable {
            if (pendingTradeDoneCallback == null || pendingAuthCallback != null) return@Runnable
            val account = activeTradeAccount
            val signal = activeTradeSignal
            val activeStepId = OverlayStateBus.snapshot.value.executionSteps
                .lastOrNull { it.status == ExecutionStepStatus.ACTIVE }
                ?.id
                ?: ExecutionStepId.OPENING_TERMINAL

            if (activeStepId == ExecutionStepId.EXECUTING_TRADE) {
                runCatching { webView?.evaluateJavascript(WebTerminalScripts.buildCancelTradeScript(), null) }
                finishTradeWithUnknown(
                    "Trade confirmation stalled without broker response — error:Unable to confirm trade submission. The trade was not retried."
                )
            } else {
                runCatching { webView?.evaluateJavascript(WebTerminalScripts.buildCancelTradeScript(), null) }
                val platformLabel = (signal?.platform ?: account?.platform)?.label ?: "Web"
                val symbolName = signal?.symbol ?: "symbol"
                val msg = when (activeStepId) {
                    ExecutionStepId.OPENING_TERMINAL ->
                        "Web Terminal did not confirm opening ($platformLabel). Check connection or open Inspect Live Web Terminal."
                    ExecutionStepId.AUTHENTICATING_ACCOUNT ->
                        "Could not confirm an active authenticated session in $platformLabel Web Terminal (no live Balance or Market Watch quotes). Open Inspect Live Web Terminal or re-authenticate."
                    ExecutionStepId.SELECTING_SYMBOL ->
                        "Symbol search stalled for $symbolName inside $platformLabel Web Terminal. Verify Market Watch / Quotes loaded."
                    ExecutionStepId.PREPARING_TRADE ->
                        "Order form preparation stalled for $symbolName inside $platformLabel Web Terminal."
                    else ->
                        "Execution stalled before trade submission for $symbolName."
                }
                finishTradeWithError(msg, failedStep = activeStepId)
            }
        }
        phaseStallRunnable = runner
        mainHandler.postDelayed(runner, PHASE_STALL_TIMEOUT_MS)
    }

    private var defaultChromeMobileUa: String = ""

    private fun applyCleanUserAgent(settings: WebSettings, desktopMode: Boolean) {
        if (defaultChromeMobileUa.isEmpty()) {
            val base = WebSettings.getDefaultUserAgent(appContext)
            // Removing "; wv" & "Version/4.0" prevents Cloudflare & broker gateways from blocking WebView as an embedded wrapper.
            defaultChromeMobileUa = base
                .replace("; wv)", ")")
                .replace("; wv;", ";")
                .replace("Version/4.0 ", "")
        }
        settings.userAgentString = if (desktopMode) {
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
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
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    if (url == "about:blank") return
                    _status.value = _status.value.copy(
                        currentUrl = url ?: "",
                        progress = 5,
                        isLoading = true,
                        isPageReady = false,
                        lastError = null,
                    )
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
        return created
    }

    fun navigateToUrl(url: String) {
        val clean = url.trim().let {
            if (it.startsWith("http://") || it.startsWith("https://")) it else "https://$it"
        }
        mainHandler.post {
            val wv = obtainWebView()
            wv.loadUrl(clean)
        }
    }

    fun reload() {
        mainHandler.post {
            webView?.reload()
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
                applyCleanUserAgent(wv.settings, next)
                wv.reload()
            }
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
    }

    fun detachIfParent(expectedParent: ViewGroup) {
        val wv = webView ?: return
        if (wv.parent === expectedParent) {
            expectedParent.removeView(wv)
            contextWrapper.baseContext = appContext
        }
    }

    fun detachFromParent() {
        val detachAction = {
            contextWrapper.baseContext = appContext
            val wv = webView
            if (wv != null) {
                (wv.parent as? ViewGroup)?.removeView(wv)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            detachAction()
        } else {
            mainHandler.post(detachAction)
        }
    }

    private fun ensureDesktopLayoutIfDetached(wv: WebView) {
        if (wv.parent == null && (wv.width <= 0 || wv.height <= 0)) {
            runCatching {
                val widthSpec = View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY)
                val heightSpec = View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY)
                wv.measure(widthSpec, heightSpec)
                wv.layout(0, 0, 1280, 800)
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

            if (!clearPreviousSession && wv.url == terminalUrl && _status.value.isPageReady) {
                clearAuthTimers()
                authTimeoutRunnable = timeout
                mainHandler.postDelayed(timeout, AUTH_TIMEOUT_MS)
                runScript()
            } else {
                pendingOnPageFinishedAction = runScript
                wv.loadUrl(terminalUrl)
            }
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
        }
    }

    fun loadUrlForInspection(url: String) {
        mainHandler.post {
            val wv = obtainWebView()
            if (wv.url != url) {
                wv.loadUrl(url)
            }
        }
    }

    fun startBalancePolling(account: SavedAccount? = null) {
        mainHandler.post {
            if (account != null && account.terminalUrl.isNotBlank()) {
                val wv = obtainWebView()
                if (wv.url.isNullOrBlank()) {
                    OverlayStateBus.updateSnapshot {
                        it.copy(balanceStatus = "Loading ${account.broker} Web Terminal...")
                    }
                    wv.loadUrl(account.terminalUrl.trim())
                }
            }
            if (isBalancePollingActive) {
                pollBalanceOnce()
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
        }
    }

    fun stopBalancePolling() {
        mainHandler.post {
            isBalancePollingActive = false
            balancePollRunnable?.let { mainHandler.removeCallbacks(it) }
            balancePollRunnable = null
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
            clearTradeTimers()
            val hadPending = pendingTradeDoneCallback != null || activeTradeSignal != null || OverlayStateBus.snapshot.value.isExecuting
            val cb = pendingTradeDoneCallback
            pendingTradeDoneCallback = null
            pendingDiscoveryCallback = null
            pendingOnPageFinishedAction = null
            activeTradeAccount = null
            activeTradeSignal = null
            tradeReauthAttempted = false

            webView?.let { wv ->
                runCatching {
                    wv.evaluateJavascript(WebTerminalScripts.buildCancelTradeScript(), null)
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

            val effectivePlatform = signal.platform ?: account.platform
            val wv = obtainWebView()
            runCatching { wv.onResume() }
            ensureDesktopLayoutIfDetached(wv)
            pendingTradeDoneCallback = onFinished
            activeTradeAccount = account
            activeTradeSignal = signal
            tradeReauthAttempted = false

            val terminalUrl = account.terminalUrl.trim()
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
                ">> SIGNAL: ${signal.symbol} $directionLabel · LOT ${signal.lot} · COUNT ${signal.tradeCount} · ${effectivePlatform.label}"
            )

            val runTrade = {
                if (pendingTradeDoneCallback != null && !OverlayStateBus.snapshot.value.skipTrading) {
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
                    resetPhaseStallWatchdog()
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
                    )
                    wv.evaluateJavascript(js, null)
                }
            }

            val fallback = Runnable {
                val pending = pendingOnPageFinishedAction
                if (pending != null && pendingTradeDoneCallback != null) {
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
                if (pendingTradeDoneCallback != null) {
                    runCatching { wv.evaluateJavascript(WebTerminalScripts.buildCancelTradeScript(), null) }
                    val activeStep = OverlayStateBus.snapshot.value.executionSteps
                        .lastOrNull { it.status == ExecutionStepStatus.ACTIVE }
                        ?.id
                    if (activeStep == ExecutionStepId.EXECUTING_TRADE) {
                        finishTradeWithUnknown(
                            "Trading terminated after ${effectiveTimeoutMs / 1000} seconds — error:Unable to confirm trade submission. The trade was not retried."
                        )
                    } else {
                        finishTradeWithError(
                            "Trading terminated after ${effectiveTimeoutMs / 1000} seconds before order placement could start (${activeStep?.label ?: "Terminal check"}).",
                            failedStep = activeStep,
                        )
                    }
                }
            }
            tradeTimeoutRunnable = timeout
            mainHandler.postDelayed(timeout, effectiveTimeoutMs)

            val curUrl = wv.url
            val curHost = curUrl?.let { runCatching { android.net.Uri.parse(it).host }.getOrNull() }
            val targetHost = runCatching { android.net.Uri.parse(terminalUrl).host }.getOrNull()
            val sameHost = !curHost.isNullOrBlank() && curHost.equals(targetHost, ignoreCase = true)
            if (_status.value.isPageReady && _status.value.lastError == null && !curUrl.isNullOrBlank() && curUrl != "about:blank" && sameHost) {
                runTrade()
            } else {
                OverlayStateBus.updateDynamicStep(
                    key = ExecutionStepId.OPENING_TERMINAL.name,
                    stepId = ExecutionStepId.OPENING_TERMINAL,
                    status = ExecutionStepStatus.ACTIVE,
                    label = "Opening terminal",
                    progress = ExecutionStepId.OPENING_TERMINAL.progress,
                    headerStatus = "Opening terminal",
                    detail = "Loading ${effectivePlatform.label} Web Terminal...",
                )
                resetPhaseStallWatchdog()
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
        fun onTradePhase(phaseKey: String, detail: String) {
            mainHandler.post {
                val upper = phaseKey.uppercase(Locale.US)
                if (upper != "DONE" && upper != "UNKNOWN" && upper != "ERROR") {
                    resetPhaseStallWatchdog()
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
        }

        @JavascriptInterface
        fun onTradeResult(resultJson: String) {
            mainHandler.post {
                resetPhaseStallWatchdog()
                runCatching {
                    val obj = JSONObject(resultJson)
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
        }

        @JavascriptInterface
        fun onTradeLog(step: String, isError: Boolean, isDone: Boolean) {
            mainHandler.post {
                if (isDone) {
                    clearTradeTimers()
                } else {
                    resetPhaseStallWatchdog()
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
                    cb?.invoke(!isError, step)
                }
            }
        }

        @JavascriptInterface
        fun onSymbolDiscovered(resultJson: String) {
            mainHandler.post {
                runCatching {
                    val obj = JSONObject(resultJson)
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
        fun onTradeRequiresAuth() {
            mainHandler.post {
                if (OverlayStateBus.snapshot.value.skipTrading) {
                    cancelActiveTradeRun("Skip Trades active — aborted re-authentication.", markAsStopped = false)
                    return@post
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
                        return@post
                    }
                    authenticateInBackground(
                        terminalUrl = url,
                        login = account.login,
                        password = account.password,
                        server = account.server,
                        platform = account.platform,
                    ) { ok, msg ->
                        if (OverlayStateBus.snapshot.value.skipTrading) {
                            cancelActiveTradeRun("Skip Trades active — cancelled pending signal.", markAsStopped = false)
                            return@authenticateInBackground
                        }
                        if (ok) {
                            clearTradeTimers()
                            val effectiveTimeoutMs = TRADE_TIMEOUT_MS.coerceAtLeast(30_000L + signal.tradeCount * 15_000L)
                            val timeout = Runnable {
                                if (pendingTradeDoneCallback != null) {
                                    runCatching { webView?.evaluateJavascript(WebTerminalScripts.buildCancelTradeScript(), null) }
                                    finishTradeWithUnknown(
                                        "Trading terminated after ${effectiveTimeoutMs / 1000} seconds — error:Unable to confirm trade submission. The trade was not retried."
                                    )
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
                            resetPhaseStallWatchdog()
                            val effectivePlatform = signal.platform ?: account.platform
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
