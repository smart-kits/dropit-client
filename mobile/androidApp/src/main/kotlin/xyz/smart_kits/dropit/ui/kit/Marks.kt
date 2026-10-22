package xyz.smart_kits.dropit.ui.kit

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import xyz.smart_kits.dropit.R
import xyz.smart_kits.dropit.ui.theme.StampFace
import xyz.smart_kits.dropit.ui.theme.ink
import xyz.smart_kits.dropit.ui.theme.label
import xyz.smart_kits.dropit.ui.theme.mono
import xyz.smart_kits.dropit.ui.theme.sans
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * What did not go out, said where it was sent from: the red RETURNED mark, struck a little off-true, and
 * one line per reason. It stays until the next edit or send, and what was written stays with it.
 */
@Composable
fun Returned(reasons: List<String>, modifier: Modifier = Modifier) {
    val red = ink.red
    Row(
        modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Assertive },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Txt(
            stringResource(R.string.label_returned).uppercase(),
            label(14.sp, tracking = .08.em),
            red,
            Modifier.rotate(-3f).border(2.dp, red, RoundedCornerShape(3.dp)).padding(horizontal = 7.dp, vertical = 1.dp),
        )
        Column(Modifier.weight(1f).padding(top = 1.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            for (r in reasons) Txt(r, sans(13.sp, FontWeight.SemiBold, lineHeight = 19.sp), red)
        }
    }
}

/**
 * The record of arrival on a received letter: a navy double ring holding date, time and where it came
 * from, struck at a tilt that differs from letter to letter. Read out as one sentence.
 */
@Composable
fun Postmark(date: String, time: String, source: String, seq: Long, modifier: Modifier = Modifier, size: Dp = 72.dp) {
    val mark = ink.mark.copy(alpha = .9f)
    val spoken = stringResource(R.string.postmark_name, date, time, source)
    Column(
        modifier
            .size(size)
            .rotate(postmarkTilt(seq))
            .drawBehind {
                drawCircle(mark, radius = this.size.minDimension / 2 - 1.dp.toPx(), style = Stroke(2.dp.toPx()))
                drawCircle(mark, radius = this.size.minDimension / 2 - 4.dp.toPx(), style = Stroke(1.dp.toPx()))
            }
            .clearAndSetSemantics { contentDescription = spoken },
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Txt(date, mono(10.sp, FontWeight.SemiBold), mark, maxLines = 1)
        Txt(time, mono(12.sp, FontWeight.Bold), mark, maxLines = 1)
        Txt(source.uppercase(), mono(9.sp, FontWeight.SemiBold), mark, Modifier.widthIn(max = size - 20.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** -6° to -18°, by the letter's number: no two land alike, and the same letter always lands the same way */
fun postmarkTilt(seq: Long): Float = -6f - ((seq * 7) % 13).toFloat()

/** A small tag in a rounded outline: navy for "this phone", grey for the rest */
@Composable
fun Tag(text: String, modifier: Modifier = Modifier, self: Boolean = false) {
    val c = if (self) ink.navy else ink.dim
    Txt(
        text, sans(12.sp, FontWeight.SemiBold, lineHeight = 15.sp), c,
        modifier.border(1.dp, if (self) ink.navy else ink.line, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
        maxLines = 1,
    )
}

/**
 * The sale sticker: a red sixteen-point starburst slapped on a buy button's corner. It means a deal and
 * nothing else. [small] sits above [big] ("SAVE" / "57%"); with one word, pass it as [big].
 */
@Composable
fun Burst(big: String, modifier: Modifier = Modifier, small: String? = null, size: Dp = 46.dp) {
    Box(
        modifier
            .size(size)
            .rotate(14f)
            .drawBehind {
                val path = Path()
                val c = Offset(this.size.width / 2, this.size.height / 2)
                val outer = this.size.minDimension / 2
                val inner = outer * .82f
                for (k in 0 until 32) {
                    val r = if (k % 2 == 0) outer else inner
                    val a = k * PI / 16 - PI / 2
                    val p = Offset(c.x + (r * cos(a)).toFloat(), c.y + (r * sin(a)).toFloat())
                    if (k == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                }
                path.close()
                drawPath(path, StampFace)
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (small != null) Txt(small, label(10.sp), Color.White, maxLines = 1)
            Txt(big, label(if (small != null) 14.sp else 11.5.sp), Color.White, maxLines = 1, align = TextAlign.Center)
        }
    }
}

/**
 * Something that did not work, that is not a letter coming back: a list that would not load, a checkout that would not open, an
 * account that could not be made. A plain red line with a mark in front. The "Returned" stamp is for what was posted and could not
 * be delivered, and only that.
 */
@Composable
fun ErrorLine(text: String, modifier: Modifier = Modifier) {
    val red = ink.red
    Row(
        modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Assertive },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Glyph(Icons.Alert, red, Modifier.padding(top = 1.dp), size = 18.dp)
        Txt(text, sans(13.sp, FontWeight.SemiBold, lineHeight = 19.sp), red, Modifier.weight(1f))
    }
}
