package com.example.service

import com.example.model.BrokerTradeResult
import com.example.model.ExecutionStepLog
import com.example.model.TradeDirection
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ExecutionStepId(val label: String, val progress: Float) {
    SIGNAL_RECEIVED("Signal received", 0.14f),
    OPENING_TERMINAL("Opening terminal", 0.28f),
    AUTHENTICATING_ACCOUNT("Authenticating account", 0.44f),
    SELECTING_SYMBOL("Searching symbol", 0.62f),
    PREPARING_TRADE("Preparing trade", 0.78f),
    EXECUTING_TRADE("Executing trade", 0.92f),
}

enum class ExecutionStepStatus {
    PENDING,
    ACTIVE,
    COMPLETED,
    ERROR,
}

data class ExecutionStepItem(
    val id: ExecutionStepId,
    val status: ExecutionStepStatus = ExecutionStepStatus.ACTIVE,
    val label: String = id.label,
    val key: String = id.name,
)

fun defaultExecutionSteps(): List<ExecutionStepItem> = emptyList()

data class OverlaySnapshot(
    val isRunning: Boolean = false,
    val isBalanceMonitorRunning: Boolean = false,
    val botName: String = "BTECH SCALPER V2.3",
    val botOwner: String = "Bee Blessings",
    val serverName: String = "JustMarkets-Live",
    val accentHex: String = "#9138EE",
    val isConnected: Boolean = false,
    val skipTrading: Boolean = false,
    val isExecuting: Boolean = false,
    val statusTitle: String = "Trade Channel monitor",
    val statusDetail: String = "Waiting for new signal",
    val statusPill: String = "IDLE",
    val accountBalance: String = "—",
    val accountEquity: String = "—",
    val accountFloatingPl: String = "—",
    val accountMargin: String = "—",
    val balanceUpdatedAt: String = "",
    val balanceStatus: String = "Waiting for Web Terminal",
    val logs: List<ExecutionStepLog> = emptyList(),
    val executionSteps: List<ExecutionStepItem> = emptyList(),
    val executionProgress: Float = 0f,
    val executionHeaderStatus: String = "Waiting for new signal",
    val hasExecutionTracker: Boolean = false,
    val executionFailed: Boolean = false,
    val executionCompleted: Boolean = false,
    val isDarkTheme: Boolean = false,
    val lastTradeResult: BrokerTradeResult? = null,
)

sealed class OverlayActionEvent {
    data object StopBotRequested : OverlayActionEvent()
    data object StopBalanceMonitorRequested : OverlayActionEvent()
    data object RefreshBalanceRequested : OverlayActionEvent()
    data class SkipTradingChanged(val skipTrading: Boolean) : OverlayActionEvent()
    data class SimulateTradeRequested(
        val symbol: String? = null,
        val direction: TradeDirection = TradeDirection.BUY,
    ) : OverlayActionEvent()
}

object OverlayStateBus {
    private val _snapshot = MutableStateFlow(OverlaySnapshot())
    val snapshot = _snapshot.asStateFlow()

    private val _actions = MutableSharedFlow<OverlayActionEvent>(extraBufferCapacity = 16)
    val actions = _actions.asSharedFlow()

    private var logSeq = 1L
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun updateSnapshot(transform: (OverlaySnapshot) -> OverlaySnapshot) {
        _snapshot.value = transform(_snapshot.value)
    }

    fun appendLog(message: String, isError: Boolean = false, isSuccess: Boolean = false) {
        val entry = ExecutionStepLog(
            id = logSeq++,
            timestamp = timeFormat.format(Date()),
            message = message,
            isError = isError,
            isSuccess = isSuccess,
        )
        _snapshot.value = _snapshot.value.let { cur ->
            val updated = (cur.logs + entry).takeLast(100)
            cur.copy(logs = updated)
        }
    }

    fun clearLogs() {
        logSeq = 1L
        _snapshot.value = _snapshot.value.let { cur ->
            if (cur.isExecuting) {
                cur.copy(logs = emptyList())
            } else {
                cur.copy(
                    logs = emptyList(),
                    hasExecutionTracker = false,
                    executionFailed = false,
                    executionCompleted = false,
                    executionProgress = 0f,
                    executionHeaderStatus = "Waiting for new signal",
                    executionSteps = emptyList(),
                    statusDetail = "Waiting for new signal",
                )
            }
        }
    }

