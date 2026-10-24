package ch.offgrid.android.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import ch.offgrid.android.data.MessageStatus
import ch.offgrid.android.domain.LinkDetector
import ch.offgrid.android.ui.theme.Grotesk
import ch.offgrid.android.ui.theme.Mono
import ch.offgrid.android.ui.theme.Og
import ch.offgrid.android.ui.theme.OgType
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

private val inShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomEnd = 20.dp, bottomStart = 6.dp)
private val outShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomEnd = 6.dp, bottomStart = 20.dp)

/** An outgoing bubble right after another outgoing one tucks its top corner in (`Main.dc.html`). */
private val outShapeContinued = RoundedCornerShape(topStart = 20.dp, topEnd = 6.dp, bottomEnd = 6.dp, bottomStart = 20.dp)

private fun bubbleShape(out: Boolean, continued: Boolean) = when {
    !out -> inShape
    continued -> outShapeContinued
    else -> outShape
}

/** Day separator: line, `HEUTE`, line. */
@Composable
fun DayDivider(label: String) {
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 10.dp).semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        HorizontalDivider(Modifier.weight(1f), thickness = 1.dp, color = Og.line)
        Text(label, style = OgType.section)
        HorizontalDivider(Modifier.weight(1f), thickness = 1.dp, color = Og.line)
    }
}

/**
 * Meta line under outgoing bubbles: time, status word and small signal bars, a clock while the antenna
 * waits for the contact, or the failure word from [meta] with the retry button.
 */
@Composable
private fun OutMeta(status: Int, meta: String, onRetry: () -> Unit) {
    AnimatedContent(
        targetState = status == MessageStatus.FAILED,
        transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) },
        contentAlignment = Alignment.CenterEnd,
        label = "outMeta",
    ) { failed ->
        if (failed) {
            Row(Modifier.padding(end = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(meta, style = OgType.meta.copy(color = Og.danger))
                Box(
                    Modifier.heightIn(min = 32.dp).clip(RoundedCornerShape(10.dp)).background(Og.surface)
                        .border(1.dp, Og.border, RoundedCornerShape(10.dp))
                        .clickable(role = Role.Button, onClick = onRetry).padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("Erneut senden", style = TextStyle(fontFamily = Grotesk, fontSize = 13.sp, fontWeight = FontWeight(600), color = Og.text)) }
            }
        } else {
            val level = when (status) {
                MessageStatus.DELIVERED -> 4
                MessageStatus.SENT -> 2
                else -> 0
            }
            Row(Modifier.padding(end = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (status == MessageStatus.WAITING) {
                    // No check mark: the antenna sent it but has no ACK yet and tries again when the contact is heard.
                    OgIconView(OgIcon.Clock, Og.text3)
                    Text(meta, style = OgType.meta)
                } else {
                    Text(meta, style = OgType.meta)
                    SignalBars(level, small = true)
                }
            }
        }
    }
}

private fun isQueued(status: Int) = status == MessageStatus.LOCAL_QUEUED || status == MessageStatus.DEVICE_QUEUED

/**
 * Outgoing bubble per status: solid accent when sent or delivered, dashed accent outline while
 * queued, red outline on failure. Fill and outlines cross-fade when the status changes.
 */
@Composable
private fun Modifier.outBubble(status: Int, shape: Shape): Modifier {
    val failed = status == MessageStatus.FAILED
    val solid = !isQueued(status) && !failed
    val fill by animateFloatAsState(if (solid) 1f else 0f, tween(250), label = "fill")
    val dashed by animateFloatAsState(if (isQueued(status)) 1f else 0f, tween(250), label = "dashed")
    val danger by animateFloatAsState(if (failed) 1f else 0f, tween(250), label = "danger")
    return this
        .clip(shape)
        .background(Og.accent.copy(alpha = fill))
        .dashedBorder(Og.accent.copy(alpha = dashed), 1.5.dp, shape)
        .border(1.5.dp, Og.danger.copy(alpha = danger), shape)
}

/** Text colour inside an outgoing bubble: on-accent on the solid fill, normal text on outlines. */
@Composable
private fun outForeground(status: Int): Color {
    val outlined = isQueued(status) || status == MessageStatus.FAILED
    return animateColorAsState(if (outlined) Og.text else Og.onAccent, tween(250), label = "fg").value
}

/**
 * Long press with haptic feedback and a TalkBack action of the same name. [onTap] gets the tap
 * position in this element's coordinates; it shares the detector with the long press so a link
 * under the finger cannot open after the details sheet did.
 */
@Composable
private fun Modifier.longPressAction(label: String, onTap: ((Offset) -> Unit)? = null, action: () -> Unit): Modifier {
    val haptics = LocalHapticFeedback.current
    return pointerInput(action, onTap) {
        detectTapGestures(
            onTap = onTap,
            onLongPress = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                action()
            },
        )
    }.semantics { onLongClick(label) { action(); true } }
}

