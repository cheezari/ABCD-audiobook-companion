package com.cheezari.chaptercompanion

import androidx.compose.runtime.remember
import android.graphics.BitmapFactory
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.sp
import androidx.palette.graphics.Palette
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer

fun Modifier.bouncyPress(pressedScale: Float = 0.95f): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) pressedScale else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "bouncy_scale"
    )
    graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

fun extractSeedColorFromCoverBytes(coverBytes: ByteArray?): Color? {
    if (coverBytes == null || coverBytes.isEmpty()) return null
    val bitmap = BitmapFactory.decodeByteArray(coverBytes, 0, coverBytes.size) ?: return null
    val palette = Palette.from(bitmap).generate()
    val swatch = palette.vibrantSwatch
        ?: palette.mutedSwatch
        ?: palette.dominantSwatch
        ?: return null
    return Color(swatch.rgb)
}

private fun buildCalmLightScheme(seed: Color) = lightColorScheme(
    primary = seed,
    onPrimary = if (seed.luminance() > 0.45f) Color(0xFF1A1916) else Color(0xFFFFFFFF),
    primaryContainer = seed.copy(alpha = 0.16f),
    onPrimaryContainer = Color(0xFF1A1916),
    secondary = Color(0xFF4F5866),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE8ECEF),
    onSecondaryContainer = Color(0xFF1A1D22),
    background = Color(0xFFF7F5F0),
    onBackground = Color(0xFF1B1A17),
    surface = Color(0xFFF7F5F0),
    onSurface = Color(0xFF1B1A17),
    surfaceVariant = Color(0xFFEDE9E0),
    onSurfaceVariant = Color(0xFF4A4740),
    outline = Color(0xFF837E74)
)

private fun buildCalmDarkScheme(seed: Color) = darkColorScheme(
    primary = seed,
    onPrimary = if (seed.luminance() > 0.45f) Color(0xFF1A1916) else Color(0xFFFFFFFF),
    primaryContainer = seed.copy(alpha = 0.24f),
    onPrimaryContainer = Color(0xFFF5F2EB),
    secondary = Color(0xFFA9B4C4),
    onSecondary = Color(0xFF161A20),
    secondaryContainer = Color(0xFF23272F),
    onSecondaryContainer = Color(0xFFE4E7EC),
    background = Color(0xFF141517),
    onBackground = Color(0xFFECE9E2),
    surface = Color(0xFF141517),
    onSurface = Color(0xFFECE9E2),
    surfaceVariant = Color(0xFF1E2025),
    onSurfaceVariant = Color(0xFFC2BEB4),
    outline = Color(0xFF77736A)
)

// Editorial serif headings paired with clean sans body text, inspired by Voice
val VoiceInspiredTypography = Typography(
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Normal,
        fontSize = 24.sp,
        lineHeight = 30.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Medium,
        fontSize = 18.sp,
        lineHeight = 24.sp
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 21.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp
    )
)

val VoiceInspiredShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun AbcdTheme(
    themeMode: ThemeModeOption = ThemeModeOption.SYSTEM,
    accentPreset: AccentPreset = AccentPreset.AMBER,
    coverAccentColor: Color? = null,
    matchCoverAccent: Boolean = true,
    content: @Composable () -> Unit
) {
    val useDark = when (themeMode) {
        ThemeModeOption.SYSTEM -> isSystemInDarkTheme()
        ThemeModeOption.LIGHT -> false
        ThemeModeOption.DARK -> true
    }

    val activeSeed = if (matchCoverAccent && coverAccentColor != null) {
        coverAccentColor
    } else {
        Color(accentPreset.hexColor)
    }

    val colorScheme = if (useDark) {
        buildCalmDarkScheme(activeSeed)
    } else {
        buildCalmLightScheme(activeSeed)
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = VoiceInspiredTypography,
        shapes = VoiceInspiredShapes,
        content = content
    )
}