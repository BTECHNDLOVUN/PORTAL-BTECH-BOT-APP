package com.example

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
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
    fun `trade script supports suffix variants and order picker fallback`() {
        val script = WebTerminalScripts.buildTradeScript(
            symbol = "XAUUSD.s",
            direction = "BUY",
            lot = "0.01",
            stopLoss = "",
            takeProfit = "",
            comment = "BTECH-EA",
            tradeCount = 1,
        )
        assertTrue(script.contains("extractBaseSymbol"))
        assertTrue(script.contains("clickAddButtonInsideRow"))
        assertTrue(script.contains("findCustomSymbolDropdown"))
        assertTrue(script.contains("VARIANT_REGEX"))
        assertTrue(script.contains("SUFFIX_CANDIDATES"))
        assertTrue(script.contains("findOrderFieldInput"))
        assertTrue(script.contains("findOrderActionButton"))
        assertTrue(script.contains("executeSequentialTrades"))
        assertTrue(script.contains("confirmed · "))
        assertTrue(script.contains("countBrokerPositions"))
        assertTrue(script.contains("readUsedMarginNumeric"))
        assertTrue(script.contains("cleanSymbolLabel"))
        assertTrue(script.contains("findReceiptConfirmButton"))
        assertTrue(script.contains("singleClick"))
        assertTrue(script.contains("findStrictNewOrderButton"))
        assertTrue(script.contains("checkAuthAndStart"))
        assertTrue(script.contains("__btechTradeRunId"))
        assertFalse(script.contains("fallbackConfirmed"))
        assertFalse(script.contains("buttonCycleCompleted"))
        assertFalse(script.contains("Retrying Trade"))

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

        // Trade 1 -> Trade 2 sequential steps
        OverlayStateBus.updateDynamicStep(
            key = "TRADE_1",
            stepId = ExecutionStepId.EXECUTING_TRADE,
            status = ExecutionStepStatus.ACTIVE,
            label = "Waiting for broker confirmation (1/2)",
            detail = "Waiting for Trade 1/2",
        )
        OverlayStateBus.updateDynamicStep(
            key = "TRADE_1",
            stepId = ExecutionStepId.EXECUTING_TRADE,
            status = ExecutionStepStatus.COMPLETED,
            label = "Trade 1/2 confirmed",
            detail = "Trade 1/2 confirmed · #1089003442 buy 0.03 XAUUSD",
        )
        OverlayStateBus.updateDynamicStep(
            key = "TRADE_2",
            stepId = ExecutionStepId.EXECUTING_TRADE,
            status = ExecutionStepStatus.ACTIVE,
            label = "Waiting for broker confirmation (2/2)",
            detail = "Waiting for Trade 2/2",
        )

        OverlayStateBus.completeExecutionSuccess("All 2/2 trades confirmed · #1089004314 buy 0.03 XAUUSD")
        snap = OverlayStateBus.snapshot.value
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
}
