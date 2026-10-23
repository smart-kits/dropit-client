package xyz.smart_kits.dropit.ui.kit

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import xyz.smart_kits.dropit.R
import xyz.smart_kits.dropit.shared.TargetWords
import xyz.smart_kits.dropit.ui.theme.ink
import xyz.smart_kits.dropit.ui.theme.label
import xyz.smart_kits.dropit.ui.theme.sans

/** How wide the send block is: 96 dp, a little more on a narrow screen (360 dp or less) or at a large text size */
@Composable
fun sendBlockWidth(): androidx.compose.ui.unit.Dp {
    val narrow = LocalConfiguration.current.screenWidthDp <= 360 || LocalDensity.current.fontScale >= 1.3f
    return if (narrow) 106.dp else 96.dp
}

/** Where a send goes by default, for the lower part of the button: [words] says it, [count] is how many devices when it is not everyone */
class Dest(val words: TargetWords, val count: Int)

/**
 * The send button: one block, two parts one above the other, the same width, with one set of rounded corners and a thin white line
 * between. The upper, red part sends to the default in one press (a long press opens the panel too); the lower, pale-blue part says where
 * that is (a devices icon and "All", "Mac, Pixel", "2 devices") and opens the panel of devices. Without [dest] (no other device can receive) there is only the red
 * part, and it is the whole block. The block takes the height it is given (the writing box's) and is [wide] 96 dp, a little more on a
 * narrow screen or a large text size, where the words shorten: no arrow, a devices icon and a count, no paper plane.
 *
 * States: pressed (a deeper red, pressed in from the top, the block sinks 2 dp and its shadow shrinks); [busy] (a turning ring and
 * "Sending…" in the red part, a thin bar along the bottom edge, the lower part greyed and dead); [sentText] (the red part turns green for
 * a moment: a check and what was said).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SplitSend(
    busy: Boolean, dest: Dest?, onSend: () -> Unit, onChoose: () -> Unit, modifier: Modifier = Modifier,
    sentText: String? = null, progress: Float? = null,
) {
    val c = ink
    val config = LocalConfiguration.current
    val fs = LocalDensity.current.fontScale
    val narrow = config.screenWidthDp <= 360 || fs >= 1.3f
    val shape = RoundedCornerShape(14.dp)
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val sent = sentText != null && !busy
    // A size in sp that stays at most [cap] once the system's text size has scaled it
    fun capped(base: Float, cap: Float) = (minOf(base * fs, cap) / fs).sp

    val top = when {
        sent -> Color(0xFF26905E) to Color(0xFF1F7A4D)
        pressed -> Color(0xFFA60B24) to Color(0xFFB30D28)
        c.dark -> Color(0xFFD02A40) to Color(0xFFC32036)
        else -> Color(0xFFD6173B) to Color(0xFFC8102E)
    }
    val glow = if (sent) Color(0xFF1F7A4D) else Color(0xFFC8102E)
    val where = when {
        dest == null -> ""
        dest.words is TargetWords.All -> stringResource(R.string.target_all)
        dest.words is TargetWords.Names -> dest.words.text
        else -> pluralStringResource(R.plurals.target_count, dest.count, dest.count)
    }
    val sendName = stringResource(R.string.send_to_desc, where.ifEmpty { stringResource(R.string.target_all) })
    val chooseName = stringResource(R.string.dest_pick_desc, where)

    BoxWithConstraints(
        modifier.width(sendBlockWidth())
            .graphicsLayer { if (pressed) { translationY = 2.dp.toPx(); scaleX = .99f; scaleY = .99f } }
            .shadow(if (pressed) 2.dp else 8.dp, shape, ambientColor = glow.copy(alpha = .5f), spotColor = glow.copy(alpha = .65f))
            .clip(shape),
    ) {
        // Sending at once is the common thing and choosing devices the rare one: the lower part is a slim 36 dp (a little more at large text
        // sizes, never more than 40 % of the block), and the red part keeps the rest (72 dp of a 108 dp block)
        val lowerH = (36.dp + 8.dp * (fs - 1f).coerceIn(0f, 1f)).coerceAtMost(maxHeight * 0.4f)
        Column(Modifier.fillMaxWidth().height(maxHeight)) {
            Box(
                Modifier.weight(1f).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(top.first, top.second)))
                    .then(if (pressed) Modifier.drawBehind { drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .3f), Color.Transparent), endY = 12.dp.toPx())) } else Modifier)
                    .combinedClickable(
                        interactionSource = src, indication = null, enabled = !busy && !sent, role = Role.Button,
                        onClick = onSend, onLongClick = if (dest != null) onChoose else null,
                    )
                    .semantics { contentDescription = sendName },
                contentAlignment = Alignment.Center,
            ) {
                when {
                    busy -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Spinner(Color.White, 20.dp)
                        Txt(stringResource(R.string.sending), sans(13.5.sp, FontWeight.SemiBold, lineHeight = 15.sp), Color.White, maxLines = 2, align = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(horizontal = 4.dp))
                    }
                    sent -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Glyph(Icons.Check, Color.White, size = 22.dp)
                        Txt(sentText!!, sans(14.sp, FontWeight.SemiBold, lineHeight = 15.sp), Color.White, maxLines = 1)
                    }
                    else -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (!narrow) Glyph(Icons.Send, Color.White, size = 21.dp)
                        Txt(stringResource(R.string.label_send).uppercase(), label(capped(21f, 27f), tracking = .08.em), Color.White, maxLines = 1)
                    }
                }
            }
            if (dest != null) {
                val tint = if (sent) Color(0xFF1F7A4D) else c.navy
                val fill = tint.copy(alpha = if (sent) .15f else if (c.dark) .22f else .14f).compositeOver(c.paper)
                val seam = Color.White.copy(alpha = if (c.dark) .18f else .75f)
                Box(
                    Modifier.fillMaxWidth().height(lowerH).background(fill).alpha(if (busy) .45f else 1f)
                        .drawBehind { drawRect(seam, Offset.Zero, Size(size.width, 1.5.dp.toPx())) }
                        .combinedClickable(enabled = !busy, role = Role.Button, onClick = onChoose)
                        .semantics { contentDescription = chooseName },
                    contentAlignment = Alignment.Center,
                ) {
                    // A devices icon and where it goes ("All", "Mac, Pixel", "2 devices"; just the count on a narrow screen), and a small low-contrast
                    // arrow that says it opens
                    Row(Modifier.padding(horizontal = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        val style = sans(capped(13.5f, 17f), FontWeight.SemiBold, lineHeight = 17.sp).copy(fontFeatureSettings = "tnum")
                        Glyph(Icons.Devices, tint, size = 16.dp)
                        val text = when {
                            dest.words is TargetWords.All -> stringResource(R.string.target_all)
                            narrow -> dest.count.toString()
                            dest.words is TargetWords.Names -> dest.words.text
                            else -> pluralStringResource(R.plurals.target_count, dest.count, dest.count)
                        }
                        Txt(text, style, tint, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (!narrow) Glyph(Icons.ChevronDown, tint.copy(alpha = .6f), size = 12.dp)
                    }
                }
            }
        }
        // A thin bar along the bottom edge while it goes: how far when that is known, otherwise one that moves
        if (busy && progress != null) {
            val moving = rememberInfiniteTransition(label = "wait").animateFloat(.15f, .85f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse), label = "bar").value
            val fraction = if (progress < 0f) (if (reducedMotion()) .3f else moving) else progress.coerceIn(0f, 1f)
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(fraction).height(3.dp).background(c.navy.copy(alpha = .85f)))
        }
    }
}