/** Underlined links in [text]; [color] is the bubble's own text colour on the solid accent fill. */
private fun linkedText(text: String, links: List<LinkDetector.Link>, color: Color): AnnotatedString =
    buildAnnotatedString {
        append(text)
        for (l in links) addStyle(SpanStyle(color = color, textDecoration = TextDecoration.Underline), l.start, l.end)
    }

/** Opens [url] in the browser or mail app. Nothing is loaded or reported beforehand. */
private fun openLink(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "Keine App zum Öffnen gefunden", Toast.LENGTH_SHORT).show()
    }
}

private val BUBBLE_PAD_X = 14.dp
private val BUBBLE_PAD_Y = 10.dp

/** TalkBack action name for a link, so links are reachable from the actions menu. */
private fun linkActionLabel(link: LinkDetector.Link, text: String): String {
    val shown = text.substring(link.start, link.end)
    return if (link.kind == LinkDetector.Kind.EMAIL) "E-Mail schreiben: $shown" else "Link öffnen: $shown"
}

/**
 * `og-message-bubble`. [groupStart] adds the 8 dp gap where the direction changes; [continued]
 * marks an outgoing bubble that directly follows another outgoing one.
 */
@Composable
fun MessageBubble(
    out: Boolean,
    status: Int,
    text: String,
    meta: String,
    groupStart: Boolean = false,
    continued: Boolean = false,
    senderName: String? = null,
    reaction: String? = null,
    onRetry: () -> Unit = {},
    onLongPress: (() -> Unit)? = null,
    longPressLabel: String = "Details",
) {
    Column(Modifier.fillMaxWidth().padding(top = if (groupStart) 8.dp else 0.dp), horizontalAlignment = if (out) Alignment.End else Alignment.Start) {
        Column(
            Modifier.fillMaxWidth(0.78f),
            horizontalAlignment = if (out) Alignment.End else Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(if (status == MessageStatus.FAILED) 6.dp else 4.dp),
        ) {
            if (senderName != null) {
                Text(
                    senderName,
                    modifier = Modifier.padding(start = 6.dp),
                    style = TextStyle(fontFamily = Grotesk, fontSize = 12.sp, fontWeight = FontWeight(650), color = Og.text2),
                )
            }
            val shape = bubbleShape(out, continued)
            val context = LocalContext.current
            val density = LocalDensity.current
            val links = remember(text) { LinkDetector.find(text) }
            var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
            // Position -> link: tap coordinates are relative to the bubble, the text sits inside its padding.
            val onTap: (Offset) -> Unit = { pos ->
                val l = layout
                if (l != null) {
                    val p = pos - Offset(with(density) { BUBBLE_PAD_X.toPx() }, with(density) { BUBBLE_PAD_Y.toPx() })
                    val hit = if (p.x < 0f || p.y < 0f || p.x > l.size.width || p.y > l.size.height) null else {
                        val i = l.getOffsetForPosition(p)
                        // The end of a short last line maps to its final character: demand a real hit.
                        val box = l.getBoundingBox(minOf(i, text.length - 1)).inflate(with(density) { 6.dp.toPx() })
                        links.firstOrNull { i >= it.start && i < it.end && box.contains(p) }
                    }
                    if (hit != null) openLink(context, hit.url)
                }
            }
            Box(
                Modifier
                    .then(if (out) Modifier.outBubble(status, shape) else Modifier.clip(shape).background(Og.surface).border(1.dp, Og.border, shape))
                    .then(
                        when {
                            onLongPress != null -> Modifier.longPressAction(longPressLabel, onTap = if (links.isEmpty()) null else onTap, action = onLongPress)
                            links.isNotEmpty() -> Modifier.pointerInput(onTap) { detectTapGestures(onTap = onTap) }
                            else -> Modifier
                        },
                    )
                    .padding(horizontal = BUBBLE_PAD_X, vertical = BUBBLE_PAD_Y),
            ) {
                Text(
                    linkedText(text, links, if (out) outForeground(status) else Og.accent),
                    style = OgType.body.copy(color = if (out) outForeground(status) else Og.text, fontWeight = FontWeight(if (out) 500 else 400)),
                    onTextLayout = { layout = it },
                    modifier = if (links.isEmpty()) Modifier else Modifier.semantics {
                        customActions = links.map { l ->
                            CustomAccessibilityAction(linkActionLabel(l, text)) { openLink(context, l.url); true }
                        }
                    },
                )
            }
            AnimatedVisibility(reaction != null) {
                Text(reaction.orEmpty(), fontSize = 16.sp, modifier = Modifier.padding(horizontal = 6.dp))
            }
            if (out) OutMeta(status, meta, onRetry) else Text(meta, style = OgType.meta, modifier = Modifier.padding(start = 6.dp))
        }
    }
}

