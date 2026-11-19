package com.example.ui

import android.content.Intent
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.core.graphics.toColorInt
import androidx.core.net.toUri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import com.example.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.model.BtechRoutes
import com.example.service.OverlayStateBus
import com.example.ui.components.BtechBottomNav
import com.example.ui.components.BtechFloatingOverlay
import com.example.ui.components.BtechPrimaryButton
import com.example.ui.components.BtechToastBanner
import com.example.ui.components.BtechTopBar
import com.example.ui.screens.AddRobotScreen
import com.example.ui.screens.BrokerLoginScreen
import com.example.ui.screens.ExecutionLogsScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.MetaBrokerListScreen
import com.example.ui.screens.ProfileScreen
import com.example.ui.screens.RobotInfoScreen
import com.example.ui.screens.SavedAccountScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.SimulateTradeDialog
import com.example.ui.screens.SymbolConfigScreen
import com.example.ui.screens.SymbolsScreen
import com.example.ui.screens.WebTerminalInspectorDialog
import com.example.ui.theme.BtechDefaultAccent
import com.example.ui.theme.BtechFinanceTheme
import com.example.viewmodel.BtechViewModel
import kotlinx.coroutines.delay

@Composable
fun BtechApp(viewModel: BtechViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val webStatus by viewModel.webStatus.collectAsStateWithLifecycle()
    val overlaySnapshot by OverlayStateBus.snapshot.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: BtechRoutes.HOME

    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        viewModel.onOverlayPermissionReturned()
    }

    var showSplash by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        delay(1600)
        showSplash = false
    }

    LaunchedEffect(state.toastMessage) {
        if (state.toastMessage != null) {
            delay(3200)
            viewModel.dismissToast()
        }
    }

    val accentColor = runCatching {
        Color(state.accentHex.toColorInt())
    }.getOrDefault(BtechDefaultAccent)

    val topRoutes = setOf(
        BtechRoutes.HOME,
        BtechRoutes.META,
        BtechRoutes.ADD_ROBOT,
        BtechRoutes.PROFILE,
        BtechRoutes.SETTINGS,
    )
    val navHighlightRoute = when (currentRoute) {
        BtechRoutes.BROKER_LOGIN, BtechRoutes.SAVED_ACCOUNT -> BtechRoutes.META
        BtechRoutes.SYMBOLS, BtechRoutes.SYMBOL_CONFIG, BtechRoutes.INFO, BtechRoutes.LOGS -> BtechRoutes.HOME
        else -> currentRoute
    }
    val isHomeWithHero = currentRoute == BtechRoutes.HOME && state.hasRobot

    BtechFinanceTheme(
        darkTheme = state.isDarkTheme,
        accentColor = accentColor,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Keep WebView attached to an active non-zero Window viewport once loaded or during
            // background auth/trading/balance monitoring so Chromium timers and getBoundingClientRect() never throttle.
            if (!state.showWebInspector && (state.isAuthBusy || state.isSimulatingTrade || state.isBalanceMonitorRunning || state.showInAppFloatingOverlay || webStatus.isLoading || webStatus.isPageReady || webStatus.currentUrl.isNotEmpty())) {
                BackgroundWebViewHost(
                    webEngine = viewModel.webEngine,
                    modifier = Modifier
                        .requiredSize(1024.dp, 800.dp)
                        .alpha(0f),
                )
            }

            Scaffold(
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                BtechBottomNav(
                    currentRoute = navHighlightRoute,
                    hasRobot = state.hasRobot,
                    navStyle = state.navStyle,
                    onNavigate = { target ->
                        navController.navigate(target) {
                            popUpTo(BtechRoutes.HOME) { saveState = true }
                            launchSingleTop = true
                            restoreState = target in topRoutes
                        }
                    },
                )
            },
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = innerPadding.calculateBottomPadding()),
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    if (!isHomeWithHero) {
                        BtechTopBar(
                            overHero = false,
                            onNotificationClick = { viewModel.showToast("No new notifications") },
                        )
                    }

                    Box(modifier = Modifier.weight(1f)) {
                        NavHost(
                            navController = navController,
                            startDestination = BtechRoutes.HOME,
                        ) {
                            composable(BtechRoutes.HOME) {
                                HomeScreen(
                                    state = state,
                                    onAddRobotClick = { navController.navigate(BtechRoutes.ADD_ROBOT) },
                                    onToggleBot = viewModel::toggleBot,
                                    onSimulateTradeClick = viewModel::openSimulateDialog,
                                    onSymbolsClick = { navController.navigate(BtechRoutes.SYMBOLS) },
                                    onInfoClick = { navController.navigate(BtechRoutes.INFO) },
                                    onLogsClick = { navController.navigate(BtechRoutes.LOGS) },
                                    onOpenWebTerminal = { viewModel.openWebInspector() },
                                    onToggleBalanceMonitor = viewModel::toggleFloatingBalanceMonitor,
                                    onRefreshBalance = { viewModel.webEngine.pollBalanceOnce() },
                                )
                            }

                            composable(BtechRoutes.ADD_ROBOT) {
                                AddRobotScreen(
                                    onAuthenticate = { key, onDone ->
                                        viewModel.authenticateRobot(key, onDone)
                                    },
                                    onBack = {
                                        navController.navigate(BtechRoutes.HOME) {
                                            popUpTo(BtechRoutes.HOME) { inclusive = true }
                                        }
                                    },
                                )
                            }

                            composable(BtechRoutes.SYMBOLS) {
                                SymbolsScreen(
                                    state = state,
                                    onTabChange = viewModel::setSymbolTab,
                                    onOpenSymbolConfig = { sym ->
                                        viewModel.startEditingSymbol(sym)
                                        navController.navigate(BtechRoutes.SYMBOL_CONFIG)
                                    },
                                    onBack = { navController.popBackStack() },
                                )
                            }

                            composable(BtechRoutes.SYMBOL_CONFIG) {
                                val sym = state.editingSymbol ?: "XAUUSD"
                                val existing = state.allowedSymbols.firstOrNull { it.symbol == sym }
                                SymbolConfigScreen(
                                    symbol = sym,
                                    existingConfig = existing,
                                    onSave = { cfg ->
                                        viewModel.saveSymbolConfig(cfg) {
                                            navController.popBackStack()
                                        }
                                    },
                                    onDelete = { s ->
                                        viewModel.removeAllowedSymbol(s)
                                        navController.popBackStack()
                                    },
                                    onCancel = { navController.popBackStack() },
                                )
                            }

                            composable(BtechRoutes.INFO) {
                                RobotInfoScreen(
                                    state = state,
                                    onRemoveRobot = {
                                        viewModel.removeRobot {
                                            navController.navigate(BtechRoutes.HOME) {
                                                popUpTo(BtechRoutes.HOME) { inclusive = true }
                                            }
                                        }
                                    },
                                    onBack = { navController.popBackStack() },
                                )
                            }

                            composable(BtechRoutes.META) {
                                MetaBrokerListScreen(
                                    onSelectBroker = { name ->
                                        viewModel.selectBroker(name)
                                        navController.navigate(BtechRoutes.BROKER_LOGIN)
                                    },
                                    onOpenSavedDetails = { navController.navigate(BtechRoutes.SAVED_ACCOUNT) },
                                )
                            }

                            composable(BtechRoutes.BROKER_LOGIN) {
                                BrokerLoginScreen(
                                    state = state,
                                    webStatus = webStatus,
                                    resolvedTerminalUrl = viewModel.resolvedTerminalInfo()?.third,
                                    onPlatformChange = viewModel::setPlatform,
                                    onLoginChange = viewModel::setLoginInput,
                                    onPasswordChange = viewModel::setPasswordInput,
                                    onServerIdChange = viewModel::setSelectedServerId,
                                    onMt5ServerChange = viewModel::setMt5ServerInput,
                                    onMt4ServerChange = viewModel::setMt4ServerInput,
                                    onCustomUrlChange = viewModel::setCustomTerminalUrl,
                                    onAuthenticateClick = viewModel::authenticateBrokerAccount,
                                    onCancelAuth = viewModel::cancelAuthBusy,
                                    onInspectWebTerminal = { url ->
                                        viewModel.cancelAuthBusy()
                                        viewModel.openWebInspector(url)
                                    },
                                    onDismissAuthResult = viewModel::dismissAuthDialog,
                                    onAuthSuccessContinue = {
                                        navController.navigate(BtechRoutes.SAVED_ACCOUNT) {
                                            popUpTo(BtechRoutes.META)
                                        }
                                    },
                                    onRemoveSavedAccount = { viewModel.removeSavedAccount() },
                                    onBack = { navController.popBackStack() },
                                )
                            }

                            composable(BtechRoutes.SAVED_ACCOUNT) {
                                SavedAccountScreen(
                                    state = state,
                                    onChooseBroker = { navController.popBackStack(BtechRoutes.META, false) },
                                    onEditCredentials = { navController.navigate(BtechRoutes.BROKER_LOGIN) },
                                    onInspectWebTerminal = { viewModel.openWebInspector() },
                                    onToggleBalanceMonitor = viewModel::toggleFloatingBalanceMonitor,
                                    onRemoveSavedAccount = { viewModel.removeSavedAccount() },
                                    onBack = { navController.popBackStack() },
                                )
                            }

                            composable(BtechRoutes.PROFILE) {
                                ProfileScreen(
                                    state = state,
                                    onEditName = viewModel::setProfileName,
                                    onToggleTheme = viewModel::setDarkTheme,
                                    onSelectAccentHex = viewModel::setAccentHex,
                                    onSaveCustomColors = viewModel::saveCustomColors,
                                    onSelectNavStyle = viewModel::setNavStyle,
                                )
                            }

                            composable(BtechRoutes.SETTINGS) {
                                SettingsScreen(
                                    onOpenLogs = { navController.navigate(BtechRoutes.LOGS) },
                                    onOpenWebTerminal = { viewModel.openWebInspector() },
                                    onShowMessage = { msg -> viewModel.showToast(msg) },
                                )
                            }

                            composable(BtechRoutes.LOGS) {
                                ExecutionLogsScreen(
                                    state = state,
                                    onSimulateClick = viewModel::openSimulateDialog,
                                    onInspectWebTerminal = { viewModel.openWebInspector() },
                                    onClearLogs = { OverlayStateBus.clearLogs() },
                                    onShowMessage = { msg -> viewModel.showToast(msg) },
                                    onBack = { navController.popBackStack() },
                                )
                            }
                        }
                    }
                }

                // Floating top bar overlay when Hero Artwork is visible
                if (isHomeWithHero) {
                    BtechTopBar(
                        overHero = true,
                        onNotificationClick = { viewModel.showToast("No new notifications") },
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                }

                if (state.showInAppFloatingOverlay && !state.isBalanceMonitorRunning) {
                    BtechFloatingOverlay(
                        snapshot = overlaySnapshot,
                        onToggleSkipTrades = { OverlayStateBus.toggleSkipTrading() },
                        onRefreshBalance = { viewModel.webEngine.pollBalanceOnce() },
                        onStop = {
                            if (state.isRunning) {
                                viewModel.stopBot()
                            } else {
                                viewModel.dismissInAppFloatingOverlay()
                            }
                        },
                    )
                }

                BtechToastBanner(
                    message = state.toastMessage,
                    isError = state.toastIsError,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp),
                )
            }
        }

            AnimatedVisibility(
                visible = showSplash,
                exit = fadeOut(animationSpec = tween(durationMillis = 400)),
                modifier = Modifier.zIndex(100f),
            ) {
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFFF6F7FC))
                        .testTag("splash_screen_overlay"),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.img_btech_splash),
                        contentDescription = stringResource(id = R.string.app_name),
                        contentScale = if (maxHeight > maxWidth) ContentScale.Crop else ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Text(
                        text = buildAnnotatedString {
                            withStyle(SpanStyle(color = Color(0xFF0E1B3A))) {
                                append("BTECH ")
                            }
                            withStyle(SpanStyle(color = Color(0xFF5438EE))) {
                                append("FINANCE")
                            }
                        },
                        fontSize = 15.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.6.sp,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Visible,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(
                                bottom = if (maxHeight > maxWidth) (maxHeight * 0.21f) else 24.dp,
                            ),
                    )
                }
            }

            if (state.showOverlayPermissionPrompt) {
                AlertDialog(
                    onDismissRequest = viewModel::dismissOverlayPermissionPrompt,
                    containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surface,
                    title = { Text("Allow Display Over Other Apps", fontWeight = FontWeight.Bold) },
                    text = {
                        Text(
                            "BTECH FINANCE needs Android's overlay permission so the floating robot bubble and live balance monitor stay visible over MT4/MT5 and other apps. You can also use In-App Floating immediately."
                        )
                    },
                    confirmButton = {
                        BtechPrimaryButton(
                            text = "OPEN OVERLAY SETTINGS",
                            onClick = {
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    "package:${context.packageName}".toUri(),
                                )
                                overlayPermissionLauncher.launch(intent)
                            },
                        )
                    },
                    dismissButton = {
                        Column {
                            TextButton(onClick = viewModel::enableInAppFloatingFallback) {
                                Text("USE IN-APP FLOATING")
                            }
                            TextButton(onClick = viewModel::dismissOverlayPermissionPrompt) {
                                Text("CANCEL")
                            }
                        }
                    },
                )
            }

            if (state.showSimulateDialog) {
                SimulateTradeDialog(
                    state = state,
                    onDismiss = viewModel::closeSimulateDialog,
                    onConfirmSimulate = viewModel::executeSimulatedTrade,
                )
            }

            if (state.showWebInspector) {
                WebTerminalInspectorDialog(
                    webEngine = viewModel.webEngine,
                    webStatus = webStatus,
                    onConfirmSession = viewModel::confirmManualWebSession,
                    onDismiss = viewModel::closeWebInspector,
                )
            }
        }
    }
}

@Composable
private fun BackgroundWebViewHost(
    webEngine: com.example.webterminal.WebTerminalEngine,
    modifier: Modifier = Modifier,
) {
    var hostContainer by remember { mutableStateOf<FrameLayout?>(null) }

    DisposableEffect(hostContainer) {
        val container = hostContainer
        onDispose {
            if (container != null) {
                webEngine.detachIfParent(container)
            }
        }
    }

    AndroidView(
        factory = { ctx ->
            object : FrameLayout(ctx) {
                override fun dispatchTouchEvent(ev: MotionEvent?): Boolean = false
                override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean = true
            }.apply {
                isFocusable = false
                isFocusableInTouchMode = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                hostContainer = this
                webEngine.attachToContainer(this, ctx)
            }
        },
        update = { container ->
            webEngine.attachToContainer(container, container.context)
        },
        modifier = modifier,
    )
}
