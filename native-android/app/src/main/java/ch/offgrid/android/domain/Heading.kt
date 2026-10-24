package ch.offgrid.android.domain

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Angle helpers for the compass on the location screen. Every angle is in degrees, 0 = north,
 * clockwise. Pure Kotlin so the wrap-around rules are covered by JVM tests.
 */
object Heading {
    private val RELATIVE = listOf(
        "geradeaus", "schräg rechts vorne", "rechts", "schräg rechts hinten",
        "hinter dir", "schräg links hinten", "links", "schräg links vorne",
    )

    /** Any angle into 0 until 360 (-10 → 350, 370 → 10). */
    fun normalize(deg: Double): Double {
        val r = deg % 360.0
        // -0.0 and values a hair below 360 after the modulo both mean north.
        return if (r < 0) (r + 360.0).let { if (it >= 360.0) 0.0 else it } else r + 0.0
    }

    /** Signed turn from [from] to [to] along the shorter way, in -180 until 180 (350 → 10 is +20). */
    fun shortestDelta(from: Double, to: Double): Double {
        val d = normalize(to - from)
        return if (d >= 180.0) d - 360.0 else d
    }

    /**
     * Next value of a continuous (unwrapped) angle that should now show [target]. The rendered
     * rotation follows this value, so 359 → 1 turns 2 degrees forward instead of 358 back.
     */
    fun unwrap(continuous: Double, target: Double): Double = continuous + shortestDelta(continuous, target)

    /** Where the target lies seen from the phone's top edge: bearing minus heading, 0 = straight ahead. */
    fun relative(bearing: Double, heading: Double): Double = normalize(bearing - heading)

    /** One of eight 45 degree sectors, 0 = north (or straight ahead). */
    fun sector(deg: Double): Int = (normalize(deg) / 45.0).roundToInt() % 8

    /**
     * [sector] with hysteresis: the previous sector is kept until the angle is more than
     * [margin] degrees past its edge, so a heading that wobbles on a border does not flip the
     * spoken direction back and forth.
     */
    fun stickySector(deg: Double, previous: Int?, margin: Double = 8.0): Int {
        if (previous != null && abs(shortestDelta(previous * 45.0, deg)) <= 22.5 + margin) return previous
        return sector(deg)
    }

    /** Words for a relative sector from [stickySector] of [relative]: "geradeaus", "rechts" … */
    fun relativeName(sector: Int): String = RELATIVE[sector.mod(8)]
}

/**
 * Exponential smoothing of a compass heading on the unit circle: sine and cosine are averaged
 * separately, so 359 and 1 average to 0 and never to 180. The weight of a new reading depends on the
 * time since the last one ([timeConstantMs]), which keeps the feel the same at any sensor rate.
 */
class HeadingSmoother(private val timeConstantMs: Double = TIME_CONSTANT_MS) {
    private var s = 0.0
    private var c = 0.0
    private var lastAt: Long? = null

    /** Smoothed heading, null before the first reading. */
    var value: Double? = null
        private set

    fun update(heading: Double, atMs: Long): Double {
        val rad = Math.toRadians(heading)
        val last = lastAt
        if (last == null) {
            s = sin(rad)
            c = cos(rad)
        } else {
            val dt = (atMs - last).coerceAtLeast(0L).toDouble()
            val alpha = 1.0 - exp(-dt / timeConstantMs)
            s += alpha * (sin(rad) - s)
            c += alpha * (cos(rad) - c)
        }
        lastAt = atMs
        // Opposite readings could cancel out to the origin; keep the previous direction then.
        val result = if (hypot(s, c) < 1e-6) value ?: heading else Heading.normalize(Math.toDegrees(atan2(s, c)))
        value = result
        return result
    }

    fun reset() {
        lastAt = null
        value = null
    }

    companion object {
        /** About 0.2 s: steady in the hand, still quick when the user turns around. */
        const val TIME_CONSTANT_MS = 200.0
    }
}
