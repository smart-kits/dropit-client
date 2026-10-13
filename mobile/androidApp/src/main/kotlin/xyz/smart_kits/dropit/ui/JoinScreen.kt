package xyz.smart_kits.dropit.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import xyz.smart_kits.dropit.R
import xyz.smart_kits.dropit.app
import xyz.smart_kits.dropit.shared.ApiError
import xyz.smart_kits.dropit.shared.OfflineError
import xyz.smart_kits.dropit.shared.PRIVACY_URL
import xyz.smart_kits.dropit.ui.kit.Btn
import xyz.smart_kits.dropit.ui.kit.Envelope
import xyz.smart_kits.dropit.ui.kit.Glyph
import xyz.smart_kits.dropit.ui.kit.Icons
import xyz.smart_kits.dropit.ui.kit.Returned
import xyz.smart_kits.dropit.ui.kit.Tip
import xyz.smart_kits.dropit.ui.kit.Txt
import xyz.smart_kits.dropit.ui.theme.ink
import xyz.smart_kits.dropit.ui.theme.label
import xyz.smart_kits.dropit.ui.theme.mono
import xyz.smart_kits.dropit.ui.theme.sans

/** The sentence for a failure, in the app's language */
@Composable
internal fun errorText(e: Throwable): String {
    val ctx = LocalContext.current
    return when (e) {
        is ApiError -> ctx.app.strings.render(e.sentence())
        is OfflineError -> ctx.app.strings.render(e.sentence)
        else -> e.message ?: e.toString()
    }
}

/** The mark and the wordmark, at the top of the two main screens */
@Composable
internal fun Brand(modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.ic_logo), contentDescription = null, modifier = Modifier.size(24.dp).clip(RoundedCornerShape(4.dp)))
        Txt("dropit", label(26.sp, tracking = (-.01).em), ink.ink, Modifier.semantics { heading() })
    }
}

/** The code in what was typed or pasted: letters and digits, in capitals, at most six */
internal fun pairingCodeOf(typed: String): String = typed.filter(Char::isLetterOrDigit).uppercase().take(6)

/** Draws letters as capitals, one character for one, so the cursor stays where it was */
private val Capitals = VisualTransformation { text ->
    TransformedText(AnnotatedString(text.text.map(Char::uppercaseChar).joinToString("")), OffsetMapping.Identity)
}

/**
 * Not joined yet. Joining comes first — scan the other device's code or type it; creating an account is a
 * quiet line underneath, because an account made by mistake splits a person's things in two.
 */
