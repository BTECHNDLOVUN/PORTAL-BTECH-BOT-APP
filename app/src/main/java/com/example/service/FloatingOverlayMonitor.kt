package com.example.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.graphics.toColorInt
import com.example.MainActivity
import com.example.R
import com.example.webterminal.WebTerminalEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Foreground overlay Service that renders a draggable HTML-prototype floating window
 * over other app views for real-time account balance updates, minimize-to-bubble,
 * and collapsible execution logs.
 */
class FloatingOverlayMonitor : Service() {

    companion object {
        private const val CHANNEL_ID = "btech_balance_monitor"
        private const val NOTIFICATION_ID = 4103

        fun canDrawOverlays(context: Context): Boolean {
            return Settings.canDrawOverlays(context)
        }

        fun start(context: Context) {
            val intent = Intent(context, FloatingOverlayMonitor::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingOverlayMonitor::class.java))
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var windowManager: WindowManager

    private var cardView: View? = null
    private var cardParams: WindowManager.LayoutParams? = null
    private var bubbleView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null

    private var isMinimizedToBubble = false
    private var logsVisible = false

    private var tvTitle: TextView? = null
    private var tvOwner: TextView? = null
    private var tvServerLine: TextView? = null
    private var tvLiveBadge: TextView? = null
    private var tvBalanceValue: TextView? = null
    private var tvEquityValue: TextView? = null
    private var tvProfitValue: TextView? = null
    private var tvUpdatedLine: TextView? = null
    private var tvSkipBtn: TextView? = null
    private var tvLogsToggleBtn: TextView? = null
    private var tvLogConsole: TextView? = null
    private var logScrollView: ScrollView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()

        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), fgsType)

        if (!canDrawOverlays(this)) {
            OverlayStateBus.updateSnapshot {
                it.copy(
                    isBalanceMonitorRunning = false,
                    balanceStatus = "Overlay permission denied",
                )
            }
            stopSelf()
            return
        }

        runCatching {
            createHtmlFloatingPanel()
            createMinimizedBubble()
            OverlayStateBus.updateSnapshot { it.copy(isBalanceMonitorRunning = true) }
            WebTerminalEngine.getInstance(applicationContext).startBalancePolling()
            observeSnapshot()
        }.onFailure { err ->
            OverlayStateBus.appendLog("FloatingOverlayMonitor error: ${err.message}", isError = true)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        removeViewsSafely()
        OverlayStateBus.updateSnapshot { cur ->
            if (!cur.isRunning) {
                WebTerminalEngine.getInstance(applicationContext).stopBalancePolling()
            }
            cur.copy(isBalanceMonitorRunning = false)
        }
        super.onDestroy()
    }

    private fun observeSnapshot() {
        serviceScope.launch {
            OverlayStateBus.snapshot.collectLatest { snap ->
                tvTitle?.text = snap.botName
                tvOwner?.text = snap.botOwner
                val connLabel = if (snap.isConnected) {
                    getString(R.string.overlay_status_connected)
                } else {
                    getString(R.string.overlay_status_ready)
                }
                tvServerLine?.text = getString(R.string.overlay_server_status, connLabel, snap.serverName)
                tvLiveBadge?.text = getString(R.string.overlay_live_badge)
                tvBalanceValue?.text = snap.accountBalance
                tvEquityValue?.text = snap.accountEquity
                tvProfitValue?.text = snap.accountFloatingPl
                tvProfitValue?.setTextColor(
                    when {
                        snap.accountFloatingPl.startsWith("-") -> "#FF5966".toColorInt()
                        snap.accountFloatingPl == "—" -> "#E2E8F0".toColorInt()
                        else -> "#41D39A".toColorInt()
                    },
                )
                tvUpdatedLine?.text = if (snap.balanceUpdatedAt.isNotBlank()) {
                    getString(R.string.overlay_balance_updated, snap.balanceStatus, snap.balanceUpdatedAt)
                } else {
                    snap.balanceStatus
                }
                tvSkipBtn?.text = getString(
                    if (snap.skipTrading) R.string.overlay_resume_trading else R.string.overlay_skip_trades,
                )

                val logText = if (snap.logs.isEmpty()) {
                    getString(R.string.balance_monitor_console_idle, snap.serverName, snap.balanceStatus)
                } else {
                    snap.logs.takeLast(12).joinToString("\n") {
                        getString(R.string.overlay_log_entry, it.timestamp, it.message)
                    }
                }
                tvLogConsole?.text = logText
                logScrollView?.post { logScrollView?.fullScroll(View.FOCUS_DOWN) }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createHtmlFloatingPanel() {
        val density = resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        val cardWidth = (resources.displayMetrics.widthPixels - dp(24)).coerceAtMost(dp(344))

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor("#070B14".toColorInt())
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), "#526071".toColorInt())
            }
            clipToOutline = true
            elevation = 16 * density
        }

        // Full-bleed EA Hero Header matching HTML .float-panel-hero (~182dp, draggable)
        val hero = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(182))
            setBackgroundColor("#0B0E18".toColorInt())
        }
        val heroImg = ImageView(this).apply {
            setImageResource(R.drawable.astra_hero)
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        val scrimLeft = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf("#B8000000".toColorInt(), "#1E000000".toColorInt()),
            )
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        val scrimBottom = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf("#10000000".toColorInt(), "#B3000000".toColorInt()),
            )
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        hero.addView(heroImg)
        hero.addView(scrimLeft)
        hero.addView(scrimBottom)

        // Top-left identity lines (botName + botOwner)
        val titleStack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START,
            ).apply { setMargins(dp(15), dp(11), dp(48), 0) }
        }
        val titleTv = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val ownerTv = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(3), 0, 0)
        }
        titleStack.addView(titleTv)
        titleStack.addView(ownerTv)
        hero.addView(titleStack)
        tvTitle = titleTv
        tvOwner = ownerTv

        // Bottom-left glowing server connection status
        val serverTv = TextView(this).apply {
            setTextColor("#57E69D".toColorInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.START,
            ).apply { setMargins(dp(15), 0, dp(15), dp(12)) }
        }
        hero.addView(serverTv)
        tvServerLine = serverTv

        // Top-right close button (collapses back to circular bubble like HTML closeFloat)
        val closeBtn = TextView(this).apply {
            text = getString(R.string.overlay_close_symbol)
            contentDescription = "Minimize balance overlay to bubble"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            background = GradientDrawable().apply {
                setColor("#BB07101D".toColorInt())
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), "#55FFFFFF".toColorInt())
            }
            layoutParams = FrameLayout.LayoutParams(dp(28), dp(28), Gravity.TOP or Gravity.END).apply {
                setMargins(0, dp(9), dp(9), 0)
            }
            setOnClickListener { setMinimized(true) }
        }
        hero.addView(closeBtn)
        root.addView(hero)

        // Exact 3-item HTML controls bar: SKIP TRADES | SHOW/HIDE LOGS | ● LIVE
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor("#0B111C".toColorInt())
            setPadding(dp(10), dp(9), dp(10), dp(9))
        }
        val skipBtn = makeChip(getString(R.string.overlay_skip_trades), "#FFB04A", "#211305", null, dp(8)) {
            OverlayStateBus.toggleSkipTrading()
        }
        tvSkipBtn = skipBtn

        val logsBtn = makeChip(getString(R.string.overlay_show_logs), "#202C3C", "#E9F2FF", "#45546A", dp(8)) {
            logsVisible = !logsVisible
            logScrollView?.visibility = if (logsVisible) View.VISIBLE else View.GONE
            tvLogsToggleBtn?.text = getString(
                if (logsVisible) R.string.overlay_hide_logs else R.string.overlay_show_logs,
            )
        }
        tvLogsToggleBtn = logsBtn

        val liveBadge = TextView(this).apply {
            text = getString(R.string.overlay_live_badge)
            setTextColor("#68EDA6".toColorInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 8.5f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(8), dp(5), dp(8), dp(5))
            background = GradientDrawable().apply {
                setColor("#102E22".toColorInt())
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), "#2D8054".toColorInt())
            }
        }
        tvLiveBadge = liveBadge

        val spacer = View(this)
        controls.addView(skipBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(31)).apply { marginEnd = dp(7) })
        controls.addView(logsBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(31)).apply { marginEnd = dp(7) })
        controls.addView(spacer, LinearLayout.LayoutParams(0, 1, 1f))
        controls.addView(liveBadge)
        root.addView(controls)

        // Controls bottom divider
        val controlsDivider = View(this).apply {
            setBackgroundColor("#263447".toColorInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
        }
        root.addView(controlsDivider)

        // Body (.float-panel-body): Status Card (with Live Balance) + Collapsible Blue Console
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor("#080E19".toColorInt())
            setPadding(dp(10), dp(10), dp(10), dp(6))
        }

        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(11), dp(10), dp(11), dp(10))
            background = GradientDrawable().apply {
                setColor("#101B2B".toColorInt())
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), "#25384E".toColorInt())
            }
        }

        val statusHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val statusTitleTv = TextView(this).apply {
            text = getString(R.string.balance_monitor_title)
            setTextColor("#F1F5FB".toColorInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.DEFAULT_BOLD
        }
        val statusPill = TextView(this).apply {
            text = getString(R.string.balance_monitor_pill)
            setTextColor("#41D39A".toColorInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 8.5f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(7), dp(3), dp(7), dp(3))
            background = GradientDrawable().apply {
                setColor("#12372D".toColorInt())
                cornerRadius = dp(14).toFloat()
            }
        }
        statusHeader.addView(statusTitleTv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        statusHeader.addView(statusPill)
        statusCard.addView(statusHeader)

        val balanceValRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(5), 0, dp(4))
        }
        val balanceTv = TextView(this).apply {
            text = getString(R.string.overlay_placeholder_dash)
            setTextColor("#41D39A".toColorInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            typeface = Typeface.MONOSPACE
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val refreshBtn = TextView(this).apply {
            text = getString(R.string.overlay_refresh_symbol)
            contentDescription = "Refresh balance"
            gravity = Gravity.CENTER
            setTextColor("#68EDA6".toColorInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            background = GradientDrawable().apply {
                setColor("#1C2D3E".toColorInt())
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), "#345371".toColorInt())
            }
            setOnClickListener {
                OverlayStateBus.emitAction(OverlayActionEvent.RefreshBalanceRequested)
                WebTerminalEngine.getInstance(applicationContext).pollBalanceOnce()
            }
        }
        balanceValRow.addView(balanceTv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        balanceValRow.addView(refreshBtn, LinearLayout.LayoutParams(dp(26), dp(26)))
        statusCard.addView(balanceValRow)
        tvBalanceValue = balanceTv

        // Compact Equity & Floating P/L row
        val subRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val equityLbl = TextView(this).apply {
            text = getString(R.string.balance_monitor_equity_prefix)
            setTextColor("#9FB1C8".toColorInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        }
        val equityVal = TextView(this).apply {
            text = getString(R.string.overlay_placeholder_dash)
            setTextColor("#EEF4F7".toColorInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.MONOSPACE
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val plSpacer = TextView(this).apply {
            text = getString(R.string.balance_monitor_pl_prefix)
            setTextColor("#9FB1C8".toColorInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        }
        val plVal = TextView(this).apply {
            text = getString(R.string.overlay_placeholder_dash)
            setTextColor("#E2E8F0".toColorInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.MONOSPACE
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        subRow.addView(equityLbl)
        subRow.addView(equityVal)
        subRow.addView(plSpacer)
        subRow.addView(plVal)
        statusCard.addView(subRow)
        tvEquityValue = equityVal
        tvProfitValue = plVal

        val updatedTv = TextView(this).apply {
            text = getString(R.string.balance_monitor_waiting)
            setTextColor("#9FB1C8".toColorInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            setPadding(0, dp(4), 0, 0)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        statusCard.addView(updatedTv)
        tvUpdatedLine = updatedTv
        body.addView(statusCard)

        // Collapsible blue gradient console matching HTML .float-log
        val logScroll = ScrollView(this).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(126)).apply {
                topMargin = dp(8)
            }
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf("#0756E5".toColorInt(), "#0735AE".toColorInt()),
            ).apply {
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), "#347DFF".toColorInt())
            }
            setPadding(dp(11), dp(9), dp(11), dp(9))
        }
        val consoleTv = TextView(this).apply {
            setTextColor("#E9F3FF".toColorInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            typeface = Typeface.MONOSPACE
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        logScroll.addView(consoleTv)
        body.addView(logScroll)
        logScrollView = logScroll
        tvLogConsole = consoleTv
        root.addView(body)

        // Footer (.float-panel-footer): Powered by BTECH FINANCE + red STOP button
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor("#080E19".toColorInt())
            setPadding(dp(10), dp(6), dp(10), dp(10))
        }
        val footerCopy = TextView(this).apply {
            text = getString(R.string.overlay_powered_by)
            setTextColor("#8397AF".toColorInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 8.5f)
        }
        val stopBtn = makeChip(getString(R.string.overlay_stop_monitor), "#D92C40", "#FFFFFF", null, dp(8)) {
            OverlayStateBus.emitAction(OverlayActionEvent.StopBalanceMonitorRequested)
            stopSelf()
        }
        footer.addView(footerCopy, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        footer.addView(stopBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(28)))
        root.addView(footer)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            cardWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = ((resources.displayMetrics.widthPixels - cardWidth) / 2).coerceAtLeast(dp(10))
            y = (resources.displayMetrics.heightPixels * 0.14f).toInt()
        }

        attachDragListener(hero, root, params, cardWidth, dp(160))

        cardView = root
        cardParams = params
        windowManager.addView(root, params)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createMinimizedBubble() {
        val density = resources.displayMetrics.density
        val sizePx = (58 * density).toInt()
        val borderPx = (3 * density).toInt()

        val container = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor("#102743".toColorInt())
                setStroke(borderPx, Color.WHITE)
            }
            elevation = 12 * density
            clipToOutline = true
            visibility = View.GONE
        }

        val avatar = ImageView(this).apply {
            setImageResource(R.drawable.astra_hero)
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = "Expand floating balance monitor"
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL }
            clipToOutline = true
        }
        container.addView(
            avatar,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ).apply { setMargins(borderPx, borderPx, borderPx, borderPx) },
        )

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = resources.displayMetrics.widthPixels - sizePx - (18 * density).toInt()
            y = (resources.displayMetrics.heightPixels * 0.50f).toInt()
        }

        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var startX = 0
        var startY = 0
        var initX = 0f
        var initY = 0f
        var moved = false

        container.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    initX = event.rawX
                    initY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initX).toInt()
                    val dy = (event.rawY - initY).toInt()
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) moved = true
                    params.x = (startX + dx).coerceIn(0, resources.displayMetrics.widthPixels - sizePx)
                    params.y = (startY + dy).coerceIn(0, resources.displayMetrics.heightPixels - sizePx)
                    if (container.isAttachedToWindow) {
                        runCatching { windowManager.updateViewLayout(container, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) setMinimized(false)
                    true
                }
                else -> false
            }
        }

        bubbleView = container
        bubbleParams = params
        windowManager.addView(container, params)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachDragListener(
        handle: View,
        target: View,
        params: WindowManager.LayoutParams,
        viewWidth: Int,
        minBottomGuardPx: Int,
    ) {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false

        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) moved = true
                    params.x = (startX + dx).coerceIn(0, (resources.displayMetrics.widthPixels - viewWidth).coerceAtLeast(0))
                    params.y = (startY + dy).coerceIn(0, (resources.displayMetrics.heightPixels - minBottomGuardPx).coerceAtLeast(0))
                    if (target.isAttachedToWindow) {
                        runCatching { windowManager.updateViewLayout(target, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> moved
                else -> false
            }
        }
    }

    private fun makeChip(
        label: String,
        bgHex: String,
        fgHex: String,
        strokeHex: String?,
        radiusPx: Int,
        onClick: () -> Unit,
    ): TextView {
        val dp = resources.displayMetrics.density
        return TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(fgHex.toColorInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding((10 * dp).toInt(), (4 * dp).toInt(), (10 * dp).toInt(), (4 * dp).toInt())
            background = GradientDrawable().apply {
                setColor(bgHex.toColorInt())
                cornerRadius = radiusPx.toFloat()
                if (strokeHex != null) {
                    setStroke((1 * dp).toInt(), strokeHex.toColorInt())
                }
            }
            setOnClickListener { onClick() }
        }
    }

    private fun setMinimized(minimized: Boolean) {
        isMinimizedToBubble = minimized
        cardView?.visibility = if (minimized) View.GONE else View.VISIBLE
        bubbleView?.visibility = if (minimized) View.VISIBLE else View.GONE
    }

    private fun removeViewsSafely() {
        cardView?.let { runCatching { windowManager.removeView(it) } }
        bubbleView?.let { runCatching { windowManager.removeView(it) } }
        cardView = null
        bubbleView = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.overlay_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            1,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .setContentTitle(getString(R.string.balance_monitor_notification_title))
            .setContentText(getString(R.string.balance_monitor_notification_text))
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
