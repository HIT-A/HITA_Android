package cn.limpu.hita.ui.design

import android.content.SharedPreferences
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cn.limpu.hita.R
import com.limpu.style.ThemeTools

enum class HitaThemeStyle {
    Classic,
    AppleGlass,
    Cyber,
    SoraCloud,
    Persona5,
    DeepSpace,
    Sumi
}

@Immutable
data class HitaThemeState(
    val mode: ThemeTools.MODE,
    val preferenceStyle: ThemeTools.STYLE,
    val colorPreset: ThemeTools.COLOR_PRESET,
    val style: HitaThemeStyle,
    val isDark: Boolean,
)

@Immutable
data class HitaSpacing(
    val none: Dp,
    val xs: Dp,
    val sm: Dp,
    val md: Dp,
    val lg: Dp,
    val xl: Dp,
    val xxl: Dp,
    val xxxl: Dp,
    val xxxxl: Dp
)

@Immutable
data class HitaRadius(
    val none: Dp,
    val xs: Dp,
    val sm: Dp,
    val md: Dp,
    val lg: Dp,
    val xl: Dp,
    val full: Dp
)

@Immutable
data class HitaComponentSize(
    val toolbarHeight: Dp,
    val bottomNavigationHeight: Dp,
    val timetableLabelWidth: Dp,
    val timetableDateHeight: Dp,
    val timelineWidth: Dp
)

@Immutable
data class HitaDesignTokens(
    val spacing: HitaSpacing,
    val radius: HitaRadius,
    val componentSize: HitaComponentSize
)

private val LocalHitaDesignTokens = staticCompositionLocalOf {
    HitaDesignTokens(
        spacing = HitaSpacing(
            none = 0.dp,
            xs = 4.dp,
            sm = 8.dp,
            md = 12.dp,
            lg = 16.dp,
            xl = 24.dp,
            xxl = 32.dp,
            xxxl = 48.dp,
            xxxxl = 64.dp
        ),
        radius = HitaRadius(
            none = 0.dp,
            xs = 4.dp,
            sm = 8.dp,
            md = 12.dp,
            lg = 16.dp,
            xl = 24.dp,
            full = 999.dp
        ),
        componentSize = HitaComponentSize(
            toolbarHeight = 56.dp,
            bottomNavigationHeight = 80.dp,
            timetableLabelWidth = 28.dp,
            timetableDateHeight = 24.dp,
            timelineWidth = 2.dp
        )
    )
}

private val LocalHitaThemeState = staticCompositionLocalOf {
    HitaThemeState(
        mode = ThemeTools.MODE.FOLLOW,
        preferenceStyle = ThemeTools.STYLE.CLASSIC,
        colorPreset = ThemeTools.COLOR_PRESET.CLASSIC,
        style = HitaThemeStyle.Classic,
        isDark = false,
    )
}

private val defaultTypography = Typography()
internal fun hitaTypographyFor(fonts: HitaFontScheme): Typography = defaultTypography.copy(
    displayLarge = defaultTypography.displayLarge.copy(fontFamily = fonts.display),
    displayMedium = defaultTypography.displayMedium.copy(fontFamily = fonts.display),
    displaySmall = defaultTypography.displaySmall.copy(fontFamily = fonts.display),
    headlineLarge = defaultTypography.headlineLarge.copy(fontFamily = fonts.display),
    headlineMedium = defaultTypography.headlineMedium.copy(fontFamily = fonts.display),
    headlineSmall = defaultTypography.headlineSmall.copy(fontFamily = fonts.display),
    titleLarge = defaultTypography.titleLarge.copy(fontFamily = fonts.display),
    titleMedium = defaultTypography.titleMedium.copy(fontFamily = fonts.display),
    titleSmall = defaultTypography.titleSmall.copy(fontFamily = fonts.display),
    bodyLarge = defaultTypography.bodyLarge.copy(fontFamily = fonts.primary),
    bodyMedium = defaultTypography.bodyMedium.copy(fontFamily = fonts.primary),
    bodySmall = defaultTypography.bodySmall.copy(fontFamily = fonts.primary),
    labelLarge = defaultTypography.labelLarge.copy(fontFamily = fonts.primary),
    labelMedium = defaultTypography.labelMedium.copy(fontFamily = fonts.primary),
    labelSmall = defaultTypography.labelSmall.copy(fontFamily = fonts.primary),
)

