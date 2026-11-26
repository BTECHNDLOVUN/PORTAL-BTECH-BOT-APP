package com.example

import android.app.Activity
import android.content.Context
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.example.model.PlatformType
import com.example.model.SavedAccount
import com.example.model.SimulatedSignalRequest
import com.example.model.TradeDirection
import com.example.service.ExecutionStepId
import com.example.service.ExecutionStepStatus
import com.example.service.OverlayStateBus
import com.example.webterminal.WatchdogFailureCategory
import com.example.webterminal.WebTerminalEngine
import com.example.webterminal.WebTerminalHeartbeat
import com.example.webterminal.WebTerminalScripts
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WebViewLifecycleHeartbeatTest {

    private fun testAccount(platform: PlatformType = PlatformType.MT5): SavedAccount = SavedAccount(
        broker = "JustMarkets",
        platform = platform,
        login = "477786797",
        password = "secret",
        server = "JustMarkets-Live",
        serverId = "jm-live",
        terminalUrl = if (platform == PlatformType.MT5) {
            "https://live.justmarkets.com"
        } else {
            "https://metatraderweb.app/terminal"
        },
        authenticated = true,
        connectionStatus = "CONNECTED",
    )

    private fun testSignal(symbol: String = "XAUUSD.s", count: Int = 3): SimulatedSignalRequest =
        SimulatedSignalRequest(
            symbol = symbol,
            direction = TradeDirection.BUY,
            lot = "0.01",
            stopLoss = "",
            takeProfit = "",
            comment = "BTECH_TEST",
            tradeCount = count,
            platform = PlatformType.MT5,
            signalId = "sig-test-1",
        )

    // -------------------------------------------------------------------------
    // Test A: Background Authentication & Persistent Background WebView Attachment
    // -------------------------------------------------------------------------

    @Test
    fun `Test A - singleton WebView stays attached to background host and heartbeat records structured health`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val engine = WebTerminalEngine.getInstance(activity)

        val bgHost = FrameLayout(activity)
        activity.setContentView(bgHost)

        val wv1 = engine.obtainWebView(activity)
        engine.registerBackgroundHost(bgHost, activity)

        assertTrue(engine.hasWebView())
        assertTrue(engine.isWebViewAttached())
        assertSame(bgHost, wv1.parent)
        assertEquals(View.VISIBLE, wv1.visibility)
        assertSame(wv1, engine.obtainWebView(activity))

        val hbJson = JSONObject().apply {
            put("webview_alive", true)
            put("javascript_running", true)
            put("terminal_loaded", true)
            put("authenticated", true)
            put("dom_ready", true)
            put("symbol_discovery_running", false)
            put("order_ticket_open", false)
            put("current_stage", "READY")
            put("run_id", 0L)
            put("last_heartbeat", System.currentTimeMillis())
            put("platform", "MT5")
            put("url", "https://mt5trial9.exwebterm.com/terminal")
        }.toString()

        val parsed = engine.processHeartbeatJsonForTest(hbJson, webViewPresent = true, webViewAttached = true)!!
        assertTrue(parsed.webviewAlive)
        assertTrue(parsed.javascriptRunning)
        assertTrue(parsed.terminalLoaded)
        assertTrue(parsed.authenticated)
        assertTrue(parsed.domReady)
        assertFalse(parsed.symbolDiscoveryRunning)
        assertFalse(parsed.orderTicketOpen)
        assertEquals("READY", parsed.currentStage)
        assertTrue(parsed.lastHeartbeat > 0L)

        val wireJson = parsed.toStructuredJson()
        assertTrue(wireJson.getBoolean("webview_alive"))
        assertTrue(wireJson.getBoolean("javascript_running"))
        assertTrue(wireJson.getBoolean("authenticated"))
        assertEquals("READY", wireJson.getString("current_stage"))

        engine.unregisterBackgroundHost(bgHost)
        controller.pause().stop().destroy()
    }

    // -------------------------------------------------------------------------
    // Test B: Automatic Signal Execution Without Opening Inspect + Single-Line Counter
    // -------------------------------------------------------------------------

    @Test
    fun `Test B - signal progresses end to end in background without Inspect and updates single Trade row`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = WebTerminalEngine.getInstance(context)

        var doneCalled = false
        var doneSuccess = false
        var doneSummary = ""

        val runId = engine.startSyntheticTradeRunForTest(
            account = testAccount(),
            signal = testSignal("XAUUSD.s", count = 3),
        ) { ok, msg ->
            doneCalled = true
            doneSuccess = ok
            doneSummary = msg
        }

        engine.dispatchTradePhaseForTest(runId, "TERMINAL_OK", "MT5 terminal confirmed open")
        engine.dispatchTradePhaseForTest(runId, "AUTH_OK", "Account authenticated")
        engine.dispatchTradePhaseForTest(runId, "SYMBOL_SEARCHING", "Searching XAUUSD.s")
        engine.dispatchTradePhaseForTest(runId, "SYMBOL_FOUND", "Symbol found: XAUUSD.s")
        engine.dispatchTradePhaseForTest(runId, "SYMBOL_SELECTED", "Selected XAUUSD.s")
        engine.dispatchTradePhaseForTest(runId, "PREPARE", "Preparing trade 1/3")
        engine.dispatchTradePhaseForTest(runId, "PREPARED", "Prepared trade 1/3")
        engine.dispatchTradePhaseForTest(runId, "TRADE_SUBMIT:1:3", "Submitting 1/3")
        engine.dispatchTradePhaseForTest(runId, "TRADE_CONFIRMED:1:3", "Trade 1/3 confirmed")
        engine.dispatchTradePhaseForTest(runId, "TRADE_SUBMIT:2:3", "Submitting 2/3")
        engine.dispatchTradePhaseForTest(runId, "TRADE_CONFIRMED:2:3", "Trade 2/3 confirmed")
        engine.dispatchTradePhaseForTest(runId, "TRADE_SUBMIT:3:3", "Submitting 3/3")
        engine.dispatchTradePhaseForTest(runId, "TRADE_CONFIRMED:3:3", "Trade 3/3 confirmed")

        engine.dispatchTradeResultForTest(
            JSONObject().apply {
                put("runId", runId)
                put("status", "ACCEPTED")
                put("platform", "MT5")
                put("requestedSymbol", "XAUUSD.s")
                put("actualSymbol", "XAUUSD.s")
                put("direction", "BUY")
                put("lot", "0.01")
                put("tradeIndex", 3)
                put("tradeCount", 3)
                put("confirmedCount", 3)
                put("ticket", "#9912345")
            }.toString()
        )
        engine.dispatchTradeLogForTest(runId, "All 3/3 trades confirmed", isError = false, isDone = true)

        assertTrue(doneCalled)
        assertTrue(doneSuccess)
        assertEquals("All 3/3 trades confirmed", doneSummary)

        val snap = OverlayStateBus.snapshot.value
        assertTrue(snap.executionCompleted)
        val tradeSteps = snap.executionSteps.filter { it.id == ExecutionStepId.EXECUTING_TRADE }
        assertEquals("Single TRADE row must update in place rather than stacking rows", 1, tradeSteps.size)
        assertEquals("Executed", tradeSteps.first().label)
        val completedSteps = snap.executionSteps.filter { it.id == ExecutionStepId.TRADE_COMPLETED }
        assertEquals(1, completedSteps.size)
        assertEquals("Trade 3/3 completed", completedSteps.first().label)
    }

    // -------------------------------------------------------------------------
    // Test C & Test D: Inspect During Execution + Close Inspect Atomic Reparenting
    // -------------------------------------------------------------------------

    @Test
    fun `Test C and D - Inspect preserves redirected terminal URL and reparents atomically back on close`() {
        // 1. URL preservation checks
        assertFalse(
            "Redirected active session (live.justmarkets.com -> mt5trial9.exwebterm.com/terminal) must NOT reload on Inspect",
            WebTerminalEngine.shouldNavigateForInspection(
                currentWebViewUrl = "https://mt5trial9.exwebterm.com/terminal",
                lastRequestedUrl = "https://live.justmarkets.com",
                targetUrl = "https://live.justmarkets.com/",
                lastError = null,
                forceReload = false,
            ),
        )
        assertFalse(
            "Trailing slash difference must NOT reload existing URL",
            WebTerminalEngine.shouldNavigateForInspection(
                currentWebViewUrl = "https://metatraderweb.app/terminal/",
                lastRequestedUrl = "https://metatraderweb.app/terminal",
                targetUrl = "https://metatraderweb.app/terminal",
                lastError = null,
                forceReload = false,
            ),
        )
        assertTrue(
            "about:blank must trigger navigation when targetUrl is provided",
            WebTerminalEngine.shouldNavigateForInspection(
                currentWebViewUrl = "about:blank",
                lastRequestedUrl = "",
                targetUrl = "https://live.justmarkets.com",
                lastError = null,
                forceReload = false,
            ),
        )

        // 2. Atomic reparenting between BackgroundHost <-> InspectDialog while active trade run continues
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val engine = WebTerminalEngine.getInstance(activity)

        val root = FrameLayout(activity)
        val bgHost = FrameLayout(activity)
        val inspectorHost = FrameLayout(activity)
        root.addView(bgHost)
        root.addView(inspectorHost)
        activity.setContentView(root)

        val wv = engine.obtainWebView(activity)
        engine.registerBackgroundHost(bgHost, activity)
        assertSame(bgHost, wv.parent)

        var tradeCompleted = false
        val activeRunId = engine.startSyntheticTradeRunForTest(testAccount(), testSignal()) { ok, _ ->
            tradeCompleted = ok
        }
        engine.dispatchTradePhaseForTest(activeRunId, "SYMBOL_SEARCHING", "Searching XAUUSD.s")

        // Test C: Open Inspect while trade is running -> moves to inspectorHost without wiping runId or resetting steps
        engine.setInspectMode(true)
        engine.attachToContainer(inspectorHost, activity)
        assertSame(inspectorHost, wv.parent)
        assertEquals(activeRunId, engine.getCurrentRunId())
        assertTrue(OverlayStateBus.snapshot.value.isExecuting)

        // Background host update during Inspect must NOT steal WebView away
        engine.registerBackgroundHost(bgHost, activity)
        assertSame(inspectorHost, wv.parent)

        // Test D: Close Inspect -> atomic reparent straight back into bgHost (parent never left null)
        engine.setInspectMode(false)
        engine.detachIfParent(inspectorHost)
        assertSame("WebView must move straight back to bgHost without being left orphaned", bgHost, wv.parent)

        // Background execution continues seamlessly and completes
        engine.dispatchTradePhaseForTest(activeRunId, "TRADE_CONFIRMED:1:1", "Trade confirmed")
        engine.dispatchTradeLogForTest(activeRunId, "Trade confirmed", isError = false, isDone = true)
        assertTrue(tradeCompleted)

        engine.unregisterBackgroundHost(bgHost)
        controller.pause().stop().destroy()
    }

    // -------------------------------------------------------------------------
    // Test E: Repeated Signals & Stale Run-ID Callback Isolation
    // -------------------------------------------------------------------------

    @Test
    fun `Test E - stale callbacks from aborted signal runId are ignored and cannot corrupt new signal`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = WebTerminalEngine.getInstance(context)

        var run1DoneCalled = false
        val run1 = engine.startSyntheticTradeRunForTest(
            account = testAccount(),
            signal = testSignal("EURUSD", count = 1),
        ) { _, _ ->
            run1DoneCalled = true
        }
        engine.dispatchTradePhaseForTest(run1, "SYMBOL_SEARCHING", "Searching EURUSD")

        var run2Success = false
        var run2DoneCalled = false
        val run2 = engine.startSyntheticTradeRunForTest(
            account = testAccount(),
            signal = testSignal("XAUUSD.s", count = 2),
        ) { ok, _ ->
            run2DoneCalled = true
            run2Success = ok
        }
        assertTrue(run2 > run1)

        // Inject stale failure/error/result callbacks belonging to run1 -> must be dropped cleanly
        engine.dispatchTradePhaseForTest(run1, "ERROR", "Stale failure from signal 1")
        engine.dispatchTradeResultForTest(
            JSONObject().apply {
                put("runId", run1)
                put("status", "REJECTED")
                put("rawMessage", "Stale rejection from signal 1")
            }.toString()
        )
        engine.dispatchTradeLogForTest(run1, "Stale completion from signal 1", isError = true, isDone = true)

        assertFalse(run1DoneCalled)
        assertFalse(run2DoneCalled)
        assertTrue("Signal 2 execution must still be active", OverlayStateBus.snapshot.value.isExecuting)
        assertFalse("Stale signal 1 error must NOT mark Signal 2 failed", OverlayStateBus.snapshot.value.executionFailed)
        assertEquals(null, OverlayStateBus.snapshot.value.lastTradeResult)

        // Now Signal 2 finishes legitimately
        engine.dispatchTradePhaseForTest(run2, "TRADE_CONFIRMED:2:2", "Trade 2/2 confirmed")
        engine.dispatchTradeLogForTest(run2, "All 2/2 confirmed", isError = false, isDone = true)
        assertTrue(run2DoneCalled)
        assertTrue(run2Success)

        // Also confirm cancel script with stale run1 won't cancel newer run2 in JS
        val cancelRun1Js = WebTerminalScripts.buildCancelTradeScript(run1)
        assertTrue(cancelRun1Js.contains("window.__btechActiveKotlinRunId !== $run1"))
    }

    // -------------------------------------------------------------------------
    // Test F: Lost Connection, JS Heartbeat Probe Execution, & Watchdog Classification
    // -------------------------------------------------------------------------

    @Test
    fun `Test F - watchdog distinguishes attachment, loading, JS freeze, DOM, auth, discovery, ticket, and broker stalls`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = WebTerminalEngine.getInstance(context)
        val healthyHb = WebTerminalHeartbeat(
            webviewAlive = true,
            javascriptRunning = true,
            terminalLoaded = true,
            authenticated = true,
            domReady = true,
            symbolDiscoveryRunning = false,
            orderTicketOpen = false,
            currentStage = "SYMBOL_SEARCHING",
            runId = 10L,
            lastHeartbeat = 1000L,
            currentUrl = "https://mt5trial9.exwebterm.com/terminal",
        )

        val detached = WebTerminalEngine.classifyWatchdogStall(
            activeStepId = ExecutionStepId.SELECTING_SYMBOL,
            webViewPresent = true,
            webViewAttached = false,
            isLoading = false,
            currentUrl = "https://mt5trial9.exwebterm.com/terminal",
            jsResponded = false,
            heartbeat = healthyHb.copy(webviewAlive = false),
            symbolName = "XAUUSD.s",
            platformLabel = "MT5",
        )
        assertEquals(WatchdogFailureCategory.WEBVIEW_NOT_ATTACHED, detached.first)

        val jsFrozen = engine.evaluateWatchdogForTest(
            runId = 10L,
            activeStepId = ExecutionStepId.SELECTING_SYMBOL,
            jsResponded = false,
            heartbeat = healthyHb.copy(javascriptRunning = false),
            symbolName = "XAUUSD.s",
            platformLabel = "MT5",
        )
        assertEquals(WatchdogFailureCategory.JS_NOT_RESPONDING, jsFrozen.first)
        assertFalse("JS freeze must NEVER blame missing symbol", jsFrozen.second.contains("not found", ignoreCase = true))

        val domNotReady = engine.evaluateWatchdogForTest(
            runId = 10L,
            activeStepId = ExecutionStepId.OPENING_TERMINAL,
            jsResponded = true,
            heartbeat = healthyHb.copy(domReady = false, terminalLoaded = false),
            symbolName = "XAUUSD.s",
            platformLabel = "MT5",
        )
        assertEquals(WatchdogFailureCategory.DOM_NOT_READY, domNotReady.first)

        val authIncomplete = engine.evaluateWatchdogForTest(
            runId = 10L,
            activeStepId = ExecutionStepId.AUTHENTICATING_ACCOUNT,
            jsResponded = true,
            heartbeat = healthyHb.copy(authenticated = false),
            symbolName = "XAUUSD.s",
            platformLabel = "MT5",
        )
        assertEquals(WatchdogFailureCategory.AUTH_INCOMPLETE, authIncomplete.first)

        val searchingActive = engine.evaluateWatchdogForTest(
            runId = 10L,
            activeStepId = ExecutionStepId.SELECTING_SYMBOL,
            jsResponded = true,
            heartbeat = healthyHb.copy(symbolDiscoveryRunning = true),
            symbolName = "XAUUSD.s",
            platformLabel = "MT5",
        )
        assertEquals(WatchdogFailureCategory.SYMBOL_SEARCH_RUNNING, searchingActive.first)

        val symbolNotFound = engine.evaluateWatchdogForTest(
            runId = 10L,
            activeStepId = ExecutionStepId.SELECTING_SYMBOL,
            jsResponded = true,
            heartbeat = healthyHb.copy(symbolDiscoveryRunning = false),
            symbolName = "XAUUSD.s",
            platformLabel = "MT5",
        )
        assertEquals(WatchdogFailureCategory.SYMBOL_GENUINELY_NOT_FOUND, symbolNotFound.first)

        val ticketMissing = engine.evaluateWatchdogForTest(
            runId = 10L,
            activeStepId = ExecutionStepId.PREPARING_TRADE,
            jsResponded = true,
            heartbeat = healthyHb.copy(orderTicketOpen = false),
            symbolName = "XAUUSD.s",
            platformLabel = "MT5",
        )
        assertEquals(WatchdogFailureCategory.ORDER_TICKET_NOT_FOUND, ticketMissing.first)

        val brokerPending = engine.evaluateWatchdogForTest(
            runId = 10L,
            activeStepId = ExecutionStepId.EXECUTING_TRADE,
            jsResponded = true,
            heartbeat = healthyHb.copy(orderTicketOpen = true),
            symbolName = "XAUUSD.s",
            platformLabel = "MT5",
        )
        assertEquals(WatchdogFailureCategory.BROKER_RESPONSE_PENDING, brokerPending.first)
        assertTrue(brokerPending.second.contains("not retried", ignoreCase = true))
    }

    @Test
    fun `Test F2 - real JS heartbeat probe detects connected, searching, and disconnected terminal DOM states`() {
        val nodeBinary = listOf("/usr/bin/node", "/usr/local/bin/node").firstOrNull { File(it).canExecute() }
            ?: return // gracefully skip only if host has no Node CLI
        val probeJs = WebTerminalScripts.buildHeartbeatProbeScript("MT5").trim().removeSuffix(";")
        val script = """
            function mkEl(tag, text) {
              return {
                tagName: tag,
                nodeType: 1,
                hidden: false,
                disabled: false,
                style: { display: 'block', visibility: 'visible', opacity: '1', pointerEvents: 'auto' },
                children: [],
                _innerText: text || '',
                get innerText() { return this._innerText; },
                set innerText(v) { this._innerText = v; },
                get textContent() { return this._innerText; },
                getBoundingClientRect: function() { return { left: 10, top: 10, width: 120, height: 32 }; },
                getAttribute: function() { return ''; },
                querySelectorAll: function() { return []; }
              };
            }
            global.window = global;
            global.self = global;
            global.location = { href: 'https://mt5trial9.exwebterm.com/terminal' };
            global.getComputedStyle = function(el) { return el.style || { display: 'block', visibility: 'visible', opacity: '1', pointerEvents: 'auto' }; };
            const searchEl = mkEl('INPUT', '');
            const volEl = mkEl('DIV', '');
            const bodyEl = mkEl('BODY', 'Balance: 3000.00 Equity: 2990.00 Free Margin: 2950.00 Search symbol BTCUSD 57116.54 Positions');
            bodyEl.children = [searchEl, volEl];
            global.document = {
              readyState: 'complete',
              body: bodyEl,
              querySelectorAll: function(sel) {
                if (sel === '*') return [bodyEl, searchEl, volEl];
                if (sel.indexOf('Search symbol') >= 0) return [searchEl];
                if (sel.indexOf('#volume') >= 0 || sel.indexOf('trade-form') >= 0) return [volEl];
                return [];
              }
            };
            window.__btechActiveKotlinRunId = 42;
            window.__btechCurrentStage = 'SYMBOL_SEARCHING';
            window.__btechDiscoveryRunning = true;
            const resConnected = JSON.parse($probeJs);

            bodyEl.innerText = 'Trading accounts\nConnection closed or not established\n477786797 JustMarkets-Live\nConnect to account';
            window.__btechDiscoveryRunning = false;
            window.__btechCurrentStage = 'DISCONNECTED';
            const resDisconnected = JSON.parse($probeJs);

            console.log(JSON.stringify({ connected: resConnected, disconnected: resDisconnected }));
        """.trimIndent()

        val tempFile = File.createTempFile("btech_hb_probe_", ".js")
        tempFile.writeText(script)
        try {
            val proc = ProcessBuilder(nodeBinary, tempFile.absolutePath)
                .redirectErrorStream(true)
                .start()
            val finished = proc.waitFor(10, TimeUnit.SECONDS)
            val output = proc.inputStream.bufferedReader().readText().trim()
            assertTrue("Node probe timed out: $output", finished)
            assertEquals("Node exited with error: $output", 0, proc.exitValue())

            val parsed = JSONObject(output.lines().last())
            val conn = parsed.getJSONObject("connected")
            assertTrue(conn.getBoolean("webview_alive"))
            assertTrue(conn.getBoolean("javascript_running"))
            assertTrue(conn.getBoolean("terminal_loaded"))
            assertTrue(conn.getBoolean("authenticated"))
            assertTrue(conn.getBoolean("dom_ready"))
            assertTrue(conn.getBoolean("symbol_discovery_running"))
            assertTrue(conn.getBoolean("order_ticket_open"))
            assertEquals("SYMBOL_SEARCHING", conn.getString("current_stage"))
            assertEquals(42L, conn.getLong("run_id"))

            val disc = parsed.getJSONObject("disconnected")
            assertTrue(disc.getBoolean("webview_alive"))
            assertTrue(disc.getBoolean("javascript_running"))
            assertTrue(disc.getBoolean("dom_ready"))
            assertFalse("Disconnected reconnect screen must report authenticated=false", disc.getBoolean("authenticated"))
            assertFalse(disc.getBoolean("symbol_discovery_running"))
            assertEquals("DISCONNECTED", disc.getString("current_stage"))
        } finally {
            tempFile.delete()
        }
    }

    // -------------------------------------------------------------------------
    // Test G: Part 13 Desktop Viewport Configuration, Origin Scoping & Diagnostics
    // -------------------------------------------------------------------------

    @Test
    fun `Test G - desktop UA applied before navigation, origin rules exclude wildcard, toggle and diagnostics work`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val engine = WebTerminalEngine.getInstance(activity)

        // Ensure clean starting desktop state
        if (!engine.status.value.isDesktopMode) {
            engine.toggleDesktopMode()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        }
        engine.setDesktopViewportWidth(1366)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        val wv = engine.obtainWebView(activity)
        // Applied BEFORE navigation
        assertTrue(engine.status.value.isDesktopMode)
        assertEquals(1366, engine.status.value.desktopViewportWidth)
        assertEquals(WebTerminalEngine.DESKTOP_USER_AGENT, wv.settings.userAgentString)
        assertTrue(wv.settings.userAgentString.contains("X11; Linux x86_64"))
        assertTrue(wv.settings.userAgentString.contains("Chrome/130.0.0.0"))
        assertTrue(wv.settings.useWideViewPort)
        assertTrue(wv.settings.loadWithOverviewMode)
        assertEquals(100, wv.settings.textZoom)
        assertTrue(wv.settings.builtInZoomControls)
        assertFalse(wv.settings.displayZoomControls)

        // Allowed origins strictly scoped (no wildcard '*') and cover MT5, MT4, and redirected hosts
        val origins = engine.getRegisteredViewportOriginsForTest(
            listOf(
                "https://live.justmarkets.com/terminal",
                "https://metatraderweb.app/trade?version=4",
                "https://mt5trial9.exwebterm.com/terminal",
                "https://custom-broker.example.com/webtrader/login",
            )
        )
        assertFalse("DOCUMENT_START_SCRIPT allowed origins must NEVER use '*'", origins.contains("*"))
        assertTrue(origins.contains("https://live.justmarkets.com"))
        assertTrue(origins.contains("https://metatraderweb.app"))
        assertTrue(origins.contains("https://mt5trial9.exwebterm.com"))
        assertTrue(origins.contains("https://custom-broker.example.com"))

        // Toggle desktop mode off -> mobile UA -> toggle back on -> desktop UA restored
        engine.toggleDesktopMode()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertFalse(engine.status.value.isDesktopMode)
        assertFalse(wv.settings.loadWithOverviewMode)
        assertTrue(wv.settings.userAgentString.contains("Mobile"))

        engine.toggleDesktopMode()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(engine.status.value.isDesktopMode)
        assertTrue(wv.settings.loadWithOverviewMode)
        assertEquals(WebTerminalEngine.DESKTOP_USER_AGENT, wv.settings.userAgentString)

        // Configurable desktop width
        engine.setDesktopViewportWidth(1440)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(1440, engine.status.value.desktopViewportWidth)
        assertEquals(WebTerminalEngine.DESKTOP_USER_AGENT, wv.settings.userAgentString)

        // Reset back to default 1366 for subsequent tests
        engine.setDesktopViewportWidth(1366)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        // Diagnostics JSON parsing exposes innerWidth/clientWidth/visualViewportWidth without screen.width
        val diagJson = JSONObject().apply {
            put("innerWidth", 1366)
            put("clientWidth", 1366)
            put("visualViewportWidth", 1366)
            put("innerHeight", 768)
            put("metaViewport", "width=1366, user-scalable=yes")
            put("userAgent", WebTerminalEngine.DESKTOP_USER_AGENT)
            put("url", "https://live.justmarkets.com/terminal")
            put("targetWidth", 1366)
            put("viewportEnforced", true)
            put("layoutIsDesktopWidth", true)
            put("documentStartSupported", true)
            put("fallbackApplied", false)
        }.toString()
        val parsedDiag = engine.processViewportDiagnosticsJsonForTest(diagJson)!!
        assertEquals(1366, parsedDiag.innerWidth)
        assertEquals(1366, parsedDiag.clientWidth)
        assertEquals(1366, parsedDiag.visualViewportWidth)
        assertEquals(768, parsedDiag.innerHeight)
        assertEquals("width=1366, user-scalable=yes", parsedDiag.metaViewport)
        assertTrue(parsedDiag.viewportEnforced)
        assertTrue(parsedDiag.layoutIsDesktopWidth)
        assertTrue(parsedDiag.documentStartSupported)
        assertFalse(parsedDiag.fallbackApplied)
        assertEquals(parsedDiag, engine.status.value.viewportDiagnostics)

        controller.pause().stop().destroy()
    }

    // -------------------------------------------------------------------------
    // Test H: Mid-Batch Watchdog Attribution, Self-Healing Reattach, & Service Host Fallback
    // -------------------------------------------------------------------------

    @Test
    fun `Test H - mid-batch watchdog attributes EXECUTING_TRADE not OPENING_TERMINAL and service fallback host keeps WebView attached`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val engine = WebTerminalEngine.getInstance(activity)

        val root = FrameLayout(activity)
        val bgHost = FrameLayout(activity)
        val serviceHost = FrameLayout(activity)
        val tempOrphanParent = FrameLayout(activity)
        root.addView(bgHost)
        root.addView(serviceHost)
        activity.setContentView(root)

        val wv = engine.obtainWebView(activity)
        engine.registerBackgroundHost(bgHost, activity)
        assertSame(bgHost, wv.parent)

        // 1. Mid-batch progress: after Trade 4/5 confirmed & dialog cleared, resolveCurrentExecutionStep must NEVER fall back to OPENING_TERMINAL
        val runId = engine.startSyntheticTradeRunForTest(testAccount(), testSignal("GBPCAD", count = 5))
        engine.dispatchTradePhaseForTest(runId, "TERMINAL_OK", "Confirmed active MT5 session")
        engine.dispatchTradePhaseForTest(runId, "AUTH_OK", "Account authenticated")
        engine.dispatchTradePhaseForTest(runId, "SYMBOL_FOUND", "Symbol found: GBPCAD")
        engine.dispatchTradePhaseForTest(runId, "SYMBOL_SELECTED", "Selected GBPCAD")
        engine.dispatchTradePhaseForTest(runId, "PREPARE", "Preparing trade 4/5")
        engine.dispatchTradePhaseForTest(runId, "TRADE_SUBMIT:4:5", "Submitting 4/5")
        engine.dispatchTradePhaseForTest(runId, "TRADE_CONFIRMED:4:5", "Trade 4/5 confirmed")
        assertEquals(ExecutionStepId.EXECUTING_TRADE, engine.resolveCurrentExecutionStep())
        engine.dispatchTradePhaseForTest(runId, "TERMINAL_CLEAR:4:5", "Waiting before 5/5")
        assertEquals(ExecutionStepId.EXECUTING_TRADE, engine.resolveCurrentExecutionStep())

        // Finish trade cleanly
        engine.dispatchTradePhaseForTest(runId, "TRADE_CONFIRMED:5:5", "Trade 5/5 confirmed")
        engine.dispatchTradeLogForTest(runId, "All 5/5 confirmed", isError = false, isDone = true)

        // 2. Service fallback host keeps WebView attached when Activity unregisters its background host
        engine.registerServiceFallbackHost(serviceHost, activity)
        engine.unregisterBackgroundHost(bgHost)
        assertSame("WebView must move atomically to service fallback host when Activity host unregisters", serviceHost, wv.parent)

        // When Activity background host returns, WebView moves back seamlessly on obtainWebView()
        engine.unregisterServiceFallbackHost(serviceHost)
        assertEquals(null, wv.parent)
        engine.registerBackgroundHost(bgHost, activity)
        assertSame(bgHost, engine.obtainWebView(activity).parent)

        // 3. Self-healing reattach if WebView was transiently orphaned outside Inspect mode
        tempOrphanParent.addView(wv.also { (it.parent as? android.view.ViewGroup)?.removeView(it) })
        tempOrphanParent.removeView(wv)
        assertEquals(null, wv.parent)
        val healed = engine.obtainWebView(activity)
        assertSame("obtainWebView must reattach orphaned WebView to active host", bgHost, healed.parent)

        engine.unregisterBackgroundHost(bgHost)
        controller.pause().stop().destroy()
    }
}
