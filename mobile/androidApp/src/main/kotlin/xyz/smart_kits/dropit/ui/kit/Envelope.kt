package xyz.smart_kits.dropit.ui.kit

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import xyz.smart_kits.dropit.ui.theme.Ink
import xyz.smart_kits.dropit.ui.theme.ink

/** The stripe's bars along its run: a colour bar and a paper gap, twice (red, then navy) */
enum class Stripe(val bar: Dp, val gap: Dp, val ring: Dp) {
    Phone(9.dp, 6.dp, 7.dp),
    Card(7.dp, 5.dp, 5.dp),
}

/** Whether the person turned animations off in the system: then nothing here moves */
@Composable
fun reducedMotion(): Boolean {
    val ctx = LocalContext.current
    return remember { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
}

/**
 * The airmail envelope: paper framed in the red-and-navy stripe. It frames only what takes drops in.
 * While something is on its way ([transit]) the stripe runs like a barber pole.
 */
@Composable
fun Envelope(
    modifier: Modifier = Modifier,
    transit: Boolean = false,
    stripe: Stripe = Stripe.Phone,
    padding: PaddingValues = PaddingValues(start = 12.dp, top = 14.dp, end = 12.dp, bottom = 12.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = ink
    val phase: State<Float> = if (transit && !reducedMotion()) {
        rememberInfiniteTransition(label = "transit").animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "stripe")
    } else {
        remember { mutableFloatStateOf(0f) }
    }
    Column(
        modifier
            .shadow(if (colors.dark) 8.dp else 5.dp, RectangleShape, ambientColor = Color.Black.copy(alpha = .35f), spotColor = Color.Black.copy(alpha = .35f))
            .drawBehind { envelope(colors, stripe, phase.value) }
            .padding(stripe.ring)
            .padding(padding),
        content = content,
    )
}

private fun DrawScope.envelope(colors: Ink, stripe: Stripe, phase: Float) {
    drawRect(colors.paper)
    val ring = stripe.ring.toPx()
    val frame = Path().apply {
        fillType = PathFillType.EvenOdd
        addRect(Rect(Offset.Zero, size))
        addRect(Rect(ring, ring, size.width - ring, size.height - ring))
    }
    val bar = stripe.bar.toPx()
    val period = 2 * (bar + stripe.gap.toPx())
    clipPath(frame) {
        // Turned 45°, a bar at a fixed distance along the turned x-axis runs from top-right to bottom-left
        rotate(45f, pivot = Offset.Zero) {
            val reach = (size.width + size.height) * .7072f
            var u = -period + phase * period
            while (u < reach) {
                drawRect(colors.airRed, Offset(u, -size.width), Size(bar, size.width + size.height))
                drawRect(colors.airNavy, Offset(u + period / 2, -size.width), Size(bar, size.width + size.height))
                u += period
            }
        }
    }
}