object HitaTheme {
    val tokens: HitaDesignTokens
        @Composable
        @ReadOnlyComposable
        get() = LocalHitaDesignTokens.current

    val mode: ThemeTools.MODE
        @Composable
        @ReadOnlyComposable
        get() = LocalHitaThemeState.current.mode

    val preferenceStyle: ThemeTools.STYLE
        @Composable
        @ReadOnlyComposable
        get() = LocalHitaThemeState.current.preferenceStyle

    val style: HitaThemeStyle
        @Composable
        @ReadOnlyComposable
        get() = LocalHitaThemeState.current.style

    val colorPreset: ThemeTools.COLOR_PRESET
        @Composable
        @ReadOnlyComposable
        get() = LocalHitaThemeState.current.colorPreset

    val isDark: Boolean
        @Composable
        @ReadOnlyComposable
        get() = LocalHitaThemeState.current.isDark

    val fonts: HitaFontScheme
        @Composable
        @ReadOnlyComposable
        get() = hitaFontSchemeFor(LocalHitaThemeState.current.style)
}

@Composable
fun HitaComposeTheme(
    style: HitaThemeStyle? = null,
    darkTheme: Boolean? = null,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val preferences = remember(context) { ThemeTools.getThemePreferences(context) }
    var themeMode by remember(preferences) { mutableStateOf(ThemeTools.getThemeMode(context)) }
    var preferenceStyle by remember(preferences) { mutableStateOf(ThemeTools.getThemeStyle(context)) }
    var colorPreset by remember(preferences) { mutableStateOf(ThemeTools.getColorPreset(context)) }
    DisposableEffect(preferences) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                ThemeTools.KEY_MODE -> themeMode = ThemeTools.getThemeMode(context)
                ThemeTools.KEY_STYLE -> preferenceStyle = ThemeTools.getThemeStyle(context)
                ThemeTools.KEY_COLOR_PRESET -> colorPreset = ThemeTools.getColorPreset(context)
            }
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    val tokens = hitaDesignTokens()
    val actualDarkTheme = darkTheme ?: when (themeMode) {
        ThemeTools.MODE.DARK -> true
        ThemeTools.MODE.LIGHT -> false
        ThemeTools.MODE.FOLLOW -> isSystemInDarkTheme()
    }
    val actualStyle = style ?: preferenceStyle.toHitaThemeStyle()
    val themeState = HitaThemeState(
        mode = themeMode,
        preferenceStyle = preferenceStyle,
        colorPreset = colorPreset,
        style = actualStyle,
        isDark = actualDarkTheme,
    )
    val fontScheme = hitaFontSchemeFor(actualStyle)
    val colorScheme = hitaColorScheme(
        style = actualStyle,
        colorPreset = colorPreset,
        darkTheme = actualDarkTheme
    )
    CompositionLocalProvider(
        LocalHitaDesignTokens provides tokens,
        LocalHitaThemeState provides themeState,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = hitaTypographyFor(fontScheme),
            shapes = Shapes(),
        ) {
            // Compose 的 `LocalContentColor` 默认值是 **纯黑**（material3 `ContentColorKt`），
            // 且 `MaterialTheme` 不会设置它——只有 `Surface`/`Scaffold`/`Card` 这类带背景的
            // 容器才会顺带绑定一次。本项目 42 处 `HitaComposeTheme` 调用点里几乎都没有外层
            // `Surface`，于是所有未显式指定 `color` 的 `Text` / `Icon` 在暗色主题下都是黑字
            // 压在深色背景上，对比度接近 1:1（用户报告的就是威海登录页的「本科生/研究生」）。
            //
            // 在主题根统一绑定 `onSurface` 一次性修好这一整类问题，而不是逐点补 color：
            // 逐点补会把「默认值是什么」这个隐式契约留给每个调用方，漏一处就是一处不可读文字。
            CompositionLocalProvider(LocalContentColor provides colorScheme.onSurface) {
                content()
            }
        }
    }
}

private fun ThemeTools.STYLE.toHitaThemeStyle(): HitaThemeStyle {
    return when (this) {
        ThemeTools.STYLE.CLASSIC -> HitaThemeStyle.Classic
        ThemeTools.STYLE.APPLE_GLASS -> HitaThemeStyle.AppleGlass
        ThemeTools.STYLE.CYBER -> HitaThemeStyle.Cyber
        ThemeTools.STYLE.SORA_CLOUD -> HitaThemeStyle.SoraCloud
        ThemeTools.STYLE.P5 -> HitaThemeStyle.Persona5
        ThemeTools.STYLE.DEEP_SPACE -> HitaThemeStyle.DeepSpace
        ThemeTools.STYLE.SUMI -> HitaThemeStyle.Sumi
    }
}

