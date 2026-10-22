package xyz.smart_kits.dropit.ui.kit

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import xyz.smart_kits.dropit.R
import xyz.smart_kits.dropit.ui.theme.ink
import xyz.smart_kits.dropit.ui.theme.label

/**
 * The send button: a square-cornered red button in two parts that touch. The left part sends to the default, in one press; the right part
 * (a small devices icon and an arrow), or a long press on the left, opens the panel of devices. Without [chooser] there is only the left
 * part, as wide as both: with no other device there is nobody to choose. [partial]: how many devices the default has when it is only some
 * of them, written small on the icon so a send that is not to everyone is never a surprise.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SplitSend(
    text: String, busy: Boolean, chooser: Boolean, onSend: () -> Unit, onChoose: () -> Unit, modifier: Modifier = Modifier,
    partial: Int? = null, height: Dp = 56.dp, width: Dp = 128.dp, chooserName: String = stringResource(R.string.panel_choose),
) {
    val c = ink
    val corner = 9.dp
    val face = c.btn
    val seam = Color.White.copy(alpha = .42f)
    // The words grow with the system's text size, so the button does too (a little less than they do)
    val grow = ((LocalDensity.current.fontScale.coerceIn(1f, 1.6f) - 1f) * .6f) + 1f
    Row(modifier.height(height).width(width * grow).alpha(if (busy) .75f else 1f)) {
        Box(
            Modifier.weight(1f).fillMaxHeight()
                .clip(if (chooser) RoundedCornerShape(topStart = corner, bottomStart = corner) else RoundedCornerShape(corner))
                .background(face)
                .combinedClickable(enabled = !busy, role = Role.Button, onClick = onSend, onLongClick = if (chooser) onChoose else null),
            contentAlignment = Alignment.Center,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (busy) Spinner(Color.White, 18.dp)
                // With the choosing part beside it there is only room for the ring while it is on its way
                if (!(busy && chooser)) Txt(text, label(21.sp, tracking = .06.em), Color.White, maxLines = 1)
            }
        }
        if (chooser) {
            Box(Modifier.fillMaxHeight().width(1.dp).background(seam))
            Box(
                Modifier.width(50.dp).fillMaxHeight().clip(RoundedCornerShape(topEnd = corner, bottomEnd = corner)).background(face)
                    .combinedClickable(enabled = !busy, role = Role.Button, onClick = onChoose)
                    .semantics { contentDescription = chooserName },
                contentAlignment = Alignment.Center,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Glyph(Icons.Devices, Color.White, size = 20.dp)
                    Glyph(Icons.ChevronDown, Color.White.copy(alpha = .9f), size = 11.dp)
                }
                if (partial != null) Box(
                    Modifier.align(Alignment.TopEnd).offset(x = (-3).dp, y = 5.dp).size(16.dp).clip(CircleShape).background(Color.White),
                    contentAlignment = Alignment.Center,
                ) { Txt(partial.toString(), label(11.sp), c.btn, maxLines = 1) }
            }
        }
    }
}
