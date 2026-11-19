package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

data class BtechExtraColors(
    val surface2: Color,
    val surface3: Color,
    val line: Color,
    val muted: Color,
    val navStrip: Color,
    val accentSoft: Color,
    val green: Color,
    val greenContainer: Color,
    val red: Color,
    val redContainer: Color,
    val amber: Color,
    val amberContainer: Color,
    val isDark: Boolean,
)

val LocalBtechColors = staticCompositionLocalOf {
    BtechExtraColors(
        surface2 = BtechLightSurface2,
        surface3 = BtechLightSurface3,
        line = BtechLightLine,
        muted = BtechLightMuted,
        navStrip = BtechLightNavStrip,
        accentSoft = BtechDefaultAccent.copy(alpha = 0.14f),
        green = BtechGreenLight,
        greenContainer = BtechGreenLightContainer,
        red = Color(0xFFD92C40),
        redContainer = BtechRedLightContainer,
        amber = Color(0xFFA86B00),
        amberContainer = BtechAmberLightContainer,
        isDark = false,
    )
}

fun contrastOnColor(accent: Color): Color {
    return if (accent.luminance() > 0.42f) Color(0xFF14202B) else Color.White
}

@Composable
fun BtechFinanceTheme(
    darkTheme: Boolean = false,
    accentColor: Color = BtechDefaultAccent,
    content: @Composable () -> Unit,
) {
    val onAccent = contrastOnColor(accentColor)
    val colorScheme = if (darkTheme) {
        darkColorScheme(
            primary = accentColor,
            onPrimary = onAccent,
            primaryContainer = accentColor.copy(alpha = 0.18f),
            onPrimaryContainer = accentColor,
            secondary = BtechDarkSurface3,
            onSecondary = BtechDarkText,
            background = BtechDarkBg,
            onBackground = BtechDarkText,
            surface = BtechDarkSurface,
            onSurface = BtechDarkText,
            surfaceVariant = BtechDarkSurface2,
            onSurfaceVariant = BtechDarkMuted,
            outline = BtechDarkLine,
            error = BtechRed,
            onError = Color.White,
        )
    } else {
        lightColorScheme(
            primary = accentColor,
            onPrimary = onAccent,
            primaryContainer = accentColor.copy(alpha = 0.14f),
            onPrimaryContainer = accentColor,
            secondary = BtechLightSurface3,
            onSecondary = BtechLightText,
            background = BtechLightBg,
            onBackground = BtechLightText,
            surface = BtechLightSurface,
            onSurface = BtechLightText,
            surfaceVariant = BtechLightSurface2,
            onSurfaceVariant = BtechLightMuted,
            outline = BtechLightLine,
            error = BtechRed,
            onError = Color.White,
        )
    }

    val extraColors = if (darkTheme) {
        BtechExtraColors(
            surface2 = BtechDarkSurface2,
            surface3 = BtechDarkSurface3,
            line = BtechDarkLine,
            muted = BtechDarkMuted,
            navStrip = BtechDarkNavStrip,
            accentSoft = accentColor.copy(alpha = 0.18f),
            green = BtechGreen,
            greenContainer = BtechGreenDarkContainer,
            red = BtechRed,
            redContainer = BtechRedDarkContainer,
            amber = BtechAmber,
            amberContainer = BtechAmberDarkContainer,
            isDark = true,
        )
    } else {
        BtechExtraColors(
            surface2 = BtechLightSurface2,
            surface3 = BtechLightSurface3,
            line = BtechLightLine,
            muted = BtechLightMuted,
            navStrip = BtechLightNavStrip,
            accentSoft = accentColor.copy(alpha = 0.14f),
            green = BtechGreenLight,
            greenContainer = BtechGreenLightContainer,
            red = Color(0xFFD92C40),
            redContainer = BtechRedLightContainer,
            amber = Color(0xFFA86B00),
            amberContainer = BtechAmberLightContainer,
            isDark = false,
        )
    }

    CompositionLocalProvider(LocalBtechColors provides extraColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = BtechTypography,
            content = content,
        )
    }
}
