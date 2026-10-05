package com.reiyy.yuitag.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Primary brand color (YuiTag Classic)
val Blue500 = Color(0xFF4F8CFF)
val Blue700 = Color(0xFF3A6AD4)
val OnPrimary = Color(0xFFFFFFFF)

// Light theme surface colors
val SurfaceLight = Color(0xFFF8F9FC)
val SurfaceContainerLight = Color(0xFFFFFFFF)
val SurfaceVariantLight = Color(0xFFE8EDF2)
val OnSurfaceLight = Color(0xFF1A1C1E)
val OnSurfaceVariantLight = Color(0xFF6B7280)
val OutlineLight = Color(0xFFD1D5DB)

// Status colors
val SuccessGreen = Color(0xFF34D399)
val ErrorRed = Color(0xFFF87171)
val WarningYellow = Color(0xFFFBBF24)

// Semantic Success colors for Light & Dark
val SuccessContainerLight = Color(0xFFD1FADF)
val OnSuccessContainerLight = Color(0xFF027A48)
val SuccessContainerDark = Color(0xFF0B3F28)
val OnSuccessContainerDark = Color(0xFF75E0A7)

// --- MD3 Extended Status Colors ---

@Immutable
data class StatusColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val error: Color,
    val onError: Color,
    val errorContainer: Color,
    val onErrorContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color
)

val LightStatusColors = StatusColors(
    success = SuccessGreen,
    onSuccess = Color(0xFFFFFFFF),
    successContainer = SuccessGreen.copy(alpha = 0.15f),
    onSuccessContainer = SuccessGreen,
    error = ErrorRed,
    onError = Color(0xFFFFFFFF),
    errorContainer = ErrorRed.copy(alpha = 0.15f),
    onErrorContainer = ErrorRed,
    warning = WarningYellow,
    onWarning = Color(0xFFFFFFFF),
    warningContainer = Color(0xFFFEF3C7),
    onWarningContainer = Color(0xFFD97706)
)

val DarkStatusColors = StatusColors(
    success = Color(0xFF75E0A7),
    onSuccess = Color(0xFF00391F),
    successContainer = Color(0xFF133826),
    onSuccessContainer = Color(0xFF75E0A7),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF3E1818),
    onErrorContainer = Color(0xFFFFB4AB),
    warning = Color(0xFFFFDE9F),
    onWarning = Color(0xFF452B00),
    warningContainer = Color(0xFF382C0E),
    onWarningContainer = Color(0xFFFFDE9F)
)

val LocalStatusColors = staticCompositionLocalOf { LightStatusColors }
val LocalDarkTheme = staticCompositionLocalOf { false }

val MaterialTheme.statusColors: StatusColors
    @Composable
    @ReadOnlyComposable
    get() = LocalStatusColors.current

// AirTag-style colors (保持查找页现状使用)
val AirTagGray = Color(0xFF2C2C2E)         // 未连接背景
val AirTagBlue = Color(0xFF007AFF)         // 已连接背景
val AirTagRippleWhite = Color(0xFFFFFFFF)  // 涟漪白色

// --- MD3 Baseline Tonal Palette ---

// Light
val md_theme_light_primary = Blue500
val md_theme_light_onPrimary = Color(0xFFFFFFFF)
val md_theme_light_primaryContainer = Color(0xFFDCE8FF)
val md_theme_light_onPrimaryContainer = Color(0xFF001B3E)
val md_theme_light_inversePrimary = Color(0xFFA5C8FF)

val md_theme_light_secondary = Color(0xFF535F70)
val md_theme_light_onSecondary = Color(0xFFFFFFFF)
val md_theme_light_secondaryContainer = Color(0xFFD7E3F7)
val md_theme_light_onSecondaryContainer = Color(0xFF101C2B)

val md_theme_light_tertiary = Color(0xFF6B5778)
val md_theme_light_onTertiary = Color(0xFFFFFFFF)
val md_theme_light_tertiaryContainer = Color(0xFFF2DAFF)
val md_theme_light_onTertiaryContainer = Color(0xFF251431)

