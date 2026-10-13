package xyz.smart_kits.dropit.ui.kit

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Line icons, all one family: a 24-unit grid, 1.75 stroke, round ends. Drawn here so nothing is downloaded
// or bundled as pictures. Each is a lazy value: built the first time it is shown.

private fun line(name: String, vararg paths: String): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        for (p in paths) {
            addPath(
                pathData = addPathNodes(p), stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            )
        }
    }.build()

private fun circle(cx: Float, cy: Float, r: Float) = "M${cx + r} ${cy}a$r $r 0 1 1 ${-2 * r} 0a$r $r 0 1 1 ${2 * r} 0z"

object Icons {
    val Clip by lazy { line("clip", "M20.5 11.5l-8.2 8.2a5 5 0 0 1-7-7l8.5-8.5a3.3 3.3 0 0 1 4.7 4.7l-8.5 8.5a1.7 1.7 0 0 1-2.4-2.4l7.8-7.8") }
    val File by lazy { line("file", "M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z", "M14 3v5h5") }
    val Copy by lazy { line("copy", "M11 9h7a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2h-7a2 2 0 0 1-2-2v-7a2 2 0 0 1 2-2z", "M5 15V6a2 2 0 0 1 2-2h8") }
    val Pull by lazy { line("pull", "M20 12a8 8 0 1 1-2.4-5.7M20 4v5h-5") }
    val Plus by lazy { line("plus", "M12 5v14M5 12h14") }
    val Share by lazy { line("share", circle(18f, 5f, 2.5f), circle(6f, 12f, 2.5f), circle(18f, 19f, 2.5f), "M8.2 10.8l7.6-4.4M8.2 13.2l7.6 4.4") }
    val Open by lazy { line("open", "M14 4h6v6M20 4l-9 9", "M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5") }
    val Gear by lazy {
        line(
            "gear", circle(12f, 12f, 3f),
            "M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z",
        )
    }
    val Scan by lazy { line("scan", "M4 8V5a1 1 0 0 1 1-1h3M16 4h3a1 1 0 0 1 1 1v3M20 16v3a1 1 0 0 1-1 1h-3M8 20H5a1 1 0 0 1-1-1v-3", "M8 12h8") }
    val Torch by lazy { line("torch", "M8 3h8l-1 6H9z", "M9 9h6v11a1 1 0 0 1-1 1h-4a1 1 0 0 1-1-1z", "M12 13v3") }
    val Dots by lazy { line("dots", circle(5f, 12f, 1f), circle(12f, 12f, 1f), circle(19f, 12f, 1f)) }
    val Back by lazy { line("back", "M15 5l-7 7 7 7") }
    val Chevron by lazy { line("chevron", "M9 5l7 7-7 7") }
    val External by lazy { line("external", "M8 16L17 7M9 7h8v8") }
    val X by lazy { line("x", "M6 6l12 12M18 6L6 18") }
}

@Composable
fun Glyph(icon: ImageVector, tint: Color, modifier: Modifier = Modifier, size: Dp = 18.dp, name: String? = null) {
    Image(icon, contentDescription = name, colorFilter = ColorFilter.tint(tint), modifier = modifier.size(size))
}