    fun beginNewSignalExecution(statusTitle: String, detail: String) {
        logSeq = 1L
        val initialSteps = listOf(
            ExecutionStepItem(
                id = ExecutionStepId.SIGNAL_RECEIVED,
                status = ExecutionStepStatus.COMPLETED,
                label = ExecutionStepId.SIGNAL_RECEIVED.label,
                key = ExecutionStepId.SIGNAL_RECEIVED.name,
            ),
            ExecutionStepItem(
                id = ExecutionStepId.OPENING_TERMINAL,
                status = ExecutionStepStatus.ACTIVE,
                label = ExecutionStepId.OPENING_TERMINAL.label,
                key = ExecutionStepId.OPENING_TERMINAL.name,
            ),
        )
        _snapshot.value = _snapshot.value.copy(
            logs = emptyList(),
            isExecuting = true,
            statusTitle = statusTitle,
            statusDetail = detail,
            statusPill = "EXECUTING",
            hasExecutionTracker = true,
            executionFailed = false,
            executionCompleted = false,
            executionProgress = ExecutionStepId.OPENING_TERMINAL.progress,
            executionHeaderStatus = ExecutionStepId.OPENING_TERMINAL.label,
            executionSteps = initialSteps,
            lastTradeResult = null,
        )
    }

    fun recordTradeResult(result: BrokerTradeResult) {
        _snapshot.value = _snapshot.value.copy(lastTradeResult = result)
    }

    fun markExecutionUnknown(detail: String) {
        _snapshot.value = _snapshot.value.let { cur ->
            val updatedSteps = cur.executionSteps.map { item ->
                if (item.status == ExecutionStepStatus.ACTIVE || item.status == ExecutionStepStatus.PENDING) {
                    item.copy(status = ExecutionStepStatus.ERROR, label = "Unconfirmed (UNKNOWN)")
                } else {
                    item
                }
            }
            cur.copy(
                isExecuting = false,
                statusPill = "UNKNOWN",
                statusDetail = detail,
                hasExecutionTracker = true,
                executionFailed = true,
                executionCompleted = false,
                executionHeaderStatus = "Unconfirmed — Not Retried",
                executionSteps = updatedSteps,
            )
        }
    }

    fun updateDynamicStep(
        key: String,
        stepId: ExecutionStepId,
        status: ExecutionStepStatus,
        label: String = stepId.label,
        progress: Float = stepId.progress,
        headerStatus: String? = null,
        detail: String? = null,
        completePreviousActive: Boolean = true,
    ) {
        _snapshot.value = _snapshot.value.let { cur ->
            val updated = cur.executionSteps.map { item ->
                if (item.key == key) {
                    item.copy(id = stepId, status = status, label = label)
                } else if (completePreviousActive && item.status == ExecutionStepStatus.ACTIVE) {
                    item.copy(status = ExecutionStepStatus.COMPLETED)
                } else {
                    item
                }
            }.toMutableList()

            if (updated.none { it.key == key }) {
                updated.add(
                    ExecutionStepItem(
                        id = stepId,
                        status = status,
                        label = label,
                        key = key,
                    ),
                )
            }

            cur.copy(
                isExecuting = true,
                statusPill = "EXECUTING",
                statusDetail = detail?.takeIf { it.isNotBlank() } ?: cur.statusDetail,
                hasExecutionTracker = true,
                executionFailed = false,
                executionCompleted = false,
                executionProgress = progress.coerceIn(0f, 1f),
                executionHeaderStatus = headerStatus?.takeIf { it.isNotBlank() } ?: label,
                executionSteps = updated,
            )
        }
    }

    fun completeStep(
        key: String,
        label: String? = null,
        progress: Float? = null,
        headerStatus: String? = null,
        detail: String? = null,
    ) {
        _snapshot.value = _snapshot.value.let { cur ->
            val updated = cur.executionSteps.map { item ->
                if (item.key == key) {
                    item.copy(
                        status = ExecutionStepStatus.COMPLETED,
                        label = label ?: item.label,
                    )
                } else {
                    item
                }
            }
            cur.copy(
                statusDetail = detail?.takeIf { it.isNotBlank() } ?: cur.statusDetail,
                executionProgress = progress?.coerceIn(0f, 1f) ?: cur.executionProgress,
                executionHeaderStatus = headerStatus?.takeIf { it.isNotBlank() } ?: cur.executionHeaderStatus,
                executionSteps = updated,
            )
        }
    }

    fun updateExecutionPhase(
        stepId: ExecutionStepId,
        headerStatus: String? = null,
        detail: String? = null,
        label: String = stepId.label,
        status: ExecutionStepStatus = ExecutionStepStatus.ACTIVE,
        key: String = stepId.name,
    ) {
        updateDynamicStep(
            key = key,
            stepId = stepId,
            status = status,
            label = label,
            progress = stepId.progress,
            headerStatus = headerStatus ?: label,
            detail = detail,
            completePreviousActive = true,
        )
    }

