package com.example.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
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
import android.widget.ProgressBar
import android.widget.TextView
import android.content.res.ColorStateList
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.graphics.toColorInt
import com.example.MainActivity
import com.example.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs

class BtechOverlayService : Service() {

    companion object {
        private const val CHANNEL_ID = "btech_overlay_monitor"
        private const val NOTIFICATION_ID = 4102

        fun canDrawOverlays(context: Context): Boolean {
            return Settings.canDrawOverlays(context)
        }

        fun start(context: Context) {
            val intent = Intent(context, BtechOverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BtechOverlayService::class.java))
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var windowManager: WindowManager

    private var bubbleView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null

    private var panelView: View? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var isPanelExpanded = false
    private var logsVisible = true

    // Dynamic panel views
    private var tvTitle: TextView? = null
    private var tvOwner: TextView? = null
    private var tvServerLine: TextView? = null
    private var tvSkipBtn: TextView? = null
    private var tvLogsToggleBtn: TextView? = null
    private var tvStatusPill: TextView? = null
    private var tvStatusTitle: TextView? = null
    private var tvStatusDetail: TextView? = null
    private var tvBalanceLine: TextView? = null
    private var blueExecCard: LinearLayout? = null
    private var tvExecTitle: TextView? = null
    private var tvExecHeaderRight: TextView? = null
    private var execProgressBar: ProgressBar? = null
    private var execStepsContainer: LinearLayout? = null
    private var tvExecDetail: TextView? = null
    private var lastRenderedStepSignature: String? = null

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
                it.copy(isRunning = false, statusDetail = "Overlay permission denied")
            }
            stopSelf()
            return
        }

        runCatching {
            createBubbleView()
            createPanelView()
            observeSnapshot()
        }.onFailure { err ->
            OverlayStateBus.appendLog("Overlay init error: ${err.message}", isError = true)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        removeViewsSafely()
        super.onDestroy()
    }

    private fun observeSnapshot() {
        val density = resources.displayMetrics.density
        serviceScope.launch {
            OverlayStateBus.snapshot.collectLatest { snap ->
                tvTitle?.text = snap.botName
                tvOwner?.text = snap.botOwner
                tvServerLine?.text = getString(
                    R.string.overlay_server_status,
                    getString(if (snap.isConnected) R.string.overlay_status_connected else R.string.overlay_status_ready),
                    snap.serverName,
                )
                tvSkipBtn?.text = getString(
                    if (snap.skipTrading) R.string.overlay_resume_trading else R.string.overlay_skip_trades,
                )
                tvStatusTitle?.text = snap.statusTitle
                tvStatusDetail?.text = snap.statusDetail
                tvStatusPill?.text = snap.statusPill
                val balTime = if (snap.balanceUpdatedAt.isNotBlank()) " · ${snap.balanceUpdatedAt}" else ""
                tvBalanceLine?.text = getString(
                    R.string.overlay_balance_summary,
                    snap.accountBalance,
                    snap.accountEquity,
                    snap.accountFloatingPl,
                    balTime,
                )

                val cyanActive = "#7CE8FF".toColorInt()
                val greenColor = "#28D17C".toColorInt()
                val greenBarColor = "#39E58C".toColorInt()
                val errorColor = "#FF5263".toColorInt()
                val errorTextColor = "#FFB4BC".toColorInt()
                val lightBlueMuted = "#DCEBFF".toColorInt()

                val isIdle = !snap.hasExecutionTracker || snap.executionSteps.isEmpty()

                blueExecCard?.visibility = if (logsVisible) View.VISIBLE else View.GONE
                tvExecTitle?.text = getString(R.string.overlay_exec_header)
                tvExecTitle?.setTextColor(Color.WHITE)

                val headerRight = if (isIdle) "" else snap.executionHeaderStatus
                tvExecHeaderRight?.text = headerRight
                tvExecHeaderRight?.visibility = if (headerRight.isEmpty()) View.GONE else View.VISIBLE
                tvExecHeaderRight?.setTextColor(if (snap.executionFailed) errorTextColor else lightBlueMuted)

                val barColor = when {
                    snap.executionFailed -> errorColor
                    snap.executionCompleted -> greenBarColor
                    else -> cyanActive
                }
                execProgressBar?.apply {
                    max = 100
                    progress = (snap.executionProgress * 100f).toInt().coerceIn(0, 100)
                    progressTintList = ColorStateList.valueOf(barColor)
                }

                val stepSignature = if (isIdle) {
                    "IDLE"
                } else {
                    snap.executionSteps.joinToString("|") { "${it.key}:${it.status}:${it.label}" }
                }

                if (stepSignature != lastRenderedStepSignature) {
                    lastRenderedStepSignature = stepSignature
                    val container = execStepsContainer
                    container?.removeAllViews()
                    if (container != null) {
                        fun dpInt(v: Int): Int = (v * density).toInt()
                        if (isIdle) {
                            val idleTv = TextView(this@BtechOverlayService).apply {
                                text = getString(R.string.overlay_waiting_new_signal)
                                setTextColor(lightBlueMuted)
                                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                                typeface = Typeface.DEFAULT_BOLD
                                maxLines = 1
                                ellipsize = TextUtils.TruncateAt.END
                                setPadding(0, dpInt(2), 0, dpInt(2))
                            }
                            container.addView(idleTv)
                        } else {
                            val badgePx = dpInt(15)
                            snap.executionSteps.forEach { step ->
                                val row = LinearLayout(this@BtechOverlayService).apply {
                                    orientation = LinearLayout.HORIZONTAL
                                    gravity = Gravity.CENTER_VERTICAL
                                    setPadding(0, dpInt(2), 0, dpInt(2))
                                }
                                val iconSlot = FrameLayout(this@BtechOverlayService).apply {
                                    layoutParams = LinearLayout.LayoutParams(dpInt(17), dpInt(17)).apply {
                                        marginEnd = dpInt(7)
                                    }
                                }
                                val labelTv = TextView(this@BtechOverlayService).apply {
                                    text = step.label
                                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                                    maxLines = 1
                                    ellipsize = TextUtils.TruncateAt.END
                                }
                                when (step.status) {
                                    ExecutionStepStatus.COMPLETED -> {
                                        val badge = TextView(this@BtechOverlayService).apply {
                                            text = getString(R.string.overlay_step_check)
                                            gravity = Gravity.CENTER
                                            setTextColor(Color.WHITE)
                                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
                                            typeface = Typeface.DEFAULT_BOLD
                                            background = GradientDrawable().apply {
                                                shape = GradientDrawable.OVAL
                                                setColor(greenColor)
                                            }
                                            layoutParams = FrameLayout.LayoutParams(badgePx, badgePx, Gravity.CENTER)
                                        }
                                        iconSlot.addView(badge)
                                        labelTv.setTextColor("#EAF3FF".toColorInt())
                                        labelTv.typeface = Typeface.DEFAULT
                                    }
                                    ExecutionStepStatus.ACTIVE -> {
                                        val spinner = ProgressBar(this@BtechOverlayService, null, android.R.attr.progressBarStyleSmall).apply {
                                            isIndeterminate = true
                                            indeterminateTintList = ColorStateList.valueOf(Color.WHITE)
                                            layoutParams = FrameLayout.LayoutParams(badgePx, badgePx, Gravity.CENTER)
                                        }
                                        iconSlot.addView(spinner)
                                        labelTv.setTextColor(Color.WHITE)
                                        labelTv.typeface = Typeface.DEFAULT_BOLD
                                    }
                                    ExecutionStepStatus.ERROR -> {
                                        val badge = TextView(this@BtechOverlayService).apply {
                                            text = getString(R.string.overlay_step_cross)
                                            gravity = Gravity.CENTER
                                            setTextColor(Color.WHITE)
                                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
                                            typeface = Typeface.DEFAULT_BOLD
                                            background = GradientDrawable().apply {
                                                shape = GradientDrawable.OVAL
                                                setColor(errorColor)
                                            }
                                            layoutParams = FrameLayout.LayoutParams(badgePx, badgePx, Gravity.CENTER)
                                        }
                                        iconSlot.addView(badge)
                                        labelTv.setTextColor(errorTextColor)
                                        labelTv.typeface = Typeface.DEFAULT_BOLD
                                    }
                                    ExecutionStepStatus.PENDING -> {
                                        val ringPx = dpInt(12)
                                        val ring = View(this@BtechOverlayService).apply {
                                            background = GradientDrawable().apply {
                                                shape = GradientDrawable.OVAL
                                                setColor(Color.TRANSPARENT)
                                                setStroke((1.5f * density).toInt(), "#66FFFFFF".toColorInt())
                                            }
                                            layoutParams = FrameLayout.LayoutParams(ringPx, ringPx, Gravity.CENTER)
                                        }
                                        iconSlot.addView(ring)
                                        labelTv.setTextColor("#99DCEBFF".toColorInt())
                                        labelTv.typeface = Typeface.DEFAULT
                                    }
                                }
                                row.addView(iconSlot)
                                row.addView(labelTv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                                container.addView(row)
                            }
                        }
                    }
                }

                if (isIdle) {
                    tvExecDetail?.visibility = View.GONE
                } else {
                    tvExecDetail?.visibility = View.VISIBLE
                    tvExecDetail?.text = snap.statusDetail
                    tvExecDetail?.setTextColor(if (snap.executionFailed) errorTextColor else "#EAF3FF".toColorInt())
                    tvExecDetail?.background = GradientDrawable().apply {
                        setColor("#52021442".toColorInt())
                        cornerRadius = (7 * density)
                        setStroke((1 * density).toInt(), if (snap.executionFailed) "#FF6B78".toColorInt() else "#5A9BFF".toColorInt())
                    }
                }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createBubbleView() {
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
        }

        val avatar = ImageView(this).apply {
            setImageResource(R.drawable.astra_hero)
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = "Open BTECH floating monitor"
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL }
            clipToOutline = true
        }
        val innerParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ).apply {
            setMargins(borderPx, borderPx, borderPx, borderPx)
        }
        container.addView(avatar, innerParams)

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
            x = (resources.displayMetrics.widthPixels - sizePx - (18 * density).toInt())
            y = (resources.displayMetrics.heightPixels * 0.62f).toInt()
        }

        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var startX = 0
        var startY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var moved = false

        container.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                        moved = true
                    }
                    params.x = (startX + dx).coerceIn(0, resources.displayMetrics.widthPixels - sizePx)
                    params.y = (startY + dy).coerceIn(0, resources.displayMetrics.heightPixels - sizePx)
                    windowManager.updateViewLayout(container, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        togglePanel()
                    }
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
    private fun createPanelView() {
        val density = resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        val panelWidth = (resources.displayMetrics.widthPixels - dp(28)).coerceAtMost(dp(356))

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

        // Hero Header (draggable, matches HTML prototype .float-panel-hero: ~170dp)
        val hero = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(170))
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
        hero.addView(heroImg)

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
        hero.addView(scrimLeft)
        hero.addView(scrimBottom)

        val titleStack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START,
            ).apply { setMargins(dp(14), dp(12), dp(48), 0) }
        }
        val titleTv = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val ownerTv = TextView(this).apply {
            setTextColor("#E2E5EB".toColorInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            maxLines = 1
        }
        titleStack.addView(titleTv)
        titleStack.addView(ownerTv)
        hero.addView(titleStack)
        tvTitle = titleTv
        tvOwner = ownerTv

        val serverTv = TextView(this).apply {
            setTextColor("#57E69D".toColorInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.START,
            ).apply { setMargins(dp(14), 0, dp(14), dp(12)) }
        }
        hero.addView(serverTv)
        tvServerLine = serverTv

        val closeBtn = TextView(this).apply {
            text = getString(R.string.overlay_close_symbol)
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
            setOnClickListener { hidePanel() }
        }
        hero.addView(closeBtn)
        root.addView(hero)

        // Controls row: SKIP TRADES | HIDE LOGS | ● LIVE (exact 3-item HTML layout)
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor("#0B111C".toColorInt())
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }

        val skipBtn = makeControlChip(getString(R.string.overlay_skip_trades), "#FFB04A", "#211305", dp(8)) {
            OverlayStateBus.toggleSkipTrading()
        }
        tvSkipBtn = skipBtn

        val logsBtn = makeControlChip(getString(R.string.overlay_hide_logs), "#202C3C", "#E9F2FF", dp(8)) {
            logsVisible = !logsVisible
            blueExecCard?.visibility = if (logsVisible) View.VISIBLE else View.GONE
            tvLogsToggleBtn?.text = getString(
                if (logsVisible) R.string.overlay_hide_logs else R.string.overlay_show_logs,
            )
        }
        tvLogsToggleBtn = logsBtn

        val liveBadge = TextView(this).apply {
            text = getString(R.string.overlay_live_badge)
            setTextColor("#68EDA6".toColorInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(9), dp(5), dp(9), dp(5))
            background = GradientDrawable().apply {
                setColor("#102E22".toColorInt())
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), "#2D8054".toColorInt())
            }
        }

        val spacer = View(this)
        controls.addView(skipBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(31)).apply { marginEnd = dp(7) })
        controls.addView(logsBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(31)).apply { marginEnd = dp(7) })
        controls.addView(spacer, LinearLayout.LayoutParams(0, 1, 1f))
        controls.addView(liveBadge)
        root.addView(controls)

        val controlsDivider = View(this).apply {
            setBackgroundColor("#263447".toColorInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
        }
        root.addView(controlsDivider)

        // Body: Compact Status + Balance Card followed by the Blue Trade Execution Card
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor("#080E19".toColorInt())
            setPadding(dp(10), dp(10), dp(10), dp(6))
        }

        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
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
            setTextColor("#F1F5FB".toColorInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val statusPillTv = TextView(this).apply {
            setTextColor("#41D39A".toColorInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 8.5f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(8), dp(2), dp(8), dp(2))
            background = GradientDrawable().apply {
                setColor("#12372D".toColorInt())
                cornerRadius = dp(14).toFloat()
            }
        }
        statusHeader.addView(statusTitleTv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        statusHeader.addView(statusPillTv)

        val statusDetailTv = TextView(this).apply {
            setTextColor("#9FB1C8".toColorInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
            setPadding(0, dp(3), 0, 0)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val balanceLineTv = TextView(this).apply {
            text = getString(R.string.overlay_balance_summary_placeholder)
            setTextColor("#41D39A".toColorInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(4), 0, 0)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        statusCard.addView(statusHeader)
        statusCard.addView(statusDetailTv)
        statusCard.addView(balanceLineTv)
        body.addView(statusCard)
        tvStatusTitle = statusTitleTv
        tvStatusPill = statusPillTv
        tvStatusDetail = statusDetailTv
        tvBalanceLine = balanceLineTv

        // Blue Gradient Window: Animated Trade Execution Tracker replacing raw logs
        val execBlueCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(9), dp(10), dp(9))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf("#0756E5".toColorInt(), "#0735AE".toColorInt()),
            ).apply {
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), "#347DFF".toColorInt())
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            }
        }
        val execHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val execTitleTv = TextView(this).apply {
            text = getString(R.string.overlay_exec_header)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
        }
        val execRightTv = TextView(this).apply {
            text = getString(R.string.overlay_exec_waiting)
            setTextColor("#DCEBFF".toColorInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.END
        }
        execHeader.addView(execTitleTv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        execHeader.addView(execRightTv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(8)
        })
        execBlueCard.addView(execHeader)

        val execBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            progressTintList = ColorStateList.valueOf("#7CE8FF".toColorInt())
            progressBackgroundTintList = ColorStateList.valueOf("#45FFFFFF".toColorInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(4)).apply {
                topMargin = dp(6)
                bottomMargin = dp(7)
            }
        }
        execBlueCard.addView(execBar)

        val stepsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        execBlueCard.addView(stepsContainer)
        lastRenderedStepSignature = null

        val execDetailTv = TextView(this).apply {
            text = getString(R.string.overlay_exec_ready)
            setTextColor("#EAF3FF".toColorInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(9), dp(6), dp(9), dp(6))
            background = GradientDrawable().apply {
                setColor("#52021442".toColorInt())
                cornerRadius = dp(7).toFloat()
                setStroke(dp(1), "#5A9BFF".toColorInt())
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(7)
            }
            visibility = View.GONE
        }
        execBlueCard.addView(execDetailTv)
        body.addView(execBlueCard)
        blueExecCard = execBlueCard
        tvExecTitle = execTitleTv
        tvExecHeaderRight = execRightTv
        execProgressBar = execBar
        execStepsContainer = stepsContainer
        tvExecDetail = execDetailTv
        root.addView(body)

        // Footer
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
        val stopBtn = makeControlChip(getString(R.string.overlay_stop_robot), "#D92C40", "#FFFFFF", dp(8)) {
            OverlayStateBus.emitAction(OverlayActionEvent.StopBotRequested)
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
            panelWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = ((resources.displayMetrics.widthPixels - panelWidth) / 2).coerceAtLeast(dp(10))
            y = (resources.displayMetrics.heightPixels * 0.16f).toInt()
        }

        // Drag panel by hero header
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        hero.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = (startX + (event.rawX - touchX).toInt())
                        .coerceIn(0, (resources.displayMetrics.widthPixels - panelWidth).coerceAtLeast(0))
                    params.y = (startY + (event.rawY - touchY).toInt())
                        .coerceIn(0, resources.displayMetrics.heightPixels - dp(120))
                    windowManager.updateViewLayout(root, params)
                    true
                }
                else -> false
            }
        }

        root.visibility = View.GONE
        panelView = root
        panelParams = params
        windowManager.addView(root, params)
    }

    private fun makeControlChip(
        label: String,
        bgHex: String,
        fgHex: String,
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
            setPadding((8 * dp).toInt(), (4 * dp).toInt(), (8 * dp).toInt(), (4 * dp).toInt())
            background = GradientDrawable().apply {
                setColor(bgHex.toColorInt())
                cornerRadius = radiusPx.toFloat()
            }
            setOnClickListener { onClick() }
        }
    }

    private fun togglePanel() {
        if (isPanelExpanded) hidePanel() else showPanel()
    }

    private fun showPanel() {
        isPanelExpanded = true
        panelView?.visibility = View.VISIBLE
    }

    private fun hidePanel() {
        isPanelExpanded = false
        panelView?.visibility = View.GONE
    }

    private fun removeViewsSafely() {
        bubbleView?.let { runCatching { windowManager.removeView(it) } }
        panelView?.let { runCatching { windowManager.removeView(it) } }
        bubbleView = null
        panelView = null
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
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .setContentTitle(getString(R.string.overlay_notification_title))
            .setContentText(getString(R.string.overlay_notification_text))
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