@Composable
private fun hitaDesignTokens(): HitaDesignTokens {
    return HitaDesignTokens(
        spacing = HitaSpacing(
            none = dimensionResource(R.dimen.ds_space_none),
            xs = dimensionResource(R.dimen.ds_space_xs),
            sm = dimensionResource(R.dimen.ds_space_sm),
            md = dimensionResource(R.dimen.ds_space_md),
            lg = dimensionResource(R.dimen.ds_space_lg),
            xl = dimensionResource(R.dimen.ds_space_xl),
            xxl = dimensionResource(R.dimen.ds_space_2xl),
            xxxl = dimensionResource(R.dimen.ds_space_3xl),
            xxxxl = dimensionResource(R.dimen.ds_space_4xl)
        ),
        radius = HitaRadius(
            none = dimensionResource(R.dimen.ds_radius_none),
            xs = dimensionResource(R.dimen.ds_radius_xs),
            sm = dimensionResource(R.dimen.ds_radius_sm),
            md = dimensionResource(R.dimen.ds_radius_md),
            lg = dimensionResource(R.dimen.ds_radius_lg),
            xl = dimensionResource(R.dimen.ds_radius_xl),
            full = dimensionResource(R.dimen.ds_radius_full)
        ),
        componentSize = HitaComponentSize(
            toolbarHeight = dimensionResource(R.dimen.ds_toolbar_height),
            bottomNavigationHeight = dimensionResource(R.dimen.ds_bottom_navigation_height),
            timetableLabelWidth = dimensionResource(R.dimen.ds_timetable_label_width),
            timetableDateHeight = dimensionResource(R.dimen.ds_timetable_date_height),
            timelineWidth = dimensionResource(R.dimen.ds_timeline_width)
        )
    )
}

