package xyz.smart_kits.dropit.ui.theme

import android.graphics.Typeface
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.content.res.ResourcesCompat
import xyz.smart_kits.dropit.R

/**
 * The colours of the airmail world, one set per scheme. Two inks carry meaning — red: sending, failure, a
 * sale; navy: arrival, links, focus — and never decorate. `air*` and the stamp are print colours and never
 * carry text.
 */
@Immutable
data class Ink(
    val ground: Color,
    val paper: Color,
    val ink: Color,
    val dim: Color,
    val line: Color,
    val red: Color,
    val navy: Color,
    val mark: Color,
    val wash: Color,
    val airRed: Color,
    val airNavy: Color,
    val stampPaper: Color,
    val dark: Boolean,
)

/** The stamp's face is a printed object: the same red in both schemes */
val StampFace = Color(0xFFC8102E)

val LightInk = Ink(
    ground = Color(0xFFEEF1F5), paper = Color(0xFFFFFFFF), ink = Color(0xFF15171C), dim = Color(0xFF595E67),
    line = Color(0xFFD3D8DF), red = Color(0xFFC8102E), navy = Color(0xFF1F4E9E), mark = Color(0xFF1F4E9E),
    wash = Color(0x141F4E9E), airRed = Color(0xFFC8102E), airNavy = Color(0xFF1F4E9E),
    stampPaper = Color(0xFFE6EAF0), dark = false,
)

val DarkInk = Ink(
    ground = Color(0xFF111318), paper = Color(0xFF1B1E24), ink = Color(0xFFE9EAEC), dim = Color(0xFFA3A8B0),
    line = Color(0xFF30353E), red = Color(0xFFFF6B78), navy = Color(0xFF7AA2F0), mark = Color(0xFF86A8F2),
    wash = Color(0x1F5B8DEF), airRed = Color(0xFFD42A41), airNavy = Color(0xFF3563B8),
    stampPaper = Color(0xFFF4F5F7), dark = true,
)

val LocalInk = staticCompositionLocalOf { LightInk }
private val LocalLabel = staticCompositionLocalOf<FontFamily> { FontFamily.SansSerif }

val ink: Ink @Composable @ReadOnlyComposable get() = LocalInk.current

/**
 * Three voices: condensed capitals name things ([label]), mono records facts ([mono]), the system sans is
 * what people wrote ([sans]). Sizes are sp, so they follow the system text size.
 */
@Composable
@ReadOnlyComposable
fun label(size: TextUnit, tracking: TextUnit = 0.em, lineHeight: TextUnit = TextUnit.Unspecified) = TextStyle(
    fontFamily = LocalLabel.current, fontWeight = FontWeight.SemiBold, fontSize = size, letterSpacing = tracking,
    lineHeight = lineHeight,
)

fun mono(size: TextUnit, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = FontFamily.Monospace, fontWeight = weight, fontSize = size, fontFeatureSettings = "tnum",
)

fun sans(size: TextUnit, weight: FontWeight = FontWeight.Normal, lineHeight: TextUnit = size * 1.5f) = TextStyle(
    fontFamily = FontFamily.Default, fontWeight = weight, fontSize = size, lineHeight = lineHeight,
)

/** The label face: the Latin capitals, then the Chinese subset for what they lack, then the system's sans */
@Composable
private fun rememberLabelFamily(): FontFamily {
    val ctx = LocalContext.current
    return remember {
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                val latin = android.graphics.fonts.Font.Builder(ctx.resources, R.font.barlow_condensed_600).build()
                val zh = android.graphics.fonts.Font.Builder(ctx.resources, R.font.dropit_label_zh).build()
                FontFamily(
                    Typeface.CustomFallbackBuilder(android.graphics.fonts.FontFamily.Builder(latin).build())
                        .addCustomFallback(android.graphics.fonts.FontFamily.Builder(zh).build())
                        .setSystemFallback("sans-serif")
                        .build(),
                )
            } else {
                // Before Android 10 a typeface cannot name its own fallback: Chinese labels use the system face
                FontFamily(ResourcesCompat.getFont(ctx, R.font.barlow_condensed_600)!!)
            }
        }.getOrDefault(FontFamily.SansSerif)
    }
}

@Composable
fun DropitTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (dark) DarkInk else LightInk
    // Material is only underneath the few system-like pieces used (sheets, dialogs): give it the same paper
    val material = if (dark) {
        darkColorScheme(primary = colors.navy, surface = colors.paper, background = colors.ground, onSurface = colors.ink, onBackground = colors.ink, error = colors.red)
    } else {
        lightColorScheme(primary = colors.navy, surface = colors.paper, background = colors.ground, onSurface = colors.ink, onBackground = colors.ink, error = colors.red)
    }
    CompositionLocalProvider(
        LocalInk provides colors,
        LocalLabel provides rememberLabelFamily(),
        LocalTextSelectionColors provides TextSelectionColors(handleColor = colors.navy, backgroundColor = colors.wash.copy(alpha = .3f)),
    ) {
        MaterialTheme(colorScheme = material, content = content)
    }
}
