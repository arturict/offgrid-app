package ch.offgrid.android.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import ch.offgrid.android.ui.theme.LocalReducedMotion
import ch.offgrid.android.ui.theme.Og
import ch.offgrid.android.ui.theme.OgType

/**
 * Progress of a guided flow: [total] bars, "SCHRITT n VON m" and the step's [title] as a heading. [step] null marks
 * the flow as finished (all bars filled, "FERTIG"); 0 fills none. [label] replaces the position text. The whole
 * block is one live region, so TalkBack reads the new step when it changes.
 */
@Composable
fun WizardProgress(step: Int?, total: Int, title: String, modifier: Modifier = Modifier, label: String? = null) {
    val position = label ?: if (step == null) "Fertig" else "Schritt $step von $total"
    Column(
        modifier.fillMaxWidth().padding(start = Og.gutterText, top = 4.dp, end = Og.gutterText)
            .clearAndSetSemantics {
                contentDescription = "$position: $title"
                heading()
                liveRegion = LiveRegionMode.Polite
            },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (i in 1..total) {
                val filled = step == null || i <= step
                val color by animateColorAsState(if (filled) Og.accent else Og.line, tween(220), label = "wizardBar")
                Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(color))
            }
        }
        Text(position.uppercase(), style = OgType.section.copy(color = Og.accent))
        Text(title, style = OgType.hero.copy(lineHeight = 1.2.em))
    }
}

/**
 * Time left in a pairing window as `m:ss` in a pill; accent while there is time, danger in the last minute.
 * Not a live region: a screen reader would otherwise announce every second.
 */
@Composable
fun CountdownPill(secondsLeft: Int, modifier: Modifier = Modifier) {
    val urgent = secondsLeft < 60
    val color by animateColorAsState(if (urgent) Og.danger else Og.accent, tween(220), label = "countdown")
    val minutes = secondsLeft / 60
    val seconds = secondsLeft % 60
    Row(
        modifier.clip(CircleShape).background(if (urgent) Og.dangerBg else Og.accentSoft)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clearAndSetSemantics {
                contentDescription = "Noch $minutes Minuten und $seconds Sekunden gültig"
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        OgIconView(OgIcon.Clock, color)
        Text("Noch %d:%02d".format(minutes, seconds), style = OgType.value.copy(color = color))
    }
}

/**
 * Two phones that scan each other: the upper arrow goes from left to right, the lower one back. With motion the
 * arrows take turns in the accent colour ("zuerst scannt einer, dann der andere"); reduced motion shows both.
 */
@Composable
fun MutualScanIllustration(modifier: Modifier = Modifier) {
    val phase = if (LocalReducedMotion.current) {
        -1f
    } else {
        val transition = rememberInfiniteTransition(label = "mutualScan")
        transition.animateFloat(0f, 2f, infiniteRepeatable(tween(3_200, easing = LinearEasing), RepeatMode.Restart), label = "turn").value
    }
    Row(
        modifier.clearAndSetSemantics {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PhoneGlyph()
        Canvas(Modifier.width(76.dp).height(56.dp)) {
            val first = phase < 1f
            val second = phase < 0f || phase >= 1f
            arrow(from = Offset(0f, size.height * 0.3f), to = Offset(size.width, size.height * 0.3f),
                color = if (first) Og.accent else Og.borderMuted)
            arrow(from = Offset(size.width, size.height * 0.7f), to = Offset(0f, size.height * 0.7f),
                color = if (second) Og.accent else Og.borderMuted)
        }
        PhoneGlyph()
    }
}

/** A phone outline with a QR icon on its screen. */
@Composable
private fun PhoneGlyph() {
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier.size(width = 58.dp, height = 96.dp).clip(shape).background(Og.surface).border(1.5.dp, Og.borderMuted, shape),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(Og.text), contentAlignment = Alignment.Center) {
            OgIconView(OgIcon.Qr, Og.bg, size = 26.dp)
        }
    }
}

private fun DrawScope.arrow(from: Offset, to: Offset, color: Color) {
    val stroke = 2.5.dp.toPx()
    val head = 8.dp.toPx()
    val dir = if (to.x > from.x) 1f else -1f
    drawLine(color, from, Offset(to.x - dir * stroke, to.y), strokeWidth = stroke, cap = StrokeCap.Round)
    val tip = Path().apply {
        moveTo(to.x - dir * head, to.y - head * 0.8f)
        lineTo(to.x - dir * stroke / 2, to.y)
        lineTo(to.x - dir * head, to.y + head * 0.8f)
    }
    drawPath(tip, color, style = Stroke(stroke, cap = StrokeCap.Round))
}