@Composable
fun JoinScreen(code: String?, resumed: Int, joinNow: Boolean = false, onJoined: () -> Unit, onScan: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = ink
    var input by remember(code) { mutableStateOf(code.orEmpty()) }
    val typed = pairingCodeOf(input)
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var creating by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }

    fun run(block: suspend () -> Unit) {
        busy = true; error = null
        scope.launch {
            try { block(); onJoined() } catch (e: Throwable) { error = e } finally { busy = false }
        }
    }

    // Scanned with this phone's camera: that was the decision, so it joins straight away. A code that turns
    // out wrong or expired stays in the field with the reason under it
    LaunchedEffect(code, joinNow) { if (joinNow && typed.length == 6 && !busy) run { ctx.app.account.join(typed) } }

    Column(
        Modifier.fillMaxSize().background(c.ground).safeDrawingPadding().imePadding().verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 24.dp),
    ) {
        Brand(Modifier.padding(bottom = 14.dp))
        // Read again each time the screen comes back: a share may have just been held for after joining
        val waiting = remember(resumed) { ctx.app.store.waiting.size }
        if (waiting > 0) {
            Tip(pluralStringResource(R.plurals.join_waiting, waiting, waiting), Modifier.padding(bottom = 12.dp))
        }
        Envelope(Modifier.fillMaxWidth()) {
            Txt(stringResource(R.string.label_join_title), label(28.sp, tracking = (-.01).em, lineHeight = 30.sp), c.ink, Modifier.semantics { heading() })
            Txt(stringResource(R.string.join_help), sans(13.sp, lineHeight = 19.5.sp), c.dim, Modifier.padding(top = 6.dp, bottom = 10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                var focused by remember { mutableStateOf(false) }
                val scanName = stringResource(R.string.scan)
                BasicTextField(
                    value = input,
                    // Kept exactly as the keyboard typed it: rewriting the text while the keyboard is still composing
                    // a word makes it commit that word again ("vc" became "VVC"). Capitals are only drawn, and the
                    // code is cleaned up when read
                    onValueChange = { input = it; error = null },
                    modifier = Modifier.weight(1f).focusRequester(focus).onFocusChanged { focused = it.isFocused },
                    singleLine = true,
                    textStyle = mono(17.sp, FontWeight.SemiBold).copy(color = c.ink, letterSpacing = .12.em),
                    cursorBrush = SolidColor(c.red),
                    visualTransformation = Capitals,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Go,
                    ),
                    keyboardActions = KeyboardActions(onGo = { if (typed.length == 6 && !busy) run { ctx.app.account.join(typed) } }),
                    decorationBox = { field ->
                        Row(
                            Modifier
                                .height(44.dp)
                                .drawBehind {
                                    // Focus: a navy border with a soft navy halo around it, no outline
                                    if (focused) drawRoundRect(c.wash, Offset(-3.dp.toPx(), -3.dp.toPx()), Size(size.width + 6.dp.toPx(), size.height + 6.dp.toPx()), CornerRadius(9.dp.toPx()))
                                }
                                .clip(RoundedCornerShape(6.dp)).background(c.paper)
                                .border(1.dp, if (focused) c.navy else c.line, RoundedCornerShape(6.dp)),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Scanning is the quick way on a phone: it sits inside the field, where the eye already is
                            Box(
                                Modifier.padding(start = 2.dp).size(40.dp).clip(RoundedCornerShape(4.dp))
                                    .clickable(role = Role.Button, onClick = onScan).semantics { contentDescription = scanName },
                                contentAlignment = Alignment.Center,
                            ) { Glyph(Icons.Scan, c.ink, size = 20.dp) }
                            Box(Modifier.weight(1f).padding(end = 10.dp), contentAlignment = Alignment.CenterStart) {
                                if (input.isEmpty()) Txt(stringResource(R.string.join_code), sans(15.sp), c.dim, maxLines = 1)
                                field()
                            }
                        }
                    },
                )
                Btn(stringResource(R.string.join), { run { ctx.app.account.join(typed) } }, solid = true, enabled = !busy && typed.length == 6)
            }
            Row(Modifier.padding(top = 10.dp)) {
                Txt(stringResource(R.string.consent), sans(12.5.sp, lineHeight = 18.5.sp), c.dim, Modifier.weight(1f))
            }
            Txt(
                stringResource(R.string.privacy), sans(12.5.sp).copy(textDecoration = TextDecoration.Underline), c.navy,
                Modifier.padding(top = 2.dp).clickable(role = Role.Button) { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_URL))) }.padding(vertical = 8.dp),
            )
            error?.let { Returned(listOf(errorText(it)), Modifier.padding(top = 4.dp)) }
        }
        Spacer(Modifier.height(14.dp))
        if (!creating) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Txt(stringResource(R.string.first_device) + " ", sans(12.5.sp), c.dim)
                Txt(
                    stringResource(R.string.create_account), sans(12.5.sp).copy(textDecoration = TextDecoration.Underline), c.dim,
                    Modifier.clickable(role = Role.Button) { creating = true }.padding(vertical = 12.dp),
                )
            }
        } else {
            // One more tap, with what it means said first: a second account by mistake is hard to undo
            Txt(stringResource(R.string.create_note), sans(12.5.sp, lineHeight = 18.5.sp), c.dim, Modifier.padding(horizontal = 4.dp, vertical = 6.dp))
            Btn(stringResource(R.string.create_agree), { run { ctx.app.account.createAccount() } }, Modifier.fillMaxWidth(), enabled = !busy)
        }
    }
}
