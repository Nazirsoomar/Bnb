package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val DarkColorScheme = darkColorScheme(
    primary = SignalTeal,
    onPrimary = SlateBgDark,
    primaryContainer = SignalTealContainer,
    onPrimaryContainer = SignalTeal,
    secondary = IndigoLink,
    onSecondary = Color.White,
    secondaryContainer = IndigoDarkContainer,
    onSecondaryContainer = IndigoLightContainer,
    tertiary = AmberOtp,
    onTertiary = SlateBgDark,
    tertiaryContainer = AmberOtpDarkBg,
    onTertiaryContainer = AmberOtp,
    background = SlateBgDark,
    onBackground = TextPrimaryDark,
    surface = SlateSurfaceDark,
    onSurface = TextPrimaryDark,
    surfaceVariant = SlateSurfaceElevatedDark,
    onSurfaceVariant = TextSecondaryDark,
    surfaceContainerLowest = SlateBgDark,
    surfaceContainerLow = SlateSurfaceDark,
    surfaceContainer = SlateSurfaceElevatedDark,
    surfaceContainerHigh = SlateSurfaceHighestDark,
    outline = SlateOutlineDark,
    error = RoseAlert,
    onError = Color.White,
    errorContainer = RoseAlertDarkBg,
    onErrorContainer = RoseAlert
)

private val LightColorScheme = lightColorScheme(
    primary = SignalTealDark,
    onPrimary = Color.White,
    primaryContainer = SignalTealLightContainer,
    onPrimaryContainer = SignalTealDark,
    secondary = IndigoLinkDark,
    onSecondary = Color.White,
    secondaryContainer = IndigoLightContainer,
    onSecondaryContainer = IndigoLinkDark,
    tertiary = AmberOtpDark,
    onTertiary = Color.White,
    tertiaryContainer = AmberOtpLightBg,
    onTertiaryContainer = AmberOtpDark,
    background = SlateBgLight,
    onBackground = TextPrimaryLight,
    surface = SlateSurfaceLight,
    onSurface = TextPrimaryLight,
    surfaceVariant = SlateSurfaceElevatedLight,
    onSurfaceVariant = TextSecondaryLight,
    outline = SlateOutlineLight,
    error = RoseAlert,
    onError = Color.White
)

val AppShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun DualPhoneTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content
    )
}
