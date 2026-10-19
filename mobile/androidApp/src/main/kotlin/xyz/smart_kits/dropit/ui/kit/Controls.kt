package xyz.smart_kits.dropit.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import xyz.smart_kits.dropit.R
import xyz.smart_kits.dropit.ui.theme.ink
import xyz.smart_kits.dropit.ui.theme.label
import xyz.smart_kits.dropit.ui.theme.mono
import xyz.smart_kits.dropit.ui.theme.sans

/** Text in one of the three voices; the colour is said every time, so nothing inherits one by accident */
@Composable
fun Txt(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    align: TextAlign? = null,
) {
    BasicText(text, modifier, style.copy(color = color, textAlign = align ?: TextAlign.Unspecified), maxLines = maxLines, overflow = overflow)
}

/**
 * Ink fill for the one thing to do on a screen that has no stamp; hairline outline for the rest. With [tint] (the brand blue, for
 * the ways to pay) the fill is that colour, and an outline button is drawn in it, a little heavier
 */
@Composable
fun Btn(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    solid: Boolean = false,
    enabled: Boolean = true,
    small: Boolean = false,
    icon: ImageVector? = null,
    tint: Color? = null,
    /** Working on it: a small spinner in front, and no second press */
    loading: Boolean = false,
) {
    val c = ink
    val shape = RoundedCornerShape(6.dp)
    val accent = tint ?: c.ink
    val live = enabled && !loading
    Row(
        modifier
            .alpha(if (enabled) 1f else .6f)
            .clip(shape)
            .background(if (solid) accent else c.paper)
            .border(if (tint != null && !solid) 1.5.dp else 1.dp, if (solid) accent else tint ?: c.line, shape)
            .clickable(enabled = live, role = Role.Button, onClick = onClick)
            .defaultMinSize(minHeight = if (small) 34.dp else 44.dp)
            .padding(horizontal = if (small) 12.dp else 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val fg = if (solid) c.paper else tint ?: c.ink
        if (loading) Spinner(fg) else if (icon != null) Glyph(icon, fg)
        Txt(text, sans(if (small) 13.sp else 15.sp, if (solid) FontWeight.SemiBold else FontWeight.Normal, lineHeight = 20.sp), fg, align = TextAlign.Center)
    }
}

/** A small turning ring in the colour of the words next to it */
@Composable
fun Spinner(color: Color, size: Dp = 16.dp, modifier: Modifier = Modifier) {
    androidx.compose.material3.CircularProgressIndicator(modifier.size(size), color = color, strokeWidth = 2.dp)
}

/** A quiet action in a row: pencil-grey words, an icon in front if it has one. Red only for removing. */
@Composable
fun LinkAction(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    text: String? = null,
    icon: ImageVector? = null,
    color: Color = ink.dim,
    name: String? = null,
    enabled: Boolean = true,
    underline: Boolean = false,
    iconSize: androidx.compose.ui.unit.Dp = 18.dp,
    minSize: androidx.compose.ui.unit.Dp = 40.dp,
    /** Working on it: the icon turns into a small spinner */
    loading: Boolean = false,
) {
    Row(
        modifier
            .alpha(if (enabled) 1f else .5f)
            .clip(RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = name, onClick = onClick)
            .defaultMinSize(minWidth = minSize, minHeight = minSize)
            .padding(horizontal = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) Spinner(color, iconSize - 4.dp) else if (icon != null) Glyph(icon, color, size = iconSize, name = if (text == null) name else null)
        if (text != null) {
            Txt(text, sans(13.sp).let { if (underline) it.copy(textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline) else it }, color, maxLines = 1)
        }
    }
}

/** A section's heading row: the heading, a count in mono, actions pushed to the right */
@Composable
fun SectionHead(title: String, modifier: Modifier = Modifier, count: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(title.uppercase(), label(19.sp, tracking = .02.em), ink.ink, Modifier.semantics { heading() })
        if (count != null) Txt(count, mono(12.sp), ink.dim, Modifier.padding(start = 8.dp))
        Spacer(Modifier.weight(1f))
        actions()
    }
}