/**
 * Bearing that turns along the shorter way round (350° → 10° turns 20°, not 340°). The value is
 * unbounded; rotation does not care.
 */
@Composable
fun rememberBearingAngle(bearing: Double?): State<Float> {
    val angle = remember { Animatable((bearing ?: 0.0).toFloat()) }
    LaunchedEffect(bearing) {
        if (bearing == null) return@LaunchedEffect
        val delta = ((bearing.toFloat() - angle.value) % 360f + 540f) % 360f - 180f
        angle.animateTo(angle.value + delta, tween(450, easing = FastOutSlowInEasing))
    }
    return angle.asState()
}

/** `og-location-bubble`: own position (accent) or the peer's with distance and direction. */
@Composable
fun LocationBubble(
    out: Boolean,
    status: Int,
    title: String,
    coordinate: String,
    accuracy: String?,
    meta: String,
    distance: String?,
    directionText: String?,
    bearing: Double?,
    onOpen: () -> Unit,
    onRetry: () -> Unit,
    groupStart: Boolean = false,
    continued: Boolean = false,
) {
    Column(
        Modifier.fillMaxWidth().padding(top = if (groupStart) 8.dp else 0.dp),
        horizontalAlignment = if (out) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(if (status == MessageStatus.FAILED) 6.dp else 4.dp),
    ) {
        val shape = bubbleShape(out, continued)
        Column(
            Modifier
                .width(if (out) 250.dp else 260.dp)
                .then(
                    // Own location: the whole bubble opens the detail. Incoming: only "Anzeigen" does.
                    if (out) Modifier.outBubble(status, shape).clickable(role = Role.Button, onClickLabel = "Anzeigen", onClick = onOpen)
                    else Modifier.clip(shape).background(Og.surface).border(1.dp, Og.border, shape),
                )
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(if (out) 6.dp else 10.dp),
        ) {
            val fg = if (out) outForeground(status) else Og.text
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OgIconView(OgIcon.Pin, if (out) fg else Og.accent, size = 18.dp, strokeWidth = 2.2f)
                Text(title, style = TextStyle(fontFamily = Grotesk, fontSize = 15.sp, fontWeight = FontWeight(700), color = fg))
            }
            if (out) {
                Text(
                    coordinate + (accuracy?.let { "\nGenauigkeit $it" } ?: ""),
                    style = TextStyle(fontFamily = Mono, fontSize = 12.sp, lineHeight = 18.sp, color = fg),
                )
            } else {
                if (distance != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        val angle by rememberBearingAngle(bearing)
                        Box(Modifier.size(44.dp).border(1.dp, Og.border, CircleShape), contentAlignment = Alignment.Center) {
                            Icon(DirectionArrow, null, tint = Og.accent, modifier = Modifier.size(26.dp).rotate(angle))
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(
                                distance,
                                style = TextStyle(
                                    fontFamily = Grotesk, fontSize = 24.sp, lineHeight = 1.1.em, fontWeight = FontWeight(750),
                                    letterSpacing = (-0.02).em, color = Og.text,
                                ),
                            )
                            if (directionText != null) Text(directionText, style = TextStyle(fontFamily = Grotesk, fontSize = 14.sp, color = Og.text2))
                        }
                    }
                }
                Text(
                    coordinate + (accuracy?.let { " · $it" } ?: ""),
                    style = TextStyle(fontFamily = Mono, fontSize = 12.sp, lineHeight = 1.5.em, color = Og.text3),
                )
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 40.dp).clip(RoundedCornerShape(12.dp)).background(Og.line)
                        .clickable(role = Role.Button, onClick = onOpen),
                    contentAlignment = Alignment.Center,
                ) { Text("Anzeigen", style = TextStyle(fontFamily = Grotesk, fontSize = 14.sp, fontWeight = FontWeight(650), color = Og.text)) }
            }
        }
        if (out) OutMeta(status, meta, onRetry) else Text(meta, style = OgType.meta, modifier = Modifier.padding(start = 6.dp))
    }
}

