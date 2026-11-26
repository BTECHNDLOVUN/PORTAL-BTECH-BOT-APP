package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.model.BtechRoutes
import com.example.model.NavStyle
import com.example.service.ExecutionStepStatus
import com.example.service.OverlaySnapshot
import com.example.ui.theme.BtechTerminalBlue
import com.example.ui.theme.BtechTerminalBlueBorder
import com.example.ui.theme.BtechTerminalBlueDeep
import com.example.ui.theme.LocalBtechColors

@Composable
fun BtechTopBar(
    overHero: Boolean,
    onNotificationClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val extra = LocalBtechColors.current
    val bgBrush = if (overHero) {
        Brush.verticalGradient(
            listOf(Color.Black.copy(alpha = 0.62f), Color.Black.copy(alpha = 0.16f), Color.Transparent)
        )
    } else {
        Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent))
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(bgBrush)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.White)
                    .border(1.dp, extra.line, RoundedCornerShape(10.dp))
                    .padding(4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(id = R.drawable.btech_logo),
                    contentDescription = "BTECH FINANCE logo",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Text(
                text = "BTECH FINANCE",
                color = if (overHero) Color.White else MaterialTheme.colorScheme.onBackground,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 13.sp,
                    letterSpacing = 1.4.sp,
                    shadow = if (overHero) Shadow(
                        color = Color.Black.copy(alpha = 0.65f),
                        offset = Offset(0f, 1f),
                        blurRadius = 8f,
                    ) else null,
                ),
            )
        }

        IconButton(
            onClick = onNotificationClick,
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(if (overHero) Color.Black.copy(alpha = 0.34f) else extra.surface2)
                .border(
                    width = 1.dp,
                    color = if (overHero) Color.White.copy(alpha = 0.35f) else extra.line,
                    shape = CircleShape,
                )
                .testTag("notifications_button"),
        ) {
            Icon(
                imageVector = Icons.Filled.NotificationsNone,
                contentDescription = "Notifications",
                tint = if (overHero) Color.White else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(19.dp),
            )
        }
    }
}

data class NavDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

@Composable
fun BtechBottomNav(
    currentRoute: String,
    hasRobot: Boolean,
    navStyle: NavStyle,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val extra = LocalBtechColors.current
    val items = if (hasRobot) {
        listOf(
            NavDestination(BtechRoutes.HOME, "Home", Icons.Filled.Home),
            NavDestination(BtechRoutes.META, "Meta", Icons.Filled.BarChart),
            NavDestination(BtechRoutes.PROFILE, "Profile", Icons.Filled.Person),
            NavDestination(BtechRoutes.SETTINGS, "Settings", Icons.Filled.Settings),
        )
    } else {
        listOf(
            NavDestination(BtechRoutes.HOME, "Home", Icons.Filled.Home),
            NavDestination(BtechRoutes.META, "Meta", Icons.Filled.BarChart),
            NavDestination(BtechRoutes.ADD_ROBOT, "Add", Icons.Filled.Add),
            NavDestination(BtechRoutes.PROFILE, "Profile", Icons.Filled.Person),
            NavDestination(BtechRoutes.SETTINGS, "Settings", Icons.Filled.Settings),
        )
    }

    val activeIndex = items.indexOfFirst { it.route == currentRoute }.coerceAtLeast(0)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(82.dp),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, extra.line),
            shadowElevation = 10.dp,
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val itemWidth = maxWidth / items.size
                val indicatorOffset by animateDpAsState(
                    targetValue = itemWidth * activeIndex,
                    label = "nav_indicator",
                )

                // Top 25% header strip
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(20.dp)
                        .align(Alignment.TopStart)
                        .background(extra.navStrip),
                ) {
                    if (navStyle == NavStyle.INDICATOR) {
                        Box(
                            modifier = Modifier
                                .offset(x = indicatorOffset)
                                .width(itemWidth)
                                .height(2.5.dp)
                                .align(Alignment.BottomStart)
                                .background(MaterialTheme.colorScheme.primary),
                        )
                    }
                }

                // Bottom 75% nav items (lifted active circles cleanly cross the 25%/75% boundary)
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 20.dp, start = 6.dp, end = 6.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    items.forEach { dest ->
                        val isActive = dest.route == currentRoute
                        val tint = if (isActive) MaterialTheme.colorScheme.primary else extra.muted
                        val isRaised = isActive && navStyle != NavStyle.INDICATOR
                        val iconLift by animateDpAsState(
                            targetValue = if (isRaised) (-11).dp else 0.dp,
                            label = "icon_lift",
                        )
                        val circleFill = when {
                            !isRaised -> Color.Transparent
                            navStyle == NavStyle.RAISED_TOP -> MaterialTheme.colorScheme.surface
                            else -> extra.surface2
                        }
                        val ringColor = when {
                            !isRaised -> Color.Transparent
                            navStyle == NavStyle.RAISED_TOP -> extra.navStrip
                            else -> MaterialTheme.colorScheme.surface
                        }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxSize()
                                .minimumInteractiveComponentSize()
                                .clickable { onNavigate(dest.route) }
                                .testTag("nav_${dest.route}"),
                        ) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .padding(top = 2.dp)
                                    .offset(y = iconLift)
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(circleFill)
                                    .border(
                                        width = if (isRaised) 4.dp else 0.dp,
                                        color = ringColor,
                                        shape = CircleShape,
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = dest.icon,
                                    contentDescription = dest.label,
                                    tint = tint,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            Text(
                                text = dest.label,
                                color = tint,
                                fontSize = 9.sp,
                                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1,
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = 2.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun BtechCard(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val extra = LocalBtechColors.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, extra.line),
        shadowElevation = 2.dp,
    ) {
        Column(modifier = Modifier.padding(padding), content = content)
    }
}

