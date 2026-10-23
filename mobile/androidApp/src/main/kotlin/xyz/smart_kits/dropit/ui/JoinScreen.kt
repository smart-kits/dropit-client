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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.draw.rotate
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
import xyz.smart_kits.dropit.BuildConfig
import xyz.smart_kits.dropit.R
import xyz.smart_kits.dropit.app
import xyz.smart_kits.dropit.shared.ApiError
import xyz.smart_kits.dropit.shared.DeviceFullHelp
import xyz.smart_kits.dropit.shared.deviceFullHelp
import xyz.smart_kits.dropit.shared.OfflineError
import xyz.smart_kits.dropit.shared.PRIVACY_URL
import xyz.smart_kits.dropit.ui.kit.Cta
import xyz.smart_kits.dropit.ui.kit.dashed
import xyz.smart_kits.dropit.ui.kit.Envelope
import xyz.smart_kits.dropit.ui.kit.Glyph
import xyz.smart_kits.dropit.ui.kit.IconTile
import xyz.smart_kits.dropit.ui.kit.Stripe
import xyz.smart_kits.dropit.ui.kit.Icons
import xyz.smart_kits.dropit.ui.kit.ErrorLine
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

/**
 * Under the "all places are taken" sentence: remove one on a device already on the account, or (with a link from the service) pay.
 * This phone has no sign-in yet, so the link is the one the service sent along with the refusal, not the app's own checkout.
 */