@Composable
private fun hitaColorScheme(
    style: HitaThemeStyle,
    colorPreset: ThemeTools.COLOR_PRESET,
    darkTheme: Boolean
): ColorScheme {
    val base = if (darkTheme) {
        darkColorScheme(
            primary = colorResource(R.color.ds_color_brand),
            onPrimary = colorResource(R.color.ds_color_brand_on),
            primaryContainer = colorResource(R.color.ds_color_brand_container),
            onPrimaryContainer = colorResource(R.color.ds_color_brand_on_container),
            secondary = colorResource(R.color.ds_color_accent),
            tertiary = colorResource(R.color.ds_color_info),
            background = colorResource(R.color.ds_color_background),
            surface = colorResource(R.color.ds_color_surface),
            onSurface = colorResource(R.color.ds_color_text_primary),
            onSurfaceVariant = colorResource(R.color.ds_color_text_secondary),
            outline = colorResource(R.color.ds_color_outline),
            outlineVariant = colorResource(R.color.ds_color_outline_subtle),
            error = colorResource(R.color.ds_color_error),
            errorContainer = colorResource(R.color.ds_color_error_container)
        )
    } else {
        lightColorScheme(
            primary = colorResource(R.color.ds_color_brand),
            onPrimary = colorResource(R.color.ds_color_brand_on),
            primaryContainer = colorResource(R.color.ds_color_brand_container),
            onPrimaryContainer = colorResource(R.color.ds_color_brand_on_container),
            secondary = colorResource(R.color.ds_color_accent),
            tertiary = colorResource(R.color.ds_color_info),
            background = colorResource(R.color.ds_color_background),
            surface = colorResource(R.color.ds_color_surface),
            onSurface = colorResource(R.color.ds_color_text_primary),
            onSurfaceVariant = colorResource(R.color.ds_color_text_secondary),
            outline = colorResource(R.color.ds_color_outline),
            outlineVariant = colorResource(R.color.ds_color_outline_subtle),
            error = colorResource(R.color.ds_color_error),
            errorContainer = colorResource(R.color.ds_color_error_container)
        )
    }

    val basic = hitaBasicColorScheme(base, colorPreset, darkTheme)

    return when (style) {
        HitaThemeStyle.Classic -> basic
        HitaThemeStyle.AppleGlass -> base.copy(
            // 暗色下 primary 必须压深：白字压在 0xFF0A84FF 上只有 3.65:1，达不到正文 4.5:1。
            primary = if (darkTheme) Color(0xFF0A6CD6) else Color(0xFF007AFF),
            onPrimary = Color.White,
            primaryContainer = if (darkTheme) Color(0xFF12324E) else Color(0xFFD9ECFF),
            onPrimaryContainer = if (darkTheme) Color(0xFFD6EAFF) else Color(0xFF00315F),
            secondary = if (darkTheme) Color(0xFF63E6D2) else Color(0xFF00A89D),
            tertiary = if (darkTheme) Color(0xFFBF5AF2) else Color(0xFFAF52DE),
            background = if (darkTheme) Color(0xFF090B10) else Color(0xFFF4F8FC),
            surface = if (darkTheme) Color.White.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.42f),
            surfaceVariant = if (darkTheme) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.30f),
            onSurface = if (darkTheme) Color(0xFFF5F7FA) else Color(0xFF101318),
            onSurfaceVariant = if (darkTheme) Color(0xFFCCD2DA) else Color(0xFF505761),
            // 描边是可见图形元素（分隔线/边框），按 3:1 判定。白色 0.34/0.18 叠加在
            // 0xFF090B10 上分别只有 1.97:1 和 1.08:1——暗色下等于不可见。
            // 取值按「叠加后的实际表面色」校准（surface = 0.16 白 over background = #303236），
            // 否则对着纯背景算够、压在卡片上仍然不够。
            outline = if (darkTheme) Color(0xFF9AA0A6) else Color.White.copy(alpha = 0.72f),
            outlineVariant = if (darkTheme) Color(0xFF7E838A) else Color.White.copy(alpha = 0.48f)
        )
        HitaThemeStyle.Cyber -> if (darkTheme) {
            base.copy(
                primary = Color(0xFF22D3EE),
                onPrimary = Color(0xFF03131A),
                primaryContainer = Color(0xFF0C4457),
                onPrimaryContainer = Color(0xFFB8F3FF),
                secondary = Color(0xFFA78BFA),
                tertiary = Color(0xFFF472B6),
                background = Color(0xFF04050C),
                surface = Color(0xFF0A0E1D),
                surfaceVariant = Color(0xFF161D33),
                onSurface = Color(0xFFE6EDF7),
                onSurfaceVariant = Color(0xFF93A0BE),
                outline = Color(0xFF737C92),
                outlineVariant = Color(0xFF616B85),
            )
        } else {
            base.copy(
                primary = Color(0xFF0891B2),
                onPrimary = Color.White,
                primaryContainer = Color(0xFFCFF6FE),
                onPrimaryContainer = Color(0xFF083344),
                secondary = Color(0xFF7C5CD6),
                tertiary = Color(0xFFDB2777),
                background = Color(0xFFF3F6FC),
                surface = Color.White,
                surfaceVariant = Color(0xFFE6ECF7),
                onSurface = Color(0xFF10182B),
                onSurfaceVariant = Color(0xFF4A5570),
                outline = Color(0xFF7A86A3),
                outlineVariant = Color(0xFFC6CEE0),
            )
        }
        HitaThemeStyle.SoraCloud -> if (darkTheme) {
            base.copy(
                primary = Color(0xFFF06A52),
                onPrimary = Color(0xFF2A0C08),
                primaryContainer = Color(0xFF6F2A21),
                onPrimaryContainer = Color(0xFFFFDAD2),
                secondary = Color(0xFF8FB2B5),
                tertiary = Color(0xFFE6B85E),
                background = Color(0xFF181714),
                surface = Color(0xFF25221D),
                surfaceVariant = Color(0xFF343028),
                onSurface = Color(0xFFF4ECD9),
                onSurfaceVariant = Color(0xFFC9BEA8),
                outline = Color(0xFF918D86),
                outlineVariant = Color(0xFF807B73),
            )
        } else {
            base.copy(
                primary = Color(0xFFC94B38),
                onPrimary = Color(0xFFFFF8E8),
                primaryContainer = Color(0xFFF1C8B8),
                onPrimaryContainer = Color(0xFF4C120B),
                secondary = Color(0xFF315D6D),
                tertiary = Color(0xFFD2A23C),
                background = Color(0xFFF3EBD9),
                surface = Color(0xFFFFF8E8),
                surfaceVariant = Color(0xFFE8DFC9),
                onSurface = Color(0xFF25221F),
                onSurfaceVariant = Color(0xFF625B50),
                outline = Color(0xFF70675B),
                outlineVariant = Color(0xFFC8BDA7),
            )
        }
        HitaThemeStyle.Persona5 -> base.copy(
            primary = Color(0xFFE60012),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFB50020),
            onPrimaryContainer = Color.White,
            secondary = if (darkTheme) Color(0xFFF5F5F5) else Color(0xFF101010),
            tertiary = Color(0xFFFFC400),
            background = if (darkTheme) Color(0xFF0B0B0C) else Color(0xFFF4F4F4),
            surface = if (darkTheme) Color(0xFF17171A) else Color.White,
            surfaceVariant = if (darkTheme) Color(0xFF232327) else Color(0xFFECECEC),
            onSurface = if (darkTheme) Color(0xFFF5F5F5) else Color(0xFF101010),
            onSurfaceVariant = if (darkTheme) Color(0xFFB9B9C0) else Color(0xFF4A4A4A),
            outline = if (darkTheme) Color(0xFF7F7F83) else Color(0xFF8F8F8F),
            outlineVariant = if (darkTheme) Color(0xFF707075) else Color(0xFFD9D9D9),
        )
        HitaThemeStyle.DeepSpace -> if (darkTheme) {
            // 深空：静谧夜空 + 星砂金 + 暗星云紫/玫瑰，不科幻、不刺眼
            base.copy(
                primary = Color(0xFFE3C878),
                onPrimary = Color(0xFF221806),
                primaryContainer = Color(0xFF473713),
                onPrimaryContainer = Color(0xFFF7E4B2),
                secondary = Color(0xFF9B8EC4),
                tertiary = Color(0xFFC48A9B),
                background = Color(0xFF04060D),
                surface = Color(0xFF0B1020),
                surfaceVariant = Color(0xFF151C33),
                onSurface = Color(0xFFEDEEF0),
                onSurfaceVariant = Color(0xFFA9B0BE),
                outline = Color(0xFF737A8B),
                outlineVariant = Color(0xFF646B7E),
            )
        } else {
            // 晨昏蒙影：月白暖灰 + 暗星金
            base.copy(
                primary = Color(0xFF8A6D1F),
                onPrimary = Color(0xFFFFFDF5),
                primaryContainer = Color(0xFFF0E2B8),
                onPrimaryContainer = Color(0xFF3D2E05),
                secondary = Color(0xFF6E6394),
                tertiary = Color(0xFF9C5E72),
                background = Color(0xFFF1EEE7),
                surface = Color(0xFFFDFBF4),
                surfaceVariant = Color(0xFFE7E2D5),
                onSurface = Color(0xFF1D2129),
                onSurfaceVariant = Color(0xFF565E6E),
                outline = Color(0xFF8B93A6),
                outlineVariant = Color(0xFFD8D4C8),
            )
        }
        HitaThemeStyle.Sumi -> if (darkTheme) {
            // 墨夜：黑宣 + 墨白 + 朱砂亮
            base.copy(
                primary = Color(0xFFE4DCCB),
                onPrimary = Color(0xFF1C1A16),
                primaryContainer = Color(0xFF3A362E),
                onPrimaryContainer = Color(0xFFEDE5D3),
                secondary = Color(0xFFC66A54),
                tertiary = Color(0xFF8A937E),
                background = Color(0xFF161512),
                surface = Color(0xFF211F1B),
                surfaceVariant = Color(0xFF2C2924),
                onSurface = Color(0xFFE9E2D2),
                onSurfaceVariant = Color(0xFFA79D8B),
                outline = Color(0xFF8A857B),
                outlineVariant = Color(0xFF797469),
            )
        } else {
            // 宣纸：暖白纸面 + 墨色 + 朱砂印泥
            base.copy(
                primary = Color(0xFF33322F),
                onPrimary = Color(0xFFF7F2E7),
                primaryContainer = Color(0xFFDDD4C2),
                onPrimaryContainer = Color(0xFF2A2926),
                secondary = Color(0xFFA63B2A),
                tertiary = Color(0xFF5E6B5A),
                background = Color(0xFFF4EEE1),
                surface = Color(0xFFFBF7EC),
                surfaceVariant = Color(0xFFEFE7D6),
                onSurface = Color(0xFF262521),
                onSurfaceVariant = Color(0xFF6B655A),
                outline = Color(0xFFA79C88),
                outlineVariant = Color(0xFFDCD2BE),
            )
        }
    }
}