    fun completeExecutionSuccess(detail: String) {
        _snapshot.value = _snapshot.value.let { cur ->
            val completedSteps = cur.executionSteps.map { item ->
                if (item.status == ExecutionStepStatus.ACTIVE || item.status == ExecutionStepStatus.PENDING) {
                    item.copy(status = ExecutionStepStatus.COMPLETED)
                } else {
                    item
                }
            }
            cur.copy(
                isExecuting = false,
                statusPill = "DONE",
                statusDetail = detail,
                hasExecutionTracker = true,
                executionFailed = false,
                executionCompleted = true,
                executionProgress = 1f,
                executionHeaderStatus = "Completed",
                executionSteps = completedSteps,
            )
        }
    }

    fun failExecution(detail: String, failedStep: ExecutionStepId? = null) {
        _snapshot.value = _snapshot.value.let { cur ->
            val existing = cur.executionSteps.toMutableList()
            val targetIndex = when {
                failedStep != null -> existing.indexOfLast { it.id == failedStep }
                else -> {
                    val activeIdx = existing.indexOfLast { it.status == ExecutionStepStatus.ACTIVE }
                    if (activeIdx >= 0) activeIdx else existing.lastIndex
                }
            }

            val updatedSteps = if (targetIndex >= 0) {
                existing.mapIndexed { idx, item ->
                    when {
                        idx < targetIndex && item.status == ExecutionStepStatus.ACTIVE ->
                            item.copy(status = ExecutionStepStatus.COMPLETED)
                        idx == targetIndex ->
                            item.copy(status = ExecutionStepStatus.ERROR)
                        else -> item
                    }
                }
            } else {
                val completedPrev = existing.map { item ->
                    if (item.status == ExecutionStepStatus.ACTIVE) item.copy(status = ExecutionStepStatus.COMPLETED) else item
                }
                val step = failedStep ?: ExecutionStepId.AUTHENTICATING_ACCOUNT
                completedPrev + ExecutionStepItem(
                    id = step,
                    status = ExecutionStepStatus.ERROR,
                    label = step.label,
                    key = step.name,
                )
            }

            val failedProgress = updatedSteps.lastOrNull { it.status == ExecutionStepStatus.ERROR }?.id?.progress
                ?: cur.executionProgress

            cur.copy(
                isExecuting = false,
                statusPill = "ERROR",
                statusDetail = detail,
                hasExecutionTracker = true,
                executionFailed = true,
                executionCompleted = false,
                executionProgress = failedProgress,
                executionHeaderStatus = "Something went wrong",
                executionSteps = updatedSteps,
            )
        }
    }

    fun cancelActiveExecution(detail: String, markAsStopped: Boolean = false) {
        _snapshot.value = _snapshot.value.let { cur ->
            val wasExecuting = cur.isExecuting
            val updatedSteps = cur.executionSteps.map { item ->
                if (item.status == ExecutionStepStatus.ACTIVE || item.status == ExecutionStepStatus.PENDING) {
                    item.copy(status = ExecutionStepStatus.ERROR)
                } else {
                    item
                }
            }
            cur.copy(
                isExecuting = false,
                isRunning = if (markAsStopped) false else cur.isRunning,
                statusPill = when {
                    markAsStopped -> "STOPPED"
                    cur.skipTrading -> "PAUSED"
                    else -> "CANCELLED"
                },
                statusDetail = detail,
                executionFailed = wasExecuting || cur.executionFailed,
                executionCompleted = if (wasExecuting) false else cur.executionCompleted,
                executionHeaderStatus = when {
                    !cur.hasExecutionTracker || updatedSteps.isEmpty() -> cur.executionHeaderStatus
                    markAsStopped -> "Stopped"
                    else -> "Skipped / Cancelled"
                },
                executionSteps = updatedSteps,
            )
        }
    }

    fun toggleSkipTrading() {
        var nextValue = false
        _snapshot.value = _snapshot.value.let { cur ->
            val next = !cur.skipTrading
            nextValue = next
            cur.copy(
                skipTrading = next,
                statusDetail = if (next) {
                    "Trade execution paused (Skip Trades active)"
                } else {
                    "Listening for signals or simulated trades"
                },
            )
        }
        _actions.tryEmit(OverlayActionEvent.SkipTradingChanged(nextValue))
    }

    fun emitAction(event: OverlayActionEvent) {
        _actions.tryEmit(event)
    }
}
