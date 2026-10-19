package xyz.smart_kits.dropit.ui.kit

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import xyz.smart_kits.dropit.R
import xyz.smart_kits.dropit.ui.theme.ink
import xyz.smart_kits.dropit.ui.theme.sans

/** How long a page may wait for what it needs before it says so, and before it gives up and offers to try again */
const val SLOW_AFTER_MS = 4_000L
const val GIVE_UP_AFTER_MS = 45_000L

/** True once [loading] has gone on for [afterMs]: the page then says it is slow, so waiting never looks like being stuck */
@Composable
fun rememberSlow(loading: Boolean, afterMs: Long = SLOW_AFTER_MS): Boolean {
    var slow by remember { mutableStateOf(false) }
    LaunchedEffect(loading) {
        slow = false
        if (loading) { delay(afterMs); slow = true }
    }
    return slow
}

/**
 * What a page shows while it waits for its list: rows in the shape of what is coming, breathing slowly, so the page already has its
 * layout. After a few seconds a line under them says the connection is slow (and keeps turning); the page itself gives up later
 * with an error and a way to try again.
 */
@Composable
fun LoadingRows(slow: Boolean, modifier: Modifier = Modifier, rows: Int = 3) {
    val c = ink
    val pulse by rememberInfiniteTransition(label = "loading").animateFloat(
        initialValue = .45f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse",
    )
    val name = stringResource(R.string.loading)
    Column(modifier.fillMaxWidth().semantics { contentDescription = name; liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(rows) { i ->
            val shape = RoundedCornerShape(3.dp)
            Column(
                Modifier.fillMaxWidth().clip(shape).background(c.paper).border(1.dp, c.line, shape).padding(horizontal = 14.dp, vertical = 12.dp).alpha(pulse),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.width(if (i % 2 == 0) 120.dp else 90.dp).height(14.dp))
                Box(Modifier.fillMaxWidth(if (i % 2 == 0) .7f else .5f).height(11.dp))
            }
        }
        if (slow) {
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Spinner(c.dim, 14.dp)
                Txt(stringResource(R.string.slow_net_load), sans(13.sp), c.dim)
            }
        }
    }
}

/** A grey bar that stands for a line of text still to come */
@Composable
private fun Box(modifier: Modifier) {
    androidx.compose.foundation.layout.Box(modifier.clip(RoundedCornerShape(3.dp)).background(ink.line))
}