@Composable
fun BtechPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    containerColor: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp),
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor,
        ),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun BtechSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val extra = LocalBtechColors.current
    OutlinedButton(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = extra.accentSoft,
            contentColor = MaterialTheme.colorScheme.primary,
        ),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun BtechStatusPill(
    text: String,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(100.dp))
            .background(containerColor)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(
            text = text,
            color = contentColor,
            fontSize = 10.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 0.5.sp,
        )
    }
}

@Composable
fun PageHeader(title: String, subtitle: String) {
    val extra = LocalBtechColors.current
    Column(modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = extra.muted,
        )
        Spacer(modifier = Modifier.height(14.dp))
    }
}

@Composable
fun BtechToastBanner(
    message: String?,
    isError: Boolean,
    modifier: Modifier = Modifier,
) {
    val extra = LocalBtechColors.current
    AnimatedVisibility(
        visible = message != null,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
        modifier = modifier,
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = if (isError) Color(0xFF4A1720) else Color(0xFF14382F),
            border = BorderStroke(1.dp, if (isError) Color(0xFFDC3545) else extra.green),
            shadowElevation = 8.dp,
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
            Text(
                text = message ?: "",
                color = if (isError) Color(0xFFFFE2E5) else Color(0xFFA8FFDF),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
fun TradeExecutionTrackerCard(
    snapshot: OverlaySnapshot,
    modifier: Modifier = Modifier,
    darkSurface: Boolean = false,
    blueTerminalStyle: Boolean = false,
) {
    val extra = LocalBtechColors.current
    val accentColor = MaterialTheme.colorScheme.primary
    val greenTick = if (blueTerminalStyle) Color(0xFF28D17C) else Color(0xFF28B463)
    val errorRed = if (blueTerminalStyle) Color(0xFFFF5263) else Color(0xFFE53935)
    val activeAccent = if (blueTerminalStyle) Color(0xFF7CE8FF) else accentColor

    val cardBg = if (darkSurface) Color(0xFF0E1726) else MaterialTheme.colorScheme.surface
    val cardBorder = when {
        blueTerminalStyle -> BtechTerminalBlueBorder
        darkSurface -> Color(0xFF25384E)
        else -> extra.line
    }
    val primaryText = when {
        blueTerminalStyle -> Color.White
        darkSurface -> Color(0xFFF1F5FB)
        else -> MaterialTheme.colorScheme.onSurface
    }
    val mutedText = when {
        blueTerminalStyle -> Color(0xFFDCEBFF)
        darkSurface -> Color(0xFF9FB1C8)
        else -> extra.muted
    }
    val trackBg = when {
        blueTerminalStyle -> Color(0x45FFFFFF)
        darkSurface -> Color(0xFF1F2D42)
        else -> extra.surface2
    }
    val detailBg = when {
        blueTerminalStyle -> Color(0x52021442)
        darkSurface -> Color(0xFF132033)
        else -> extra.surface2
    }
    val detailBorder = when {
        snapshot.executionFailed -> if (blueTerminalStyle) Color(0xFFFF6B78) else errorRed.copy(alpha = 0.45f)
        blueTerminalStyle -> Color(0xFF5A9BFF)
        else -> cardBorder
    }

    val animatedProgress by animateFloatAsState(
        targetValue = snapshot.executionProgress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 320),
        label = "trade_exec_progress",
    )

    val barColor = when {
        snapshot.executionFailed -> if (blueTerminalStyle) Color(0xFFFF6B78) else errorRed
        snapshot.executionCompleted -> if (blueTerminalStyle) Color(0xFF39E58C) else greenTick
        else -> activeAccent
    }

    val isIdle = !snapshot.hasExecutionTracker || snapshot.executionSteps.isEmpty()

    val headerRightText = if (isIdle) "" else snapshot.executionHeaderStatus
    val footerDetailText = snapshot.statusDetail

    val cardModifier = if (blueTerminalStyle) {
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Brush.linearGradient(listOf(BtechTerminalBlue, BtechTerminalBlueDeep)))
            .border(1.dp, BtechTerminalBlueBorder, RoundedCornerShape(8.dp))
            .testTag("trade_execution_tracker")
    } else {
        modifier
            .fillMaxWidth()
            .testTag("trade_execution_tracker")
    }

    Surface(
        modifier = cardModifier,
        shape = RoundedCornerShape(if (blueTerminalStyle) 8.dp else 10.dp),
        color = if (blueTerminalStyle) Color.Transparent else cardBg,
        border = if (blueTerminalStyle) null else BorderStroke(1.dp, cardBorder),
    ) {
        Column(modifier = Modifier.padding(if (blueTerminalStyle) 10.dp else 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.AutoAwesome,
                        contentDescription = null,
                        tint = if (blueTerminalStyle) Color.White else accentColor,
                        modifier = Modifier.size(if (blueTerminalStyle) 14.dp else 16.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Trade Execution",
                        color = if (blueTerminalStyle) Color.White else accentColor,
                        fontSize = if (blueTerminalStyle) 11.sp else 12.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                }
                if (headerRightText.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = headerRightText,
                        color = when {
                            snapshot.executionFailed && blueTerminalStyle -> Color(0xFFFFB4BC)
                            snapshot.executionFailed -> errorRed
                            else -> mutedText
                        },
                        fontSize = if (blueTerminalStyle) 9.5.sp else 10.sp,
                        fontWeight = if (snapshot.executionFailed) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(modifier = Modifier.height(if (blueTerminalStyle) 6.dp else 8.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (blueTerminalStyle) 3.5.dp else 4.dp)
                    .clip(RoundedCornerShape(100.dp))
                    .background(trackBg),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(animatedProgress)
                        .height(if (blueTerminalStyle) 3.5.dp else 4.dp)
                        .clip(RoundedCornerShape(100.dp))
                        .background(barColor),
                )
            }

            Spacer(modifier = Modifier.height(if (blueTerminalStyle) 8.dp else 10.dp))

            if (isIdle) {
                Text(
                    text = "Waiting for new signal",
                    color = if (blueTerminalStyle) Color(0xFFDCEBFF) else mutedText,
                    fontSize = if (blueTerminalStyle) 10.5.sp else 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                        .testTag("tracker_waiting_idle"),
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(if (blueTerminalStyle) 4.dp else 7.dp)) {
                    snapshot.executionSteps.forEach { step ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier.size(if (blueTerminalStyle) 17.dp else 20.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                when (step.status) {
                                    ExecutionStepStatus.COMPLETED -> {
                                        Box(
                                            modifier = Modifier
                                                .size(if (blueTerminalStyle) 15.dp else 17.dp)
                                                .clip(CircleShape)
                                                .background(greenTick),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.Check,
                                                contentDescription = "Completed",
                                                tint = Color.White,
                                                modifier = Modifier.size(if (blueTerminalStyle) 10.dp else 12.dp),
                                            )
                                        }
                                    }
                                    ExecutionStepStatus.ACTIVE -> {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(if (blueTerminalStyle) 13.dp else 16.dp),
                                            color = if (blueTerminalStyle) Color.White else accentColor,
                                            trackColor = trackBg,
                                            strokeWidth = 2.dp,
                                        )
                                    }
                                    ExecutionStepStatus.ERROR -> {
                                        Box(
                                            modifier = Modifier
                                                .size(if (blueTerminalStyle) 15.dp else 17.dp)
                                                .clip(CircleShape)
                                                .background(errorRed),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.Close,
                                                contentDescription = "Failed",
                                                tint = Color.White,
                                                modifier = Modifier.size(if (blueTerminalStyle) 10.dp else 12.dp),
                                            )
                                        }
                                    }
                                    ExecutionStepStatus.PENDING -> {
                                        Box(
                                            modifier = Modifier
                                                .size(if (blueTerminalStyle) 12.dp else 14.dp)
                                                .border(
                                                    1.5.dp,
                                                    if (blueTerminalStyle) Color(0x66FFFFFF) else mutedText.copy(alpha = 0.35f),
                                                    CircleShape,
                                                ),
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.width(if (blueTerminalStyle) 7.dp else 9.dp))

                            val labelColor = when (step.status) {
                                ExecutionStepStatus.ACTIVE -> if (blueTerminalStyle) Color.White else accentColor
                                ExecutionStepStatus.ERROR -> if (blueTerminalStyle) Color(0xFFFFB4BC) else errorRed
                                ExecutionStepStatus.COMPLETED -> if (blueTerminalStyle) Color(0xFFEAF3FF) else primaryText
                                ExecutionStepStatus.PENDING -> if (blueTerminalStyle) Color(0x99DCEBFF) else mutedText.copy(alpha = 0.65f)
                            }
                            Text(
                                text = step.label,
                                color = labelColor,
                                fontSize = if (blueTerminalStyle) 10.sp else 11.sp,
                                fontWeight = if (step.status == ExecutionStepStatus.ACTIVE || step.status == ExecutionStepStatus.ERROR) {
                                    FontWeight.SemiBold
                                } else {
                                    FontWeight.Normal
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(if (blueTerminalStyle) 7.dp else 10.dp))

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(7.dp),
                    color = detailBg,
                    border = BorderStroke(1.dp, detailBorder),
                ) {
                    Text(
                        text = footerDetailText,
                        color = when {
                            snapshot.executionFailed && blueTerminalStyle -> Color(0xFFFFB4BC)
                            snapshot.executionFailed -> errorRed
                            blueTerminalStyle -> Color(0xFFEAF3FF)
                            else -> primaryText.copy(alpha = 0.9f)
                        },
                        fontSize = if (blueTerminalStyle) 9.5.sp else 10.sp,
                        lineHeight = if (blueTerminalStyle) 13.sp else 14.sp,
                        maxLines = if (blueTerminalStyle) 2 else Int.MAX_VALUE,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = if (blueTerminalStyle) 6.dp else 8.dp),
                    )
                }
            }
        }
    }
}

