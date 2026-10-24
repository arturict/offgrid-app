package ch.offgrid.android.ui.components

import android.view.Surface
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleStartEffect
import ch.offgrid.android.domain.CompassSensor
import ch.offgrid.android.domain.GeoFix
import ch.offgrid.android.domain.Heading
import ch.offgrid.android.ui.theme.LocalReducedMotion
import ch.offgrid.android.ui.theme.Mono
import ch.offgrid.android.ui.theme.Og
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlin.math.cos
import kotlin.math.sin

/** Phone heading for the compass dial; [heading] stays null until the first reading. */
@Stable
class CompassState(val available: Boolean) {
    /** Smoothed heading to true north in degrees, null before the first reading. */
    var heading by mutableStateOf<Double?>(null)
        internal set

    /** True while the magnetometer reports low or unreliable accuracy and needs a figure eight. */
    var unreliable by mutableStateOf(false)
        internal set
}

/**
 * Listens to the compass only while the screen is started and stops when it leaves the screen or the
 * app goes to the background. [declinationFix] corrects magnetic to true north; null keeps magnetic.
 */
@Composable
fun rememberCompass(declinationFix: GeoFix?): CompassState {
    val context = LocalContext.current
    val view = LocalView.current
    val sensor = remember { CompassSensor(context.applicationContext) }
    val state = remember { CompassState(sensor.available) }
    SideEffect { sensor.setDeclinationFix(declinationFix) }
    LifecycleStartEffect(sensor) {
        sensor.start(
            displayRotation = { view.display?.rotation ?: Surface.ROTATION_0 },
            onHeading = { state.heading = it },
            onUnreliable = { state.unreliable = it },
        )
        onStopOrDispose { sensor.stop() }
    }
    return state
}

/**
 * A continuous angle that follows [source] along the shorter way (359 → 1 turns 2 degrees, never 358).
 * It springs to each reading, or jumps there when the system asks for reduced motion. Reading the
 * value only in the draw phase keeps sensor updates out of recomposition.
 */
@Composable
fun rememberCompassAngle(source: () -> Double?): Animatable<Float, *> {
    val reduced = LocalReducedMotion.current
    val angle = remember { Animatable(0f) }
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(reduced) {
        snapshotFlow(source).filterNotNull().collectLatest { target ->
            if (!started) {
                started = true
                angle.snapTo(Heading.normalize(target).toFloat())
                return@collectLatest
            }
            val next = Heading.unwrap(angle.value.toDouble(), target).toFloat()
            if (reduced) angle.snapTo(next)
            else angle.animateTo(next, spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow))
        }
    }
    return angle
}

/**
 * Eight-way sector of [source] with hysteresis (see [Heading.stickySector]). It changes only when the
 * direction in words changes, so the dial's spoken description does not follow every degree.
 */
@Composable
fun rememberStickySector(key: Any?, source: () -> Double?): Int? {
    var sector by remember(key) { mutableStateOf<Int?>(null) }
    LaunchedEffect(key) {
        snapshotFlow(source).collect { deg -> sector = deg?.let { Heading.stickySector(it, sector) } }
    }
    return sector
}

/**
 * `og-bearing-dial`: compass rose and accent arrow. [roseTurn] turns the rose (ticks and letters, the
 * letters stay upright) so its N can point to real north; [arrowTurn] is the arrow's screen angle,
 * clockwise from the top, and null hides it. [unknown] shows the "?" of a direction without a fix.
 * Both angles are read only while drawing.
 */
@Composable
fun CompassDial(label: String, roseTurn: () -> Float, arrowTurn: (() -> Float)?, unknown: Boolean = false) {
    val measurer = rememberTextMeasurer()
    Canvas(Modifier.size(232.dp).semantics { contentDescription = label; role = Role.Image }) {
        val s = size.width / 240f
        val c = Offset(120 * s, 120 * s)
        val rose = roseTurn()
        drawCircle(Og.surface2, 116 * s, c)
        drawCircle(Og.border, 116 * s, c, style = Stroke(1.5.dp.toPx()))
        drawCircle(Og.line, 78 * s, c, style = Stroke(1.dp.toPx()))
        listOf(30, 60, 120, 150, 210, 240, 300, 330).forEach { deg ->
            rotate(deg + rose, c) {
                drawLine(Og.borderMuted, Offset(120 * s, 8 * s), Offset(120 * s, 16 * s), 2.dp.toPx(), StrokeCap.Round)
            }
        }
        // Letter at [radius] from the centre in direction [deg] of the rose; the radii are the design's positions.
        fun letter(t: String, deg: Float, radius: Float, color: Color, weight: Int) {
            val layout = measurer.measure(t, TextStyle(fontFamily = Mono, fontSize = 13.sp, fontWeight = FontWeight(weight), color = color))
            val a = Math.toRadians((deg + rose).toDouble())
            val x = c.x + radius * s * sin(a).toFloat()
            val y = c.y - radius * s * cos(a).toFloat()
            drawText(layout, topLeft = Offset(x - layout.size.width / 2f, y - layout.size.height / 2f))
        }
        letter("N", 0f, 90f, Og.accent, 600)
        letter("O", 90f, 92f, Og.text3, 400)
        letter("S", 180f, 94f, Og.text3, 400)
        letter("W", 270f, 92f, Og.text3, 400)
        if (arrowTurn != null) {
            rotate(arrowTurn(), c) {
                val p = Path().apply {
                    moveTo(120 * s, 54 * s); lineTo(148 * s, 154 * s); lineTo(120 * s, 138 * s); lineTo(92 * s, 154 * s); close()
                }
                drawPath(p, Og.accent)
            }
            drawCircle(Og.bg, 4 * s, c)
        } else if (unknown) {
            val layout = measurer.measure("?", TextStyle(fontFamily = Mono, fontSize = 13.sp, color = Og.text3))
            drawText(layout, topLeft = Offset(c.x - layout.size.width / 2f, c.y - layout.size.height / 2f))
        }
    }
}