val md_theme_light_error = ErrorRed
val md_theme_light_onError = Color(0xFFFFFFFF)
val md_theme_light_errorContainer = Color(0xFFFFDAD6)
val md_theme_light_onErrorContainer = Color(0xFF410002)

val md_theme_light_background = SurfaceLight
val md_theme_light_onBackground = OnSurfaceLight

val md_theme_light_surface = SurfaceContainerLight
val md_theme_light_onSurface = OnSurfaceLight
val md_theme_light_surfaceVariant = SurfaceVariantLight
val md_theme_light_onSurfaceVariant = OnSurfaceVariantLight

val md_theme_light_surfaceDim = Color(0xFFD8D9E0)
val md_theme_light_surfaceBright = SurfaceContainerLight
val md_theme_light_surfaceContainerLowest = Color(0xFFFFFFFF)
val md_theme_light_surfaceContainerLow = SurfaceLight
val md_theme_light_surfaceContainer = SurfaceContainerLight
val md_theme_light_surfaceContainerHigh = Color(0xFFF0F3F8)
val md_theme_light_surfaceContainerHighest = SurfaceVariantLight

val md_theme_light_outline = OutlineLight
val md_theme_light_outlineVariant = Color(0xFFE0E2EC)
val md_theme_light_inverseSurface = Color(0xFF2E3036)
val md_theme_light_inverseOnSurface = Color(0xFFF0F0F7)
val md_theme_light_scrim = Color(0xFF000000)

// Dark
val md_theme_dark_primary = Color(0xFFA5C8FF)
val md_theme_dark_onPrimary = Color(0xFF00315F)
val md_theme_dark_primaryContainer = Color(0xFF004786)
val md_theme_dark_onPrimaryContainer = Color(0xFFD5E3FF)
val md_theme_dark_inversePrimary = Blue500

val md_theme_dark_secondary = Color(0xFFBDC7DC)
val md_theme_dark_onSecondary = Color(0xFF273141)
val md_theme_dark_secondaryContainer = Color(0xFF3D4758)
val md_theme_dark_onSecondaryContainer = Color(0xFFD9E3F8)

val md_theme_dark_tertiary = Color(0xFFDABDE2)
val md_theme_dark_onTertiary = Color(0xFF3E2846)
val md_theme_dark_tertiaryContainer = Color(0xFF553E5D)
val md_theme_dark_onTertiaryContainer = Color(0xFFF7D8FF)

val md_theme_dark_error = Color(0xFFFFB4AB)
val md_theme_dark_onError = Color(0xFF690005)
val md_theme_dark_errorContainer = Color(0xFF93000A)
val md_theme_dark_onErrorContainer = Color(0xFFFFDAD6)

val md_theme_dark_background = Color(0xFF111318)
val md_theme_dark_onBackground = Color(0xFFE1E2E9)

val md_theme_dark_surface = Color(0xFF111318)
val md_theme_dark_onSurface = Color(0xFFE1E2E9)
val md_theme_dark_surfaceVariant = Color(0xFF43474E)
val md_theme_dark_onSurfaceVariant = Color(0xFFC4C6D0)

val md_theme_dark_surfaceDim = Color(0xFF111318)
val md_theme_dark_surfaceBright = Color(0xFF37393E)
val md_theme_dark_surfaceContainerLowest = Color(0xFF0C0E13)
val md_theme_dark_surfaceContainerLow = Color(0xFF191C20)
val md_theme_dark_surfaceContainer = Color(0xFF1D2024)
val md_theme_dark_surfaceContainerHigh = Color(0xFF272A2F)
val md_theme_dark_surfaceContainerHighest = Color(0xFF32353A)

val md_theme_dark_outline = Color(0xFF8E9099)
val md_theme_dark_outlineVariant = Color(0xFF43474E)
val md_theme_dark_inverseSurface = Color(0xFFE1E2E9)
val md_theme_dark_inverseOnSurface = Color(0xFF191C20)
val md_theme_dark_scrim = Color(0xFF000000)