/** `og-composer`: optional location button, 48 high input with byte counter, send button. */
@Composable
fun Composer(
    text: String,
    onTextChange: (String) -> Unit,
    placeholder: String,
    label: String,
    sendLabel: String,
    usedBytes: Int,
    maxBytes: Int,
    onSend: () -> Unit,
    onLocation: (() -> Unit)?,
    parts: Int = 1,
    maxParts: Int = 1,
) {
    // A text longer than one radio message is split by the app into up to maxParts messages.
    val over = parts > maxParts || (maxParts == 1 && usedBytes > maxBytes)
    val canSend = text.isNotBlank() && !over
    // The design keeps the send button at full colour while the field is empty; only an
    // over-long text dims it.
    val sendAlpha by animateFloatAsState(if (over) 0.4f else 1f, tween(150), label = "sendAlpha")
    val counterColor by animateColorAsState(if (over) Og.danger else Og.text3, tween(150), label = "counter")
    // The buttons line up with the input, so they sit above the counter: 5 dp gap plus the counter's
    // height (21 dp at 1x like the HTML; more when large fonts wrap it).
    val density = LocalDensity.current
    var counterHeight by remember { mutableIntStateOf(with(density) { 16.dp.roundToPx() }) }
    val buttonBottom = with(density) { counterHeight.toDp() } + 5.dp
    Column(Modifier.fillMaxWidth().background(Og.bar)) {
        HorizontalDivider(thickness = 1.dp, color = Og.line)
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, top = 10.dp, end = 14.dp, bottom = 22.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(if (onLocation != null) 8.dp else 10.dp),
        ) {
            if (onLocation != null) {
                Box(
                    Modifier.padding(bottom = buttonBottom).size(48.dp).clip(RoundedCornerShape(16.dp)).background(Og.surface)
                        .border(1.dp, Og.border, RoundedCornerShape(16.dp)).clickable(role = Role.Button, onClick = onLocation)
                        .semantics { contentDescription = "Standort senden" },
                    contentAlignment = Alignment.Center,
                ) { OgIconView(OgIcon.Pin, Og.text) }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                BasicTextField(
                    value = text,
                    onValueChange = onTextChange,
                    textStyle = TextStyle(fontFamily = Grotesk, fontSize = 16.sp, color = Og.text),
                    cursorBrush = SolidColor(Og.accent),
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (canSend) onSend() }),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
                    decorationBox = { inner ->
                        Box(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(16.dp)).background(Og.surface)
                                .border(1.dp, Og.border, RoundedCornerShape(16.dp)).padding(horizontal = 16.dp, vertical = 12.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            if (text.isEmpty()) Text(placeholder, maxLines = 1, style = TextStyle(fontFamily = Grotesk, fontSize = 16.sp, color = Og.placeholder))
                            inner()
                        }
                    },
                )
                Text(
                    when {
                        parts > maxParts -> "Zu lang: höchstens $maxParts Funksprüche"
                        parts > 1 -> "Wird als $parts Funksprüche gesendet"
                        else -> "$usedBytes / $maxBytes Zeichen pro Funkspruch"
                    },
                    style = OgType.meta.copy(color = counterColor),
                    modifier = Modifier.padding(start = 4.dp).onSizeChanged { counterHeight = it.height },
                )
            }
            Box(
                Modifier.padding(bottom = buttonBottom).size(48.dp).alpha(sendAlpha).clip(RoundedCornerShape(16.dp)).background(Og.accent)
                    .clickable(enabled = canSend, role = Role.Button, onClick = onSend)
                    .semantics { contentDescription = sendLabel },
                contentAlignment = Alignment.Center,
            ) { OgIconView(OgIcon.Send, Og.onAccent) }
        }
    }
}

