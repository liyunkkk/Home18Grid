package com.home18grid.ui

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color

/**
 * Material 3 Expressive 风格调色板。
 * 所有颜色显式定义，不依赖动态取色，保证在 HyperOS 上表现一致。
 */
@Immutable
data class AppPalette(
    val background: Color,
    val card: Color,
    val cardPressed: Color,
    val titleText: Color,
    val summaryText: Color,
    val sectionLabel: Color,
    val divider: Color,
    // Hero 状态卡：激活
    val heroOkBg: Color,
    val heroOkFg: Color,
    // Hero 状态卡：异常
    val heroErrBg: Color,
    val heroErrFg: Color,
    // 底部胶囊导航
    val navBar: Color,
    val navSelectedBg: Color,
    val navSelectedFg: Color,
    val navUnselectedFg: Color,
    // 开关
    val switchOnTrack: Color,
    val switchOffTrack: Color,
)

private val LightPalette = AppPalette(
    background = Color(0xFFF1F2F5),
    card = Color(0xFFFFFFFF),
    cardPressed = Color(0xFFEDEEF2),
    titleText = Color(0xFF15161A),
    summaryText = Color(0xFF8B8D94),
    sectionLabel = Color(0xFF8B8D94),
    divider = Color(0xFFECEDF1),
    heroOkBg = Color(0xFFD8F2DC),
    heroOkFg = Color(0xFF1CA344),
    heroErrBg = Color(0xFFFFDCD7),
    heroErrFg = Color(0xFFD9382C),
    navBar = Color(0xFFFFFFFF),
    navSelectedBg = Color(0xFFDCE6FF),
    navSelectedFg = Color(0xFF0B57D0),
    navUnselectedFg = Color(0xFF44464F),
    switchOnTrack = Color(0xFF0B6BF2),
    switchOffTrack = Color(0xFFD8D9DE),
)

private val DarkPalette = AppPalette(
    background = Color(0xFF0D0E11),
    card = Color(0xFF1B1D22),
    cardPressed = Color(0xFF23262C),
    titleText = Color(0xFFF2F3F6),
    summaryText = Color(0xFF9A9CA4),
    sectionLabel = Color(0xFF8A8C94),
    divider = Color(0xFF272A30),
    heroOkBg = Color(0xFF1B3A24),
    heroOkFg = Color(0xFF5CD97D),
    heroErrBg = Color(0xFF43201C),
    heroErrFg = Color(0xFFFF897D),
    navBar = Color(0xFF1E2025),
    navSelectedBg = Color(0xFF243554),
    navSelectedFg = Color(0xFFA8C7FA),
    navUnselectedFg = Color(0xFFB4B6BE),
    switchOnTrack = Color(0xFF4A90F7),
    switchOffTrack = Color(0xFF3A3D44),
)

val LocalAppPalette: ProvidableCompositionLocal<AppPalette> =
    staticCompositionLocalOf { LightPalette }

/**
 * 包一层 MaterialTheme 是为了让 clickable 的水波纹与 Switch 正常工作，
 * 组件配色一律走 [LocalAppPalette]，不受 MaterialTheme 默认色板影响。
 */
@Composable
fun Home18GridTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val palette = if (darkTheme) DarkPalette else LightPalette
    CompositionLocalProvider(LocalAppPalette provides palette) {
        MaterialTheme(
            colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme(),
            content = content
        )
    }
}