@Composable
private fun DeviceFullHint(help: DeviceFullHelp) {
    val ctx = LocalContext.current
    val c = ink
    val url = help.upgradeUrl
    Txt(
        if (url != null) stringResource(R.string.mo_w4_join_up, ctx.app.plans.paid.devices) else stringResource(R.string.mo_w4_join),
        sans(14.sp, FontWeight.SemiBold, lineHeight = 20.sp), c.red, Modifier.padding(top = 10.dp),
    )
    if (url != null) Txt(
        upgradeWord("devices"), sans(13.sp, FontWeight.Bold).copy(textDecoration = TextDecoration.Underline), c.navy,
        Modifier.clickable(role = Role.Button) { ctx.openLink(url, httpsOnly = true) }.heightIn(min = 48.dp).padding(vertical = 14.dp),
    )
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
    var errorAt by remember { mutableStateOf("join") }      // where the last try was made: its answer is shown there, under what was pressed
    var preparing by remember { mutableStateOf(false) }
    var slow by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    // Working for a while with nothing back: say so, so a slow connection doesn't look like a dead button
    LaunchedEffect(busy) { slow = false; if (busy) { kotlinx.coroutines.delay(4_000); slow = true } }

    fun run(where: String = "join", block: suspend () -> Unit) {
        busy = true; error = null; errorAt = where
        scope.launch {
            try { block(); onJoined() } catch (e: Throwable) { error = e } finally { busy = false }
        }
    }

    // Scanned with this phone's camera: that was the decision, so it joins straight away. A code that turns
    // out wrong or expired stays in the field with the reason under it
    LaunchedEffect(code, joinNow) { if (joinNow && typed.length == 6 && !busy) run { ctx.app.account.join(typed) } }

    Column(
        Modifier.fillMaxSize().background(c.ground).safeDrawingPadding().imePadding().verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 32.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The mark: this phone, the envelope with the logo on it, and the other device it goes to; the name and what it is for
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconTile(Icons.Phone, size = 48.dp)
            Glyph(Icons.Chevron, c.line, size = 16.dp)
            Envelope(Modifier.size(width = 136.dp, height = 88.dp).rotate(-3f), stripe = Stripe.Card, padding = PaddingValues(0.dp)) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Image(painterResource(R.drawable.ic_logo), contentDescription = null, modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)))
                }
            }
            Glyph(Icons.Chevron, c.line, size = 16.dp)
            IconTile(Icons.Laptop, size = 48.dp)
        }
        Txt("dropit", label(44.sp, tracking = (-.01).em), c.ink, Modifier.padding(top = 22.dp).semantics { heading() })
        Txt(stringResource(R.string.join_tagline), sans(16.sp, lineHeight = 22.sp), c.dim, Modifier.padding(top = 2.dp, bottom = 26.dp), align = androidx.compose.ui.text.style.TextAlign.Center)
        // Taken off from another device (or replaced): said once here, in the service's own words, until the next join
        val lost = remember(resumed) { ctx.app.store.lostKey }
        if (lost != null) ErrorLine(ctx.app.strings.render(xyz.smart_kits.dropit.shared.Message(lost)), Modifier.padding(bottom = 14.dp))
        // Read again each time the screen comes back: a share may have just been held for after joining
        val waiting = remember(resumed) { ctx.app.store.waiting.size }
        if (waiting > 0) {
            Tip(pluralStringResource(R.plurals.join_waiting, waiting, waiting), Modifier.padding(bottom = 12.dp))
        }
        // Joining an account that exists, said first and kept short: where the code is, then the quick way (scan), then the other way (type)
        Txt(stringResource(R.string.join_existing), sans(17.sp, FontWeight.Bold, lineHeight = 22.sp), c.ink, Modifier.fillMaxWidth().padding(start = 2.dp, bottom = 4.dp).semantics { heading() })
        Txt(stringResource(R.string.join_help), sans(13.5.sp, lineHeight = 19.5.sp), c.dim, Modifier.fillMaxWidth().padding(start = 2.dp, end = 2.dp, bottom = 14.dp))
        // Scanning is the quick way on a phone: it is the main button
        Cta(stringResource(R.string.scan), onScan, Modifier.padding(bottom = 14.dp), icon = Icons.Scan)
        xyz.smart_kits.dropit.ui.kit.Panel {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Txt(stringResource(R.string.join_or_type), sans(14.sp, FontWeight.SemiBold, lineHeight = 19.sp), c.dim, Modifier.padding(bottom = 10.dp))
                var focused by remember { mutableStateOf(false) }
                BasicTextField(
                    value = input,
                    // Kept exactly as the keyboard typed it: rewriting the text while the keyboard is still composing
                    // a word makes it commit that word again ("vc" became "VVC"). Capitals are only drawn, and the
                    // code is cleaned up when read
                    onValueChange = { input = it; error = null },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).onFocusChanged { focused = it.isFocused },
                    singleLine = true,
                    textStyle = mono(18.sp, FontWeight.SemiBold).copy(color = c.ink, letterSpacing = .12.em),
                    cursorBrush = SolidColor(c.red),
                    visualTransformation = Capitals,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Go,
                    ),
                    keyboardActions = KeyboardActions(onGo = { if (typed.length == 6 && !busy) run { ctx.app.account.join(typed) } }),
                    decorationBox = { field ->
                        Row(
                            Modifier.height(52.dp).dashed(if (focused) c.navy else c.line, radius = 8.dp).padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                                if (input.isEmpty()) Txt(stringResource(R.string.join_code), sans(16.sp), c.dim, maxLines = 1)
                                field()
                            }
                        }
                    },
                )
                Cta(stringResource(R.string.join), { run { ctx.app.account.join(typed) } }, Modifier.padding(top = 12.dp), enabled = typed.length == 6, loading = busy && errorAt == "join", ghost = typed.length == 6)
                if (busy && slow && !preparing && errorAt == "join") Txt(stringResource(R.string.slow_net), sans(12.5.sp), c.dim, Modifier.padding(top = 8.dp))
                // Turned away because every place is taken: one whole sentence of what to do about it and (only when the service gave one)
                // the way to pay, instead of the service's half sentence above it
                val full = if (errorAt == "join") deviceFullHelp(error, BuildConfig.PAYMENTS && ctx.app.plans.offering) else null
                if (full == null && errorAt == "join") error?.let { ErrorLine(errorText(it), Modifier.padding(top = 10.dp)) }
                full?.let { help -> DeviceFullHint(help) }
            }
        }
        Spacer(Modifier.height(14.dp))
        if (!creating) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Txt(stringResource(R.string.first_device) + " ", sans(14.sp), c.dim)
                Txt(
                    stringResource(R.string.create_account), sans(14.sp, FontWeight.Bold).copy(textDecoration = TextDecoration.Underline), c.navy,
                    Modifier.clickable(role = Role.Button) { creating = true }.heightIn(min = 48.dp).padding(vertical = 14.dp),
                )
            }
        } else {
            // One more tap, with what it means said first: a second account by mistake is hard to undo
            Txt(stringResource(R.string.create_note), sans(12.5.sp, lineHeight = 18.5.sp), c.dim, Modifier.padding(horizontal = 4.dp, vertical = 6.dp))
            Cta(stringResource(R.string.create_agree), { run("create") {
                ctx.app.account.createAccount { preparing = it }
                val f = ctx.app.plans.free
                ctx.app.account.assumeFree(f.devices, f.spaceMB * 1_048_576L, f.fileMB * 1_048_576L)
            } }, ghost = true, loading = busy)
            // The answer to creating is shown here, under the button that was pressed, not up in the join box
            if (busy && slow && !preparing) Txt(stringResource(R.string.slow_net), sans(12.5.sp), c.dim, Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
            if (errorAt == "create") error?.let { ErrorLine(errorText(it), Modifier.padding(top = 8.dp)) }
            // Only while the service wants a moment: a line, not a dialog
            if (preparing) Txt(stringResource(R.string.create_preparing), sans(12.5.sp), c.dim, Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
        }
        // What joining sends, said before the button is pressed (not softened to fit): the name of this phone and a digest of its fingerprint
        Txt(stringResource(R.string.consent), sans(12.5.sp, lineHeight = 18.5.sp), c.dim, Modifier.padding(start = 6.dp, end = 6.dp, top = 14.dp), align = androidx.compose.ui.text.style.TextAlign.Center)
        Txt(
            stringResource(R.string.privacy), sans(13.sp).copy(textDecoration = TextDecoration.Underline), c.navy,
            Modifier.clickable(role = Role.Button) { ctx.openLink(PRIVACY_URL) }.heightIn(min = 48.dp).padding(vertical = 14.dp),
        )
    }
}
