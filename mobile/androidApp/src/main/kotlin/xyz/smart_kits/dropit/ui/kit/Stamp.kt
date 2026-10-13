package xyz.smart_kits.dropit.ui.kit

import android.graphics.BlurMaskFilter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import xyz.smart_kits.dropit.ui.theme.StampFace
import xyz.smart_kits.dropit.ui.theme.ink
import xyz.smart_kits.dropit.ui.theme.label
import kotlin.math.roundToInt

/**
 * The send button, and the only primary action on a screen that sends: a real stamp. Red printed face with
 * its white double frame, a perforated paper edge, a little off-true at rest; it straightens and sinks when
 * pressed and stays down while [busy]. It never becomes a full-width bar, and never fades when it can't be
 * used.
 */
@Composable
fun Stamp(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    width: Dp = 112.dp,
    height: Dp = 60.dp,
    fontSize: TextUnit = 20.sp,
) {
    val paper = ink.stampPaper
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val down = busy || pressed
    val still = reducedMotion()
    val sink by animateFloatAsState(if (down) 1f else 0f, tween(if (still) 0 else 180), label = "stamp")
    Box(
        modifier
            .size(width, height)
            .graphicsLayer {
                rotationZ = -1.5f * (1 - sink)
                translationY = 2.dp.toPx() * sink
                scaleX = 1 - .03f * sink
                scaleY = 1 - .03f * sink
            }
            .drawWithCache {
                val hole = 3.dp.toPx()
                val edge = perforated(size, hole)
                val margin = 5.dp.toPx()
                val shadow = Paint().asFrameworkPaint().apply { isAntiAlias = true }
                onDrawBehind {
                    // A separate piece of paper stuck on: its shadow follows the perforated outline, tighter when pressed
                    shadow.color = Color.Black.copy(alpha = .18f + .04f * sink).toArgb()
                    shadow.maskFilter = BlurMaskFilter((3.dp.toPx() * (1 - .6f * sink)).coerceAtLeast(.5f), BlurMaskFilter.Blur.NORMAL)
                    drawIntoCanvas { c ->
                        c.nativeCanvas.save()
                        c.nativeCanvas.translate(0f, 2.dp.toPx() * (1 - .5f * sink))
                        c.nativeCanvas.drawPath(edge.asAndroidPath(), shadow)
                        c.nativeCanvas.restore()
                    }
                    drawPath(edge, paper)
                    val face = Size(size.width - 2 * margin, size.height - 2 * margin)
                    drawRect(StampFace, Offset(margin, margin), face)
                    // The printed double frame: a 2dp white line at the edge of the face, a hairline 4dp in
                    val w2 = 2.dp.toPx()
                    drawRect(Color.White.copy(alpha = .55f), Offset(margin + w2 / 2, margin + w2 / 2), Size(face.width - w2, face.height - w2), style = Stroke(w2))
                    val inset = 4.5.dp.toPx()
                    drawRoundRect(Color.White.copy(alpha = .35f), Offset(margin + inset, margin + inset), Size(face.width - 2 * inset, face.height - 2 * inset), CornerRadius.Zero, style = Stroke(1.dp.toPx()))
                }
            }
            .clickable(source, indication = null, enabled = !busy, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(text, style = label(fontSize, tracking = .06.em).copy(color = Color.White, textAlign = TextAlign.Center), maxLines = 1)
    }
}

/** A rectangle with half-circles bitten out all along its edge, evenly, a hole on every corner */
private fun perforated(size: Size, hole: Float): Path {
    val holes = Path()
    fun along(length: Float, at: (Float) -> Offset) {
        val n = (length / (2 * hole)).roundToInt().coerceAtLeast(1)
        for (k in 0..n) holes.addOval(Rect(at(length * k / n), hole))
    }
    along(size.width) { Offset(it, 0f) }
    along(size.width) { Offset(it, size.height) }
    along(size.height) { Offset(0f, it) }
    along(size.height) { Offset(size.width, it) }
    return Path.combine(PathOperation.Difference, Path().apply { addRect(Rect(Offset.Zero, size)) }, holes)
}
