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
    val Down by lazy { line("down", "M12 4v11M7 11l5 5 5-5M5 20h14") }
    val Copy by lazy { line("copy", "M11.5 9h6a2.5 2.5 0 0 1 2.5 2.5v6a2.5 2.5 0 0 1-2.5 2.5h-6A2.5 2.5 0 0 1 9 17.5v-6A2.5 2.5 0 0 1 11.5 9z", "M5 15V6.5A2.5 2.5 0 0 1 7.5 4H15") }
    val Pull by lazy { line("pull", "M20 12a8 8 0 1 1-2.4-5.7M20 4v5h-5") }
    val Plus by lazy { line("plus", "M12 5v14M5 12h14") }
    val Share by lazy { line("share", circle(6f, 12f, 2.4f), circle(18f, 6f, 2.4f), circle(18f, 18f, 2.4f), "M8.2 10.9l7.6-3.8M8.2 13.1l7.6 3.8") }
    val Open by lazy { line("open", "M14 4h6v6M20 4l-9 9", "M18 14v4.5a1.5 1.5 0 0 1-1.5 1.5h-11A1.5 1.5 0 0 1 4 18.5v-11A1.5 1.5 0 0 1 5.5 6H10") }
    val Retry by lazy { line("retry", "M20 12a8 8 0 1 1-2.6-5.9M20 4v4.5h-4.5") }
    val Trash by lazy { line("trash", "M5 7h14M10 7V5h4v2M7 7l.8 12h8.4L17 7M10.5 11v5M13.5 11v5") }
    val Link by lazy { line("link", "M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1M14 10a4 4 0 0 0-5.7 0l-3 3A4 4 0 0 0 11 18.7l1-1") }
    val Lines by lazy { line("lines", "M5 6h14M5 11h14M5 16h9") }
    val Picture by lazy { line("picture", "M6 4.5h12A2.5 2.5 0 0 1 20.5 7v10a2.5 2.5 0 0 1-2.5 2.5H6A2.5 2.5 0 0 1 3.5 17V7A2.5 2.5 0 0 1 6 4.5z", circle(9f, 10f, 1.7f), "M4 17.5l5-4.5 3.5 3 3-2.5 4.5 4") }
    val Gear by lazy {
        line(
            "gear", circle(12f, 12f, 3f),
            "M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z",
        )
    }
    val Up by lazy { line("up", "M12 19V6M6.5 11.5L12 6l5.5 5.5") }
    val Menu by lazy { line("menu", "M4 7h16M4 12h16M4 17h16") }
    val Check by lazy { line("check", "M5 12.5l4.5 4.5L19 7") }
    val Alert by lazy { line("alert", "M12 3.5a8.5 8.5 0 1 0 0 17 8.5 8.5 0 0 0 0-17z", "M12 8v5", "M12 16v.01") }
    val Scan by lazy { line("scan", "M4 8V5a1 1 0 0 1 1-1h3M16 4h3a1 1 0 0 1 1 1v3M20 16v3a1 1 0 0 1-1 1h-3M8 20H5a1 1 0 0 1-1-1v-3", "M8 12h8") }
    val Torch by lazy { line("torch", "M8 3h8l-1 6H9z", "M9 9h6v11a1 1 0 0 1-1 1h-4a1 1 0 0 1-1-1z", "M12 13v3") }
    val Dots by lazy { line("dots", circle(5f, 12f, 1f), circle(12f, 12f, 1f), circle(19f, 12f, 1f)) }
    val Back by lazy { line("back", "M15 5l-7 7 7 7") }
    val Chevron by lazy { line("chevron", "M9 5l7 7-7 7") }
    val External by lazy { line("external", "M8 16L17 7M9 7h8v8") }
    val X by lazy { line("x", "M6 6l12 12M18 6L6 18") }

    // The settings page and the panels: one tile each
    val Star by lazy { line("star", "M12 3.8l2.5 5.2 5.7.8-4.1 4 1 5.7-5.1-2.7-5.1 2.7 1-5.7-4.1-4 5.7-.8z") }
    val Bell by lazy { line("bell", "M6 16.5V11a6 6 0 0 1 12 0v5.5l1.5 1.5h-15z", "M10 20.5a2 2 0 0 0 4 0") }
    val Bolt by lazy { line("bolt", "M13 3L5.5 13.5H12L11 21l7.5-10.5H12z") }
    val Tiles by lazy {
        line(
            "tiles",
            "M5.5 4h3A1.5 1.5 0 0 1 10 5.5v3A1.5 1.5 0 0 1 8.5 10h-3A1.5 1.5 0 0 1 4 8.5v-3A1.5 1.5 0 0 1 5.5 4z",
            "M15.5 4h3A1.5 1.5 0 0 1 20 5.5v3A1.5 1.5 0 0 1 18.5 10h-3A1.5 1.5 0 0 1 14 8.5v-3A1.5 1.5 0 0 1 15.5 4z",
            "M5.5 14h3A1.5 1.5 0 0 1 10 15.5v3A1.5 1.5 0 0 1 8.5 20h-3A1.5 1.5 0 0 1 4 18.5v-3A1.5 1.5 0 0 1 5.5 14z",
            "M17 14v6M14 17h6",
        )
    }
    val Devices by lazy {
        line(
            "devices",
            "M4 5.5h11A1.5 1.5 0 0 1 16.5 7v7a1.5 1.5 0 0 1-1.5 1.5H4A1.5 1.5 0 0 1 2.5 14V7A1.5 1.5 0 0 1 4 5.5z",
            "M18.5 9.5h1a1.5 1.5 0 0 1 1.5 1.5v6a1.5 1.5 0 0 1-1.5 1.5h-1a1.5 1.5 0 0 1-1.5-1.5v-6a1.5 1.5 0 0 1 1.5-1.5z",
            "M7 18.5h5M9.5 15.5v3",
        )
    }
    val Target by lazy { line("target", circle(12f, 12f, 8.5f), circle(12f, 12f, 3.2f)) }
    val Phone by lazy {
        line("phone", "M8.5 3.5h7A1.5 1.5 0 0 1 17 5v14a1.5 1.5 0 0 1-1.5 1.5h-7A1.5 1.5 0 0 1 7 19V5a1.5 1.5 0 0 1 1.5-1.5z", "M10.5 17h3")
    }
    val Tap by lazy {
        line("tap", "M8.5 3.5h7A1.5 1.5 0 0 1 17 5v10.5a1.5 1.5 0 0 1-1.5 1.5h-7A1.5 1.5 0 0 1 7 15.5V5a1.5 1.5 0 0 1 1.5-1.5z", "M10.5 14h3", "M9 20.5a4.5 1.6 0 0 0 6 0")
    }
    val Globe by lazy { line("globe", circle(12f, 12f, 8.5f), "M3.5 12h17", "M12 3.5c2.5 2.6 3.8 5.4 3.8 8.5s-1.3 5.9-3.8 8.5c-2.5-2.6-3.8-5.4-3.8-8.5S9.5 6.1 12 3.5z") }
    val Info by lazy { line("info", circle(12f, 12f, 8.5f), "M12 11v5.5", "M12 7.7v.01") }
    val Lock by lazy {
        line("lock", "M7.5 11V8.5a4.5 4.5 0 0 1 9 0V11", "M7 11h10a1.5 1.5 0 0 1 1.5 1.5v6A1.5 1.5 0 0 1 17 20H7a1.5 1.5 0 0 1-1.5-1.5v-6A1.5 1.5 0 0 1 7 11z")
    }
    val Code by lazy { line("code", "M8.5 7.5L4 12l4.5 4.5M15.5 7.5L20 12l-4.5 4.5M13.5 5l-3 14") }
    val SignOut by lazy { line("signout", "M10 4H6.5A1.5 1.5 0 0 0 5 5.5v13A1.5 1.5 0 0 0 6.5 20H10", "M14 8l4 4-4 4M18 12H9.5") }
    val Laptop by lazy { line("laptop", "M6 6h12a1 1 0 0 1 1 1v8H5V7a1 1 0 0 1 1-1z", "M3 18.5h18") }
    val Browser by lazy {
        line("browser", "M5.5 5h13A1.5 1.5 0 0 1 20 6.5v11a1.5 1.5 0 0 1-1.5 1.5h-13A1.5 1.5 0 0 1 4 17.5v-11A1.5 1.5 0 0 1 5.5 5z", "M4 9h16")
    }
    val Terminal by lazy {
        line("terminal", "M5.5 5h13A1.5 1.5 0 0 1 20 6.5v11a1.5 1.5 0 0 1-1.5 1.5h-13A1.5 1.5 0 0 1 4 17.5v-11A1.5 1.5 0 0 1 5.5 5z", "M8 10l3 2.5L8 15M13 15h3.5")
    }
    /** An arrow into a wall: send it on once more */
    val Again by lazy { line("again", "M4 12h12M11 7l5 5-5 5M20 5v14") }
    val ChevronDown by lazy { line("chevrondown", "M6 9l6 6 6-6") }
    val Clock by lazy { line("clock", circle(12f, 12f, 8.5f), "M12 7.5V12l3 2") }
}

@Composable
fun Glyph(icon: ImageVector, tint: Color, modifier: Modifier = Modifier, size: Dp = 18.dp, name: String? = null) {
    Image(icon, contentDescription = name, colorFilter = ColorFilter.tint(tint), modifier = modifier.size(size))
}