private val sheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

/**
 * `og-sheet`: scrim plus bottom panel with handle and title. It slides up and the scrim fades in;
 * both reverse when [visible] turns false. Back and a tap on the scrim close it.
 */
@Composable
fun BoxScope.OgSheet(visible: Boolean, title: String, onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    BackHandler(enabled = visible, onBack = onClose)
    AnimatedVisibility(visible, Modifier.matchParentSize(), enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
        Box(
            Modifier.fillMaxWidth().background(Og.scrim)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
        )
    }
    AnimatedVisibility(
        visible,
        Modifier.align(Alignment.BottomCenter),
        enter = slideInVertically(tween(280, easing = FastOutSlowInEasing)) { it },
        exit = slideOutVertically(tween(200)) { it },
    ) {
        Column(
            Modifier.fillMaxWidth()
                .clip(sheetShape)
                .background(Og.surface)
                .topBorder(Og.border, 1.dp, 28.dp)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .semantics { paneTitle = title }
                .navigationBarsPadding()
                .padding(start = 20.dp, top = 10.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).size(36.dp, 4.dp).clip(RoundedCornerShape(999.dp)).background(Og.borderMuted))
            Text(title, style = OgType.pageTitle, modifier = Modifier.semantics { heading() })
            content()
        }
    }
}

/** `border-top` on a panel with rounded top corners: the line follows both corner arcs. */
private fun Modifier.topBorder(color: Color, width: Dp, radius: Dp) = drawWithContent {
    drawContent()
    val w = width.toPx()
    val r = radius.toPx()
    val half = w / 2
    val path = Path().apply {
        moveTo(half, r)
        arcTo(Rect(half, half, 2 * r - half, 2 * r - half), 180f, 90f, false)
        lineTo(size.width - r, half)
        arcTo(Rect(size.width - 2 * r + half, half, size.width - half, 2 * r - half), 270f, 90f, false)
    }
    drawPath(path, color, style = Stroke(w))
}

/** `Handshake.dc.html`: 25 modules of 9 px. */
private val QR_SIZE = 225.dp

/**
 * QR code on a light card (padding 22, radius 28). Modules touch and have a 2 dp corner radius
 * like the design; the code fades in once it is ready.
 */
@Composable
fun QrCard(data: String?, modifier: Modifier = Modifier) {
    val matrix = remember(data) {
        data?.let {
            QRCodeWriter().encode(it, BarcodeFormat.QR_CODE, 0, 0,
                mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 0))
        }
    }
    Box(
        modifier.clip(RoundedCornerShape(Og.rQr)).background(Og.text).padding(22.dp)
            .semantics { contentDescription = "QR-Code mit deinem Schlüssel"; role = Role.Image },
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(matrix, animationSpec = tween(250), label = "qr") { m ->
            Box(Modifier.size(QR_SIZE), contentAlignment = Alignment.Center) {
                if (m == null) {
                    Spinner(28.dp)
                } else {
                    Canvas(Modifier.size(QR_SIZE)) {
                        val n = m.width
                        val cell = size.width / n
                        val r = minOf(2.dp.toPx(), cell / 2)
                        for (y in 0 until n) for (x in 0 until n) {
                            if (m.get(x, y)) drawRoundRect(Og.bg, Offset(x * cell, y * cell), Size(cell, cell), CornerRadius(r, r))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun VSpace(height: Dp) = Spacer(Modifier.height(height))
