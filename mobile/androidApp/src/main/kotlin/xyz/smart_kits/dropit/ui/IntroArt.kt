package xyz.smart_kits.dropit.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import xyz.smart_kits.dropit.R
import xyz.smart_kits.dropit.ui.kit.Cta
import xyz.smart_kits.dropit.ui.kit.CtaBar
import xyz.smart_kits.dropit.ui.kit.Envelope
import xyz.smart_kits.dropit.ui.kit.Glyph
import xyz.smart_kits.dropit.ui.kit.IconTile
import xyz.smart_kits.dropit.ui.kit.Icons
import xyz.smart_kits.dropit.ui.kit.Panel
import xyz.smart_kits.dropit.ui.kit.Stripe
import xyz.smart_kits.dropit.ui.kit.Txt
import xyz.smart_kits.dropit.ui.kit.btn
import xyz.smart_kits.dropit.ui.kit.dashed
import xyz.smart_kits.dropit.ui.theme.ink
import xyz.smart_kits.dropit.ui.theme.label
import xyz.smart_kits.dropit.ui.theme.mono
import xyz.smart_kits.dropit.ui.theme.sans

/** Links, text, pictures and files in one envelope, and the devices they go to */
@Composable
internal fun WhatItIs() {
    val c = ink
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Envelope(Modifier.size(width = 264.dp, height = 128.dp), stripe = Stripe.Card, padding = PaddingValues(0.dp)) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                for (icon in listOf(Icons.Link, Icons.Lines, Icons.Picture, Icons.File)) Glyph(icon, c.navy, size = 38.dp)
            }
        }
        Glyph(Icons.Down, c.red, Modifier.padding(vertical = 12.dp), size = 26.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            for (icon in listOf(Icons.Phone, Icons.Laptop, Icons.Browser)) IconTile(icon, size = 60.dp)
        }
    }
}

/** Write (or share) and press send; the other device joins with a code */
@Composable
internal fun HowToSend() {
    val c = ink
    Column(Modifier.widthIn(max = 340.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Panel {
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).height(52.dp).dashed(c.line).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                    Txt(stringResource(R.string.compose_hint), sans(13.5.sp), c.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box(Modifier.height(52.dp).clip(RoundedCornerShape(9.dp)).background(c.btn).padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                    Txt(stringResource(R.string.label_send).uppercase(), label(17.sp), Color.White, maxLines = 1)
                }
            }
        }
        Glyph(Icons.Down, c.dim, Modifier.padding(vertical = 10.dp), size = 22.dp)
        Panel {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconTile(Icons.Scan, size = 52.dp)
                Column(Modifier.weight(1f)) {
                    Txt(stringResource(R.string.join_code), sans(12.5.sp), c.dim)
                    Txt("7K2M9Q", mono(26.sp, FontWeight.Bold).copy(letterSpacing = 3.sp), c.ink)
                }
            }
        }
    }
}

/** What arrives, as a notification, with the tick of delivery on its corner */
@Composable
internal fun ItArrives() {
    val c = ink
    Box(Modifier.widthIn(max = 320.dp)) {
        SampleNotification()
        Box(
            Modifier.align(Alignment.TopEnd).offset(x = 10.dp, y = (-14).dp).size(40.dp).clip(CircleShape).background(c.ok),
            contentAlignment = Alignment.Center,
        ) { Glyph(Icons.Check, if (c.dark) Color(0xFF0E1626) else Color.White, size = 24.dp) }
    }
}
