package com.example

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.example.data.BtechPreferencesRepository
import com.example.model.BubbleGlowStyle
import com.example.service.ExecutionStepId
import com.example.service.ExecutionStepStatus
import com.example.service.FloatingOverlayMonitor
import com.example.service.OverlayActionEvent
import com.example.service.OverlayStateBus
import com.example.webterminal.WebTerminalScripts
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FloatingOverlayMonitorTest {

    @Test
    fun `FloatingOverlayMonitor is registered in manifest`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val component = ComponentName(context, FloatingOverlayMonitor::class.java)
        val info = context.packageManager.getServiceInfo(component, PackageManager.GET_META_DATA)
        assertNotNull(info)
        assertFalse(info.exported)
    }

    @Test
    fun `OverlayStateBus stores real-time balance snapshot fields`() {
        OverlayStateBus.updateSnapshot {
            it.copy(
                isBalanceMonitorRunning = true,
                accountBalance = "$1,250.40",
                accountEquity = "$1,268.90",
                accountFloatingPl = "+$18.50",
                balanceUpdatedAt = "12:00:05",
                balanceStatus = "Live Web Terminal telemetry",
            )
        }
        val snap = OverlayStateBus.snapshot.value
        assertTrue(snap.isBalanceMonitorRunning)
        assertEquals("$1,250.40", snap.accountBalance)
        assertEquals("$1,268.90", snap.accountEquity)
        assertEquals("+$18.50", snap.accountFloatingPl)
        assertEquals("12:00:05", snap.balanceUpdatedAt)

        // Reset to avoid leaking across tests
        OverlayStateBus.updateSnapshot {
            it.copy(
                isBalanceMonitorRunning = false,
                accountBalance = "—",
                accountEquity = "—",
                accountFloatingPl = "—",
                balanceUpdatedAt = "",
                balanceStatus = "Waiting for Web Terminal",
            )
        }
    }

    @Test
    fun `OverlayStateBus emits refresh and stop balance monitor actions`() = runBlocking {
        var receivedRefresh = false
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            val ev = withTimeout(5_000L) { OverlayStateBus.actions.first() }
            receivedRefresh = ev is OverlayActionEvent.RefreshBalanceRequested
        }
        OverlayStateBus.emitAction(OverlayActionEvent.RefreshBalanceRequested)
        job.join()
        assertTrue(receivedRefresh)
    }

    @Test
    fun `balance telemetry script targets bridge and parses MT fields`() {
        val script = WebTerminalScripts.buildBalanceTelemetryScript()
        assertTrue(script.contains("onBalanceUpdate"))
        assertTrue(script.contains("balance", ignoreCase = true))
        assertTrue(script.contains("equity", ignoreCase = true))
        assertTrue(script.contains("profit", ignoreCase = true))
    }

    @Test
    fun `trade script enforces separate MT4 and MT5 discovery and pre-order verification`() {
        val mt5Script = WebTerminalScripts.buildTradeScript(
            symbol = "XAUUSD.s",
            direction = "BUY",
            lot = "0.01",
            stopLoss = "",
            takeProfit = "",
            comment = "BTECH-EA",
            tradeCount = 1,
            platform = "MT5",
            broker = "Exness",
            server = "Exness-MT5Trial9",
        )
        assertTrue(mt5Script.contains("discoverMT5Symbol"))
        assertTrue(mt5Script.contains("discoverAllBrokerSymbols"))
        assertTrue(mt5Script.contains("stripKnownSuffix"))
        assertTrue(mt5Script.contains("matchRank"))
        assertTrue(mt5Script.contains("findOrderFieldInput"))
        assertTrue(mt5Script.contains("findOrderActionButton"))
        assertTrue(mt5Script.contains("verifySelectedInstrumentAndOrderForm"))
        assertTrue(mt5Script.contains("executeSequentialTrades"))
        assertTrue(mt5Script.contains("countBrokerPositions"))
        assertTrue(mt5Script.contains("readUsedMarginNumeric"))
        assertTrue(mt5Script.contains("cleanSymbolLabel"))
        assertTrue(mt5Script.contains("findScopedResultOkButton"))
        assertTrue(mt5Script.contains("findRequoteDialogAction"))
        assertTrue(mt5Script.contains("applyOrderDeviationIfPresent"))
        assertTrue(mt5Script.contains("dismissTradeResult"))
        assertTrue(mt5Script.contains("isExplicitAcceptanceStatusText"))
        assertTrue(mt5Script.contains("singleClick"))
        assertTrue(mt5Script.contains("findStrictNewOrderButton"))
        assertTrue(mt5Script.contains("checkAuthAndStart"))
        assertTrue(mt5Script.contains("__btechTradeRunId"))
        assertTrue(mt5Script.contains("onSymbolDiscovered"))
        assertFalse(mt5Script.contains("DRY_RUN"))
        assertFalse(mt5Script.contains("fallbackConfirmed"))
        assertFalse(mt5Script.contains("buttonCycleCompleted"))
        assertFalse(mt5Script.contains("Retrying Trade"))
        assertFalse(mt5Script.contains("Waiting 2s before Trade"))
        assertTrue(mt5Script.contains("schedule(runNextTrade, 2000)"))
        assertFalse(mt5Script.contains("deepAll('body *')"))
        assertFalse(mt5Script.contains("queryDeepAll('body *')"))

        val mt4Script = WebTerminalScripts.buildTradeScript(
            symbol = "XAUUSD",
            direction = "SELL",
            lot = "0.02",
            stopLoss = "2650",
            takeProfit = "2600",
            comment = "BTECH-MT4",
            tradeCount = 1,
            platform = "MT4",
            broker = "JustMarkets",
            server = "JustMarkets-Demo",
        )
        assertTrue(mt4Script.contains("discoverMT4Symbol"))
        assertTrue(mt4Script.contains("openMT4ContextMenu"))
        assertTrue(mt4Script.contains("findExactVisibleText('Show All'"))
        assertTrue(mt4Script.contains("doubleClickMT4Row"))
        assertTrue(mt4Script.contains("PLATFORM = \"MT4\""))

        val balScript = WebTerminalScripts.buildBalanceTelemetryScript()
        assertTrue(balScript.contains("[A-Z]{3}(?![a-z])"))
    }

    @Test
    fun `auth script handles MT5 trading accounts picker and workspace checks`() {
        val script = WebTerminalScripts.buildAuthScript(
            login = "477786797",
            password = "secret",
            server = "Exness-MT5Trial9",
            platform = "MT5",
        )
        assertTrue(script.contains("trading accounts"))
        assertTrue(script.contains("connect to account"))
        assertTrue(script.contains("instant execution"))
    }

    @Test
    fun `OverlayStateBus starts idle with zero steps and adds steps one by one dynamically`() {
        OverlayStateBus.clearLogs()
        var snap = OverlayStateBus.snapshot.value
        assertFalse(snap.hasExecutionTracker)
        assertTrue(snap.executionSteps.isEmpty())
        assertEquals("Waiting for new signal", snap.executionHeaderStatus)

        OverlayStateBus.appendLog("Old log entry from previous trade")
        assertEquals(1, OverlayStateBus.snapshot.value.logs.size)

        OverlayStateBus.beginNewSignalExecution(
            statusTitle = "Signal: XAUUSD BUY",
            detail = "Signal received: 2x 0.03 lots",
        )
        snap = OverlayStateBus.snapshot.value
        assertEquals(0, snap.logs.size)
        assertTrue(snap.hasExecutionTracker)
        assertEquals(2, snap.executionSteps.size)
        assertEquals(ExecutionStepStatus.COMPLETED, snap.executionSteps[0].status)
        assertEquals("Signal received", snap.executionSteps[0].label)
        assertEquals(ExecutionStepStatus.ACTIVE, snap.executionSteps[1].status)
        assertEquals("Opening terminal", snap.executionSteps[1].label)

        OverlayStateBus.updateDynamicStep(
            key = ExecutionStepId.AUTHENTICATING_ACCOUNT.name,
            stepId = ExecutionStepId.AUTHENTICATING_ACCOUNT,
            status = ExecutionStepStatus.ACTIVE,
            label = "Authenticating account",
        )
        snap = OverlayStateBus.snapshot.value
        assertEquals(3, snap.executionSteps.size)
        assertEquals(ExecutionStepStatus.COMPLETED, snap.executionSteps[1].status)
        assertEquals(ExecutionStepStatus.ACTIVE, snap.executionSteps[2].status)

        // Single symbol row transitions in-place: Searching -> Symbol found -> Selecting -> Selected
        OverlayStateBus.updateDynamicStep(
            key = "SYMBOL",
            stepId = ExecutionStepId.SELECTING_SYMBOL,
            status = ExecutionStepStatus.ACTIVE,
            label = "Searching symbol",
        )
        assertEquals(4, OverlayStateBus.snapshot.value.executionSteps.size)
        assertEquals("Searching symbol", OverlayStateBus.snapshot.value.executionSteps[3].label)

        OverlayStateBus.updateDynamicStep(
            key = "SYMBOL",
            stepId = ExecutionStepId.SELECTING_SYMBOL,
            status = ExecutionStepStatus.COMPLETED,
            label = "Symbol found",
        )
        assertEquals(4, OverlayStateBus.snapshot.value.executionSteps.size)
        assertEquals("Symbol found", OverlayStateBus.snapshot.value.executionSteps[3].label)

        OverlayStateBus.updateDynamicStep(
            key = "SYMBOL",
            stepId = ExecutionStepId.SELECTING_SYMBOL,
            status = ExecutionStepStatus.ACTIVE,
            label = "Selecting symbol",
        )
        assertEquals(4, OverlayStateBus.snapshot.value.executionSteps.size)
        assertEquals("Selecting symbol", OverlayStateBus.snapshot.value.executionSteps[3].label)

        OverlayStateBus.updateDynamicStep(
            key = "SYMBOL",
            stepId = ExecutionStepId.SELECTING_SYMBOL,
            status = ExecutionStepStatus.COMPLETED,
            label = "Symbol selected",
        )
        snap = OverlayStateBus.snapshot.value
        assertEquals(4, snap.executionSteps.size)
        assertEquals("Symbol selected", snap.executionSteps[3].label)
        assertEquals(ExecutionStepStatus.COMPLETED, snap.executionSteps[3].status)

        // Trade 1/10 -> 2/10 -> 10/10 updates the SINGLE 'TRADE' row in-place (never stacks multiple rows)
        OverlayStateBus.updateDynamicStep(
            key = "TRADE",
            stepId = ExecutionStepId.EXECUTING_TRADE,
            status = ExecutionStepStatus.ACTIVE,
            label = "Waiting for broker confirmation (1/10)",
            detail = "Waiting for Trade 1/10",
        )
        assertEquals(5, OverlayStateBus.snapshot.value.executionSteps.size)
        assertEquals("Waiting for broker confirmation (1/10)", OverlayStateBus.snapshot.value.executionSteps[4].label)

        OverlayStateBus.updateDynamicStep(
            key = "TRADE",
            stepId = ExecutionStepId.EXECUTING_TRADE,
            status = ExecutionStepStatus.COMPLETED,
            label = "Trade 1/10 confirmed",
            detail = "Trade 1/10 confirmed · #1089003442 buy 0.03 XAUUSD",
        )
        assertEquals(5, OverlayStateBus.snapshot.value.executionSteps.size)
        assertEquals("Trade 1/10 confirmed", OverlayStateBus.snapshot.value.executionSteps[4].label)

        OverlayStateBus.updateDynamicStep(
            key = "TRADE",
            stepId = ExecutionStepId.EXECUTING_TRADE,
            status = ExecutionStepStatus.ACTIVE,
            label = "Executing trade 2/10",
            detail = "Trade 2/10 executing",
        )
        assertEquals(5, OverlayStateBus.snapshot.value.executionSteps.size)
        assertEquals("Executing trade 2/10", OverlayStateBus.snapshot.value.executionSteps[4].label)

        OverlayStateBus.updateDynamicStep(
            key = "TRADE",
            stepId = ExecutionStepId.EXECUTING_TRADE,
            status = ExecutionStepStatus.COMPLETED,
            label = "Trade 2/10 confirmed",
            detail = "Trade 2/10 confirmed · #1089003443 buy 0.03 XAUUSD",
        )
        assertEquals(5, OverlayStateBus.snapshot.value.executionSteps.size)
        assertEquals("Trade 2/10 confirmed", OverlayStateBus.snapshot.value.executionSteps[4].label)

        OverlayStateBus.updateDynamicStep(
            key = "TRADE",
            stepId = ExecutionStepId.EXECUTING_TRADE,
            status = ExecutionStepStatus.COMPLETED,
            label = "Trade 10/10 confirmed",
            detail = "All 10/10 trades confirmed",
        )
        assertEquals(5, OverlayStateBus.snapshot.value.executionSteps.size)
        assertEquals("Trade 10/10 confirmed", OverlayStateBus.snapshot.value.executionSteps[4].label)

        OverlayStateBus.completeExecutionSuccess(
            "All 10/10 trades confirmed · #1089004314 buy 0.03 XAUUSD",
            totalTrades = 10,
        )
        snap = OverlayStateBus.snapshot.value
        assertEquals(6, snap.executionSteps.size)
        assertEquals("Executed", snap.executionSteps[4].label)
        assertEquals(ExecutionStepId.TRADE_COMPLETED, snap.executionSteps[5].id)
        assertEquals("Trade 10/10 completed", snap.executionSteps[5].label)
        assertTrue(snap.executionCompleted)
        assertFalse(snap.isExecuting)
        assertEquals(1f, snap.executionProgress)
        assertTrue(snap.executionSteps.all { it.status == ExecutionStepStatus.COMPLETED })

        OverlayStateBus.failExecution("Trading terminated after 60 seconds", ExecutionStepId.AUTHENTICATING_ACCOUNT)
        snap = OverlayStateBus.snapshot.value
        assertTrue(snap.executionFailed)
        assertEquals("Something went wrong", snap.executionHeaderStatus)
        assertEquals(ExecutionStepStatus.ERROR, snap.executionSteps.first { it.id == ExecutionStepId.AUTHENTICATING_ACCOUNT }.status)

        OverlayStateBus.clearLogs()
        assertTrue(OverlayStateBus.snapshot.value.executionSteps.isEmpty())
    }

    @Test
    fun `OverlaySnapshot defaults to light mode and tracks theme updates`() {
        OverlayStateBus.updateSnapshot { it.copy(isDarkTheme = false) }
        assertFalse(OverlayStateBus.snapshot.value.isDarkTheme)

        OverlayStateBus.updateSnapshot { it.copy(isDarkTheme = true) }
        assertTrue(OverlayStateBus.snapshot.value.isDarkTheme)

        OverlayStateBus.updateSnapshot { it.copy(isDarkTheme = false) }
        assertFalse(OverlayStateBus.snapshot.value.isDarkTheme)
    }

    @Test
    fun `BubbleGlowStyle exposes all 6 colors and persists through repository and OverlayStateBus`() = runBlocking {
        assertEquals(6, BubbleGlowStyle.CYCLE_HEX_COLORS.size)
        assertTrue(BubbleGlowStyle.CYCLE_HEX_COLORS.contains(BubbleGlowStyle.LIME.hex))
        assertTrue(BubbleGlowStyle.CYCLE_HEX_COLORS.contains(BubbleGlowStyle.PURPLE.hex))
        assertTrue(BubbleGlowStyle.CYCLE_HEX_COLORS.contains(BubbleGlowStyle.BLUE.hex))
        assertTrue(BubbleGlowStyle.CYCLE_HEX_COLORS.contains(BubbleGlowStyle.DARK_STYLE.hex))
        assertTrue(BubbleGlowStyle.CYCLE_HEX_COLORS.contains(BubbleGlowStyle.ORANGE_RED.hex))
        assertTrue(BubbleGlowStyle.CYCLE_HEX_COLORS.contains(BubbleGlowStyle.PINK_RED.hex))
        assertEquals(BubbleGlowStyle.AUTO, BubbleGlowStyle.fromId(null))
        assertEquals(BubbleGlowStyle.PRIME, BubbleGlowStyle.fromId("prime"))
        assertEquals(BubbleGlowStyle.ORANGE_RED, BubbleGlowStyle.fromId("orange_red"))

        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = BtechPreferencesRepository(context)
        repo.setBubbleGlowStyle(BubbleGlowStyle.PRIME)
        val savedPrime = repo.dataFlow.first()
        assertEquals(BubbleGlowStyle.PRIME, savedPrime.bubbleGlowStyle)

        repo.setBubbleGlowStyle(BubbleGlowStyle.PINK_RED)
        val saved = repo.dataFlow.first()
        assertEquals(BubbleGlowStyle.PINK_RED, saved.bubbleGlowStyle)

        OverlayStateBus.updateSnapshot { it.copy(bubbleGlowStyle = saved.bubbleGlowStyle) }
        assertEquals(BubbleGlowStyle.PINK_RED, OverlayStateBus.snapshot.value.bubbleGlowStyle)

        // Reset defaults
        repo.setBubbleGlowStyle(BubbleGlowStyle.AUTO)
        OverlayStateBus.updateSnapshot { it.copy(bubbleGlowStyle = BubbleGlowStyle.AUTO) }
    }
}
