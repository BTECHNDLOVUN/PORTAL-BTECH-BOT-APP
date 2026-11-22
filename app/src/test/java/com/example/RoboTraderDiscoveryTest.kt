package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.BtechPreferencesRepository
import com.example.model.BrokerCatalog
import com.example.model.BrokerSymbolResolver
import com.example.model.PlatformType
import com.example.model.SimulatedSignalRequest
import com.example.model.SymbolDiscoveryRequest
import com.example.model.TradeChannelSignal
import com.example.model.TradeDirection
import com.example.service.ExecutionStepStatus
import com.example.service.OverlayActionEvent
import com.example.service.OverlayStateBus
import com.example.webterminal.WebTerminalScripts
import com.example.webterminal.WebTerminalStatus
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RoboTraderDiscoveryTest {

    // -------------------------------------------------------------------------
    // PART A: Kotlin Resolver, Isolation, Signal Parser & Cancellation Tests
    // -------------------------------------------------------------------------

    @Test
    fun `BrokerSymbolResolver enforces strict exact broker symbols and rejects suffix mismatches`() {
        val offered = listOf("EURJPY", "EURUSD.ecn", "XAUUSD.m", "XAGUSD", "US500", "GBPJPY", "BTCUSD", "ETHUSD", "AUDUSD", "NZDUSD", "USDCAD", "USDCHF")

        // 1. Suffix mismatch MUST NOT match: requesting XAUUSD when broker only offers XAUUSD.m -> Symbol not found
        val xauNoSuffixMiss = BrokerSymbolResolver.resolve(
            SymbolDiscoveryRequest(requestedSymbol = "XAUUSD", broker = "JustMarkets", platform = PlatformType.MT4, server = "JustMarkets-Demo"),
            discoveredSymbols = offered,
            cachedMappings = mapOf("XAUUSD" to "XAUUSD.m"),
        )
        assertFalse(xauNoSuffixMiss.success)
        assertNull(xauNoSuffixMiss.actualBrokerSymbol)
        assertEquals("NOT_FOUND", xauNoSuffixMiss.matchType)
        assertTrue(xauNoSuffixMiss.message.contains("not found"))

        // 2. Suffix mismatch MUST NOT match: requesting EURUSD when broker only offers EURUSD.ecn -> Symbol not found
        val eurNoSuffixMiss = BrokerSymbolResolver.resolve(
            SymbolDiscoveryRequest(requestedSymbol = "EURUSD", broker = "IC Markets", platform = PlatformType.MT5, server = "ICMarketsSC-Demo"),
            discoveredSymbols = offered,
        )
        assertFalse(eurNoSuffixMiss.success)
        assertNull(eurNoSuffixMiss.actualBrokerSymbol)
        assertEquals("NOT_FOUND", eurNoSuffixMiss.matchType)

        // 2b. Exact & case-insensitive broker suffix DOES match
        val exactDotM = BrokerSymbolResolver.resolve(
            SymbolDiscoveryRequest(requestedSymbol = "XAUUSD.m", broker = "JustMarkets", platform = PlatformType.MT4, server = "JustMarkets-Demo"),
            discoveredSymbols = offered,
        )
        assertTrue(exactDotM.success)
        assertEquals("XAUUSD.m", exactDotM.actualBrokerSymbol)
        assertEquals("EXACT", exactDotM.matchType)

        val caseInsensitiveDotEcn = BrokerSymbolResolver.resolve(
            SymbolDiscoveryRequest(requestedSymbol = "eurusd.ecn", broker = "IC Markets", platform = PlatformType.MT5, server = "ICMarketsSC-Demo"),
            discoveredSymbols = offered,
        )
        assertTrue(caseInsensitiveDotEcn.success)
        assertEquals("EURUSD.ecn", caseInsensitiveDotEcn.actualBrokerSymbol)
        assertEquals("CASE_INSENSITIVE", caseInsensitiveDotEcn.matchType)

        // 3. Similar-looking different instrument MUST NOT match (US30 vs US500, GOLD vs XAUUSD without verified alias)
        val us30Miss = BrokerSymbolResolver.resolve(
            SymbolDiscoveryRequest(requestedSymbol = "US30", broker = "Exness", platform = PlatformType.MT5, server = "Exness-MT5Trial9"),
            discoveredSymbols = offered,
        )
        assertFalse(us30Miss.success)
        assertNull(us30Miss.actualBrokerSymbol)

        val goldNoAlias = BrokerSymbolResolver.resolve(
            SymbolDiscoveryRequest(requestedSymbol = "GOLD", broker = "Exness", platform = PlatformType.MT5, server = "Exness-MT5Trial9"),
            discoveredSymbols = listOf("XAUUSD", "XAGUSD"),
        )
        assertFalse(goldNoAlias.success)

        // 4. Explicit verified broker alias resolves only when verified in discovered symbols
        val goldWithVerifiedAlias = BrokerSymbolResolver.resolve(
            SymbolDiscoveryRequest(requestedSymbol = "GOLD", broker = "Exness", platform = PlatformType.MT5, server = "Exness-MT5Trial9"),
            discoveredSymbols = listOf("XAUUSD", "XAGUSD"),
            verifiedAliases = mapOf("GOLD" to "XAUUSD"),
        )
        assertTrue(goldWithVerifiedAlias.success)
        assertEquals("XAUUSD", goldWithVerifiedAlias.actualBrokerSymbol)
        assertEquals("VERIFIED_ALIAS", goldWithVerifiedAlias.matchType)

        // 5. Missing instrument
        val missing = BrokerSymbolResolver.resolve(
            SymbolDiscoveryRequest(requestedSymbol = "DOGEUSD", broker = "Exness", platform = PlatformType.MT5, server = "Exness-MT5Trial9"),
            discoveredSymbols = offered,
        )
        assertFalse(missing.success)
        assertTrue(missing.message.contains("not found"))
    }

    @Test
    fun `BtechPreferencesRepository isolates discovered catalogs by broker platform and server`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = BtechPreferencesRepository(context)

        val mt4Symbols = listOf(
            "EURUSD", "GBPUSD", "USDJPY", "XAUUSD.m", "XAGUSD.m",
            "US30", "US500", "NAS100", "BTCUSD", "ETHUSD", "DE40", "AUDUSD",
        )
        val mt5Symbols = listOf(
            "EURUSD.ecn", "GBPUSD.ecn", "XAUUSD.ecn", "UK100", "GER40",
        )

        repo.recordDiscoveredSymbols(
            broker = "Exness",
            platform = PlatformType.MT4,
            server = "Exness-MT4Trial",
            symbols = mt4Symbols,
            requestedSymbol = "XAUUSD",
            actualBrokerSymbol = "XAUUSD.m",
        )
        repo.recordDiscoveredSymbols(
            broker = "Exness",
            platform = PlatformType.MT5,
            server = "Exness-MT5Trial9",
            symbols = mt5Symbols,
            requestedSymbol = "EURUSD",
            actualBrokerSymbol = "EURUSD.ecn",
        )

        val mt4Catalog = repo.getDiscoveredCatalog("Exness", PlatformType.MT4, "Exness-MT4Trial")
        val mt5Catalog = repo.getDiscoveredCatalog("Exness", PlatformType.MT5, "Exness-MT5Trial9")

        assertNotNull(mt4Catalog)
        assertNotNull(mt5Catalog)
        // Proves all 12+ symbols are stored without the old 8-symbol truncation
        assertEquals(12, mt4Catalog!!.symbols.size)
        assertEquals("XAUUSD.m", mt4Catalog.resolvedMappings["XAUUSD"])
        assertEquals(5, mt5Catalog!!.symbols.size)
        assertEquals("EURUSD.ecn", mt5Catalog.resolvedMappings["EURUSD"])
        // MT4 must never see MT5-specific mappings
        assertNull(mt4Catalog.resolvedMappings["EURUSD"])
        assertNull(mt5Catalog.resolvedMappings["XAUUSD"])
    }

    @Test
    fun `TradeChannelSignal parses valid JSON and rejects malformed or invalid lots`() {
        val validJson = """
            {
              "signal_id": "SIG-9041",
              "platform": "MT4",
              "symbol": "XAUUSD.m",
              "direction": "SELL",
              "lot": "0.05",
              "stop_loss": "2660.50",
              "take_profit": "2620.00",
              "comment": "BTECH-PORTAL",
              "trade_count": 3
            }
        """.trimIndent()
        val parsed = TradeChannelSignal.fromJson(validJson)
        assertNotNull(parsed)
        assertEquals(PlatformType.MT4, parsed!!.platform)
        assertEquals("XAUUSD.m", parsed.symbol)
        assertEquals(TradeDirection.SELL, parsed.direction)
        assertEquals("0.05", parsed.lot)
        assertEquals(3, parsed.tradeCount)

        val simReq = parsed.toSignalRequest()
        assertEquals("SIG-9041", simReq.signalId)
        assertEquals("XAUUSD.m", simReq.symbol)
        assertEquals(PlatformType.MT4, simReq.platform)

        assertNull(TradeChannelSignal.fromJson("""{"symbol":"XAUUSD","direction":"HOLD","lot":"0.01"}"""))
        assertNull(TradeChannelSignal.fromJson("""{"symbol":"XAUUSD","direction":"BUY","lot":"0"}"""))
    }

    @Test
    fun `OverlayStateBus emits SkipTradingChanged and cancels active execution on Stop or Skip`() = runBlocking {
        OverlayStateBus.clearLogs()
        OverlayStateBus.beginNewSignalExecution("Signal: XAUUSD BUY", "Preparing...")
        assertTrue(OverlayStateBus.snapshot.value.isExecuting)

        var skipEventSeen = false
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            val ev = withTimeout(5_000L) { OverlayStateBus.actions.first() }
            skipEventSeen = ev is OverlayActionEvent.SkipTradingChanged && ev.skipTrading
        }
        OverlayStateBus.toggleSkipTrading()
        job.join()
        assertTrue(skipEventSeen)

        OverlayStateBus.cancelActiveExecution("Skip Trades pressed during symbol discovery")
        val snap = OverlayStateBus.snapshot.value
        assertFalse(snap.isExecuting)
        assertTrue(snap.executionFailed)
        assertEquals("PAUSED", snap.statusPill)
        assertEquals("Skipped / Cancelled", snap.executionHeaderStatus)
        assertTrue(snap.executionSteps.any { it.status == ExecutionStepStatus.ERROR })

        // Reset skipTrading & state
        if (OverlayStateBus.snapshot.value.skipTrading) OverlayStateBus.toggleSkipTrading()
        OverlayStateBus.clearLogs()
    }

    // -------------------------------------------------------------------------
    // PART B: Real Node.js DOM Execution Suite for MT4 & MT5 (All 13 Scenarios)
    // -------------------------------------------------------------------------

    @Test
    fun `MT4 RoboTrader real JS DOM suite verifies all discovery, Show All, missing, mismatch, auth, cancel and execution paths`() {
        val results = runNodeHarness(
            buildMt4SuiteJs(
                discoveryScriptFn = { sym, aliasesJson ->
                    WebTerminalScripts.buildSymbolDiscoveryScript(
                        platform = "MT4",
                        requestedSymbol = sym,
                        broker = "JustMarkets",
                        server = "JustMarkets-Demo",
                        verifiedAliasesJson = aliasesJson,
                        pollDelayMs = 15,
                    )
                },
                tradeScriptFn = { sym, dir, count ->
                    WebTerminalScripts.buildTradeScript(
                        symbol = sym,
                        direction = dir,
                        lot = "0.02",
                        stopLoss = "2650.00",
                        takeProfit = "2680.00",
                        comment = "BTECH-MT4",
                        tradeCount = count,
                        platform = "MT4",
                        broker = "JustMarkets",
                        server = "JustMarkets-Demo",
                    )
                },
                cancelScript = WebTerminalScripts.buildCancelTradeScript(),
            )
        )

        assertTrue("MT4 suite failed: ${results.optString("error")}", results.optBoolean("ok", false))
        assertTrue(results.getBoolean("exactMatchOk"))
        assertTrue(results.getBoolean("caseInsensitiveOk"))
        assertTrue(results.getBoolean("showAllByTextNotIndex7Ok"))
        assertTrue(results.getBoolean("mt4DidNotTouchMt5Search"))
        assertTrue(results.getBoolean("suffixDotMOk"))
        assertTrue(results.getBoolean("suffixDotEcnOk"))
        assertTrue(results.getBoolean("unmatchedSuffixRejectedOk"))
        assertTrue(results.getBoolean("catalogExceeds8SymbolsOk"))
        assertTrue(results.getBoolean("missingSymbolFailedCleanly"))
        assertTrue(results.getBoolean("similarDifferentRejected"))
        assertTrue(results.getBoolean("authLostTriggersBridge"))
        assertTrue(results.getBoolean("cancelAbortsMidDiscovery"))
        assertTrue(results.getBoolean("mt4MockOrderExecutedOnceAndConfirmed"))
        assertTrue(results.getBoolean("zeroDoubleClicksUsedOk"))
        assertEquals(1, results.getInt("executedBuyClicks"))
        assertEquals(0, results.getInt("executedSellClicks"))
    }

    @Test
    fun `MT5 RoboTrader real JS DOM suite verifies Search Symbol, order form verification, missing, mismatch, auth, cancel and execution paths`() {
        val results = runNodeHarness(
            buildMt5SuiteJs(
                discoveryScriptFn = { sym, aliasesJson ->
                    WebTerminalScripts.buildSymbolDiscoveryScript(
                        platform = "MT5",
                        requestedSymbol = sym,
                        broker = "Exness",
                        server = "Exness-MT5Trial9",
                        verifiedAliasesJson = aliasesJson,
                        pollDelayMs = 15,
                    )
                },
                tradeScriptFn = { sym, dir, count, aliasesJson ->
                    WebTerminalScripts.buildTradeScript(
                        symbol = sym,
                        direction = dir,
                        lot = "0.03",
                        stopLoss = "1.0800",
                        takeProfit = "1.0950",
                        comment = "BTECH-MT5",
                        tradeCount = count,
                        platform = "MT5",
                        broker = "Exness",
                        server = "Exness-MT5Trial9",
                        verifiedAliasesJson = aliasesJson,
                    )
                },
                cancelScript = WebTerminalScripts.buildCancelTradeScript(),
            )
        )

        assertTrue("MT5 suite failed: ${results.optString("error")}", results.optBoolean("ok", false))
        assertTrue(results.getBoolean("exactSearchAndClickOk"))
        assertTrue(results.getBoolean("caseInsensitiveSearchOk"))
        assertTrue(results.getBoolean("hiddenUntilSearchedOk"))
        assertTrue(results.getBoolean("suffixDotMOk"))
        assertTrue(results.getBoolean("suffixDotEcnOk"))
        assertTrue(results.getBoolean("unmatchedSuffixRejectedOk"))
        assertTrue(results.getBoolean("searchClickAloneDidNotCompleteTrade"))
        assertTrue(results.getBoolean("missingSymbolFailedCleanly"))
        assertTrue(results.getBoolean("similarDifferentRejected"))
        assertTrue(results.getBoolean("goldNotBlindlyXauUsd"))
        assertTrue(results.getBoolean("goldWithVerifiedAliasOk"))
        assertTrue(results.getBoolean("orderFormMismatchRejected"))
        assertTrue(results.getBoolean("authLostTriggersBridge"))
        assertTrue(results.getBoolean("cancelStopsTrade2BeforeSubmit"))
        assertTrue(results.getBoolean("twoSecondDelayBetweenTradesOk"))
        assertTrue(results.getBoolean("mt5TradeInputVerifiedAndExecutedOnce"))
        assertEquals(1, results.getInt("executedBuyClicks"))
    }

    @Test
    fun `BrokerCatalog buildLaunchUrl follows RoboTrader MT4 and MT5 routes while preserving custom broker URLs`() {
        assertEquals(
            "https://metatraderweb.app/trade?version=4&login=10045&server=MetaQuotes-Demo",
            BrokerCatalog.buildLaunchUrl("https://metatraderweb.app/trade", PlatformType.MT4, "10045", "MetaQuotes-Demo"),
        )
        assertEquals(
            "https://trade.metatrader5.com/terminal?login=20099&server=MetaQuotes-Demo",
            BrokerCatalog.buildLaunchUrl("https://trade.metatrader5.com", PlatformType.MT5, "20099", "MetaQuotes-Demo"),
        )
        assertEquals(
            "https://live.justmarkets.com",
            BrokerCatalog.buildLaunchUrl(BrokerCatalog.JUSTMARKETS_MT5_URL, PlatformType.MT5, "2101016455", "JustMarkets-Demo2"),
        )
        assertEquals(
            "https://webtrader.justmarkets.com",
            BrokerCatalog.buildLaunchUrl("https://webtrader.justmarkets.com", PlatformType.MT4, "9911", "JustMarkets-Demo"),
        )

        // JustMarkets live.justmarkets.com is an MT5 URL — must flag mismatch on MT4 and pass on MT5
        assertTrue(BrokerCatalog.isUrlPlatformMismatch("https://live.justmarkets.com/terminal", PlatformType.MT4))
        assertFalse(BrokerCatalog.isUrlPlatformMismatch("https://live.justmarkets.com/terminal", PlatformType.MT5))
        // metatraderweb.app is MT4 — must flag mismatch on MT5 and NOT warn on MT4 for JustMarkets
        assertTrue(BrokerCatalog.isUrlPlatformMismatch("https://metatraderweb.app/trade", PlatformType.MT5))
        assertFalse(BrokerCatalog.isUrlPlatformMismatch("https://metatraderweb.app/trade", PlatformType.MT4))
        assertFalse(BrokerCatalog.requiresBrokerOwnUrlWarning("JustMarkets", "https://metatraderweb.app/trade", PlatformType.MT4))
        assertTrue(WebTerminalStatus().isDesktopMode)
    }

    @Test
    fun `Ported RoboTrader MT4 and MT5 demo DOM suite verifies auth, F9 form, fields, min-volume, rejection stop, bare OK rejection, stale dialog cleanup and UNKNOWN timeout`() {
        val mt5AuthFailJs = JSONObject.quote(
            WebTerminalScripts.buildAuthScript("12345", "wrongpass", "MetaQuotes-Demo", "MT5")
        )
        val mt5AuthSuccessJs = JSONObject.quote(
            WebTerminalScripts.buildAuthScript("12345", "goodpass", "MetaQuotes-Demo", "MT5")
        )
        val mt5RejectMultiJs = JSONObject.quote(
            WebTerminalScripts.buildTradeScript(
                symbol = "EURUSD",
                direction = "BUY",
                lot = "0.02",
                stopLoss = "1.0800",
                takeProfit = "1.0950",
                comment = "RT-MT5",
                tradeCount = 3,
                platform = "MT5",
                broker = "MetaQuotes",
                server = "MetaQuotes-Demo",
            )
        )
        val mt5DisabledInvalidJs = JSONObject.quote(
            WebTerminalScripts.buildTradeScript(
                symbol = "EURUSD",
                direction = "BUY",
                lot = "0.02",
                stopLoss = "",
                takeProfit = "",
                comment = "",
                tradeCount = 1,
                platform = "MT5",
                broker = "MetaQuotes",
                server = "MetaQuotes-Demo",
            )
        )
        val mt5BareOkThenUnknownJs = JSONObject.quote(
            WebTerminalScripts.buildTradeScript(
                symbol = "EURUSD",
                direction = "BUY",
                lot = "0.02",
                stopLoss = "",
                takeProfit = "",
                comment = "",
                tradeCount = 2,
                platform = "MT5",
                broker = "MetaQuotes",
                server = "MetaQuotes-Demo",
                confirmTimeoutPolls = 3,
            )
        )
        val mt5NoSymbolsJs = JSONObject.quote(
            WebTerminalScripts.buildSymbolDiscoveryScript(
                platform = "MT5",
                requestedSymbol = "DOGEUSD",
                broker = "MetaQuotes",
                server = "MetaQuotes-Demo",
                pollDelayMs = 15,
            )
        )
        val mt4PortedJs = JSONObject.quote(
            WebTerminalScripts.buildTradeScript(
                symbol = "EURUSD",
                direction = "SELL",
                lot = "0.03",
                stopLoss = "1.1200",
                takeProfit = "1.1000",
                comment = "RT-MT4",
                tradeCount = 2,
                platform = "MT4",
                broker = "MetaQuotes",
                server = "MetaQuotes-Demo",
                confirmTimeoutPolls = 3,
            )
        )
        val mt4BareOkAndRejectJs = JSONObject.quote(
            WebTerminalScripts.buildTradeScript(
                symbol = "EURUSD",
                direction = "BUY",
                lot = "0.03",
                stopLoss = "1.1000",
                takeProfit = "1.1200",
                comment = "RT-MT4",
                tradeCount = 3,
                platform = "MT4",
                broker = "MetaQuotes",
                server = "MetaQuotes-Demo",
            )
        )
        val removeSessionJs = JSONObject.quote(WebTerminalScripts.buildMt5RemoveSessionScript())

        val script = """
        (async function() {
          // 1. MT5 Auth script: invalid credentials via .error and valid login via autocomplete='username' + Connect to account
          function testMt5Auth(shouldSucceed) {
            return new Promise(function(resolve) {
              const doc = createDomDocument();
              const loginIn = doc.createElement('input');
              loginIn.setAttribute('autocomplete', 'username');
              loginIn.name = 'login';
              const pwdIn = doc.createElement('input');
              pwdIn.type = 'password';
              pwdIn.name = 'password';
              const btn = doc.createElement('button');
              btn.textContent = 'Connect to account';
              btn.addEventListener('click', function() {
                setTimeout(function() {
                  if (shouldSucceed) {
                    doc.body.removeChild(loginIn);
                    doc.body.removeChild(pwdIn);
                    doc.body.removeChild(btn);
                    const ws = doc.createElement('div');
                    ws.textContent = 'Balance: 10,000.00 USD Equity: 10,000.00 USD Positions Quotes Chart Trade';
                    doc.body.appendChild(ws);
                  } else {
                    const err = doc.createElement('div');
                    err.className = 'error';
                    err.textContent = 'Invalid account or password';
                    doc.body.appendChild(err);
                  }
                }, 40);
              });
              doc.body.appendChild(loginIn);
              doc.body.appendChild(pwdIn);
              doc.body.appendChild(btn);
              installDoc(doc);
              window.BtechBridge = {
                onAuthResult: function(ok, msg) { resolve({ ok: ok, msg: msg, loginVal: loginIn.value }); },
                onTradeLog: function() {}
              };
              eval(shouldSucceed ? $mt5AuthSuccessJs : $mt5AuthFailJs);
            });
          }

          const authFailRes = await testMt5Auth(false);
          const mt5AuthInvalidErrorOk = !authFailRes.ok && authFailRes.msg.indexOf('Invalid account or password') >= 0;
          const authOkRes = await testMt5Auth(true);
          const mt5AuthSuccessOk = !!authOkRes.ok && authOkRes.loginVal === '12345';

          // 2. MT5 'No symbols found' fails cleanly without hanging
          let doc = createDomDocument();
          const ws0 = doc.createElement('div');
          ws0.textContent = 'Balance: 10,000.00 USD Equity: 10,000.00 USD';
          doc.body.appendChild(ws0);
          const sIn = doc.createElement('input');
          sIn.setAttribute('placeholder', 'Search symbol');
          const noSymMsg = doc.createElement('div');
          noSymMsg.textContent = 'No symbols found';
          doc.body.appendChild(sIn);
          doc.body.appendChild(noSymMsg);
          installDoc(doc);
          eval($mt5NoSymbolsJs);
          const noSymRes = await window.__btechLastDiscoveryPromise;
          const mt5NoSymbolsFoundOk = !noSymRes.success && String(noSymRes.error).indexOf('error:Symbol DOGEUSD not found') >= 0;

          // 3. Helper to build full RoboTrader-shaped MT5 DOM (.trade-input, .comment, F9 title button, positions table, stale dialog)
          function buildRichMt5Dom(opts) {
            opts = opts || {};
            const d = createDomDocument();
            const state = {
              f9Opened: false,
              staleResultDismissed: false,
              staleFormClosed: false,
              openPositionCloseClicked: false,
              eventsSeen: [],
              buyClicks: 0,
              results: [],
              phases: [],
              logs: []
            };

            const ws = d.createElement('div');
            ws.textContent = 'Balance: 10,000.00 USD Equity: 10,000.00 USD Positions';
            d.body.appendChild(ws);

            // Existing open position row with 'Close' & 'Close Position' — MUST NEVER be touched by stale dialog cleanup!
            const posTable = d.createElement('table');
            const posRow = d.createElement('tr');
            posRow.textContent = '#770001 buy 0.01 XAUUSD at 2650.00';
            const closePosBtn = d.createElement('button');
            closePosBtn.textContent = 'Close Position';
            closePosBtn.addEventListener('click', function() { state.openPositionCloseClicked = true; });
            posRow.appendChild(closePosBtn);
            posTable.appendChild(posRow);
            d.body.appendChild(posTable);

            if (opts.withStaleDialogs) {
              const staleModal = d.createElement('div');
              staleModal.setAttribute('role', 'dialog');
              staleModal.textContent = 'Previous trade receipt #660001 done';
              const staleOk = d.createElement('button');
              staleOk.textContent = 'OK';
              staleOk.addEventListener('click', function() {
                state.staleResultDismissed = true;
                if (staleModal.parentElement) staleModal.parentElement.removeChild(staleModal);
              });
              staleModal.appendChild(staleOk);
              d.body.appendChild(staleModal);

              const staleForm = d.createElement('div');
              staleForm.className = 'trade-form';
              staleForm.textContent = 'Market Execution EURUSD';
              const staleVol = d.createElement('input');
              staleVol.name = 'volume';
              staleForm.appendChild(staleVol);
              const staleBuy = d.createElement('button');
              staleBuy.textContent = 'Buy by Market';
              staleForm.appendChild(staleBuy);
              const staleClose = d.createElement('button');
              staleClose.textContent = 'Close';
              staleClose.addEventListener('click', function() {
                state.staleFormClosed = true;
                if (staleForm.parentElement) staleForm.parentElement.removeChild(staleForm);
              });
              staleForm.appendChild(staleClose);
              d.body.appendChild(staleForm);
            }

            const searchBox = d.createElement('input');
            searchBox.setAttribute('placeholder', 'Search symbol');
            const rowBtn = d.createElement('button');
            rowBtn.textContent = 'EURUSD';
            rowBtn.addEventListener('click', function() {
              // F9 trigger button becomes available after selecting symbol
              if (!opts.skipF9Button) {
                const f9Btn = d.createElement('button');
                const iconSpan = d.createElement('span');
                iconSpan.setAttribute('title', 'Show Trade Form (F9)');
                f9Btn.appendChild(iconSpan);
                f9Btn.addEventListener('click', function() {
                  state.f9Opened = true;
                  mountTradeForm();
                });
                d.body.appendChild(f9Btn);
              } else {
                mountTradeForm();
              }
            });
            d.body.appendChild(searchBox);
            d.body.appendChild(rowBtn);

            let tradeForm = null;
            let volInputRef = null;
            function mountTradeForm() {
              if (tradeForm && tradeForm.parentElement) tradeForm.parentElement.removeChild(tradeForm);
              tradeForm = d.createElement('div');
              tradeForm.className = 'trade-form';

              function makeTradeInput(labelName, withDecBtnDisabled, markInvalid) {
                const wrap = d.createElement('div');
                wrap.className = 'trade-input';
                const lbl = d.createElement('span');
                lbl.className = 'label';
                lbl.textContent = labelName;
                wrap.appendChild(lbl);
                if (labelName === 'Volume') {
                  const dec = d.createElement('button');
                  dec.textContent = '-';
                  dec.disabled = !!withDecBtnDisabled;
                  wrap.appendChild(dec);
                }
                const inp = d.createElement('input');
                if (markInvalid) inp.setAttribute('aria-invalid', 'true');
                ['beforeinput', 'input', 'change', 'keydown', 'keyup', 'blur'].forEach(function(evName) {
                  inp.addEventListener(evName, function() {
                    if (labelName === 'Volume') state.eventsSeen.push(evName);
                  });
                });
                wrap.appendChild(inp);
                tradeForm.appendChild(wrap);
                return inp;
              }

              volInputRef = makeTradeInput('Volume', opts.decrementDisabled, opts.markVolumeInvalid);
              makeTradeInput('Stop Loss', false, false);
              makeTradeInput('Take Profit', false, false);

              const cWrap = d.createElement('div');
              cWrap.className = 'comment';
              const cLbl = d.createElement('div');
              cLbl.className = 'label';
              cLbl.textContent = 'Comment';
              const cInp = d.createElement('input');
              cWrap.appendChild(cLbl);
              cWrap.appendChild(cInp);
              tradeForm.appendChild(cWrap);

              const sellBtn = d.createElement('button');
              sellBtn.textContent = 'Sell by Market';
              const buyBtn = d.createElement('button');
              buyBtn.textContent = 'Buy by Market';
              if (opts.disableBuyButton) buyBtn.disabled = true;

              buyBtn.addEventListener('click', function() {
                state.buyClicks++;
                if (opts.mode === 'reject') {
                  const errDialog = d.createElement('div');
                  errDialog.setAttribute('role', 'dialog');
                  const statusNode = d.createElement('div');
                  statusNode.className = 'status';
                  statusNode.textContent = 'Off quotes';
                  const okB = d.createElement('button');
                  okB.textContent = 'OK';
                  okB.addEventListener('click', function() {
                    if (errDialog.parentElement) errDialog.parentElement.removeChild(errDialog);
                  });
                  errDialog.appendChild(statusNode);
                  errDialog.appendChild(okB);
                  d.body.appendChild(errDialog);
                } else if (opts.mode === 'bare_ok_timeout') {
                  // Shows ONLY a bare OK button with empty/non-confirming text -> must NEVER count as success!
                  const bareDialog = d.createElement('div');
                  bareDialog.setAttribute('role', 'dialog');
                  const okB = d.createElement('button');
                  okB.textContent = 'OK';
                  bareDialog.appendChild(okB);
                  d.body.appendChild(bareDialog);
                }
              });

              tradeForm.appendChild(sellBtn);
              tradeForm.appendChild(buyBtn);
              d.body.appendChild(tradeForm);
            }

            installDoc(d);
            window.BtechBridge = {
              onTradePhase: function(k, d) { state.phases.push({ k: k, d: d }); },
              onTradeLog: function(m, err, done) { state.logs.push({ m: m, err: err, done: done }); },
              onSymbolDiscovered: function() {},
              onTradeResult: function(json) { state.results.push(JSON.parse(json)); }
            };
            return { doc: d, state: state, getVolInput: function() { return volInputRef; } };
          }

          // 4. MT5 stale dialog cleanup + F9 form open + beforeinput/Tab/blur + broker rejection stops 3-trade sequence!
          let ctx = buildRichMt5Dom({ withStaleDialogs: true, mode: 'reject', decrementDisabled: true });
          eval($mt5RejectMultiJs);
          for (let w = 0; w < 80 && !ctx.state.logs.some(function(l) { return l.done; }); w++) {
            await sleepMs(40);
          }
          const mt5StaleCleanupPreservedPositionsOk =
            ctx.state.staleResultDismissed &&
            ctx.state.staleFormClosed &&
            !ctx.state.openPositionCloseClicked;
          const mt5F9FormOpenedOk = ctx.state.f9Opened;
          const mt5FieldEventSequenceOk =
            ['beforeinput', 'input', 'change', 'keydown', 'keyup', 'blur'].every(function(ev) {
              return ctx.state.eventsSeen.indexOf(ev) >= 0;
            });
          const mt5VerifyMinVolOk =
            ctx.getVolInput() &&
            window.BtechRoboTraderDiscovery.verifyMT5MinimumVolume(ctx.getVolInput()) === 'filled';
          const mt5RejectionStopsMultiTradeOk =
            ctx.state.buyClicks === 1 &&
            ctx.state.results.length === 1 &&
            ctx.state.results[0].status === 'REJECTED' &&
            ctx.state.results[0].rawMessage.indexOf('Off quotes') >= 0 &&
            ctx.state.logs.some(function(l) { return l.err && l.done; });

          // 5. MT5 disabled button with aria-invalid Volume must NEVER be force-enabled
          ctx = buildRichMt5Dom({ skipF9Button: true, disableBuyButton: true, markVolumeInvalid: true });
          eval($mt5DisabledInvalidJs);
          for (let w = 0; w < 60 && !ctx.state.logs.some(function(l) { return l.done; }); w++) {
            await sleepMs(40);
          }
          const mt5DisabledNotForceEnabledOk =
            ctx.state.buyClicks === 0 &&
            ctx.state.results.some(function(r) { return r.status === 'REJECTED' && r.rawMessage.indexOf('Invalid Volume value') >= 0; });

          // 6. MT5 bare OK dialog MUST NOT confirm trade -> times out to UNKNOWN without retry & stops Trade 2/2!
          ctx = buildRichMt5Dom({ skipF9Button: true, mode: 'bare_ok_timeout' });
          eval($mt5BareOkThenUnknownJs);
          for (let w = 0; w < 100 && !ctx.state.logs.some(function(l) { return l.done; }); w++) {
            await sleepMs(40);
          }
          const mt5BareOkNotCountedAndUnknownNoRetryOk =
            ctx.state.buyClicks === 1 &&
            ctx.state.results.length === 1 &&
            ctx.state.results[0].status === 'UNKNOWN' &&
            ctx.state.results[0].retried === false &&
            ctx.state.results[0].rawMessage.indexOf('Unable to confirm trade submission. The trade was not retried.') >= 0 &&
            ctx.state.phases.some(function(p) { return p.k === 'UNKNOWN'; });

          // 7. MT4 Ported Path: mousedown/mouseup/contextmenu sequence, Show All mouseover, #volume/#sl ('0' reset)/#tp/#comment,
          //    bare OK ignored, rejection stops remaining count, open position untouched
          function buildRichMt4Dom(opts) {
            opts = opts || {};
            const d = createDomDocument();
            const state = {
              ctxEvents: [],
              showAllMouseOver: false,
              slHadZeroBeforeFinal: false,
              openPositionCloseClicked: false,
              sellClicks: 0,
              buyClicks: 0,
              results: [],
              logs: []
            };
            const ws = d.createElement('div');
            ws.textContent = 'Balance: 5,000.00 USD Equity: 5,000.00 USD Market Watch';
            d.body.appendChild(ws);

            const posTable = d.createElement('table');
            const posRow = d.createElement('tr');
            posRow.textContent = '#440011 sell 0.01 EURUSD at 1.1100';
            const closePos = d.createElement('button');
            closePos.textContent = 'Close Position';
            closePos.addEventListener('click', function() { state.openPositionCloseClicked = true; });
            posRow.appendChild(closePos);
            posTable.appendChild(posRow);
            d.body.appendChild(posTable);

            const mw = d.createElement('div');
            mw.className = 'market-watch-panel';
            const mwLbl = d.createElement('div');
            mwLbl.textContent = 'Market Watch';
            mw.appendChild(mwLbl);

            const tbl = d.createElement('table');
            const tb = d.createElement('tbody');
            const row1 = d.createElement('tr');
            const td1 = d.createElement('td');
            td1.textContent = 'USDJPY 150.00 150.02';
            row1.appendChild(td1);
            tb.appendChild(row1);
            tbl.appendChild(tb);
            mw.appendChild(tbl);
            d.body.appendChild(mw);

            ['mousedown', 'mouseup', 'contextmenu'].forEach(function(evName) {
              mw.addEventListener(evName, function() { state.ctxEvents.push(evName); });
            });
            mw.addEventListener('contextmenu', function() {
              const oldMenus = d.querySelectorAll('.page-menu');
              oldMenus.forEach(function(n) { if (n.parentElement) n.parentElement.removeChild(n); });
              const menu = d.createElement('div');
              menu.className = 'page-menu';
              const showAll = d.createElement('div');
              showAll.setAttribute('role', 'menuitem');
              showAll.textContent = 'Show All';
              showAll.addEventListener('mouseover', function() { state.showAllMouseOver = true; });
              showAll.addEventListener('click', function() {
                if (menu.parentElement) menu.parentElement.removeChild(menu);
                const row2 = d.createElement('tr');
                const td2 = d.createElement('td');
                td2.textContent = 'EURUSD';
                row2.appendChild(td2);
                row2.addEventListener('dblclick', function() {
                  state.unexpectedDblClicks = (state.unexpectedDblClicks || 0) + 1;
                });
                row2.addEventListener('contextmenu', function(ev) {
                  if (ev && ev.stopPropagation) ev.stopPropagation();
                  const prevMenus = d.querySelectorAll('.page-menu');
                  prevMenus.forEach(function(n) { if (n.parentElement) n.parentElement.removeChild(n); });
                  const rowMenu = d.createElement('div');
                  rowMenu.className = 'page-menu';
                  const newOrderMi = d.createElement('div');
                  newOrderMi.setAttribute('role', 'menuitem');
                  newOrderMi.textContent = 'New Order';
                  newOrderMi.addEventListener('click', function() {
                    if (rowMenu.parentElement) rowMenu.parentElement.removeChild(rowMenu);
                    const ticket = d.createElement('div');
                    ticket.setAttribute('role', 'dialog');
                    const hdr = d.createElement('h3');
                    hdr.setAttribute('data-symbol', 'EURUSD');
                    hdr.textContent = 'EURUSD';
                    ticket.appendChild(hdr);

                    const vIn = d.createElement('input');
                    vIn.id = 'volume';
                    const slIn = d.createElement('input');
                    slIn.id = 'sl';
                    slIn.addEventListener('change', function() {
                      if (slIn.value === '0') state.slHadZeroBeforeFinal = true;
                    });
                    const tpIn = d.createElement('input');
                    tpIn.id = 'tp';
                    const cmtIn = d.createElement('input');
                    cmtIn.id = 'comment';
                    ticket.appendChild(vIn);
                    ticket.appendChild(slIn);
                    ticket.appendChild(tpIn);
                    ticket.appendChild(cmtIn);

                    const sellB = d.createElement('button');
                    sellB.textContent = 'Sell by Market';
                    sellB.addEventListener('click', function() {
                      state.sellClicks++;
                      if (opts.mode === 'bare_ok_timeout') {
                        const md = d.createElement('div');
                        md.setAttribute('role', 'dialog');
                        const ok = d.createElement('button');
                        ok.textContent = 'OK';
                        md.appendChild(ok);
                        d.body.appendChild(md);
                      }
                    });
                    const buyB = d.createElement('button');
                    buyB.textContent = 'Buy by Market';
                    buyB.addEventListener('click', function() {
                      state.buyClicks++;
                      const errD = d.createElement('div');
                      errD.setAttribute('role', 'dialog');
                      const msg = d.createElement('div');
                      msg.className = 'error';
                      msg.textContent = 'Not enough money';
                      const ok = d.createElement('button');
                      ok.textContent = 'OK';
                      errD.appendChild(msg);
                      errD.appendChild(ok);
                      d.body.appendChild(errD);
                    });
                    ticket.appendChild(sellB);
                    ticket.appendChild(buyB);
                    d.body.appendChild(ticket);
                  });
                  rowMenu.appendChild(newOrderMi);
                  d.body.appendChild(rowMenu);
                });
                tb.appendChild(row2);
              });
              menu.appendChild(showAll);
              d.body.appendChild(menu);
            });

            installDoc(d);
            window.BtechBridge = {
              onTradePhase: function() {},
              onTradeLog: function(m, err, done) { state.logs.push({ m: m, err: err, done: done }); },
              onSymbolDiscovered: function() {},
              onTradeResult: function(json) { state.results.push(JSON.parse(json)); }
            };
            return state;
          }

          let st4 = buildRichMt4Dom({ mode: 'bare_ok_timeout' });
          eval($mt4PortedJs);
          for (let w = 0; w < 100 && !st4.logs.some(function(l) { return l.done; }); w++) {
            await sleepMs(40);
          }
          const mt4ContextMenuThreeEventsOk =
            st4.ctxEvents.length >= 3 &&
            st4.ctxEvents[0] === 'mousedown' &&
            st4.ctxEvents[1] === 'mouseup' &&
            st4.ctxEvents[2] === 'contextmenu';
          const mt4ShowAllMouseOverOk = st4.showAllMouseOver === true;
          const mt4SlZeroResetOk = st4.slHadZeroBeforeFinal === true;
          const mt4BareOkNotCountedAndUnknownOk =
            st4.sellClicks === 1 &&
            !st4.unexpectedDblClicks &&
            !st4.openPositionCloseClicked &&
            st4.results.length === 1 &&
            st4.results[0].status === 'UNKNOWN' &&
            st4.results[0].retried === false;

          let st4Rej = buildRichMt4Dom({ mode: 'reject' });
          eval($mt4BareOkAndRejectJs);
          for (let w = 0; w < 80 && !st4Rej.logs.some(function(l) { return l.done; }); w++) {
            await sleepMs(40);
          }
          const mt4RejectionStops3TradeCountOk =
            st4Rej.buyClicks === 1 &&
            st4Rej.results.length === 1 &&
            st4Rej.results[0].status === 'REJECTED' &&
            st4Rej.results[0].rawMessage.indexOf('Not enough money') >= 0;

          // 8. Part 2 N explicit remove-session helper returns 'true' only when 'Remove' button exists
          const dRem = createDomDocument();
          const remBtn = dRem.createElement('button');
          remBtn.textContent = 'Remove';
          let remClicked = false;
          remBtn.addEventListener('click', function() { remClicked = true; });
          dRem.body.appendChild(remBtn);
          installDoc(dRem);
          const removeRes = eval($removeSessionJs);
          const removeSessionExplicitOk = removeRes === 'true' && remClicked === true;

          console.log(JSON.stringify({
            ok: true,
            mt5AuthInvalidErrorOk: mt5AuthInvalidErrorOk,
            mt5AuthSuccessOk: mt5AuthSuccessOk,
            mt5NoSymbolsFoundOk: mt5NoSymbolsFoundOk,
            mt5StaleCleanupPreservedPositionsOk: mt5StaleCleanupPreservedPositionsOk,
            mt5F9FormOpenedOk: mt5F9FormOpenedOk,
            mt5FieldEventSequenceOk: mt5FieldEventSequenceOk,
            mt5VerifyMinVolOk: mt5VerifyMinVolOk,
            mt5RejectionStopsMultiTradeOk: mt5RejectionStopsMultiTradeOk,
            mt5DisabledNotForceEnabledOk: mt5DisabledNotForceEnabledOk,
            mt5BareOkNotCountedAndUnknownNoRetryOk: mt5BareOkNotCountedAndUnknownNoRetryOk,
            mt4ContextMenuThreeEventsOk: mt4ContextMenuThreeEventsOk,
            mt4ShowAllMouseOverOk: mt4ShowAllMouseOverOk,
            mt4SlZeroResetOk: mt4SlZeroResetOk,
            mt4BareOkNotCountedAndUnknownOk: mt4BareOkNotCountedAndUnknownOk,
            mt4RejectionStops3TradeCountOk: mt4RejectionStops3TradeCountOk,
            removeSessionExplicitOk: removeSessionExplicitOk
          }));
        })().catch(function(err) {
          console.log(JSON.stringify({ ok: false, error: err && err.stack ? err.stack : String(err) }));
        });
        """.trimIndent()

        val results = runNodeHarness(script)
        assertTrue("Ported RoboTrader suite failed: ${results.optString("error")}", results.optBoolean("ok", false))
        assertTrue(results.getBoolean("mt5AuthInvalidErrorOk"))
        assertTrue(results.getBoolean("mt5AuthSuccessOk"))
        assertTrue(results.getBoolean("mt5NoSymbolsFoundOk"))
        assertTrue(results.getBoolean("mt5StaleCleanupPreservedPositionsOk"))
        assertTrue(results.getBoolean("mt5F9FormOpenedOk"))
        assertTrue(results.getBoolean("mt5FieldEventSequenceOk"))
        assertTrue(results.getBoolean("mt5VerifyMinVolOk"))
        assertTrue(results.getBoolean("mt5RejectionStopsMultiTradeOk"))
        assertTrue(results.getBoolean("mt5DisabledNotForceEnabledOk"))
        assertTrue(results.getBoolean("mt5BareOkNotCountedAndUnknownNoRetryOk"))
        assertTrue(results.getBoolean("mt4ContextMenuThreeEventsOk"))
        assertTrue(results.getBoolean("mt4ShowAllMouseOverOk"))
        assertTrue(results.getBoolean("mt4SlZeroResetOk"))
        assertTrue(results.getBoolean("mt4BareOkNotCountedAndUnknownOk"))
        assertTrue(results.getBoolean("mt4RejectionStops3TradeCountOk"))
        assertTrue(results.getBoolean("removeSessionExplicitOk"))
    }

    @Test
    fun `JustMarkets screenshot correction suite verifies platform mismatch, Connect to account reconnect, plus vs check watchlist chart workflow, MT4 Symbols dialog, and order-disabled banner`() {
        val mt4OnMt5UrlJs = JSONObject.quote(
            WebTerminalScripts.buildTradeScript(
                symbol = "XAUUSD.s",
                direction = "BUY",
                lot = "0.01",
                stopLoss = "",
                takeProfit = "",
                comment = "",
                tradeCount = 1,
                platform = "MT4",
                broker = "JustMarkets",
                server = "JustMarkets-Demo2",
            )
        )
        val mt5OnMt4UrlJs = JSONObject.quote(
            WebTerminalScripts.buildTradeScript(
                symbol = "EURUSD",
                direction = "BUY",
                lot = "0.01",
                stopLoss = "",
                takeProfit = "",
                comment = "",
                tradeCount = 1,
                platform = "MT5",
                broker = "JustMarkets",
                server = "JustMarkets-Demo",
            )
        )
        val mt5ReconnectTradeJs = JSONObject.quote(
            WebTerminalScripts.buildTradeScript(
                symbol = "XAUUSD.s",
                direction = "BUY",
                lot = "0.01",
                stopLoss = "",
                takeProfit = "",
                comment = "JM-PRO",
                tradeCount = 1,
                platform = "MT5",
                broker = "JustMarkets",
                server = "JustMarkets-Demo2",
                accountLogin = "2101016455",
            )
        )
        val mt5ExactMismatchTradeJs = JSONObject.quote(
            WebTerminalScripts.buildTradeScript(
                symbol = "XAUUSD",
                direction = "BUY",
                lot = "0.01",
                stopLoss = "",
                takeProfit = "",
                comment = "JM-STRICT",
                tradeCount = 1,
                platform = "MT5",
                broker = "JustMarkets",
                server = "JustMarkets-Demo2",
                accountLogin = "2101016455",
            )
        )
        val mt5CheckedDiscoveryJs = JSONObject.quote(
            WebTerminalScripts.buildSymbolDiscoveryScript(
                platform = "MT5",
                requestedSymbol = "XAGUSD.s",
                broker = "JustMarkets",
                server = "JustMarkets-Demo2",
                pollDelayMs = 15,
            )
        )
        val mt4SymbolsDialogJs = JSONObject.quote(
            WebTerminalScripts.buildSymbolDiscoveryScript(
                platform = "MT4",
                requestedSymbol = "XAUUSD.m",
                broker = "JustMarkets",
                server = "JustMarkets-Demo",
                pollDelayMs = 15,
            )
        )
        val mt5DisabledServerJs = JSONObject.quote(
            WebTerminalScripts.buildTradeScript(
                symbol = "XAGUSD.s",
                direction = "BUY",
                lot = "0.01",
                stopLoss = "",
                takeProfit = "",
                comment = "",
                tradeCount = 1,
                platform = "MT5",
                broker = "JustMarkets",
                server = "JustMarkets-Demo2",
            )
        )
        val mt5PendingPlaceJs = JSONObject.quote(
            WebTerminalScripts.buildTradeScript(
                symbol = "XAGUSD.s",
                direction = "BUY",
                lot = "0.02",
                stopLoss = "",
                takeProfit = "",
                comment = "",
                tradeCount = 1,
                platform = "MT5",
                broker = "JustMarkets",
                server = "JustMarkets-Demo2",
            )
        )
        val mt5VirtualizedBtcJs = JSONObject.quote(
            WebTerminalScripts.buildTradeScript(
                symbol = "BTCUSD.s",
                direction = "BUY",
                lot = "0.01",
                stopLoss = "",
                takeProfit = "",
                comment = "VM-BTC",
                tradeCount = 1,
                platform = "MT5",
                broker = "JustMarkets",
                server = "JustMarkets-Live",
            )
        )
        val mt4ShowAllFallbackJs = JSONObject.quote(
            WebTerminalScripts.buildSymbolDiscoveryScript(
                platform = "MT4",
                requestedSymbol = "XAUUSD.m",
                broker = "JustMarkets",
                server = "JustMarkets-Demo",
                pollDelayMs = 15,
            )
        )

        val script = """
        (async function() {
          // 1. Platform mismatch guard: MT4 account on https://live.justmarkets.com/terminal MUST reject cleanly!
          let doc = createDomDocument();
          global.location = { href: 'https://live.justmarkets.com/terminal', pathname: '/terminal' };
          installDoc(doc);
          const mismatchLogs1 = [];
          const mismatchResults1 = [];
          window.BtechBridge = {
            onTradePhase: function() {},
            onTradeLog: function(m, err, done) { mismatchLogs1.push({ m: m, err: err, done: done }); },
            onSymbolDiscovered: function() {},
            onTradeResult: function(j) { mismatchResults1.push(JSON.parse(j)); }
          };
          eval($mt4OnMt5UrlJs);
          await sleepMs(40);
          const mt4OnJustMarketsMt5RejectedOk =
            mismatchResults1.length === 1 &&
            mismatchResults1[0].status === 'REJECTED' &&
            mismatchResults1[0].rawMessage.indexOf('Platform mismatch') >= 0 &&
            mismatchResults1[0].rawMessage.indexOf('MT5') >= 0;

          // 2. Platform mismatch guard: MT5 account on https://metatraderweb.app/trade?version=4 MUST reject cleanly!
          doc = createDomDocument();
          global.location = { href: 'https://metatraderweb.app/trade?version=4', pathname: '/trade' };
          installDoc(doc);
          const mismatchResults2 = [];
          window.BtechBridge = {
            onTradePhase: function() {},
            onTradeLog: function() {},
            onSymbolDiscovered: function() {},
            onTradeResult: function(j) { mismatchResults2.push(JSON.parse(j)); }
          };
          eval($mt5OnMt4UrlJs);
          await sleepMs(40);
          const mt5OnMt4WebRejectedOk =
            mismatchResults2.length === 1 &&
            mismatchResults2[0].status === 'REJECTED' &&
            mismatchResults2[0].rawMessage.indexOf('Platform mismatch') >= 0 &&
            mismatchResults2[0].rawMessage.indexOf('MT4') >= 0;

          // Reset URL to neutral MT5 endpoint for remaining checks
          global.location = { href: 'https://live.justmarkets.com/terminal', pathname: '/terminal' };

          // 3. Screenshot 1 + 2 + 3 + 5 + 6:
          //    Disconnected MT5 ("Trading accounts: 2101016455", "Connection closed or not established")
          //    taps green <button>Connect to account</button> (NOT top-left <nav><a>, NOT "Remove"),
          //    ignores folder category rows ("Forex S 10/29", "Metals S 1/9", "OIL S 0/3"),
          //    types "XAUUSD", matches "XAUUSD.s", clicks "+", clears search ("X"),
          //    verifies "XAUUSD.s" in displayed watchlist, clicks it to switch chart from EURUSD -> XAUUSD.s,
          //    opens "Show Trade Form (F9)", submits Buy by Market ONCE, confirms #551001!
          doc = createDomDocument();
          const jmState = {
            sidebarLinkClicked: false,
            removeClicked: false,
            reconnectButtonClicks: 0,
            plusClicked: 0,
            checkClicks: 0,
            searchCleared: false,
            watchlistSelected: '',
            f9Opened: false,
            buyClicks: 0,
            logs: [],
            results: []
          };

          const rootView = doc.createElement('div');
          doc.body.appendChild(rootView);

          function renderScreenshot1Reconnect() {
            rootView.textContent = '';
            const nav = doc.createElement('nav');
            const navLink = doc.createElement('a');
            navLink.textContent = 'Connect to account';
            navLink.addEventListener('click', function() { jmState.sidebarLinkClicked = true; });
            nav.appendChild(navLink);
            rootView.appendChild(nav);

            const pane = doc.createElement('div');
            const hdr = doc.createElement('div');
            hdr.textContent = 'Trading accounts: 2101016455 | JustMarkets-Demo2 | Connection closed or not established';
            pane.appendChild(hdr);

            const connectBtn = doc.createElement('button');
            connectBtn.textContent = 'Connect to account';
            connectBtn.addEventListener('click', function() {
              jmState.reconnectButtonClicks++;
              setTimeout(renderConnectedJustMarketsWorkspace, 60);
            });
            const removeBtn = doc.createElement('button');
            removeBtn.textContent = 'Remove';
            removeBtn.addEventListener('click', function() { jmState.removeClicked = true; });
            pane.appendChild(connectBtn);
            pane.appendChild(removeBtn);
            rootView.appendChild(pane);
          }

          function renderConnectedJustMarketsWorkspace() {
            rootView.textContent = '';
            const wsHdr = doc.createElement('div');
            wsHdr.textContent = 'Balance: 100 000.00 USD Equity: 100 000.00 USD Free margin: 100 000.00 USD Quotes Chart Trade History';
            rootView.appendChild(wsHdr);

            const chartHeader = doc.createElement('div');
            chartHeader.className = 'chart-header';
            chartHeader.textContent = 'EURUSD, M15';
            rootView.appendChild(chartHeader);

            const searchWrap = doc.createElement('div');
            const searchIn = doc.createElement('input');
            searchIn.setAttribute('placeholder', 'Search symbol');
            const clearBtn = doc.createElement('button');
            clearBtn.setAttribute('aria-label', 'Clear search');
            clearBtn.textContent = '×';
            clearBtn.style.display = 'none';
            searchWrap.appendChild(searchIn);
            searchWrap.appendChild(clearBtn);
            rootView.appendChild(searchWrap);

            const listBox = doc.createElement('div');
            rootView.appendChild(listBox);

            const addedSet = { 'XAGUSD.s': true };
            let query = '';

            function renderWatchlistOrSearch() {
              listBox.textContent = '';
              if (!query) {
                clearBtn.style.display = 'none';
                ['EURUSD', 'XAGUSD.s', 'XAUUSD.s'].filter(function(s) { return !!addedSet[s]; }).forEach(function(sym) {
                  const row = doc.createElement('div');
                  row.setAttribute('role', 'row');
                  row.setAttribute('data-symbol', sym);
                  row.textContent = sym + ' 2650.10 2650.45';
                  row.addEventListener('click', function() {
                    jmState.watchlistSelected = sym;
                    chartHeader.textContent = sym + ', M15';
                  });
                  listBox.appendChild(row);
                });
              } else {
                clearBtn.style.display = 'inline-block';
                // Folder category rows from Screenshots 5 & 6 MUST be ignored
                ['Forex S 10/29', 'Metals S 1/9', 'OIL S 0/3'].forEach(function(cat) {
                  const fRow = doc.createElement('div');
                  fRow.setAttribute('role', 'row');
                  fRow.textContent = cat;
                  listBox.appendChild(fRow);
                });

                const pool = ['XAGUSD.s', 'XAUUSD.s'];
                pool.filter(function(s) { return s.toLowerCase().indexOf(query.toLowerCase()) >= 0; }).forEach(function(sym) {
                  const sRow = doc.createElement('div');
                  sRow.setAttribute('role', 'row');
                  sRow.setAttribute('data-symbol', sym);
                  const lbl = doc.createElement('span');
                  lbl.textContent = sym;
                  sRow.appendChild(lbl);
                  if (addedSet[sym]) {
                    const chk = doc.createElement('span');
                    chk.setAttribute('aria-label', 'Added');
                    chk.textContent = '✓';
                    chk.addEventListener('click', function() { jmState.checkClicks++; });
                    sRow.appendChild(chk);
                  } else {
                    const plus = doc.createElement('button');
                    plus.setAttribute('aria-label', 'Add');
                    plus.textContent = '+';
                    plus.addEventListener('click', function() {
                      jmState.plusClicked++;
                      addedSet[sym] = true;
                    });
                    sRow.appendChild(plus);
                  }
                  listBox.appendChild(sRow);
                });
              }
            }

            searchIn.addEventListener('input', function() {
              query = searchIn.value || '';
              renderWatchlistOrSearch();
            });
            clearBtn.addEventListener('click', function() {
              jmState.searchCleared = true;
              searchIn.value = '';
              query = '';
              renderWatchlistOrSearch();
            });

            const f9Btn = doc.createElement('button');
            const f9Icon = doc.createElement('span');
            f9Icon.setAttribute('title', 'Show Trade Form (F9)');
            f9Btn.appendChild(f9Icon);
            f9Btn.addEventListener('click', function() {
              jmState.f9Opened = true;
              const form = doc.createElement('div');
              form.className = 'trade-form';
              const symLbl = doc.createElement('div');
              symLbl.className = 'symbol-header';
              symLbl.setAttribute('data-symbol', jmState.watchlistSelected || 'XAUUSD.s');
              symLbl.textContent = jmState.watchlistSelected || 'XAUUSD.s';
              form.appendChild(symLbl);

              const typeSel = doc.createElement('select');
              const optMarket = doc.createElement('option');
              optMarket.value = 'Market Execution';
              optMarket.textContent = 'Market Execution';
              typeSel.appendChild(optMarket);
              form.appendChild(typeSel);

              const vWrap = doc.createElement('div');
              vWrap.className = 'trade-input';
              const vLbl = doc.createElement('span');
              vLbl.className = 'label';
              vLbl.textContent = 'Volume';
              const vIn = doc.createElement('input');
              vWrap.appendChild(vLbl);
              vWrap.appendChild(vIn);
              form.appendChild(vWrap);

              const cWrap = doc.createElement('div');
              cWrap.className = 'comment';
              const cLbl = doc.createElement('div');
              cLbl.className = 'label';
              cLbl.textContent = 'Comment';
              const cIn = doc.createElement('input');
              cWrap.appendChild(cLbl);
              cWrap.appendChild(cIn);
              form.appendChild(cWrap);

              const sellB = doc.createElement('button');
              sellB.textContent = 'Sell by Market';
              const buyB = doc.createElement('button');
              buyB.textContent = 'Buy by Market';
              buyB.addEventListener('click', function() {
                jmState.buyClicks++;
                const row = doc.createElement('tr');
                row.textContent = '#551001 buy ' + vIn.value + ' XAUUSD.s at 2650.45';
                rootView.appendChild(row);
              });
              form.appendChild(sellB);
              form.appendChild(buyB);
              rootView.appendChild(form);
            });
            rootView.appendChild(f9Btn);

            renderWatchlistOrSearch();
          }

          renderScreenshot1Reconnect();
          installDoc(doc);
          window.BtechBridge = {
            onTradeRequiresAuth: function() {},
            onTradePhase: function() {},
            onTradeLog: function(m, err, done) { jmState.logs.push({ m: m, err: err, done: done }); },
            onSymbolDiscovered: function() {},
            onTradeResult: function(j) { jmState.results.push(JSON.parse(j)); }
          };
          eval($mt5ReconnectTradeJs);
          for (let w = 0; w < 100 && !jmState.logs.some(function(l) { return l.done; }); w++) {
            await sleepMs(50);
          }

          const mt5ReconnectConnectButtonOk =
            jmState.reconnectButtonClicks === 1 &&
            !jmState.sidebarLinkClicked &&
            !jmState.removeClicked;
          const mt5PlusAddAndClearWatchlistChartOk =
            jmState.plusClicked === 1 &&
            jmState.searchCleared &&
            jmState.watchlistSelected === 'XAUUSD.s' &&
            jmState.f9Opened &&
            jmState.buyClicks === 1 &&
            jmState.results.length === 1 &&
            jmState.results[0].status === 'ACCEPTED' &&
            jmState.results[0].actualSymbol === 'XAUUSD.s';

          // 3b. Checked symbol (✓) MUST NOT be re-added!
          const chkBefore = jmState.checkClicks;
          const plusBefore = jmState.plusClicked;
          eval($mt5CheckedDiscoveryJs);
          const chkRes = await window.__btechLastDiscoveryPromise;
          const mt5CheckedSymbolNotReaddedOk =
            chkRes.success &&
            chkRes.actualSymbol === 'XAGUSD.s' &&
            chkRes.alreadyChecked === true &&
            jmState.plusClicked === plusBefore &&
            jmState.checkClicks === chkBefore;

          // 4. MT4 Symbols dialog workflow WITHOUT literal "Show All" command
          global.location = { href: 'https://metatraderweb.app/trade?version=4', pathname: '/trade' };
          const d4 = createDomDocument();
          const mt4SymState = { groupExpanded: false, showClicked: false, rowSelected: '' };
          const mwPanel = d4.createElement('div');
          mwPanel.className = 'market-watch-panel';
          const mwHdr = d4.createElement('div');
          mwHdr.textContent = 'Market Watch';
          mwPanel.appendChild(mwHdr);
          const mwTbl = d4.createElement('table');
          const mwBody = d4.createElement('tbody');
          const mwRow1 = d4.createElement('tr');
          const mwCell1 = d4.createElement('td');
          mwCell1.textContent = 'EURUSD 1.0910 1.0912';
          mwRow1.appendChild(mwCell1);
          mwBody.appendChild(mwRow1);
          mwTbl.appendChild(mwBody);
          mwPanel.appendChild(mwTbl);
          d4.body.appendChild(mwPanel);

          mwPanel.addEventListener('contextmenu', function() {
            const menu = d4.createElement('div');
            menu.className = 'page-menu';
            // Intentionally NO "Show All" item — only "New Order" and "Symbols..."
            ['New Order', 'Symbols...', 'Depth of Market'].forEach(function(label) {
              const mi = d4.createElement('div');
              mi.setAttribute('role', 'menuitem');
              mi.textContent = label;
              if (label === 'Symbols...') {
                mi.addEventListener('click', function() {
                  const dlg = d4.createElement('div');
                  dlg.setAttribute('role', 'dialog');
                  const title = d4.createElement('h3');
                  title.textContent = 'Symbols';
                  dlg.appendChild(title);

                  const grp = d4.createElement('div');
                  grp.className = 'group collapsed';
                  grp.setAttribute('aria-expanded', 'false');
                  grp.textContent = 'Metals';
                  grp.addEventListener('click', function() {
                    if (mt4SymState.groupExpanded) return;
                    mt4SymState.groupExpanded = true;
                    grp.setAttribute('aria-expanded', 'true');
                    const hiddenRow = d4.createElement('div');
                    hiddenRow.setAttribute('role', 'row');
                    hiddenRow.setAttribute('data-visible', 'false');
                    hiddenRow.textContent = 'XAUUSD.m';
                    dlg.appendChild(hiddenRow);
                  });
                  dlg.appendChild(grp);

                  const showBtn = d4.createElement('button');
                  showBtn.textContent = 'Show';
                  showBtn.addEventListener('click', function() {
                    mt4SymState.showClicked = true;
                    const newRow = d4.createElement('tr');
                    const c = d4.createElement('td');
                    c.textContent = 'XAUUSD.m 2650.00 2650.30';
                    newRow.appendChild(c);
                    newRow.addEventListener('click', function() { mt4SymState.rowSelected = 'XAUUSD.m'; });
                    mwBody.appendChild(newRow);
                  });
                  const closeB = d4.createElement('button');
                  closeB.textContent = 'Close';
                  closeB.addEventListener('click', function() {
                    if (dlg.parentElement) dlg.parentElement.removeChild(dlg);
                  });
                  dlg.appendChild(showBtn);
                  dlg.appendChild(closeB);
                  d4.body.appendChild(dlg);
                });
              }
              menu.appendChild(mi);
            });
            d4.body.appendChild(menu);
          });

          installDoc(d4);
          window.BtechBridge = { onSymbolDiscovered: function() {} };
          eval($mt4SymbolsDialogJs);
          const mt4DlgRes = await window.__btechLastDiscoveryPromise;
          const mt4SymbolsDialogWorkflowOk =
            mt4DlgRes.success &&
            mt4DlgRes.usedSymbolsDialog === true &&
            mt4DlgRes.actualSymbol === 'XAUUSD.m' &&
            mt4SymState.groupExpanded &&
            mt4SymState.showClicked &&
            mt4SymState.rowSelected === 'XAUUSD.m';

          // 5. Screenshot 4 Order Form: disabled button + "Trading for this account is disabled on the server"
          global.location = { href: 'https://live.justmarkets.com/terminal', pathname: '/terminal' };
          function buildOrderFormDoc(opts) {
            const od = createDomDocument();
            const ost = { clicks: 0, results: [], logs: [] };
            const ws = od.createElement('div');
            ws.textContent = 'Balance: 100.00 USD Equity: 100.00 USD Quotes Chart Trade';
            od.body.appendChild(ws);
            const sIn = od.createElement('input');
            sIn.setAttribute('placeholder', 'Search symbol');
            const row = od.createElement('div');
            row.setAttribute('role', 'row');
            row.setAttribute('data-symbol', 'XAGUSD.s');
            row.textContent = 'XAGUSD.s';
            row.addEventListener('click', function() {
              const form = od.createElement('div');
              form.className = 'trade-form';
              const h = od.createElement('div');
              h.setAttribute('data-symbol', 'XAGUSD.s');
              h.textContent = 'XAGUSD.s';
              form.appendChild(h);

              const sel = od.createElement('select');
              const opt = od.createElement('option');
              opt.value = opts.orderType;
              opt.textContent = opts.orderType;
              sel.appendChild(opt);
              form.appendChild(sel);

              const vWrap = od.createElement('div');
              vWrap.className = 'trade-input';
              const vLbl = od.createElement('span');
              vLbl.className = 'label';
              vLbl.textContent = 'Volume';
              const vIn = od.createElement('input');
              vWrap.appendChild(vLbl);
              vWrap.appendChild(vIn);
              form.appendChild(vWrap);

              if (opts.pending) {
                const placeB = od.createElement('button');
                placeB.textContent = 'Place';
                placeB.addEventListener('click', function() {
                  ost.clicks++;
                  const r = od.createElement('tr');
                  r.textContent = '#662001 buy limit 0.02 XAGUSD.s at 30.10';
                  od.body.appendChild(r);
                });
                form.appendChild(placeB);
              } else {
                const sellB = od.createElement('button');
                sellB.textContent = 'Sell by Market';
                sellB.disabled = !!opts.disabled;
                const buyB = od.createElement('button');
                buyB.textContent = 'Buy by Market';
                buyB.disabled = !!opts.disabled;
                form.appendChild(sellB);
                form.appendChild(buyB);
              }

              if (opts.bannerError) {
                const banner = od.createElement('div');
                banner.className = 'footer-error';
                banner.textContent = opts.bannerError;
                form.appendChild(banner);
              }
              od.body.appendChild(form);
            });
            od.body.appendChild(sIn);
            od.body.appendChild(row);
            installDoc(od);
            window.BtechBridge = {
              onTradePhase: function() {},
              onTradeLog: function(m, err, done) { ost.logs.push({ m: m, err: err, done: done }); },
              onSymbolDiscovered: function() {},
              onTradeResult: function(j) { ost.results.push(JSON.parse(j)); }
            };
            return ost;
          }

          const disSt = buildOrderFormDoc({
            orderType: 'Market Execution',
            disabled: true,
            bannerError: 'Trading for this account is disabled on the server'
          });
          eval($mt5DisabledServerJs);
          for (let w = 0; w < 60 && !disSt.logs.some(function(l) { return l.done; }); w++) {
            await sleepMs(40);
          }
          const serverDisabledBannerRejectedOk =
            disSt.clicks === 0 &&
            disSt.results.length === 1 &&
            disSt.results[0].status === 'REJECTED' &&
            disSt.results[0].rawMessage.indexOf('Trading for this account is disabled on the server') >= 0;

          const pendSt = buildOrderFormDoc({
            orderType: 'Buy Limit',
            pending: true
          });
          eval($mt5PendingPlaceJs);
          for (let w = 0; w < 60 && !pendSt.logs.some(function(l) { return l.done; }); w++) {
            await sleepMs(40);
          }
          const pendingOrderPlaceDetectedOk =
            pendSt.clicks === 1 &&
            pendSt.results.length === 1 &&
            pendSt.results[0].status === 'ACCEPTED';

          // 6. Virtualized JustMarkets MT5 watchlist + unlabeled <svg> '+' icon button:
          //    Clearing search renders only visible top pairs ('EURUSD', 'USDJPY') — 'BTCUSD.s' is NOT in top unfiltered DOM.
          //    Must NOT fail with 'not present in displayed watchlist'; falls back to re-search + click icon + select row -> Trade ACCEPTED!
          global.location = { href: 'https://live.justmarkets.com/terminal', pathname: '/terminal' };
          const vDoc = createDomDocument();
          const vSt = { iconClicks: 0, rowSelected: '', buyClicks: 0, results: [], logs: [] };
          const vWs = vDoc.createElement('div');
          vWs.textContent = 'Balance: 500.00 USD Equity: 500.00 USD Quotes Chart Trade';
          vDoc.body.appendChild(vWs);

          const vChartHdr = vDoc.createElement('div');
          vChartHdr.className = 'chart-header';
          vChartHdr.textContent = 'EURUSD, H1';
          vDoc.body.appendChild(vChartHdr);

          const vSearchWrap = vDoc.createElement('div');
          const vSearchIn = vDoc.createElement('input');
          vSearchIn.setAttribute('placeholder', 'Search symbol');
          const vClearBtn = vDoc.createElement('button');
          vClearBtn.textContent = '×';
          vSearchWrap.appendChild(vSearchIn);
          vSearchWrap.appendChild(vClearBtn);
          vDoc.body.appendChild(vSearchWrap);

          const vList = vDoc.createElement('div');
          vDoc.body.appendChild(vList);
          let vQuery = '';
          function renderVirtualWatchlist() {
            vList.textContent = '';
            if (!vQuery) {
              // Simulated virtualized viewport where BTCUSD.s is scrolled below fold and absent from DOM!
              ['EURUSD', 'USDJPY', 'GBPUSD'].forEach(function(sym) {
                const r = vDoc.createElement('div');
                r.setAttribute('role', 'row');
                r.setAttribute('data-symbol', sym);
                r.textContent = sym;
                vList.appendChild(r);
              });
            } else if ('BTCUSD.s'.toUpperCase().indexOf(vQuery.toUpperCase()) >= 0) {
              const sRow = vDoc.createElement('div');
              sRow.setAttribute('role', 'row');
              const symSpan = vDoc.createElement('span');
              symSpan.textContent = 'BTCUSD.s';
              const iconBtn = vDoc.createElement('button'); // NO text label, NO aria-label — pure icon button!
              const svg = vDoc.createElement('svg');
              const p1 = vDoc.createElement('path');
              p1.setAttribute('d', 'M12 5V19M5 12H19');
              svg.appendChild(p1);
              iconBtn.appendChild(svg);
              iconBtn.addEventListener('click', function() { vSt.iconClicks++; });
              sRow.appendChild(symSpan);
              sRow.appendChild(iconBtn);
              sRow.addEventListener('click', function() {
                vSt.rowSelected = 'BTCUSD.s';
                vChartHdr.textContent = 'BTCUSD.s, H1';
                const f = vDoc.createElement('div');
                f.className = 'trade-form';
                const sh = vDoc.createElement('div');
                sh.setAttribute('data-symbol', 'BTCUSD.s');
                sh.textContent = 'BTCUSD.s';
                f.appendChild(sh);
                const vw = vDoc.createElement('div');
                vw.className = 'trade-input';
                const vl = vDoc.createElement('span');
                vl.className = 'label';
                vl.textContent = 'Volume';
                const vi = vDoc.createElement('input');
                vw.appendChild(vl);
                vw.appendChild(vi);
                f.appendChild(vw);
                const bb = vDoc.createElement('button');
                bb.textContent = 'Buy by Market';
                bb.addEventListener('click', function() {
                  vSt.buyClicks++;
                  const tr = vDoc.createElement('tr');
                  tr.textContent = '#991002 buy ' + vi.value + ' BTCUSD.s at 91500.00';
                  vDoc.body.appendChild(tr);
                });
                f.appendChild(bb);
                vDoc.body.appendChild(f);
              });
              vList.appendChild(sRow);
            }
          }
          vSearchIn.addEventListener('input', function() {
            vQuery = vSearchIn.value || '';
            renderVirtualWatchlist();
          });
          vClearBtn.addEventListener('click', function() {
            vSearchIn.value = '';
            vQuery = '';
            renderVirtualWatchlist();
          });
          renderVirtualWatchlist();
          installDoc(vDoc);
          window.BtechBridge = {
            onTradePhase: function() {},
            onTradeLog: function(m, err, done) { vSt.logs.push({ m: m, err: err, done: done }); },
            onSymbolDiscovered: function() {},
            onTradeResult: function(j) { vSt.results.push(JSON.parse(j)); }
          };
          eval($mt5VirtualizedBtcJs);
          for (let w = 0; w < 150 && !vSt.logs.some(function(l) { return l.done; }); w++) {
            await sleepMs(40);
          }
          const mt5VirtualizedWatchlistSearchFallbackOk =
            vSt.iconClicks === 1 &&
            vSt.rowSelected === 'BTCUSD.s' &&
            vSt.buyClicks === 1 &&
            vSt.results.length === 1 &&
            vSt.results[0].status === 'ACCEPTED' &&
            vSt.results[0].actualSymbol === 'BTCUSD.s';

          // 7. When BOTH Symbols... and Show All are present, Show All is prioritised directly ahead of Symbols... -> finds XAUUSD.m!
          global.location = { href: 'https://metatraderweb.app/trade?version=4', pathname: '/trade' };
          const d4Fb = createDomDocument();
          const fb4St = { showAllUsed: false, rowSelected: '' };
          const mw4 = d4Fb.createElement('div');
          mw4.className = 'market-watch-panel';
          const mw4Lbl = d4Fb.createElement('div');
          mw4Lbl.textContent = 'Market Watch';
          mw4.appendChild(mw4Lbl);
          const mw4T = d4Fb.createElement('table');
          const mw4B = d4Fb.createElement('tbody');
          const mw4R = d4Fb.createElement('tr');
          const mw4C = d4Fb.createElement('td');
          mw4C.textContent = 'EURUSD 1.0910 1.0912';
          mw4R.appendChild(mw4C);
          mw4B.appendChild(mw4R);
          mw4T.appendChild(mw4B);
          mw4.appendChild(mw4T);
          d4Fb.body.appendChild(mw4);

          mw4.addEventListener('contextmenu', function() {
            const existing = d4Fb.querySelectorAll('.page-menu');
            existing.forEach(function(n) { if (n.parentElement) n.parentElement.removeChild(n); });
            const m = d4Fb.createElement('div');
            m.className = 'page-menu';
            const symMi = d4Fb.createElement('div');
            symMi.setAttribute('role', 'menuitem');
            symMi.textContent = 'Symbols...';
            symMi.addEventListener('click', function() {
              const dlg = d4Fb.createElement('div');
              dlg.setAttribute('role', 'dialog');
              const h = d4Fb.createElement('h3');
              h.textContent = 'Symbols';
              const cB = d4Fb.createElement('button');
              cB.textContent = 'Close';
              cB.addEventListener('click', function() { if (dlg.parentElement) dlg.parentElement.removeChild(dlg); });
              dlg.appendChild(h);
              dlg.appendChild(cB);
              d4Fb.body.appendChild(dlg);
            });
            const allMi = d4Fb.createElement('div');
            allMi.setAttribute('role', 'menuitem');
            allMi.textContent = 'Show All';
            allMi.addEventListener('click', function() {
              fb4St.showAllUsed = true;
              const nr = d4Fb.createElement('tr');
              const nc = d4Fb.createElement('td');
              nc.textContent = 'XAUUSD.m 2650.00 2650.30';
              nr.appendChild(nc);
              nr.addEventListener('click', function() { fb4St.rowSelected = 'XAUUSD.m'; });
              mw4B.appendChild(nr);
            });
            m.appendChild(symMi);
            m.appendChild(allMi);
            d4Fb.body.appendChild(m);
          });
          installDoc(d4Fb);
          window.BtechBridge = { onSymbolDiscovered: function() {} };
          eval($mt4ShowAllFallbackJs);
          const fb4Res = await window.__btechLastDiscoveryPromise;
          const mt4SymbolsToShowAllFallbackOk =
            fb4Res.success &&
            fb4Res.actualSymbol === 'XAUUSD.m' &&
            fb4St.showAllUsed &&
            fb4St.rowSelected === 'XAUUSD.m';

          // 8. Strict exact broker symbol enforcement:
          //    Requesting "XAUUSD" on a JustMarkets Pro terminal that ONLY has "XAUUSD.s" MUST reject with error:Symbol XAUUSD not found!
          global.location = { href: 'https://live.justmarkets.com/terminal', pathname: '/terminal' };
          doc = createDomDocument();
          const jmStrictState = { buyClicks: 0, logs: [], results: [] };
          const strictHdr = doc.createElement('div');
          strictHdr.textContent = 'Balance: 1,000.00 USD Equity: 1,000.00 USD Quotes Chart Trade';
          doc.body.appendChild(strictHdr);
          const strictSearch = doc.createElement('input');
          strictSearch.setAttribute('placeholder', 'Search symbol');
          doc.body.appendChild(strictSearch);
          const strictList = doc.createElement('div');
          ['XAGUSD.s', 'XAUUSD.s', 'EURUSD'].forEach(function(sym) {
            const r = doc.createElement('div');
            r.setAttribute('role', 'row');
            r.setAttribute('data-symbol', sym);
            r.textContent = sym + ' 2650.00 2650.35';
            strictList.appendChild(r);
          });
          doc.body.appendChild(strictList);
          installDoc(doc);
          window.BtechBridge = {
            onTradeRequiresAuth: function() {},
            onTradePhase: function() {},
            onTradeLog: function(m, err, done) { jmStrictState.logs.push({ m: m, err: err, done: done }); },
            onSymbolDiscovered: function() {},
            onTradeResult: function(j) { jmStrictState.results.push(JSON.parse(j)); }
          };
          eval($mt5ExactMismatchTradeJs);
          for (let w = 0; w < 100 && !jmStrictState.logs.some(function(l) { return l.done; }); w++) {
            await sleepMs(40);
          }
          const mt5SuffixMismatchRejectedOk =
            jmStrictState.buyClicks === 0 &&
            jmStrictState.logs.some(function(l) { return l.err && l.done && l.m.indexOf('error:Symbol XAUUSD not found') >= 0; });

          // 9. Authentic Terminal -> Auth -> Symbol verification:
          //    Nav text "Quotes Chart Trade History Balance: — Equity: —" MUST confirm TERMINAL_OK, MUST NOT fake AUTH_OK,
          //    and proceeds to AUTH_OK -> SYMBOL_SEARCHING ONLY once real numeric Balance renders!
          const authSeqDoc = createDomDocument();
          const authSeq = { phases: [], balUpdates: [], logs: [] };
          const navOnly = authSeqDoc.createElement('div');
          navOnly.textContent = 'Quotes Chart Trade History Balance: — Equity: — P/L: —';
          authSeqDoc.body.appendChild(navOnly);
          const seqSearch = authSeqDoc.createElement('input');
          seqSearch.setAttribute('placeholder', 'Search symbol');
          authSeqDoc.body.appendChild(seqSearch);
          const seqRow = authSeqDoc.createElement('div');
          seqRow.setAttribute('role', 'row');
          seqRow.setAttribute('data-symbol', 'XAGUSD.s');
          seqRow.textContent = 'XAGUSD.s';
          authSeqDoc.body.appendChild(seqRow);
          installDoc(authSeqDoc);
          window.BtechBridge = {
            onTradeRequiresAuth: function() { authSeq.phases.push({ k: 'REQUIRES_AUTH', d: '' }); },
            onBalanceUpdate: function(j) { authSeq.balUpdates.push(JSON.parse(j)); },
            onTradePhase: function(k, d) { authSeq.phases.push({ k: k, d: d }); },
            onTradeLog: function(m, err, done) { authSeq.logs.push({ m: m, err: err, done: done }); },
            onSymbolDiscovered: function() {},
            onTradeResult: function() {}
          };
          eval($mt5DisabledServerJs);
          await sleepMs(900);
          const noFakeAuthWhileBalanceDashOk =
            authSeq.phases.some(function(p) { return p.k === 'TERMINAL_OK'; }) &&
            !authSeq.phases.some(function(p) { return p.k === 'AUTH_OK'; }) &&
            !authSeq.phases.some(function(p) { return p.k === 'SYMBOL_SEARCHING'; });
          // Now simulate real broker connection populating Balance & Equity
          navOnly.textContent = 'Balance: 150.00 USD Equity: 150.00 USD Free margin: 150.00 USD Quotes Chart Trade';
          for (let w = 0; w < 60 && !authSeq.phases.some(function(p) { return p.k === 'SYMBOL_SEARCHING'; }); w++) {
            await sleepMs(50);
          }
          const idxTermOk = authSeq.phases.findIndex(function(p) { return p.k === 'TERMINAL_OK'; });
          const idxAuthOk = authSeq.phases.findIndex(function(p) { return p.k === 'AUTH_OK'; });
          const idxSymSearch = authSeq.phases.findIndex(function(p) { return p.k === 'SYMBOL_SEARCHING'; });
          const authenticVerifyPreventsNavFalsePositiveOk =
            noFakeAuthWhileBalanceDashOk &&
            idxTermOk >= 0 &&
            idxAuthOk > idxTermOk &&
            idxSymSearch > idxAuthOk &&
            authSeq.balUpdates.some(function(b) { return String(b.balance).indexOf('150.00') >= 0; });

          console.log(JSON.stringify({
            ok: true,
            mt4OnJustMarketsMt5RejectedOk: mt4OnJustMarketsMt5RejectedOk,
            mt5OnMt4WebRejectedOk: mt5OnMt4WebRejectedOk,
            mt5ReconnectConnectButtonOk: mt5ReconnectConnectButtonOk,
            mt5PlusAddAndClearWatchlistChartOk: mt5PlusAddAndClearWatchlistChartOk,
            mt5CheckedSymbolNotReaddedOk: mt5CheckedSymbolNotReaddedOk,
            mt4SymbolsDialogWorkflowOk: mt4SymbolsDialogWorkflowOk,
            serverDisabledBannerRejectedOk: serverDisabledBannerRejectedOk,
            pendingOrderPlaceDetectedOk: pendingOrderPlaceDetectedOk,
            mt5VirtualizedWatchlistSearchFallbackOk: mt5VirtualizedWatchlistSearchFallbackOk,
            mt4SymbolsToShowAllFallbackOk: mt4SymbolsToShowAllFallbackOk,
            mt5SuffixMismatchRejectedOk: mt5SuffixMismatchRejectedOk,
            authenticVerifyPreventsNavFalsePositiveOk: authenticVerifyPreventsNavFalsePositiveOk,
            vStDebug: vSt
          }));
        })().catch(function(err) {
          console.log(JSON.stringify({ ok: false, error: err && err.stack ? err.stack : String(err) }));
        });
        """.trimIndent()

        val results = runNodeHarness(script)
        assertTrue("Screenshot correction suite failed: ${results.optString("error")}", results.optBoolean("ok", false))
        assertTrue(results.getBoolean("mt4OnJustMarketsMt5RejectedOk"))
        assertTrue(results.getBoolean("mt5OnMt4WebRejectedOk"))
        assertTrue(results.getBoolean("mt5ReconnectConnectButtonOk"))
        assertTrue(results.getBoolean("mt5PlusAddAndClearWatchlistChartOk"))
        assertTrue(results.getBoolean("mt5CheckedSymbolNotReaddedOk"))
        assertTrue(results.getBoolean("mt4SymbolsDialogWorkflowOk"))
        assertTrue(results.getBoolean("serverDisabledBannerRejectedOk"))
        assertTrue("pendingOrderPlaceDetectedOk failed: $results", results.getBoolean("pendingOrderPlaceDetectedOk"))
        assertTrue("mt5VirtualizedWatchlistSearchFallbackOk failed: vStDebug=${results.opt("vStDebug")}", results.getBoolean("mt5VirtualizedWatchlistSearchFallbackOk"))
        assertTrue(results.getBoolean("mt4SymbolsToShowAllFallbackOk"))
        assertTrue(results.getBoolean("mt5SuffixMismatchRejectedOk"))
        assertTrue(results.getBoolean("authenticVerifyPreventsNavFalsePositiveOk"))
    }

    @Test
    fun `Part 13 Desktop Viewport JS enforcement overrides mobile meta tags, resists post-load mutations, persists, and reports layout diagnostics`() {
        val enforceDefaultJs = WebTerminalScripts.buildDesktopViewportEnforcementScript(
            targetWidthPx = 1366,
            enabled = true,
            isFallback = false,
        )
        val enforceCustomJs = WebTerminalScripts.buildDesktopViewportEnforcementScript(
            targetWidthPx = 1440,
            enabled = true,
            isFallback = false,
        )
        val diagSupportedJs = WebTerminalScripts.buildDesktopViewportDiagnosticsScript(
            targetWidthPx = 1366,
            enforcementActive = true,
            documentStartSupported = true,
            fallbackApplied = false,
        )
        val diagFallbackJs = WebTerminalScripts.buildDesktopViewportDiagnosticsScript(
            targetWidthPx = 1366,
            enforcementActive = true,
            documentStartSupported = false,
            fallbackApplied = true,
        )

        val script = """
        (async function() {
          // Provide lightweight HTMLMetaElement & MutationObserver mocks to exercise mutation interception
          const activeObservers = [];
          function notifyMetaMutation(type, target, addedNodes, removedNodes) {
            const mut = {
              type: type,
              target: target,
              addedNodes: addedNodes || [],
              removedNodes: removedNodes || []
            };
            activeObservers.forEach(function(obs) {
              try { obs.cb([mut]); } catch (_) {}
            });
          }

          class MockMutationObserver {
            constructor(cb) { this.cb = cb; this.connected = false; }
            observe() { this.connected = true; if (activeObservers.indexOf(this) < 0) activeObservers.push(this); }
            disconnect() {
              this.connected = false;
              const idx = activeObservers.indexOf(this);
              if (idx >= 0) activeObservers.splice(idx, 1);
            }
          }
          global.MutationObserver = MockMutationObserver;

          class MockHTMLMetaElement extends ShimElement {
            constructor(ownerDocument) { super('META', ownerDocument); }
            get content() { return this.getAttribute('content') || ''; }
            set content(v) {
              this.setAttribute('content', String(v || ''));
              notifyMetaMutation('attributes', this);
            }
          }
          MockHTMLMetaElement.prototype.setAttribute = function(k, v) {
            ShimElement.prototype.setAttribute.call(this, k, v);
            if (String(k || '').toLowerCase() === 'content') {
              notifyMetaMutation('attributes', this);
            }
          };
          global.HTMLMetaElement = MockHTMLMetaElement;

          const doc = createDomDocument();
          const origCreate = doc.createElement;
          doc.createElement = function(tag) {
            if (String(tag || '').toUpperCase() === 'META') {
              return new MockHTMLMetaElement(doc);
            }
            return origCreate(tag);
          };
          const origAppendHead = doc.head.appendChild.bind(doc.head);
          doc.head.appendChild = function(child) {
            const res = origAppendHead(child);
            notifyMetaMutation('childList', doc.head, [child], []);
            return res;
          };

          // 1. Broker initially supplies mobile meta tag width=device-width
          const initialMeta = doc.createElement('meta');
          initialMeta.setAttribute('name', 'viewport');
          initialMeta.setAttribute('content', 'width=device-width, initial-scale=1.0, maximum-scale=1.0');
          doc.head.appendChild(initialMeta);
          installDoc(doc);
          global.location = { href: 'https://live.justmarkets.com/terminal', pathname: '/terminal' };
          global.navigator = { userAgent: 'Mozilla/5.0 (X11; Linux x86_64) Chrome/130.0.0.0 Safari/537.36' };
          global.screen = { width: 412, height: 915 };
          global.innerWidth = 1366;
          global.innerHeight = 768;
          doc.documentElement.clientWidth = 1366;
          global.visualViewport = { width: 1366 };

          eval(${JSONObject.quote(enforceDefaultJs)});
          const initialOverrideOk =
            initialMeta.getAttribute('content') === 'width=1366, user-scalable=yes' &&
            window.__btechViewportEnforced === true &&
            window.__btechViewportObserver &&
            window.__btechViewportObserver.connected === true;

          // 2. Broker framework script attempts post-load mutation back to mobile viewport via property setter + setAttribute
          initialMeta.content = 'width=device-width, initial-scale=1';
          const propMutationBlockedOk = initialMeta.getAttribute('content') === 'width=1366, user-scalable=yes';

          initialMeta.setAttribute('content', 'width=device-width, maximum-scale=1.0');
          const setAttrMutationBlockedOk = initialMeta.getAttribute('content') === 'width=1366, user-scalable=yes';

          // 3. Broker appends a brand-new competing <meta name="viewport"> tag dynamically
          const injectedMeta = doc.createElement('meta');
          injectedMeta.setAttribute('name', 'viewport');
          injectedMeta.setAttribute('content', 'width=device-width, initial-scale=1');
          doc.head.appendChild(injectedMeta);
          const dynamicInjectedMetaOverriddenOk = injectedMeta.getAttribute('content') === 'width=1366, user-scalable=yes';

          // 4. Diagnostics report actual CSS layout viewport metrics (independent of mobile screen.width=412)
          const diag1 = JSON.parse(eval(${JSONObject.quote(diagSupportedJs)}));
          const diagnosticsDesktopOk =
            diag1.innerWidth === 1366 &&
            diag1.clientWidth === 1366 &&
            diag1.visualViewportWidth === 1366 &&
            diag1.innerHeight === 768 &&
            diag1.metaViewport.indexOf('width=1366, user-scalable=yes') >= 0 &&
            diag1.userAgent.indexOf('X11; Linux x86_64') >= 0 &&
            diag1.url === 'https://live.justmarkets.com/terminal' &&
            diag1.viewportEnforced === true &&
            diag1.layoutIsDesktopWidth === true &&
            diag1.documentStartSupported === true &&
            diag1.fallbackApplied === false;

          // 5. Re-running enforcement after simulated reload / auth transition preserves desktop viewport
          eval(${JSONObject.quote(enforceCustomJs)});
          const postReloadCustomWidthOk =
            initialMeta.getAttribute('content') === 'width=1440, user-scalable=yes' &&
            window.__btechViewportEnforced === true;

          // 6. Fallback limitation flag when DOCUMENT_START_SCRIPT is unavailable
          window.__btechViewportDocStartActive = false;
          const diagFb = JSON.parse(eval(${JSONObject.quote(diagFallbackJs)}));
          const fallbackLimitationReportedOk =
            diagFb.documentStartSupported === false &&
            diagFb.fallbackApplied === true;

          console.log(JSON.stringify({
            ok: true,
            initialOverrideOk: initialOverrideOk,
            propMutationBlockedOk: propMutationBlockedOk,
            setAttrMutationBlockedOk: setAttrMutationBlockedOk,
            dynamicInjectedMetaOverriddenOk: dynamicInjectedMetaOverriddenOk,
            diagnosticsDesktopOk: diagnosticsDesktopOk,
            postReloadCustomWidthOk: postReloadCustomWidthOk,
            fallbackLimitationReportedOk: fallbackLimitationReportedOk
          }));
        })().catch(function(err) {
          console.log(JSON.stringify({ ok: false, error: err && err.stack ? err.stack : String(err) }));
        });
        """.trimIndent()

        val results = runNodeHarness(script)
        assertTrue("Part 13 Desktop Viewport JS suite failed: ${results.optString("error")}", results.optBoolean("ok", false))
        assertTrue(results.getBoolean("initialOverrideOk"))
        assertTrue(results.getBoolean("propMutationBlockedOk"))
        assertTrue(results.getBoolean("setAttrMutationBlockedOk"))
        assertTrue(results.getBoolean("dynamicInjectedMetaOverriddenOk"))
        assertTrue(results.getBoolean("diagnosticsDesktopOk"))
        assertTrue(results.getBoolean("postReloadCustomWidthOk"))
        assertTrue(results.getBoolean("fallbackLimitationReportedOk"))
    }

    @Test
    fun `Part 13 MT5 direct search, labeled inputs, dropdown Market Execution switch, chart-control filter, non-OK dismissal, serial execution, and UNKNOWN safety`() {
        val discMetalsJs = WebTerminalScripts.buildSymbolDiscoveryScript("MT5", "XAUUSD", "JustMarkets", "JustMarkets-Live", "{}", 10)
        val discLabeledJs = WebTerminalScripts.buildSymbolDiscoveryScript("MT5", "BTCUSD.s", "JustMarkets", "JustMarkets-Live", "{}", 10)
        val discMissingInputJs = WebTerminalScripts.buildSymbolDiscoveryScript("MT5", "EURUSD", "JustMarkets", "JustMarkets-Live", "{}", 10)
        val discAliasJs = WebTerminalScripts.buildSymbolDiscoveryScript("MT5", "GOLD", "Exness", "Exness-MT5", """{"GOLD":"XAUUSD.ecn"}""", 10)

        val tradeBuySingleJs = WebTerminalScripts.buildTradeScript("XAUUSD", "BUY", "0.02", "2640", "2670", "BTECH-BUY", 1, "MT5", "JustMarkets", "JustMarkets-Live", "{}", "s1", 25, "", 101L)
        val tradeSellSingleJs = WebTerminalScripts.buildTradeScript("XAUUSD", "SELL", "0.05", "2680", "2630", "BTECH-SELL", 1, "MT5", "JustMarkets", "JustMarkets-Live", "{}", "s2", 25, "", 102L)
        val tradeRejectDoneJs = WebTerminalScripts.buildTradeScript("XAUUSD", "BUY", "0.01", "", "", "BTECH-REJ", 2, "MT5", "JustMarkets", "JustMarkets-Live", "{}", "s3", 25, "", 103L)
        val tradePendingBlankPriceJs = WebTerminalScripts.buildTradeScript("XAUUSD", "BUY", "0.01", "", "", "BTECH-PEND", 1, "MT5", "JustMarkets", "JustMarkets-Live", "{}", "s4", 25, "", 104L)
        val tradeMulti3Js = WebTerminalScripts.buildTradeScript("XAUUSD", "BUY", "0.01", "2640", "2680", "BTECH-M3", 3, "MT5", "JustMarkets", "JustMarkets-Live", "{}", "s5", 25, "", 105L)
        val tradeUnknownJs = WebTerminalScripts.buildTradeScript("XAUUSD", "BUY", "0.01", "", "", "BTECH-UNK", 2, "MT5", "JustMarkets", "JustMarkets-Live", "{}", "s6", 6, "", 106L)

        val script = """
        (async function() {
          global.location = { href: 'https://live.justmarkets.com/terminal', pathname: '/terminal' };

          // 1. Direct search across different groups WITHOUT clicking group tabs (Forex, Metals, Crypto, Indices)
          const dGrp = createDomDocument();
          const grpSt = { groupTabClicks: 0, chartSymbol: 'EURUSD' };
          const ws1 = dGrp.createElement('div');
          ws1.textContent = 'Balance: 2,500.00 USD Equity: 2,500.00 USD Quotes Chart Trade';
          dGrp.body.appendChild(ws1);
          ['Forex', 'Metals', 'Crypto', 'Indices'].forEach(function(g) {
            const tb = dGrp.createElement('button');
            tb.textContent = g;
            tb.addEventListener('click', function() { grpSt.groupTabClicks++; });
            dGrp.body.appendChild(tb);
          });
          const chHdr = dGrp.createElement('div');
          chHdr.className = 'chart-header';
          chHdr.textContent = 'EURUSD, H1';
          dGrp.body.appendChild(chHdr);
          const sIn1 = dGrp.createElement('input');
          sIn1.setAttribute('placeholder', 'Search symbol');
          dGrp.body.appendChild(sIn1);
          const list1 = dGrp.createElement('div');
          dGrp.body.appendChild(list1);
          const allItems1 = [
            { sym: 'XAUUSD', grp: 'Metals' },
            { sym: 'BTCUSD.s', grp: 'Crypto' },
            { sym: 'US500', grp: 'Indices' },
            { sym: 'EURUSD', grp: 'Forex' }
          ];
          sIn1.addEventListener('input', function() {
            list1.textContent = '';
            const q = String(sIn1.value || '').toUpperCase();
            allItems1.filter(function(it) { return !q || it.sym.toUpperCase().indexOf(q) >= 0; }).forEach(function(it) {
              const r = dGrp.createElement('div');
              r.setAttribute('role', 'row');
              r.setAttribute('data-symbol', it.sym);
              r.textContent = it.sym + ' [' + it.grp + ']';
              r.addEventListener('click', function() {
                grpSt.chartSymbol = it.sym;
                chHdr.textContent = it.sym + ', M15';
              });
              list1.appendChild(r);
            });
          });
          installDoc(dGrp);
          window.BtechBridge = { onSymbolDiscovered: function() {} };
          eval(${JSONObject.quote(discMetalsJs)});
          const rGrp = await window.__btechLastDiscoveryPromise;
          const directSearchNoGroupClicksOk = rGrp.success && rGrp.actualSymbol === 'XAUUSD' && grpSt.groupTabClicks === 0 && grpSt.chartSymbol === 'XAUUSD';

          // 2. Search input identified ONLY via <label for="..."> (no placeholder/aria-label)
          const dLbl = createDomDocument();
          const ws2 = dLbl.createElement('div');
          ws2.textContent = 'Balance: 1,000.00 USD Equity: 1,000.00 USD Quotes Chart Trade';
          dLbl.body.appendChild(ws2);
          const lblEl = dLbl.createElement('label');
          lblEl.setAttribute('for', 'broker_sym_box');
          lblEl.textContent = 'Search symbol';
          const lblInput = dLbl.createElement('input');
          lblInput.id = 'broker_sym_box';
          dLbl.body.appendChild(lblEl);
          dLbl.body.appendChild(lblInput);
          const lblList = dLbl.createElement('div');
          dLbl.body.appendChild(lblList);
          lblInput.addEventListener('input', function() {
            lblList.textContent = '';
            if (String(lblInput.value || '').toUpperCase().indexOf('BTC') >= 0) {
              const rr = dLbl.createElement('div');
              rr.setAttribute('role', 'row');
              rr.setAttribute('data-symbol', 'BTCUSD.s');
              rr.textContent = 'BTCUSD.s';
              lblList.appendChild(rr);
            }
          });
          installDoc(dLbl);
          window.BtechBridge = { onSymbolDiscovered: function() {} };
          eval(${JSONObject.quote(discLabeledJs)});
          const rLbl = await window.__btechLastDiscoveryPromise;
          const labeledSearchInputFoundOk = rLbl.success && rLbl.actualSymbol === 'BTCUSD.s';

          // 3. Missing search field returns diagnostic naming what controls ARE visible (not false "symbol not found")
          const dNoSearch = createDomDocument();
          const ws3 = dNoSearch.createElement('div');
          ws3.textContent = 'Balance: 1,000.00 USD Equity: 1,000.00 USD Quotes Chart Trade';
          dNoSearch.body.appendChild(ws3);
          const dummyBtn = dNoSearch.createElement('button');
          dummyBtn.textContent = 'Account History';
          dNoSearch.body.appendChild(dummyBtn);
          installDoc(dNoSearch);
          window.BtechBridge = { onSymbolDiscovered: function() {} };
          eval(${JSONObject.quote(discMissingInputJs)});
          const rNoSearch = await window.__btechLastDiscoveryPromise;
          const missingSearchDiagnosticOk =
            !rNoSearch.success &&
            String(rNoSearch.error).indexOf('MT5 symbol search field not found') >= 0 &&
            String(rNoSearch.error).indexOf('Account History') >= 0;

          // 4. Verified alias lookup (GOLD -> XAUUSD.ecn)
          const dAlias = createDomDocument();
          const ws4 = dAlias.createElement('div');
          ws4.textContent = 'Balance: 1,000.00 USD Equity: 1,000.00 USD';
          dAlias.body.appendChild(ws4);
          const aliasIn = dAlias.createElement('input');
          aliasIn.setAttribute('placeholder', 'Search symbol');
          dAlias.body.appendChild(aliasIn);
          const aliasList = dAlias.createElement('div');
          dAlias.body.appendChild(aliasList);
          aliasIn.addEventListener('input', function() {
            aliasList.textContent = '';
            const rowA = dAlias.createElement('div');
            rowA.setAttribute('role', 'row');
            rowA.setAttribute('data-symbol', 'XAUUSD.ecn');
            rowA.textContent = 'XAUUSD.ecn';
            aliasList.appendChild(rowA);
          });
          installDoc(dAlias);
          window.BtechBridge = { onSymbolDiscovered: function() {} };
          eval(${JSONObject.quote(discAliasJs)});
          const rAlias = await window.__btechLastDiscoveryPromise;
          const verifiedAliasOk = rAlias.success && rAlias.actualSymbol === 'XAUUSD.ecn' && rAlias.matchType === 'VERIFIED_ALIAS';

          // Helper for rich MT5 trade execution scenarios
          function setupRichMt5TradeDom(cfg) {
            cfg = cfg || {};
            const td = createDomDocument();
            const st = {
              chartToolClicks: 0,
              dropdownValue: cfg.initialOrderType || 'Buy Limit',
              buyClicks: 0,
              sellClicks: 0,
              doneDismissed: false,
              okDismissed: false,
              openForms: 0,
              ticketRemainedOpenAfterError: false,
              submittedDirections: [],
              filledLot: '',
              filledSl: '',
              filledTp: '',
              filledComment: '',
              logs: [],
              results: [],
              phases: []
            };
            const hdr = td.createElement('div');
            hdr.textContent = 'Balance: 10,000.00 USD Equity: 10,000.00 USD Quotes Chart Trade';
            td.body.appendChild(hdr);

            const chHdrT = td.createElement('div');
            chHdrT.className = 'chart-header';
            chHdrT.textContent = 'EURUSD, H1';
            td.body.appendChild(chHdrT);

            // Chart drawing & settings toolbar controls BEFORE New Order -> must NEVER be clicked
            ['Trend Line', 'Chart Settings', 'Crosshair'].forEach(function(lbl) {
              const toolBtn = td.createElement('button');
              toolBtn.setAttribute('title', lbl);
              toolBtn.textContent = lbl;
              toolBtn.addEventListener('click', function() { st.chartToolClicks++; });
              td.body.appendChild(toolBtn);
            });

            const sIn = td.createElement('input');
            sIn.setAttribute('placeholder', 'Search symbol');
            td.body.appendChild(sIn);
            const rRow = td.createElement('div');
            rRow.setAttribute('role', 'row');
            rRow.setAttribute('data-symbol', 'XAUUSD');
            rRow.textContent = 'XAUUSD';
            td.body.appendChild(rRow);

            const newOrderBtn = td.createElement('button');
            newOrderBtn.setAttribute('title', 'New Order (F9)');
            newOrderBtn.textContent = 'New Order';
            td.body.appendChild(newOrderBtn);

            rRow.addEventListener('click', function() {
              chHdrT.textContent = 'XAUUSD, H1';
            });

            let activeForm = null;
            newOrderBtn.addEventListener('click', function() {
              if (activeForm && activeForm.parentElement) activeForm.parentElement.removeChild(activeForm);
              activeForm = td.createElement('div');
              activeForm.className = 'trade-form';
              activeForm.setAttribute('role', 'dialog');
              st.openForms++;

              const closeTicket = td.createElement('button');
              closeTicket.textContent = '×';
              closeTicket.addEventListener('click', function() {
                if (activeForm && activeForm.parentElement) activeForm.parentElement.removeChild(activeForm);
              });
              activeForm.appendChild(closeTicket);

              const symTitle = td.createElement('div');
              symTitle.setAttribute('data-symbol', 'XAUUSD');
              symTitle.textContent = 'XAUUSD';
              activeForm.appendChild(symTitle);

              // Execution type dropdown
              const sel = td.createElement('select');
              const types = cfg.onlyPendingOption ? ['Buy Limit', 'Sell Limit'] : ['Buy Limit', 'Market Execution', 'Sell Limit'];
              types.forEach(function(tp) {
                const opt = td.createElement('option');
                opt.value = tp;
                opt.textContent = tp;
                sel.appendChild(opt);
              });
              sel.value = st.dropdownValue;
              sel.addEventListener('change', function() {
                st.dropdownValue = sel.value;
              });
              activeForm.appendChild(sel);

              if (cfg.onlyPendingOption) {
                const pWrap = td.createElement('div');
                pWrap.className = 'trade-input';
                const pLbl = td.createElement('span');
                pLbl.className = 'label';
                pLbl.textContent = 'Price';
                const pIn = td.createElement('input');
                pWrap.appendChild(pLbl);
                pWrap.appendChild(pIn);
                activeForm.appendChild(pWrap);
              }

              function addFld(name, onSet) {
                const w = td.createElement('div');
                w.className = 'trade-input';
                const l = td.createElement('span');
                l.className = 'label';
                l.textContent = name;
                const i = td.createElement('input');
                i.addEventListener('input', function() { onSet(i.value); });
                w.appendChild(l);
                w.appendChild(i);
                activeForm.appendChild(w);
              }
              addFld('Volume', function(v) { st.filledLot = v; });
              addFld('Stop Loss', function(v) { st.filledSl = v; });
              addFld('Take Profit', function(v) { st.filledTp = v; });

              const cw = td.createElement('div');
              cw.className = 'comment';
              const cl = td.createElement('span');
              cl.className = 'label';
              cl.textContent = 'Comment';
              const ci = td.createElement('input');
              ci.addEventListener('input', function() { st.filledComment = ci.value; });
              cw.appendChild(cl);
              cw.appendChild(ci);
              activeForm.appendChild(cw);

              const sellB = td.createElement('button');
              sellB.textContent = 'Sell by Market';
              const buyB = td.createElement('button');
              buyB.textContent = 'Buy by Market';

              function handleSubmit(isBuy) {
                if (isBuy) { st.buyClicks++; st.submittedDirections.push('BUY'); }
                else { st.sellClicks++; st.submittedDirections.push('SELL'); }
                if (cfg.outcome === 'reject_done') {
                  // Simulate broker rejection popup with ONLY "Done" button (no OK), plus underlying order ticket stays open!
                  const rejDlg = td.createElement('div');
                  rejDlg.setAttribute('role', 'alertdialog');
                  rejDlg.className = 'trade-result';
                  const msg = td.createElement('div');
                  msg.className = 'error';
                  msg.textContent = 'Not enough money';
                  const doneB = td.createElement('button');
                  doneB.textContent = 'Done';
                  doneB.addEventListener('click', function() {
                    st.doneDismissed = true;
                    if (rejDlg.parentElement) rejDlg.parentElement.removeChild(rejDlg);
                  });
                  rejDlg.appendChild(msg);
                  rejDlg.appendChild(doneB);
                  td.body.appendChild(rejDlg);
                } else if (cfg.outcome === 'unknown') {
                  // Do not display any receipt or position -> triggers UNKNOWN
                } else {
                  const posRow = td.createElement('tr');
                  posRow.textContent = '#7700' + (st.buyClicks + st.sellClicks) + ' ' + (isBuy ? 'buy' : 'sell') + ' ' + st.filledLot + ' XAUUSD at 2652.00';
                  td.body.appendChild(posRow);
                  if (cfg.useOkDialog) {
                    const okDlg = td.createElement('div');
                    okDlg.setAttribute('role', 'dialog');
                    okDlg.className = 'trade-result';
                    const txt = td.createElement('div');
                    txt.className = 'status';
                    txt.textContent = 'Done: Order #' + (st.buyClicks + st.sellClicks) + ' executed';
                    const okB = td.createElement('button');
                    okB.textContent = 'OK';
                    okB.addEventListener('click', function() {
                      st.okDismissed = true;
                      if (okDlg.parentElement) okDlg.parentElement.removeChild(okDlg);
                      if (activeForm && activeForm.parentElement) activeForm.parentElement.removeChild(activeForm);
                    });
                    okDlg.appendChild(txt);
                    okDlg.appendChild(okB);
                    td.body.appendChild(okDlg);
                  } else if (activeForm && activeForm.parentElement) {
                    activeForm.parentElement.removeChild(activeForm);
                  }
                }
              }

              buyB.addEventListener('click', function() { handleSubmit(true); });
              sellB.addEventListener('click', function() { handleSubmit(false); });
              activeForm.appendChild(sellB);
              activeForm.appendChild(buyB);
              td.body.appendChild(activeForm);
            });

            installDoc(td);
            window.BtechBridge = {
              onTradeRequiresAuth: function() {},
              onTradePhase: function(k, d) { st.phases.push({ k: k, d: d }); },
              onTradeLog: function(m, err, done) { st.logs.push({ m: m, err: err, done: done }); },
              onSymbolDiscovered: function() {},
              onTradeResult: function(j) { st.results.push(JSON.parse(j)); }
            };
            return { doc: td, st: st };
          }

          // 5. Market Buy executes once, ignores Chart Tools, actively switches dropdown from Buy Limit -> Market Execution
          let runCtx = setupRichMt5TradeDom({ initialOrderType: 'Buy Limit', useOkDialog: true });
          eval(${JSONObject.quote(tradeBuySingleJs)});
          for (let w = 0; w < 100 && !runCtx.st.logs.some(function(l) { return l.done; }); w++) { await sleepMs(40); }
          const buyDropdownSwitchedOnceOk =
            runCtx.st.chartToolClicks === 0 &&
            runCtx.st.dropdownValue === 'Market Execution' &&
            runCtx.st.filledLot === '0.02' &&
            runCtx.st.filledSl === '2640' &&
            runCtx.st.filledTp === '2670' &&
            runCtx.st.filledComment === 'BTECH-BUY' &&
            runCtx.st.buyClicks === 1 &&
            runCtx.st.sellClicks === 0 &&
            runCtx.st.okDismissed === true &&
            runCtx.doc.querySelectorAll('.trade-result, .trade-form').length === 0 &&
            runCtx.st.results.length === 1 && runCtx.st.results[0].status === 'ACCEPTED';

          // 6. Market Sell executes once
          runCtx = setupRichMt5TradeDom({ initialOrderType: 'Market Execution', useOkDialog: true });
          eval(${JSONObject.quote(tradeSellSingleJs)});
          for (let w = 0; w < 100 && !runCtx.st.logs.some(function(l) { return l.done; }); w++) { await sleepMs(40); }
          const sellSingleOk =
            runCtx.st.buyClicks === 0 &&
            runCtx.st.sellClicks === 1 &&
            runCtx.st.okDismissed === true &&
            runCtx.st.results.length === 1 && runCtx.st.results[0].status === 'ACCEPTED';

          // 7. Pending mode with blank required Price input rejects without inventing entry price
          runCtx = setupRichMt5TradeDom({ onlyPendingOption: true, initialOrderType: 'Buy Limit' });
          eval(${JSONObject.quote(tradePendingBlankPriceJs)});
          for (let w = 0; w < 100 && !runCtx.st.logs.some(function(l) { return l.done; }); w++) { await sleepMs(40); }
          const blankPendingPriceRejectedOk =
            runCtx.st.buyClicks === 0 &&
            runCtx.st.results.length === 1 &&
            runCtx.st.results[0].status === 'REJECTED' &&
            runCtx.st.results[0].rawMessage.indexOf('entry price') >= 0;

          // 8. Broker rejection -> Done button (non-OK) dismissed + open ticket closed cleanly + stops 2nd trade
          runCtx = setupRichMt5TradeDom({ initialOrderType: 'Market Execution', outcome: 'reject_done' });
          eval(${JSONObject.quote(tradeRejectDoneJs)});
          for (let w = 0; w < 120 && !runCtx.st.logs.some(function(l) { return l.done; }); w++) { await sleepMs(40); }
          const rejectionDoneAndTicketClosedOk =
            runCtx.st.buyClicks === 1 &&
            runCtx.st.doneDismissed === true &&
            runCtx.doc.querySelectorAll('.trade-result, .trade-form').length === 0 &&
            runCtx.st.results.length === 1 &&
            runCtx.st.results[0].status === 'REJECTED' &&
            runCtx.st.results[0].rawMessage.indexOf('Not enough money') >= 0;

          // 9. Serial 3-trade execution opens fresh form per trade and clears OK dialog between each trade
          runCtx = setupRichMt5TradeDom({ initialOrderType: 'Market Execution', useOkDialog: true });
          eval(${JSONObject.quote(tradeMulti3Js)});
          for (let w = 0; w < 260 && !runCtx.st.logs.some(function(l) { return l.done; }); w++) { await sleepMs(40); }
          const serial3TradesConfirmedOk =
            runCtx.st.buyClicks === 3 &&
            runCtx.st.openForms === 3 &&
            runCtx.doc.querySelectorAll('.trade-result, .trade-form').length === 0 &&
            runCtx.st.results.length === 1 &&
            runCtx.st.results[0].status === 'ACCEPTED' &&
            runCtx.st.results[0].confirmedCount === 3;

          // 10. UNKNOWN broker outcome never duplicates submission and closes stale form cleanly
          runCtx = setupRichMt5TradeDom({ initialOrderType: 'Market Execution', outcome: 'unknown' });
          eval(${JSONObject.quote(tradeUnknownJs)});
          for (let w = 0; w < 180 && !runCtx.st.logs.some(function(l) { return l.done; }); w++) { await sleepMs(40); }
          const unknownNoDuplicateSubmitOk =
            runCtx.st.buyClicks === 1 &&
            runCtx.doc.querySelectorAll('.trade-form').length === 0 &&
            runCtx.st.results.length === 1 &&
            runCtx.st.results[0].status === 'UNKNOWN';

          console.log(JSON.stringify({
            ok: true,
            directSearchNoGroupClicksOk: directSearchNoGroupClicksOk,
            labeledSearchInputFoundOk: labeledSearchInputFoundOk,
            missingSearchDiagnosticOk: missingSearchDiagnosticOk,
            verifiedAliasOk: verifiedAliasOk,
            buyDropdownSwitchedOnceOk: buyDropdownSwitchedOnceOk,
            sellSingleOk: sellSingleOk,
            blankPendingPriceRejectedOk: blankPendingPriceRejectedOk,
            rejectionDoneAndTicketClosedOk: rejectionDoneAndTicketClosedOk,
            serial3TradesConfirmedOk: serial3TradesConfirmedOk,
            unknownNoDuplicateSubmitOk: unknownNoDuplicateSubmitOk
          }));
        })().catch(function(err) {
          console.log(JSON.stringify({ ok: false, error: err && err.stack ? err.stack : String(err) }));
        });
        """.trimIndent()

        val results = runNodeHarness(script)
        assertTrue("Part 13 MT5 suite failed: ${results.optString("error")}", results.optBoolean("ok", false))
        assertTrue(results.getBoolean("directSearchNoGroupClicksOk"))
        assertTrue(results.getBoolean("labeledSearchInputFoundOk"))
        assertTrue(results.getBoolean("missingSearchDiagnosticOk"))
        assertTrue(results.getBoolean("verifiedAliasOk"))
        assertTrue(results.getBoolean("buyDropdownSwitchedOnceOk"))
        assertTrue(results.getBoolean("sellSingleOk"))
        assertTrue(results.getBoolean("blankPendingPriceRejectedOk"))
        assertTrue(results.getBoolean("rejectionDoneAndTicketClosedOk"))
        assertTrue(results.getBoolean("serial3TradesConfirmedOk"))
        assertTrue(results.getBoolean("unknownNoDuplicateSubmitOk"))
    }

    @Test
    fun `Part 13 MT4 hidden Market Watch open via View menu and Ctrl+M, panel bbox contextmenu Show All priority, row New Order without dblclick, and multi-order`() {
        val discHiddenViewJs = WebTerminalScripts.buildSymbolDiscoveryScript("MT4", "EURUSD", "Exness", "Exness-Trial10", "{}", 10)
        val discHiddenCtrlMJs = WebTerminalScripts.buildSymbolDiscoveryScript("MT4", "XAUUSD.m", "Exness", "Exness-Trial10", "{}", 10)
        val tradeMt4Multi2Js = WebTerminalScripts.buildTradeScript("XAUUSD.m", "BUY", "0.04", "2620", "2690", "BTECH-MT4", 2, "MT4", "Exness", "Exness-Trial10", "{}", "mt4-s1", 25, "", 201L)

        val script = """
        (async function() {
          global.location = { href: 'https://metatraderweb.app/trade', pathname: '/trade' };

          function setupMt4Scenario(opts) {
            opts = opts || {};
            const doc = createDomDocument();
            const st = {
              mwOpenedBy: '',
              panelContextCoords: null,
              showAllClicks: 0,
              symbolsDlgClicks: 0,
              rowRightClicks: 0,
              newOrderClicks: 0,
              dblClicks: 0,
              openForms: 0,
              buyClicks: 0,
              filledLot: '',
              filledSl: '',
              filledTp: '',
              filledComment: '',
              okDismissed: 0,
              logs: [],
              results: []
            };
            const hdr = doc.createElement('div');
            hdr.textContent = 'Balance: 3,207.68 ZAR Equity: 2,989.94 ZAR Positions';
            doc.body.appendChild(hdr);

            const mw = doc.createElement('div');
            mw.className = 'market-watch-panel';
            // Explicit realistic desktop panel bounding box
            mw.getBoundingClientRect = function() {
              if (!this.isConnected || this.style.display === 'none') {
                return { left: 0, top: 0, width: 0, height: 0, right: 0, bottom: 0 };
              }
              return { left: 12, top: 64, width: 280, height: 420, right: 292, bottom: 484 };
            };
            if (opts.hiddenInitially) mw.style.display = 'none';

            const mwTitle = doc.createElement('div');
            mwTitle.textContent = 'Market Watch';
            mw.appendChild(mwTitle);
            const tbl = doc.createElement('table');
            const tbody = doc.createElement('tbody');
            tbl.appendChild(tbody);
            mw.appendChild(tbl);

            let currentSymbols = (opts.initialSymbols || ['EURUSD', 'AUDCAD']).slice();
            function renderRows() {
              tbody.textContent = '';
              currentSymbols.forEach(function(sym) {
                const tr = doc.createElement('tr');
                const td = doc.createElement('td');
                td.textContent = sym;
                tr.appendChild(td);
                tr.addEventListener('dblclick', function() { st.dblClicks++; });
                tr.addEventListener('contextmenu', function(ev) {
                  if (ev && ev.stopPropagation) ev.stopPropagation();
                  st.rowRightClicks++;
                  const oldM = doc.querySelectorAll('.mt4-ctx-menu');
                  oldM.forEach(function(n) { if (n.parentElement) n.parentElement.removeChild(n); });
                  const menu = doc.createElement('div');
                  menu.className = 'mt4-ctx-menu';
                  ['Chart Window', 'New Order', 'Specification'].forEach(function(lbl) {
                    const mi = doc.createElement('div');
                    mi.setAttribute('role', 'menuitem');
                    mi.textContent = lbl;
                    if (lbl === 'New Order') {
                      mi.addEventListener('click', function() {
                        st.newOrderClicks++;
                        if (menu.parentElement) menu.parentElement.removeChild(menu);
                        openTicket(sym);
                      });
                    }
                    menu.appendChild(mi);
                  });
                  doc.body.appendChild(menu);
                });
                tbody.appendChild(tr);
              });
            }
            renderRows();

            mw.addEventListener('contextmenu', function(ev) {
              st.panelContextCoords = { x: ev.clientX, y: ev.clientY };
              const oldM = doc.querySelectorAll('.mt4-ctx-menu');
              oldM.forEach(function(n) { if (n.parentElement) n.parentElement.removeChild(n); });
              const menu = doc.createElement('div');
              menu.className = 'mt4-ctx-menu';
              // Show All intentionally at index 2 (not 7th) alongside Symbols... to verify Show All priority by label
              ['Chart Window', 'Symbols...', 'Show All', 'Hide All'].forEach(function(lbl) {
                const mi = doc.createElement('div');
                mi.setAttribute('role', 'menuitem');
                mi.textContent = lbl;
                if (lbl === 'Symbols...') {
                  mi.addEventListener('click', function() { st.symbolsDlgClicks++; });
                } else if (lbl === 'Show All') {
                  mi.addEventListener('click', function() {
                    st.showAllClicks++;
                    if (menu.parentElement) menu.parentElement.removeChild(menu);
                    currentSymbols = ['EURUSD', 'AUDCAD', 'XAUUSD.m', 'GBPUSD'];
                    renderRows();
                  });
                }
                menu.appendChild(mi);
              });
              doc.body.appendChild(menu);
            });
            doc.body.appendChild(mw);

            if (opts.hiddenVia === 'view_menu') {
              const viewBtn = doc.createElement('button');
              viewBtn.textContent = 'View';
              viewBtn.addEventListener('click', function() {
                const vm = doc.createElement('div');
                vm.setAttribute('role', 'menu');
                const mwItem = doc.createElement('div');
                mwItem.setAttribute('role', 'menuitem');
                mwItem.textContent = 'Market Watch';
                mwItem.addEventListener('click', function() {
                  st.mwOpenedBy = 'view_menu';
                  mw.style.display = 'block';
                  if (vm.parentElement) vm.parentElement.removeChild(vm);
                });
                vm.appendChild(mwItem);
                doc.body.appendChild(vm);
              });
              doc.body.appendChild(viewBtn);
            } else if (opts.hiddenVia === 'ctrl_m') {
              doc.body.addEventListener('keydown', function(ev) {
                if (ev && ev.ctrlKey && String(ev.key || '').toLowerCase() === 'm') {
                  st.mwOpenedBy = 'ctrl_m';
                  mw.style.display = 'block';
                }
              });
            }

            let activeTicket = null;
            function openTicket(sym) {
              if (activeTicket && activeTicket.parentElement) activeTicket.parentElement.removeChild(activeTicket);
              activeTicket = doc.createElement('div');
              activeTicket.setAttribute('role', 'dialog');
              activeTicket.className = 'mt4-order-ticket';
              st.openForms++;

              const title = doc.createElement('h3');
              title.setAttribute('data-symbol', sym);
              title.textContent = 'Order — ' + sym;
              activeTicket.appendChild(title);

              const vIn = doc.createElement('input');
              vIn.name = 'volume';
              vIn.placeholder = 'Volume';
              vIn.addEventListener('input', function() { st.filledLot = vIn.value; });
              const slIn = doc.createElement('input');
              slIn.name = 'sl';
              slIn.placeholder = 'Stop Loss';
              slIn.addEventListener('input', function() { st.filledSl = slIn.value; });
              const tpIn = doc.createElement('input');
              tpIn.name = 'tp';
              tpIn.placeholder = 'Take Profit';
              tpIn.addEventListener('input', function() { st.filledTp = tpIn.value; });
              const cmIn = doc.createElement('input');
              cmIn.name = 'comment';
              cmIn.placeholder = 'Comment';
              cmIn.addEventListener('input', function() { st.filledComment = cmIn.value; });

              const buyBtn = doc.createElement('button');
              buyBtn.textContent = 'Buy by Market';
              buyBtn.addEventListener('click', function() {
                st.buyClicks++;
                const pos = doc.createElement('tr');
                pos.textContent = '#40900' + st.buyClicks + ' buy ' + st.filledLot + ' ' + sym + ' at 2650.00';
                doc.body.appendChild(pos);
                const dlg = doc.createElement('div');
                dlg.setAttribute('role', 'dialog');
                dlg.className = 'trade-result';
                const status = doc.createElement('div');
                status.className = 'status';
                status.textContent = 'Done: Order #40900' + st.buyClicks + ' opened';
                const ok = doc.createElement('button');
                ok.textContent = 'OK';
                ok.addEventListener('click', function() {
                  st.okDismissed++;
                  if (dlg.parentElement) dlg.parentElement.removeChild(dlg);
                  if (activeTicket && activeTicket.parentElement) activeTicket.parentElement.removeChild(activeTicket);
                });
                dlg.appendChild(status);
                dlg.appendChild(ok);
                doc.body.appendChild(dlg);
              });

              activeTicket.appendChild(vIn);
              activeTicket.appendChild(slIn);
              activeTicket.appendChild(tpIn);
              activeTicket.appendChild(cmIn);
              activeTicket.appendChild(buyBtn);
              doc.body.appendChild(activeTicket);
            }

            installDoc(doc);
            window.BtechBridge = {
              onTradeRequiresAuth: function() {},
              onTradePhase: function() {},
              onTradeLog: function(m, err, done) { st.logs.push({ m: m, err: err, done: done }); },
              onSymbolDiscovered: function() {},
              onTradeResult: function(j) { st.results.push(JSON.parse(j)); }
            };
            return { doc: doc, st: st };
          }

          // 1. Hidden Market Watch opened via View -> Market Watch (existing EURUSD skips Show All)
          let ctx = setupMt4Scenario({ hiddenInitially: true, hiddenVia: 'view_menu' });
          eval(${JSONObject.quote(discHiddenViewJs)});
          const rView = await window.__btechLastDiscoveryPromise;
          const hiddenViewOpenedAndSkippedShowAllOk =
            rView.success &&
            rView.actualSymbol === 'EURUSD' &&
            ctx.st.mwOpenedBy === 'view_menu' &&
            ctx.st.showAllClicks === 0;

          // 2. Hidden Market Watch opened via Ctrl+M + missing symbol right-clicks inside panel bbox & prioritises Show All
          ctx = setupMt4Scenario({ hiddenInitially: true, hiddenVia: 'ctrl_m' });
          eval(${JSONObject.quote(discHiddenCtrlMJs)});
          const rCtrlM = await window.__btechLastDiscoveryPromise;
          const coords = ctx.st.panelContextCoords;
          const hiddenCtrlMBboxShowAllOk =
            rCtrlM.success &&
            rCtrlM.actualSymbol === 'XAUUSD.m' &&
            ctx.st.mwOpenedBy === 'ctrl_m' &&
            coords && coords.x >= 12 && coords.x <= 292 && coords.y >= 64 && coords.y <= 484 &&
            ctx.st.showAllClicks === 1 &&
            ctx.st.symbolsDlgClicks === 0;

          // 3. Full MT4 2-trade sequential execution: Show All -> re-inspect -> row right-click New Order (zero dblclicks) -> OK cleanup -> Trade 2
          ctx = setupMt4Scenario({ hiddenInitially: false });
          eval(${JSONObject.quote(tradeMt4Multi2Js)});
          for (let w = 0; w < 220 && !ctx.st.logs.some(function(l) { return l.done; }); w++) { await sleepMs(40); }
          const mt4Multi2SerialCleanOk =
            ctx.st.dblClicks === 0 &&
            ctx.st.showAllClicks === 1 &&
            ctx.st.rowRightClicks === 2 &&
            ctx.st.newOrderClicks === 2 &&
            ctx.st.openForms === 2 &&
            ctx.st.buyClicks === 2 &&
            ctx.st.okDismissed === 2 &&
            ctx.st.filledLot === '0.04' &&
            ctx.st.filledSl === '2620' &&
            ctx.st.filledTp === '2690' &&
            ctx.st.filledComment === 'BTECH-MT4' &&
            ctx.doc.querySelectorAll('.trade-result, .mt4-order-ticket').length === 0 &&
            ctx.st.results.length === 1 &&
            ctx.st.results[0].confirmedCount === 2;

          console.log(JSON.stringify({
            ok: true,
            hiddenViewOpenedAndSkippedShowAllOk: hiddenViewOpenedAndSkippedShowAllOk,
            hiddenCtrlMBboxShowAllOk: hiddenCtrlMBboxShowAllOk,
            mt4Multi2SerialCleanOk: mt4Multi2SerialCleanOk
          }));
        })().catch(function(err) {
          console.log(JSON.stringify({ ok: false, error: err && err.stack ? err.stack : String(err) }));
        });
        """.trimIndent()

        val results = runNodeHarness(script)
        assertTrue("Part 13 MT4 suite failed: ${results.optString("error")}", results.optBoolean("ok", false))
        assertTrue(results.getBoolean("hiddenViewOpenedAndSkippedShowAllOk"))
        assertTrue(results.getBoolean("hiddenCtrlMBboxShowAllOk"))
        assertTrue(results.getBoolean("mt4Multi2SerialCleanOk"))
    }

    @Test
    fun `Part 13 Strict blocking dialog cleanup retries refused dismissals, awaits async removal, aborts blocked new signals, and never closes open positions`() {
        val trade2SequentialJs = WebTerminalScripts.buildTradeScript("XAUUSD", "BUY", "0.01", "", "", "BTECH-CLN", 2, "MT5", "JustMarkets", "JustMarkets-Live", "{}", "cln-1", 25, "", 301L)
        val tradeNewSignalBlockedJs = WebTerminalScripts.buildTradeScript("EURUSD", "BUY", "0.01", "", "", "BTECH-BLK", 1, "MT5", "JustMarkets", "JustMarkets-Live", "{}", "cln-2", 25, "", 302L)
        val tradeFreshAfterErrorJs = WebTerminalScripts.buildTradeScript("XAUUSD", "BUY", "0.01", "", "", "BTECH-REC", 1, "MT5", "JustMarkets", "JustMarkets-Live", "{}", "cln-3", 25, "", 303L)

        val script = """
        (async function() {
          global.location = { href: 'https://live.justmarkets.com/terminal', pathname: '/terminal' };

          function createCleanupDom(cfg) {
            cfg = cfg || {};
            const d = createDomDocument();
            const st = {
              searchInputsTyped: 0,
              newOrderClicks: 0,
              buyClicks: 0,
              buySubmittedWhileModalOpen: false,
              openPositionActionClicks: 0,
              dismissAttemptsOnModal: 0,
              staleErrDismissed: false,
              logs: [],
              results: []
            };
            const hdr = d.createElement('div');
            hdr.textContent = 'Balance: 4,000.00 USD Equity: 4,000.00 USD Quotes Chart Trade';
            d.body.appendChild(hdr);

            // Real open position & history/pending rows with Close Position / Close / Delete / Modify controls -> NEVER clicked!
            const posTbl = d.createElement('table');
            posTbl.className = 'positions-table';
            const posRow = d.createElement('tr');
            posRow.setAttribute('role', 'row');
            const posText = d.createElement('td');
            posText.textContent = '#112233 buy 0.10 EURUSD at 1.0950';
            posRow.appendChild(posText);
            ['Close Position', 'Close', 'Delete', 'Modify'].forEach(function(lbl) {
              const tdCell = d.createElement('td');
              const btn = d.createElement('button');
              btn.textContent = lbl;
              btn.addEventListener('click', function() { st.openPositionActionClicks++; });
              tdCell.appendChild(btn);
              posRow.appendChild(tdCell);
            });
            posTbl.appendChild(posRow);
            d.body.appendChild(posTbl);

            if (cfg.staleErrorPopup) {
              const staleErr = d.createElement('div');
              staleErr.setAttribute('role', 'alertdialog');
              staleErr.className = 'trade-result';
              const txt = d.createElement('div');
              txt.className = 'error';
              txt.textContent = 'Previous Off quotes';
              const okBtn = d.createElement('button');
              okBtn.textContent = 'OK';
              okBtn.addEventListener('click', function() {
                st.staleErrDismissed = true;
                if (staleErr.parentElement) staleErr.parentElement.removeChild(staleErr);
              });
              staleErr.appendChild(txt);
              staleErr.appendChild(okBtn);
              d.body.appendChild(staleErr);
            }

            if (cfg.unclosableModal) {
              const stuckDlg = d.createElement('div');
              stuckDlg.setAttribute('role', 'dialog');
              stuckDlg.className = 'trade-result';
              const txt = d.createElement('div');
              txt.textContent = 'Previous fatal modal';
              const okBtn = d.createElement('button');
              okBtn.textContent = 'OK';
              // Deliberately does NOT remove stuckDlg on click -> simulates unclosable modal
              okBtn.addEventListener('click', function() { st.dismissAttemptsOnModal++; });
              stuckDlg.appendChild(txt);
              stuckDlg.appendChild(okBtn);
              d.body.appendChild(stuckDlg);
            }

            const sIn = d.createElement('input');
            sIn.setAttribute('placeholder', 'Search symbol');
            sIn.addEventListener('input', function() { if (sIn.value) st.searchInputsTyped++; });
            d.body.appendChild(sIn);

            ['XAUUSD', 'EURUSD'].forEach(function(sym) {
              const r = d.createElement('div');
              r.setAttribute('role', 'row');
              r.setAttribute('data-symbol', sym);
              r.textContent = sym;
              d.body.appendChild(r);
            });

            const newOrderBtn = d.createElement('button');
            newOrderBtn.setAttribute('title', 'New Order');
            newOrderBtn.textContent = 'New Order';
            let activeForm = null;
            let activeModal = null;

            newOrderBtn.addEventListener('click', function() {
              st.newOrderClicks++;
              if (activeForm && activeForm.parentElement) activeForm.parentElement.removeChild(activeForm);
              activeForm = d.createElement('div');
              activeForm.className = 'trade-form';
              activeForm.setAttribute('role', 'dialog');
              const symH = d.createElement('div');
              symH.setAttribute('data-symbol', 'XAUUSD');
              symH.textContent = 'XAUUSD';
              activeForm.appendChild(symH);
              const vw = d.createElement('div');
              vw.className = 'trade-input';
              const vl = d.createElement('span');
              vl.className = 'label';
              vl.textContent = 'Volume';
              const vi = d.createElement('input');
              vw.appendChild(vl);
              vw.appendChild(vi);
              activeForm.appendChild(vw);

              const buyB = d.createElement('button');
              buyB.textContent = 'Buy by Market';
              buyB.addEventListener('click', function() {
                if (activeModal && activeModal.isConnected) {
                  st.buySubmittedWhileModalOpen = true;
                }
                st.buyClicks++;
                const pos = d.createElement('tr');
                pos.textContent = '#8800' + st.buyClicks + ' buy ' + vi.value + ' XAUUSD at 2650.00';
                d.body.appendChild(pos);

                // Modal refuses 1st dismissal click, accepts 2nd click, and removes itself after 180ms async delay
                const m = d.createElement('div');
                m.setAttribute('role', 'dialog');
                m.className = 'trade-result';
                activeModal = m;
                let clicksOnThisModal = 0;
                const sDiv = d.createElement('div');
                sDiv.className = 'status';
                sDiv.textContent = 'Done: Order #8800' + st.buyClicks + ' confirmed';
                const ok = d.createElement('button');
                ok.textContent = 'OK';
                ok.addEventListener('click', function() {
                  clicksOnThisModal++;
                  st.dismissAttemptsOnModal++;
                  if (clicksOnThisModal >= 2) {
                    setTimeout(function() {
                      if (m.parentElement) m.parentElement.removeChild(m);
                      if (activeForm && activeForm.parentElement) activeForm.parentElement.removeChild(activeForm);
                    }, 180);
                  }
                });
                m.appendChild(sDiv);
                m.appendChild(ok);
                d.body.appendChild(m);
              });
              activeForm.appendChild(buyB);
              d.body.appendChild(activeForm);
            });
            d.body.appendChild(newOrderBtn);

            installDoc(d);
            window.BtechBridge = {
              onTradeRequiresAuth: function() {},
              onTradePhase: function() {},
              onTradeLog: function(m, err, done) { st.logs.push({ m: m, err: err, done: done }); },
              onSymbolDiscovered: function() {},
              onTradeResult: function(j) { st.results.push(JSON.parse(j)); }
            };
            return { doc: d, st: st };
          }

          // 1. Stale error cleared + Trade 1 modal refuses 1st click, delayed async 180ms disappearance awaited before Trade 2, open positions untouched
          const ctx1 = createCleanupDom({ staleErrorPopup: true });
          eval(${JSONObject.quote(trade2SequentialJs)});
          for (let w = 0; w < 260 && !ctx1.st.logs.some(function(l) { return l.done; }); w++) { await sleepMs(40); }
          const retryDelayAndOpenPosUntouchedOk =
            ctx1.st.staleErrDismissed === true &&
            ctx1.st.buyClicks === 2 &&
            !ctx1.st.buySubmittedWhileModalOpen &&
            ctx1.st.dismissAttemptsOnModal >= 4 &&
            ctx1.st.openPositionActionClicks === 0 &&
            ctx1.doc.querySelectorAll('.trade-result, .trade-form').length === 0 &&
            ctx1.st.results.length === 1 &&
            ctx1.st.results[0].confirmedCount === 2;

          // 2. Unclosable previous blocking modal MUST abort new incoming signal BEFORE symbol search or New Order!
          const ctx2 = createCleanupDom({ unclosableModal: true });
          eval(${JSONObject.quote(tradeNewSignalBlockedJs)});
          for (let w = 0; w < 120 && !ctx2.st.logs.some(function(l) { return l.done; }); w++) { await sleepMs(40); }
          const unclosableModalAbortsNewSignalOk =
            ctx2.st.dismissAttemptsOnModal >= 1 &&
            ctx2.st.searchInputsTyped === 0 &&
            ctx2.st.newOrderClicks === 0 &&
            ctx2.st.buyClicks === 0 &&
            ctx2.st.openPositionActionClicks === 0 &&
            ctx2.st.results.length === 1 &&
            ctx2.st.results[0].status === 'REJECTED' &&
            ctx2.st.results[0].rawMessage.indexOf('Previous terminal dialog could not be cleared') >= 0;

          // 3. Previous failed trade that WAS cleanly dismissed does NOT leave subsequent fresh signal blocked
          const ctx3 = createCleanupDom({ staleErrorPopup: true });
          eval(${JSONObject.quote(tradeFreshAfterErrorJs)});
          for (let w = 0; w < 180 && !ctx3.st.logs.some(function(l) { return l.done; }); w++) { await sleepMs(40); }
          const recoveredTerminalAcceptsFreshSignalOk =
            ctx3.st.staleErrDismissed === true &&
            ctx3.st.buyClicks === 1 &&
            ctx3.st.openPositionActionClicks === 0 &&
            ctx3.st.results.length === 1 &&
            ctx3.st.results[0].status === 'ACCEPTED';

          console.log(JSON.stringify({
            ok: true,
            retryDelayAndOpenPosUntouchedOk: retryDelayAndOpenPosUntouchedOk,
            unclosableModalAbortsNewSignalOk: unclosableModalAbortsNewSignalOk,
            recoveredTerminalAcceptsFreshSignalOk: recoveredTerminalAcceptsFreshSignalOk
          }));
        })().catch(function(err) {
          console.log(JSON.stringify({ ok: false, error: err && err.stack ? err.stack : String(err) }));
        });
        """.trimIndent()

        val results = runNodeHarness(script)
        assertTrue("Part 13 Strict Cleanup suite failed: ${results.optString("error")}", results.optBoolean("ok", false))
        assertTrue(results.getBoolean("retryDelayAndOpenPosUntouchedOk"))
        assertTrue(results.getBoolean("unclosableModalAbortsNewSignalOk"))
        assertTrue(results.getBoolean("recoveredTerminalAcceptsFreshSignalOk"))
    }

    private fun runNodeHarness(testScriptJs: String): JSONObject {
        val nodePath = listOf("/usr/local/bin/node", "/usr/bin/node")
            .firstOrNull { File(it).canExecute() }
            ?: "node"

        val tmpFile = File.createTempFile("btech_robotrader_test_", ".js")
        tmpFile.writeText(NODE_DOM_SHIM + "\n" + testScriptJs)
        try {
            val proc = ProcessBuilder(nodePath, tmpFile.absolutePath)
                .redirectErrorStream(false)
                .start()
            val stdout = proc.inputStream.bufferedReader().readText()
            val stderr = proc.errorStream.bufferedReader().readText()
            val finished = proc.waitFor(45, TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                throw AssertionError("Node JS DOM harness timed out. stderr=$stderr stdout=$stdout")
            }
            if (proc.exitValue() != 0) {
                throw AssertionError("Node JS DOM harness exited with ${proc.exitValue()}.\nstderr:\n$stderr\nstdout:\n$stdout")
            }
            val lastLine = stdout.trim().lines().lastOrNull { it.trim().startsWith("{") }
                ?: throw AssertionError("No JSON result line from Node harness.\nstdout:\n$stdout\nstderr:\n$stderr")
            return JSONObject(lastLine)
        } finally {
            tmpFile.delete()
        }
    }

    private fun buildMt4SuiteJs(
        discoveryScriptFn: (sym: String, aliasesJson: String) -> String,
        tradeScriptFn: (sym: String, dir: String, count: Int) -> String,
        cancelScript: String,
    ): String {
        val sExact = JSONObject.quote(discoveryScriptFn("EURUSD", "{}"))
        val sCase = JSONObject.quote(discoveryScriptFn("xauusd.m", "{}"))
        val sDotM = JSONObject.quote(discoveryScriptFn("XAUUSD.m", "{}"))
        val sDotEcn = JSONObject.quote(discoveryScriptFn("GBPUSD.ecn", "{}"))
        val sUnmatchedXau = JSONObject.quote(discoveryScriptFn("XAUUSD", "{}"))
        val sUnmatchedGbp = JSONObject.quote(discoveryScriptFn("GBPUSD", "{}"))
        val sMissing = JSONObject.quote(discoveryScriptFn("NONEXIST99", "{}"))
        val sSimilar = JSONObject.quote(discoveryScriptFn("US30", "{}"))
        val sExecTrade = JSONObject.quote(tradeScriptFn("EURUSD", "BUY", 1))
        val sAuthTrade = JSONObject.quote(tradeScriptFn("EURUSD", "BUY", 1))
        val sCancelTarget = JSONObject.quote(discoveryScriptFn("XAUUSD.m", "{}"))
        val sCancelExec = JSONObject.quote(cancelScript)

        return """
        (async function() {
          function buildMt4Dom(options) {
            options = options || {};
            const doc = createDomDocument();
            const stats = {
              mt5SearchTouched: false,
              showAllClicked: false,
              selectedSymbol: '',
              newOrderClickedSymbol: '',
              dblClicks: 0,
              buyClicks: 0,
              sellClicks: 0,
              authRequiredCalls: 0,
              logs: [],
              phases: [],
              discoveredEvents: []
            };

            if (options.loggedOut) {
              const loginForm = doc.createElement('div');
              const pwd = doc.createElement('input');
              pwd.type = 'password';
              loginForm.appendChild(pwd);
              doc.body.appendChild(loginForm);
            } else {
              const header = doc.createElement('div');
              header.textContent = 'Balance: 5,000.00 USD Equity: 5,000.00 USD New Order Positions';
              doc.body.appendChild(header);
            }

            // Trap MT5 search field to guarantee MT4 discovery NEVER falls back to searching here
            const mt5Trap = doc.createElement('input');
            mt5Trap.setAttribute('placeholder', 'Search symbol');
            mt5Trap.addEventListener('input', function() { stats.mt5SearchTouched = true; });
            doc.body.appendChild(mt5Trap);

            const mw = doc.createElement('div');
            mw.className = 'market-watch-panel';
            const mwTitle = doc.createElement('div');
            mwTitle.textContent = 'Market Watch';
            mw.appendChild(mwTitle);

            const initialSymbols = options.initialSymbols || ['EURUSD', 'USDJPY', 'US500', 'EURJPY'];
            const allSymbols = options.allSymbols || [
              'EURUSD', 'USDJPY', 'US500', 'EURJPY', 'XAUUSD.m', 'GBPUSD.ecn',
              'AUDUSD', 'NZDUSD', 'USDCAD', 'USDCHF', 'BTCUSD', 'ETHUSD', 'XAGUSD', 'GER40'
            ];

            function renderRows(list) {
              const existing = mw.querySelectorAll('.mw-row');
              existing.forEach(function(n) { mw.removeChild(n); });
              list.forEach(function(sym) {
                const row = doc.createElement('tr');
                row.className = 'mw-row';
                row.setAttribute('data-symbol', sym);
                const cell = doc.createElement('td');
                cell.textContent = sym + ' 1.1050 1.1052';
                row.appendChild(cell);
                row.addEventListener('click', function() {
                  stats.selectedSymbol = sym;
                });
                row.addEventListener('dblclick', function() {
                  stats.dblClicks++;
                });
                row.addEventListener('contextmenu', function(ev) {
                  if (ev && ev.stopPropagation) ev.stopPropagation();
                  const oldMenus = doc.querySelectorAll('.mt4-ctx-menu');
                  oldMenus.forEach(function(m) { if (m.parentElement) m.parentElement.removeChild(m); });
                  const rowMenu = doc.createElement('div');
                  rowMenu.className = 'mt4-ctx-menu';
                  ['Chart Window', 'New Order', 'Depth of Market'].forEach(function(lbl) {
                    const mi = doc.createElement('div');
                    mi.setAttribute('role', 'menuitem');
                    mi.textContent = lbl;
                    if (lbl === 'New Order') {
                      mi.addEventListener('click', function() {
                        stats.newOrderClickedSymbol = sym;
                        if (rowMenu.parentElement) rowMenu.parentElement.removeChild(rowMenu);
                        openOrderTicket(sym);
                      });
                    }
                    rowMenu.appendChild(mi);
                  });
                  doc.body.appendChild(rowMenu);
                });
                mw.appendChild(row);
              });
            }
            renderRows(initialSymbols);

            // Panel Context menu opens on panel right-click (Show All is placed at index 1, NOT index 6!)
            mw.addEventListener('contextmenu', function() {
              const oldMenus = doc.querySelectorAll('.mt4-ctx-menu');
              oldMenus.forEach(function(m) { if (m.parentElement) m.parentElement.removeChild(m); });
              const menu = doc.createElement('div');
              menu.className = 'mt4-ctx-menu';
              const items = ['New Order', 'Show All', 'Hide All', 'Chart Window', 'Tick Chart'];
              items.forEach(function(lbl) {
                const mi = doc.createElement('div');
                mi.setAttribute('role', 'menuitem');
                mi.textContent = lbl;
                if (lbl === 'Show All') {
                  mi.addEventListener('click', function() {
                    stats.showAllClicked = true;
                    if (menu.parentElement) menu.parentElement.removeChild(menu);
                    setTimeout(function() { renderRows(allSymbols); }, 20);
                  });
                }
                menu.appendChild(mi);
              });
              doc.body.appendChild(menu);
            });
            doc.body.appendChild(mw);

            let ticketEl = null;
            function openOrderTicket(sym) {
              if (ticketEl && ticketEl.parentElement) ticketEl.parentElement.removeChild(ticketEl);
              ticketEl = doc.createElement('div');
              ticketEl.setAttribute('role', 'dialog');
              ticketEl.className = 'mt4-order-ticket';

              const symTitle = doc.createElement('h2');
              symTitle.setAttribute('data-symbol', sym);
              symTitle.textContent = sym;
              ticketEl.appendChild(symTitle);

              const volInput = doc.createElement('input');
              volInput.name = 'volume';
              volInput.placeholder = 'Volume';
              ticketEl.appendChild(volInput);

              const slInput = doc.createElement('input');
              slInput.name = 'sl';
              slInput.placeholder = 'Stop Loss';
              ticketEl.appendChild(slInput);

              const tpInput = doc.createElement('input');
              tpInput.name = 'tp';
              tpInput.placeholder = 'Take Profit';
              ticketEl.appendChild(tpInput);

              const cmtInput = doc.createElement('input');
              cmtInput.name = 'comment';
              cmtInput.placeholder = 'Comment';
              ticketEl.appendChild(cmtInput);

              const btnRow = doc.createElement('div');
              const sellBtn = doc.createElement('button');
              sellBtn.textContent = 'Sell by Market';
              sellBtn.addEventListener('click', function() { stats.sellClicks++; });
              const buyBtn = doc.createElement('button');
              buyBtn.textContent = 'Buy by Market';
              buyBtn.addEventListener('click', function() {
                stats.buyClicks++;
                const posRow = doc.createElement('tr');
                posRow.textContent = '#800100' + stats.buyClicks + ' buy ' + (volInput.value || '0.02') + ' ' + sym + ' at 1.1052';
                doc.body.appendChild(posRow);
              });
              btnRow.appendChild(sellBtn);
              btnRow.appendChild(buyBtn);
              ticketEl.appendChild(btnRow);

              doc.body.appendChild(ticketEl);
            }

            installDoc(doc);
            window.BtechBridge = {
              onTradeRequiresAuth: function() { stats.authRequiredCalls++; },
              onTradePhase: function(k, d) { stats.phases.push({ k: k, d: d }); },
              onTradeLog: function(m, err, done) { stats.logs.push({ m: m, err: err, done: done }); },
              onSymbolDiscovered: function(json) { stats.discoveredEvents.push(JSON.parse(json)); }
            };
            return stats;
          }

          // 1. Exact visible match (selects without dblclick)
          let st = buildMt4Dom();
          eval($sExact);
          let res = await window.__btechLastDiscoveryPromise;
          const exactMatchOk = res.success && res.actualSymbol === 'EURUSD' && st.selectedSymbol === 'EURUSD' && !st.mt5SearchTouched && st.dblClicks === 0;

          // 2-6. Hidden symbol -> Show All by text (not hardcoded 7th item) -> case-insensitive .m -> catalog > 8
          st = buildMt4Dom();
          eval($sCase);
          res = await window.__btechLastDiscoveryPromise;
          const caseInsensitiveOk = res.success && res.actualSymbol === 'XAUUSD.m';
          const showAllByTextNotIndex7Ok = st.showAllClicked === true;
          const catalogExceeds8SymbolsOk = Array.isArray(res.discoveredSymbols) && res.discoveredSymbols.length >= 12;

          // 3. Exact suffix .m when requesting XAUUSD.m
          st = buildMt4Dom();
          eval($sDotM);
          res = await window.__btechLastDiscoveryPromise;
          const suffixDotMOk = res.success && res.actualSymbol === 'XAUUSD.m' && !st.mt5SearchTouched;

          // 4. Exact suffix .ecn when requesting GBPUSD.ecn
          st = buildMt4Dom();
          eval($sDotEcn);
          res = await window.__btechLastDiscoveryPromise;
          const suffixDotEcnOk = res.success && res.actualSymbol === 'GBPUSD.ecn';

          // 4b. Requesting XAUUSD or GBPUSD when Market Watch only has XAUUSD.m / GBPUSD.ecn MUST reject cleanly!
          st = buildMt4Dom();
          eval($sUnmatchedXau);
          const rUnmXau = await window.__btechLastDiscoveryPromise;
          st = buildMt4Dom();
          eval($sUnmatchedGbp);
          const rUnmGbp = await window.__btechLastDiscoveryPromise;
          const unmatchedSuffixRejectedOk =
            !rUnmXau.success &&
            String(rUnmXau.error).indexOf('error:Symbol XAUUSD not found') >= 0 &&
            !rUnmGbp.success &&
            String(rUnmGbp.error).indexOf('error:Symbol GBPUSD not found') >= 0;

          // 8. Missing symbol fails cleanly
          st = buildMt4Dom();
          eval($sMissing);
          res = await window.__btechLastDiscoveryPromise;
          const missingSymbolFailedCleanly = !res.success && String(res.error).indexOf('error:Symbol NONEXIST99 not found') >= 0;

          // 9. Similar-looking different symbol (US30 vs US500) must NOT match
          st = buildMt4Dom();
          eval($sSimilar);
          res = await window.__btechLastDiscoveryPromise;
          const similarDifferentRejected = !res.success && !st.mt5SearchTouched;
          const mt4DidNotTouchMt5Search = !st.mt5SearchTouched;

          // 11. Logged-out session triggers auth bridge instead of trading
          st = buildMt4Dom({ loggedOut: true });
          eval($sAuthTrade);
          await sleepMs(120);
          const authLostTriggersBridge = st.authRequiredCalls === 1 && st.buyClicks === 0;

          // 12. Stop / Cancel aborts mid-discovery
          st = buildMt4Dom();
          eval($sCancelTarget);
          setTimeout(function() { eval($sCancelExec); }, 20);
          res = await window.__btechLastDiscoveryPromise;
          const cancelAbortsMidDiscovery = res.cancelled === true && st.selectedSymbol === '';

          // 13. Full MT4 mock execution opens via row context menu 'New Order' (zero dblclick), verifies ticket + fields, clicks Buy by Market ONCE, confirms ticket, emits DONE
          st = buildMt4Dom();
          eval($sExecTrade);
          for (let w = 0; w < 80 && !st.logs.some(function(l) { return l.done; }); w++) {
            await sleepMs(50);
          }
          const zeroDoubleClicksUsedOk = st.dblClicks === 0;
          const mt4MockOrderExecutedOnceAndConfirmed =
            st.newOrderClickedSymbol === 'EURUSD' &&
            st.dblClicks === 0 &&
            st.buyClicks === 1 &&
            st.sellClicks === 0 &&
            st.phases.some(function(p) { return p.k === 'DONE'; }) &&
            st.logs.some(function(l) { return l.done && !l.err && l.m.indexOf('#8001001') >= 0; });

          console.log(JSON.stringify({
            ok: true,
            exactMatchOk: exactMatchOk,
            caseInsensitiveOk: caseInsensitiveOk,
            showAllByTextNotIndex7Ok: showAllByTextNotIndex7Ok,
            mt4DidNotTouchMt5Search: mt4DidNotTouchMt5Search,
            suffixDotMOk: suffixDotMOk,
            suffixDotEcnOk: suffixDotEcnOk,
            unmatchedSuffixRejectedOk: unmatchedSuffixRejectedOk,
            catalogExceeds8SymbolsOk: catalogExceeds8SymbolsOk,
            missingSymbolFailedCleanly: missingSymbolFailedCleanly,
            similarDifferentRejected: similarDifferentRejected,
            authLostTriggersBridge: authLostTriggersBridge,
            cancelAbortsMidDiscovery: cancelAbortsMidDiscovery,
            mt4MockOrderExecutedOnceAndConfirmed: mt4MockOrderExecutedOnceAndConfirmed,
            zeroDoubleClicksUsedOk: zeroDoubleClicksUsedOk,
            executedBuyClicks: st.buyClicks,
            executedSellClicks: st.sellClicks
          }));
        })().catch(function(err) {
          console.log(JSON.stringify({ ok: false, error: err && err.stack ? err.stack : String(err) }));
        });
        """.trimIndent()
    }

    private fun buildMt5SuiteJs(
        discoveryScriptFn: (sym: String, aliasesJson: String) -> String,
        tradeScriptFn: (sym: String, dir: String, count: Int, aliasesJson: String) -> String,
        cancelScript: String,
    ): String {
        val sExact = JSONObject.quote(discoveryScriptFn("EURUSD", "{}"))
        val sCase = JSONObject.quote(discoveryScriptFn("eurusd.ecn", "{}"))
        val sHidden = JSONObject.quote(discoveryScriptFn("CADJPY", "{}"))
        val sDotM = JSONObject.quote(discoveryScriptFn("XAUUSD.m", "{}"))
        val sDotEcn = JSONObject.quote(discoveryScriptFn("EURUSD.ecn", "{}"))
        val sUnmatchedXau = JSONObject.quote(discoveryScriptFn("XAUUSD", "{}"))
        val sUnmatchedEur = JSONObject.quote(discoveryScriptFn("EURUSD", "{}"))
        val sMissing = JSONObject.quote(discoveryScriptFn("NONEXIST99", "{}"))
        val sSimilar = JSONObject.quote(discoveryScriptFn("EURUSD", "{}"))
        val sGoldNoAlias = JSONObject.quote(discoveryScriptFn("GOLD", "{}"))
        val sGoldWithAlias = JSONObject.quote(discoveryScriptFn("GOLD", """{"GOLD":"XAUUSD"}"""))
        val sExecTrade = JSONObject.quote(tradeScriptFn("XAUUSD.m", "BUY", 1, "{}"))
        val sForeignMismatchTrade = JSONObject.quote(tradeScriptFn("XAUUSD.m", "BUY", 1, "{}"))
        val sAuthTrade = JSONObject.quote(tradeScriptFn("XAUUSD.m", "BUY", 1, "{}"))
        val sCancelLiveLoop = JSONObject.quote(tradeScriptFn("XAUUSD.m", "BUY", 2, "{}"))
        val sCancelExec = JSONObject.quote(cancelScript)

        return """
        (async function() {
          function buildMt5Dom(options) {
            options = options || {};
            const doc = createDomDocument();
            const stats = {
              clickedSymbol: '',
              buyClicks: 0,
              sellClicks: 0,
              authRequiredCalls: 0,
              logs: [],
              phases: [],
              discoveredEvents: [],
              filledLot: '',
              filledSl: '',
              filledTp: '',
              filledComment: ''
            };

            if (options.loggedOut) {
              const pwd = doc.createElement('input');
              pwd.type = 'password';
              doc.body.appendChild(pwd);
            } else {
              const ws = doc.createElement('div');
              ws.textContent = 'Balance: 10,000.00 USD Equity: 10,000.00 USD Positions';
              doc.body.appendChild(ws);
            }

            const catalog = options.catalog || [
              'EURUSD', 'EURUSD.ecn', 'XAUUSD.m', 'GBPUSD', 'USDJPY',
              'EURJPY', 'CADJPY', 'AUDUSD', 'NZDUSD', 'BTCUSD', 'ETHUSD', 'US500'
            ];
            let queryText = '';

            const searchBox = doc.createElement('input');
            searchBox.setAttribute('placeholder', 'Search symbol');
            const resultsPane = doc.createElement('div');
            resultsPane.className = 'mt5-search-results';

            function renderResults() {
              const prev = resultsPane.querySelectorAll('.symbol-row');
              prev.forEach(function(n) { resultsPane.removeChild(n); });
              const list = queryText
                ? catalog.filter(function(s) { return s.toLowerCase().indexOf(queryText.toLowerCase()) >= 0; })
                : (options.initialVisible || ['GBPUSD', 'USDJPY']);
              list.forEach(function(sym) {
                const row = doc.createElement('div');
                row.className = 'symbol-row';
                row.setAttribute('role', 'row');
                row.setAttribute('data-symbol', sym);
                row.textContent = sym;
                row.addEventListener('click', function() {
                  stats.clickedSymbol = sym;
                  renderOrderForm(options.forceForeignTicketSymbol || sym);
                });
                resultsPane.appendChild(row);
              });
            }

            searchBox.addEventListener('input', function() {
              queryText = searchBox.value || '';
              renderResults();
            });

            doc.body.appendChild(searchBox);
            doc.body.appendChild(resultsPane);
            renderResults();

            let orderPanel = null;
            function renderOrderForm(ticketSym) {
              if (orderPanel && orderPanel.parentElement) orderPanel.parentElement.removeChild(orderPanel);
              orderPanel = doc.createElement('div');
              orderPanel.className = 'trade-form';

              const hdr = doc.createElement('div');
              hdr.className = 'order-symbol-header';
              hdr.setAttribute('data-symbol', ticketSym);
              hdr.textContent = ticketSym;
              orderPanel.appendChild(hdr);

              const volWrap = doc.createElement('div');
              volWrap.className = 'trade-input';
              const volInput = doc.createElement('input');
              volInput.className = 'trade-input';
              volInput.name = 'volume';
              volInput.addEventListener('input', function() { stats.filledLot = volInput.value; });
              volWrap.appendChild(volInput);
              orderPanel.appendChild(volWrap);

              const slInput = doc.createElement('input');
              slInput.placeholder = 'SL';
              slInput.addEventListener('input', function() { stats.filledSl = slInput.value; });
              orderPanel.appendChild(slInput);

              const tpInput = doc.createElement('input');
              tpInput.placeholder = 'TP';
              tpInput.addEventListener('input', function() { stats.filledTp = tpInput.value; });
              orderPanel.appendChild(tpInput);

              const cmtInput = doc.createElement('input');
              cmtInput.placeholder = 'Comment';
              cmtInput.addEventListener('input', function() { stats.filledComment = cmtInput.value; });
              orderPanel.appendChild(cmtInput);

              const btnBar = doc.createElement('div');
              const sellBtn = doc.createElement('button');
              sellBtn.textContent = 'Sell';
              sellBtn.addEventListener('click', function() { stats.sellClicks++; });
              const buyBtn = doc.createElement('button');
              buyBtn.textContent = 'Buy';
              buyBtn.addEventListener('click', function() {
                stats.buyClicks++;
                stats.buyClickTimestamps = stats.buyClickTimestamps || [];
                stats.buyClickTimestamps.push(Date.now());
                const posRow = doc.createElement('tr');
                posRow.textContent = '#900100' + stats.buyClicks + ' buy ' + (volInput.value || '0.03') + ' ' + ticketSym + ' at 2655.10';
                doc.body.appendChild(posRow);
              });
              btnBar.appendChild(sellBtn);
              btnBar.appendChild(buyBtn);
              orderPanel.appendChild(btnBar);

              doc.body.appendChild(orderPanel);
            }

            installDoc(doc);
            window.BtechBridge = {
              onTradeRequiresAuth: function() { stats.authRequiredCalls++; },
              onTradePhase: function(k, d) { stats.phases.push({ k: k, d: d }); },
              onTradeLog: function(m, err, done) { stats.logs.push({ m: m, err: err, done: done }); },
              onSymbolDiscovered: function(json) { stats.discoveredEvents.push(JSON.parse(json)); }
            };
            return stats;
          }

          // 1. Exact search + click result (MUST NOT complete trade just from search click)
          let st = buildMt5Dom();
          eval($sExact);
          let res = await window.__btechLastDiscoveryPromise;
          const exactSearchAndClickOk = res.success && res.actualSymbol === 'EURUSD' && st.clickedSymbol === 'EURUSD';
          const searchClickAloneDidNotCompleteTrade = st.buyClicks === 0 && st.sellClicks === 0;

          // 2. Case-insensitive
          st = buildMt5Dom({ catalog: ['EURUSD.ecn', 'XAUUSD.m'] });
          eval($sCase);
          res = await window.__btechLastDiscoveryPromise;
          const caseInsensitiveSearchOk = res.success && res.actualSymbol === 'EURUSD.ecn';

          // 5. Available symbol hidden from default list until searched
          st = buildMt5Dom({ initialVisible: ['GBPUSD'] });
          eval($sHidden);
          res = await window.__btechLastDiscoveryPromise;
          const hiddenUntilSearchedOk = res.success && res.actualSymbol === 'CADJPY' && st.clickedSymbol === 'CADJPY';

          // 3. Exact suffix .m when requesting XAUUSD.m
          st = buildMt5Dom({ catalog: ['XAUUSD.m', 'EURJPY'] });
          eval($sDotM);
          res = await window.__btechLastDiscoveryPromise;
          const suffixDotMOk = res.success && res.actualSymbol === 'XAUUSD.m';

          // 4. Exact suffix .ecn when requesting EURUSD.ecn
          st = buildMt5Dom({ catalog: ['EURUSD.ecn', 'EURJPY'] });
          eval($sDotEcn);
          res = await window.__btechLastDiscoveryPromise;
          const suffixDotEcnOk = res.success && res.actualSymbol === 'EURUSD.ecn';

          // 4b. Unmatched suffix: requesting XAUUSD when broker only has XAUUSD.m or EURUSD when broker only has EURUSD.ecn MUST reject cleanly!
          st = buildMt5Dom({ catalog: ['XAUUSD.m', 'EURJPY'] });
          eval($sUnmatchedXau);
          const unmXau5 = await window.__btechLastDiscoveryPromise;
          st = buildMt5Dom({ catalog: ['EURUSD.ecn', 'EURJPY'] });
          eval($sUnmatchedEur);
          const unmEur5 = await window.__btechLastDiscoveryPromise;
          const unmatchedSuffixRejectedOk =
            !unmXau5.success &&
            String(unmXau5.error).indexOf('error:Symbol XAUUSD not found') >= 0 &&
            !unmEur5.success &&
            String(unmEur5.error).indexOf('error:Symbol EURUSD not found') >= 0;

          // 8. Missing symbol explicit error
          st = buildMt5Dom();
          eval($sMissing);
          res = await window.__btechLastDiscoveryPromise;
          const missingSymbolFailedCleanly = !res.success && String(res.error).indexOf('error:Symbol NONEXIST99 not found') >= 0;

          // 9. Similar different symbol rejected (EURUSD when only EURJPY offered)
          st = buildMt5Dom({ catalog: ['EURJPY', 'GBPJPY'], initialVisible: ['EURJPY'] });
          eval($sSimilar);
          res = await window.__btechLastDiscoveryPromise;
          const similarDifferentRejected = !res.success && st.clickedSymbol === '';

          // 9b. GOLD does NOT blindly match XAUUSD without verified alias; resolves ONLY with verified alias
          st = buildMt5Dom({ catalog: ['XAUUSD', 'XAGUSD'] });
          eval($sGoldNoAlias);
          res = await window.__btechLastDiscoveryPromise;
          const goldNotBlindlyXauUsd = !res.success;

          st = buildMt5Dom({ catalog: ['XAUUSD', 'XAGUSD'] });
          eval($sGoldWithAlias);
          res = await window.__btechLastDiscoveryPromise;
          const goldWithVerifiedAliasOk = res.success && res.actualSymbol === 'XAUUSD' && res.matchType === 'VERIFIED_ALIAS';

          // Pre-order ticket mismatch check: if ticket opened for EURJPY instead of XAUUSD.m, abort with 0 Buy clicks!
          st = buildMt5Dom({ catalog: ['XAUUSD.m'], forceForeignTicketSymbol: 'EURJPY' });
          eval($sForeignMismatchTrade);
          for (let w = 0; w < 60 && !st.logs.some(function(l) { return l.done; }); w++) {
            await sleepMs(50);
          }
          const orderFormMismatchRejected =
            st.buyClicks === 0 &&
            st.logs.some(function(l) { return l.err && l.done && l.m.indexOf('Order form instrument mismatch') >= 0; });

          // 11. Auth lost -> onTradeRequiresAuth
          st = buildMt5Dom({ loggedOut: true });
          eval($sAuthTrade);
          for (let w = 0; w < 30 && st.authRequiredCalls === 0; w++) {
            await sleepMs(25);
          }
          const authLostTriggersBridge = st.authRequiredCalls === 1 && st.buyClicks === 0;

          // 12. Live 2-trade loop cancelled immediately after Trade 1 submits stops Trade 2 across full 2s window
          st = buildMt5Dom({ catalog: ['XAUUSD.m'] });
          eval($sCancelLiveLoop);
          for (let w = 0; w < 80 && st.buyClicks < 1; w++) {
            await sleepMs(25);
          }
          eval($sCancelExec);
          await sleepMs(2400);
          const cancelStopsTrade2BeforeSubmit = st.buyClicks === 1;

          // 12b. Multi-trade execution (2 trades) enforces 2s (2000ms) delay between Trade 1 confirmation and Trade 2 submit
          st = buildMt5Dom({ catalog: ['XAUUSD.m'] });
          eval($sCancelLiveLoop);
          for (let w = 0; w < 140 && !st.logs.some(function(l) { return l.done; }); w++) {
            await sleepMs(50);
          }
          const twoSecondDelayBetweenTradesOk =
            st.buyClicks === 2 &&
            st.buyClickTimestamps &&
            st.buyClickTimestamps.length === 2 &&
            (st.buyClickTimestamps[1] - st.buyClickTimestamps[0]) >= 2000;

          // 13. Full MT5 execution with .trade-input + split Buy/Sell populates fields, clicks Buy ONCE, confirms ticket, emits DONE
          st = buildMt5Dom({ catalog: ['XAUUSD.m'] });
          eval($sExecTrade);
          for (let w = 0; w < 80 && !st.logs.some(function(l) { return l.done; }); w++) {
            await sleepMs(50);
          }
          const mt5TradeInputVerifiedAndExecutedOnce =
            st.clickedSymbol === 'XAUUSD.m' &&
            st.filledLot === '0.03' &&
            st.filledSl === '1.0800' &&
            st.filledTp === '1.0950' &&
            st.filledComment === 'BTECH-MT5' &&
            st.buyClicks === 1 &&
            st.sellClicks === 0 &&
            st.phases.some(function(p) { return p.k === 'DONE'; }) &&
            st.logs.some(function(l) { return l.done && !l.err && l.m.indexOf('#9001001') >= 0; });

          console.log(JSON.stringify({
            ok: true,
            exactSearchAndClickOk: exactSearchAndClickOk,
            caseInsensitiveSearchOk: caseInsensitiveSearchOk,
            hiddenUntilSearchedOk: hiddenUntilSearchedOk,
            suffixDotMOk: suffixDotMOk,
            suffixDotEcnOk: suffixDotEcnOk,
            unmatchedSuffixRejectedOk: unmatchedSuffixRejectedOk,
            searchClickAloneDidNotCompleteTrade: searchClickAloneDidNotCompleteTrade,
            missingSymbolFailedCleanly: missingSymbolFailedCleanly,
            similarDifferentRejected: similarDifferentRejected,
            goldNotBlindlyXauUsd: goldNotBlindlyXauUsd,
            goldWithVerifiedAliasOk: goldWithVerifiedAliasOk,
            orderFormMismatchRejected: orderFormMismatchRejected,
            authLostTriggersBridge: authLostTriggersBridge,
            cancelStopsTrade2BeforeSubmit: cancelStopsTrade2BeforeSubmit,
            twoSecondDelayBetweenTradesOk: twoSecondDelayBetweenTradesOk,
            mt5TradeInputVerifiedAndExecutedOnce: mt5TradeInputVerifiedAndExecutedOnce,
            executedBuyClicks: st.buyClicks
          }));
        })().catch(function(err) {
          console.log(JSON.stringify({ ok: false, error: err && err.stack ? err.stack : String(err) }));
        });
        """.trimIndent()
    }

    companion object {
        private val NODE_DOM_SHIM = """
        'use strict';

        function sleepMs(ms) {
          return new Promise(function(r) { setTimeout(r, ms); });
        }

        class ShimEvent {
          constructor(type, opts) {
            this.type = String(type || '');
            opts = opts || {};
            this.bubbles = !!opts.bubbles;
            this.cancelable = !!opts.cancelable;
            this.composed = !!opts.composed;
            this.defaultPrevented = false;
            this._stopped = false;
            this.target = null;
            this.currentTarget = null;
          }
          preventDefault() { this.defaultPrevented = true; }
          stopPropagation() { this._stopped = true; }
        }

        class ShimMouseEvent extends ShimEvent {
          constructor(type, opts) {
            super(type, opts);
            opts = opts || {};
            this.button = opts.button || 0;
            this.buttons = opts.buttons || 0;
            this.clientX = opts.clientX || 0;
            this.clientY = opts.clientY || 0;
          }
        }

        class ShimKeyboardEvent extends ShimEvent {
          constructor(type, opts) {
            super(type, opts);
            opts = opts || {};
            this.key = opts.key || '';
            this.code = opts.code || '';
            this.keyCode = opts.keyCode || 0;
            this.which = opts.which || 0;
            this.ctrlKey = !!opts.ctrlKey;
            this.altKey = !!opts.altKey;
            this.shiftKey = !!opts.shiftKey;
            this.metaKey = !!opts.metaKey;
          }
        }

        class ShimInputEvent extends ShimEvent {
          constructor(type, opts) {
            super(type, opts);
            opts = opts || {};
            this.data = opts.data !== undefined ? opts.data : null;
            this.inputType = opts.inputType || '';
          }
        }

        function splitSelectors(sel) {
          const out = [];
          let cur = '';
          let inQuote = null;
          let depth = 0;
          for (let i = 0; i < sel.length; i++) {
            const ch = sel[i];
            if ((ch === "'" || ch === '"') && sel[i - 1] !== '\\') {
              if (inQuote === ch) inQuote = null;
              else if (!inQuote) inQuote = ch;
            } else if (!inQuote) {
              if (ch === '[') depth++;
              else if (ch === ']') depth = Math.max(0, depth - 1);
              else if (ch === ',' && depth === 0) {
                if (cur.trim()) out.push(cur.trim());
                cur = '';
                continue;
              }
            }
            cur += ch;
          }
          if (cur.trim()) out.push(cur.trim());
          return out;
        }

        function matchesSinglePart(el, part) {
          if (!el || !el.tagName) return false;
          let p = part.trim();
          if (!p) return false;

          // descendant combinator support ("A B")
          const tokens = p.match(/(?:[^\s\[]+|\[[^\]]*\])+/g) || [p];
          if (tokens.length > 1) {
            if (!matchesCompound(el, tokens[tokens.length - 1])) return false;
            let ancestor = el.parentElement;
            let idx = tokens.length - 2;
            while (ancestor && idx >= 0) {
              if (matchesCompound(ancestor, tokens[idx])) idx--;
              ancestor = ancestor.parentElement;
            }
            return idx < 0;
          }
          return matchesCompound(el, tokens[0]);
        }

        function matchesCompound(el, token) {
          if (!el || !el.tagName) return false;
          let rem = token;
          const attrRe = /\[([^\]]+)\]/g;
          let m;
          const attrs = [];
          while ((m = attrRe.exec(token)) !== null) {
            attrs.push(m[1]);
          }
          rem = rem.replace(attrRe, '');

          // Check :disabled pseudo-class
          const hasDisabledPseudo = /:disabled$/.test(rem);
          if (hasDisabledPseudo) {
            rem = rem.replace(/:disabled$/, '');
            if (!el.disabled) return false;
          }

          // Check universal '*', tag, #id, .class
          if (rem.startsWith('*')) {
            rem = rem.slice(1);
          } else {
            const tagMatch = rem.match(/^[a-zA-Z0-9_-]+/);
            if (tagMatch) {
              if (el.tagName.toUpperCase() !== tagMatch[0].toUpperCase()) return false;
              rem = rem.slice(tagMatch[0].length);
            }
          }
          while (rem.length > 0) {
            if (rem[0] === '.') {
              rem = rem.slice(1);
              const cm = rem.match(/^[a-zA-Z0-9_-]+/);
              if (!cm) return false;
              const clsList = String(el.className || '').split(/\s+/);
              if (clsList.indexOf(cm[0]) < 0) return false;
              rem = rem.slice(cm[0].length);
            } else if (rem[0] === '#') {
              rem = rem.slice(1);
              const im = rem.match(/^[a-zA-Z0-9_-]+/);
              if (!im) return false;
              if (el.id !== im[0]) return false;
              rem = rem.slice(im[0].length);
            } else {
              return false;
            }
          }

          for (let i = 0; i < attrs.length; i++) {
            const spec = attrs[i].trim();
            const am = spec.match(/^([a-zA-Z0-9_-]+)\s*(\*?=)?\s*(?:(['"])(.*?)\3\s*(i)?)?$/);
            if (!am) continue;
            const attrName = am[1];
            const op = am[2] || '';
            const expected = am[4] !== undefined ? am[4] : '';
            const ci = !!am[5];
            let actual = el.getAttribute(attrName);
            if (actual === null || actual === undefined) return false;
            if (!op) continue;
            if (ci) {
              actual = actual.toLowerCase();
              const expL = expected.toLowerCase();
              if (op === '*=') { if (actual.indexOf(expL) < 0) return false; }
              else if (actual !== expL) return false;
            } else {
              if (op === '*=') { if (actual.indexOf(expected) < 0) return false; }
              else if (actual !== expected) return false;
            }
          }
          return true;
        }

        function matchesSelector(el, selector) {
          const parts = splitSelectors(selector);
          for (let i = 0; i < parts.length; i++) {
            if (matchesSinglePart(el, parts[i])) return true;
          }
          return false;
        }

        class ShimElement {
          constructor(tagName, ownerDocument) {
            this.tagName = String(tagName || 'DIV').toUpperCase();
            this.nodeType = 1;
            this.ownerDocument = ownerDocument;
            this.children = [];
            this.parentElement = null;
            this.attributes = {};
            this.style = { display: '', visibility: '', opacity: '1', cursor: 'pointer', backgroundColor: 'rgb(30,30,30)' };
            this._listeners = {};
            this._text = '';
            this._value = '';
            this.disabled = false;
            this.shadowRoot = null;
            this.options = [];
            this.selectedIndex = 0;
          }
          get parentNode() { return this.parentElement; }
          get previousElementSibling() {
            if (!this.parentElement) return null;
            const idx = this.parentElement.children.indexOf(this);
            return idx > 0 ? this.parentElement.children[idx - 1] : null;
          }
          get classList() {
            const self = this;
            return {
              contains: function(c) { return String(self.className || '').split(/\s+/).indexOf(c) >= 0; }
            };
          }
          matches(sel) { return matchesSelector(this, sel); }
          get isConnected() {
            let cur = this;
            while (cur) {
              if (cur === this.ownerDocument.documentElement || cur === this.ownerDocument.body) return true;
              cur = cur.parentElement;
            }
            return false;
          }
          get id() { return this.attributes['id'] || ''; }
          set id(v) { this.attributes['id'] = String(v); }
          get className() { return this.attributes['class'] || ''; }
          set className(v) { this.attributes['class'] = String(v); }
          get name() { return this.attributes['name'] || ''; }
          set name(v) { this.attributes['name'] = String(v); }
          get type() { return this.attributes['type'] || (this.tagName === 'INPUT' ? 'text' : ''); }
          set type(v) { this.attributes['type'] = String(v); }
          get placeholder() { return this.attributes['placeholder'] || ''; }
          set placeholder(v) { this.attributes['placeholder'] = String(v); }
          get value() { return this._value; }
          set value(v) {
            this._value = String(v === undefined || v === null ? '' : v);
            if (this.tagName === 'SELECT' && this.options.length > 0) {
              const idx = this.options.findIndex(o => String(o.value || o.textContent) === this._value);
              if (idx >= 0) this.selectedIndex = idx;
            }
          }
          get textContent() {
            if (this.children.length === 0) return this._text;
            return (this._text ? this._text + ' ' : '') + this.children.map(function(c) { return c.textContent; }).join(' ');
          }
          set textContent(v) {
            this._text = String(v || '');
            this.children = [];
          }
          get innerText() { return this.textContent; }
          set innerText(v) { this.textContent = v; }
          setAttribute(k, v) { this.attributes[String(k).toLowerCase()] = String(v); }
          getAttribute(k) {
            const key = String(k).toLowerCase();
            return Object.prototype.hasOwnProperty.call(this.attributes, key) ? this.attributes[key] : null;
          }
          prepend(child) {
            child.parentElement = this;
            this.children.unshift(child);
            return child;
          }
          appendChild(child) {
            child.parentElement = this;
            this.children.push(child);
            if (this.tagName === 'SELECT' && child.tagName === 'OPTION') {
              this.options.push(child);
              if (this.options.length === 1 && !this._value) {
                this._value = String(child.value || child.textContent || '');
              }
            }
            return child;
          }
          removeChild(child) {
            const idx = this.children.indexOf(child);
            if (idx >= 0) {
              this.children.splice(idx, 1);
              child.parentElement = null;
            }
            return child;
          }
          querySelectorAll(sel) {
            const out = [];
            function walk(node) {
              for (let i = 0; i < node.children.length; i++) {
                const c = node.children[i];
                if (matchesSelector(c, sel)) out.push(c);
                walk(c);
              }
            }
            walk(this);
            return out;
          }
          querySelector(sel) { return this.querySelectorAll(sel)[0] || null; }
          getElementsByTagName(tag) {
            return this.querySelectorAll(tag === '*' ? 'div,span,tr,td,th,button,input,select,option,h1,h2,h3,a,li,p' : tag);
          }
          closest(sel) {
            let cur = this;
            while (cur) {
              if (matchesSelector(cur, sel)) return cur;
              cur = cur.parentElement;
            }
            return null;
          }
          addEventListener(type, fn) {
            const key = String(type);
            this._listeners[key] = this._listeners[key] || [];
            this._listeners[key].push(fn);
          }
          dispatchEvent(ev) {
            if (!ev.target) ev.target = this;
            let cur = this;
            while (cur) {
              ev.currentTarget = cur;
              const list = (cur._listeners[ev.type] || []).slice();
              for (let i = 0; i < list.length; i++) {
                list[i].call(cur, ev);
              }
              if (!ev.bubbles || ev._stopped) break;
              cur = cur.parentElement;
            }
            return !ev.defaultPrevented;
          }
          click() {
            this.dispatchEvent(new ShimMouseEvent('click', { bubbles: true, cancelable: true }));
          }
          focus() {}
          select() {}
          getBoundingClientRect() {
            if (!this.isConnected || this.style.display === 'none' || this.style.visibility === 'hidden') {
              return { left: 0, top: 0, width: 0, height: 0, bottom: 0, right: 0 };
            }
            let top = 20;
            if (this.tagName === 'BUTTON') top = 160;
            else if (this.tagName === 'INPUT') top = 80;
            return { left: 10, top: top, width: 120, height: 32, bottom: top + 32, right: 130 };
          }
        }

        function createDomDocument() {
          const doc = {
            title: 'WebTerminal',
            readyState: 'complete',
            createElement: function(tag) { return new ShimElement(tag, doc); },
            dispatchEvent: function(ev) { return doc.documentElement.dispatchEvent(ev); },
            querySelectorAll: function(sel) { return doc.documentElement.querySelectorAll(sel); },
            querySelector: function(sel) { return doc.documentElement.querySelector(sel); },
            getElementById: function(id) { return doc.documentElement.querySelector('#' + id); },
            getElementsByTagName: function(tag) { return doc.documentElement.getElementsByTagName(tag); }
          };
          doc.documentElement = new ShimElement('HTML', doc);
          doc.head = new ShimElement('HEAD', doc);
          doc.body = new ShimElement('BODY', doc);
          doc.documentElement.appendChild(doc.head);
          doc.documentElement.appendChild(doc.body);
          doc.body.isConnected;
          return doc;
        }

        global.window = global;
        global.self = global;
        global.innerWidth = 1080;
        global.innerHeight = 1920;
        global.location = { href: 'https://web.broker.example/terminal', pathname: '/terminal' };
        global.Event = ShimEvent;
        global.MouseEvent = ShimMouseEvent;
        global.KeyboardEvent = ShimKeyboardEvent;
        global.InputEvent = ShimInputEvent;
        global.HTMLInputElement = ShimElement;
        global.HTMLTextAreaElement = ShimElement;
        global.HTMLSelectElement = ShimElement;
        global.Element = ShimElement;
        global.getComputedStyle = function(el) { return el.style; };

        function installDoc(doc) {
          global.document = doc;
        }
        """.trimIndent()
    }
}