/** The top of a screen one step in: back, the screen's name, something at the far end */
@Composable
fun TitleBar(title: String, onBack: () -> Unit, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        LinkAction(onBack, Modifier.offset(x = (-10).dp), icon = Icons.Back, name = stringResource(R.string.back), color = ink.dim)
        Txt(title.uppercase(), label(24.sp, tracking = .01.em), ink.ink, Modifier.offset(x = (-8).dp).semantics { heading() })
        Spacer(Modifier.weight(1f))
        trailing()
    }
}

/** A group's small heading above a panel */
@Composable
fun GroupHead(text: String) {
    Txt(text.uppercase(), label(13.sp, tracking = .08.em), ink.dim, Modifier.padding(start = 2.dp, top = 16.dp, bottom = 6.dp).semantics { heading() })
}

/** A paper panel with a hairline: rows inside are divided by hairlines */
@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(3.dp)).background(ink.paper).border(1.dp, ink.line, RoundedCornerShape(3.dp)), content = content)
}

@Composable
fun PanelDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(ink.line))
}

/** One row of a panel: what it is, a pencil line under it, and what sits at the far end */
@Composable
fun PanelRow(
    title: String,
    modifier: Modifier = Modifier,
    sub: String? = null,
    subColor: Color = ink.dim,
    titleColor: Color = ink.ink,
    onClick: (() -> Unit)? = null,
    extra: (@Composable ColumnScope.() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .heightIn(min = 52.dp)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Txt(title, sans(14.sp, lineHeight = 20.sp), titleColor)
            if (sub != null) Txt(sub, sans(12.sp, lineHeight = 17.sp), subColor)
            extra?.invoke(this)
        }
        trailing()
    }
}

/** On or off. Navy when on: it means "arrivals are on" wherever it is used. */
@Composable
fun Toggle(checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, name: String? = null) {
    val c = ink
    Box(
        modifier
            .size(width = 48.dp, height = 40.dp)
            .toggleable(checked, remember { MutableInteractionSource() }, indication = null, role = Role.Switch, onValueChange = onChange)
            // Read out with its name (the row's text is a separate node: a bare switch says only "switch")
            .semantics { if (name != null) contentDescription = name },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(width = 38.dp, height = 22.dp).clip(CircleShape).background(if (checked) c.navy else c.line)) {
            Box(Modifier.padding(3.dp).offset(x = if (checked) 16.dp else 0.dp).size(16.dp).clip(CircleShape).background(c.paper))
        }
    }
}

/** A thin bar: how much of something is used */
@Composable
fun Meter(fraction: Float, modifier: Modifier = Modifier) {
    val c = ink
    Box(modifier.fillMaxWidth().padding(top = 5.dp).height(5.dp).clip(RoundedCornerShape(3.dp)).background(c.line)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(5.dp).background(c.ink.copy(alpha = .7f)))
    }
}

/** A setup step left undone, said in one line under the header, with the one tap that does it */
@Composable
fun Tip(text: String, modifier: Modifier = Modifier, action: String? = null, onAction: () -> Unit = {}, onDismiss: (() -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(3.dp)).background(ink.paper).border(1.dp, ink.line, RoundedCornerShape(3.dp)).padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Txt(text, sans(12.5.sp, lineHeight = 18.sp), ink.dim, Modifier.weight(1f).padding(vertical = 4.dp))
        if (action != null) Btn(action, onAction, small = true)
        if (onDismiss != null) LinkAction(onDismiss, icon = Icons.X, name = stringResource(R.string.dismiss))
    }
}

/** A dashed outline means "put something here": the place to write, and an empty list */
fun Modifier.dashed(color: Color, radius: Dp = 6.dp, width: Dp = 1.5.dp): Modifier = drawBehind {
    val w = width.toPx()
    drawRoundRect(
        color, Offset(w / 2, w / 2), androidx.compose.ui.geometry.Size(size.width - w, size.height - w), CornerRadius(radius.toPx()),
        style = Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
    )
}

/** Keeps a ripple the colour of the ink it is on, for the few places that want one */
@Composable
fun inkRipple() = ripple(color = ink.ink)