private fun hitaBasicColorScheme(
    base: ColorScheme,
    preset: ThemeTools.COLOR_PRESET,
    darkTheme: Boolean,
): ColorScheme {
    return when (preset) {
        ThemeTools.COLOR_PRESET.CLASSIC -> base
        ThemeTools.COLOR_PRESET.FRESH -> if (darkTheme) {
            base.copy(
                primary = Color(0xFF79D5C0),
                onPrimary = Color(0xFF00382F),
                primaryContainer = Color(0xFF135348),
                onPrimaryContainer = Color(0xFFB8F1E2),
                secondary = Color(0xFFB6CCAC),
                tertiary = Color(0xFFFFB59F),
                background = Color(0xFF101512),
                surface = Color(0xFF171D19),
                surfaceVariant = Color(0xFF26312B),
                onSurface = Color(0xFFE1E9E3),
                onSurfaceVariant = Color(0xFFBCC9C1),
                outline = Color(0xFF87948D),
                outlineVariant = Color(0xFF3D4942),
            )
        } else {
            base.copy(
                primary = Color(0xFF126B5D),
                onPrimary = Color.White,
                primaryContainer = Color(0xFFCDE9DF),
                onPrimaryContainer = Color(0xFF0B3D35),
                secondary = Color(0xFF52634C),
                tertiary = Color(0xFF8A4F3D),
                background = Color(0xFFF6FAF7),
                surface = Color.White,
                surfaceVariant = Color(0xFFE5EEE8),
                onSurface = Color(0xFF17201B),
                onSurfaceVariant = Color(0xFF4A5750),
                outline = Color(0xFF727E77),
                outlineVariant = Color(0xFFC1CBC5),
            )
        }
        ThemeTools.COLOR_PRESET.FOCUS -> if (darkTheme) {
            base.copy(
                primary = Color(0xFFA3C9F0),
                onPrimary = Color(0xFF003258),
                primaryContainer = Color(0xFF244B70),
                onPrimaryContainer = Color(0xFFD2E7FF),
                secondary = Color(0xFFD8BFD4),
                tertiary = Color(0xFFE0C36D),
                background = Color(0xFF111416),
                surface = Color(0xFF191C1F),
                surfaceVariant = Color(0xFF2D3034),
                onSurface = Color(0xFFE4E7EA),
                onSurfaceVariant = Color(0xFFC3C7CC),
                outline = Color(0xFF8D9197),
                outlineVariant = Color(0xFF43474C),
            )
        } else {
            base.copy(
                primary = Color(0xFF315E8A),
                onPrimary = Color.White,
                primaryContainer = Color(0xFFD5E8FA),
                onPrimaryContainer = Color(0xFF173B5E),
                secondary = Color(0xFF765574),
                tertiary = Color(0xFF766028),
                background = Color(0xFFF8F8F6),
                surface = Color.White,
                surfaceVariant = Color(0xFFE9EBED),
                onSurface = Color(0xFF1A1C1E),
                onSurfaceVariant = Color(0xFF4D5155),
                outline = Color(0xFF74787D),
                outlineVariant = Color(0xFFC5C8CC),
            )
        }
        ThemeTools.COLOR_PRESET.HIGH_CONTRAST -> base.copy(
            primary = if (darkTheme) Color(0xFFFFFF00) else Color(0xFF0033CC),
            onPrimary = if (darkTheme) Color.Black else Color.White,
            primaryContainer = if (darkTheme) Color(0xFF3B3B00) else Color(0xFFDCE5FF),
            onPrimaryContainer = if (darkTheme) Color.White else Color.Black,
            surface = if (darkTheme) Color.Black else Color.White,
            background = if (darkTheme) Color.Black else Color.White,
            surfaceVariant = if (darkTheme) Color(0xFF151515) else Color(0xFFF0F0F0),
            onSurface = if (darkTheme) Color.White else Color.Black,
            onSurfaceVariant = if (darkTheme) Color.White else Color.Black,
            outline = if (darkTheme) Color.White else Color.Black,
            outlineVariant = if (darkTheme) Color(0xFFBDBDBD) else Color(0xFF333333),
        )
    }
}
