package ch.offgrid.android.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class HeadingTest {
    @Test
    fun bearingBetweenKnownCities() {
        // Reference values from the great-circle initial bearing (Movable Type formulae).
        assertEquals(148.1, Geo.bearingDegrees(51.5074, -0.1278, 48.8566, 2.3522), 0.5) // London → Paris
        assertEquals(51.2, Geo.bearingDegrees(40.7128, -74.0060, 51.5074, -0.1278), 0.5) // New York → London
        assertEquals(59.6, Geo.bearingDegrees(46.9480, 7.4474, 47.3769, 8.5417), 0.5) // Bern → Zürich
        assertEquals(240.4, Geo.bearingDegrees(47.3769, 8.5417, 46.9480, 7.4474), 0.5) // Zürich → Bern
        assertEquals("Südosten", Geo.compassName(Geo.bearingDegrees(47.0502, 8.3093, 46.0037, 8.9511))) // Luzern → Lugano
    }

    @Test
    fun bearingAlongMeridiansAndEquator() {
        assertEquals(0.0, Geo.bearingDegrees(46.0, 8.0, 47.0, 8.0), 1e-9)
        assertEquals(180.0, Geo.bearingDegrees(47.0, 8.0, 46.0, 8.0), 1e-9)
        assertEquals(90.0, Geo.bearingDegrees(0.0, 10.0, 0.0, 11.0), 1e-9)
        assertEquals(270.0, Geo.bearingDegrees(0.0, 11.0, 0.0, 10.0), 1e-9)
    }

    @Test
    fun bearingAcrossTheAntimeridianTakesTheShortWay() {
        assertEquals(90.0, Geo.bearingDegrees(0.0, 179.5, 0.0, -179.5), 1e-9)
        assertEquals(270.0, Geo.bearingDegrees(0.0, -179.5, 0.0, 179.5), 1e-9)
    }

    @Test
    fun bearingAtThePoles() {
        // Everything is south of the North Pole and north of the South Pole.
        assertEquals(180.0, Geo.bearingDegrees(90.0, 0.0, 47.0, 0.0), 1e-6)
        assertEquals(0.0, Geo.bearingDegrees(47.0, 8.0, 90.0, 0.0), 1e-6)
        assertEquals(180.0, Geo.bearingDegrees(47.0, 8.0, -90.0, 0.0), 1e-6)
        // The same point has no direction; the result is a valid angle rather than NaN.
        val same = Geo.bearingDegrees(47.0, 8.0, 47.0, 8.0)
        assertTrue(same in 0.0..360.0)
    }

    @Test
    fun normalizesIntoOneTurn() {
        assertEquals(350.0, Heading.normalize(-10.0), 1e-9)
        assertEquals(10.0, Heading.normalize(370.0), 1e-9)
        assertEquals(0.0, Heading.normalize(360.0), 1e-9)
        assertEquals(0.0, Heading.normalize(-360.0), 1e-9)
        assertEquals(359.5, Heading.normalize(-720.5), 1e-9)
        assertTrue(Heading.normalize(-1e-15) < 360.0)
    }

    @Test
    fun shortestDeltaTurnsTheShortWay() {
        assertEquals(20.0, Heading.shortestDelta(350.0, 10.0), 1e-9)
        assertEquals(-20.0, Heading.shortestDelta(10.0, 350.0), 1e-9)
        assertEquals(-90.0, Heading.shortestDelta(90.0, 0.0), 1e-9)
        assertEquals(0.0, Heading.shortestDelta(725.0, 5.0), 1e-9)
        assertEquals(-180.0, Heading.shortestDelta(0.0, 180.0), 1e-9)
    }

    @Test
    fun unwrapKeepsTheRenderedRotationContinuous() {
        // 359 → 1 must turn 2 degrees forward, not 358 back.
        assertEquals(361.0, Heading.unwrap(359.0, 1.0), 1e-9)
        assertEquals(-1.0, Heading.unwrap(1.0, 359.0), 1e-9)
        // After several turns the continuous value keeps counting and still lands on the target.
        var angle = 0.0
        repeat(40) { i -> angle = Heading.unwrap(angle, (i * 30.0) % 360) }
        assertEquals(39 * 30.0, angle, 1e-9)
    }

    @Test
    fun relativeDirectionIsBearingMinusHeading() {
        assertEquals(0.0, Heading.relative(42.0, 42.0), 1e-9)
        assertEquals(330.0, Heading.relative(10.0, 40.0), 1e-9)
        assertEquals("geradeaus", Heading.relativeName(Heading.sector(Heading.relative(42.0, 50.0))))
        assertEquals("rechts", Heading.relativeName(Heading.sector(Heading.relative(90.0, 0.0))))
        assertEquals("hinter dir", Heading.relativeName(Heading.sector(Heading.relative(10.0, 190.0))))
        assertEquals("schräg links vorne", Heading.relativeName(Heading.sector(Heading.relative(0.0, 45.0))))
    }

    @Test
    fun stickySectorIgnoresWobbleOnABorder() {
        assertEquals(0, Heading.stickySector(22.0, null))
        assertEquals(1, Heading.stickySector(25.0, null))
        // Once north, a wobble a few degrees past the 22.5 border keeps north …
        assertEquals(0, Heading.stickySector(25.0, 0))
        assertEquals(0, Heading.stickySector(330.0, 0))
        // … until the heading is clearly in the next sector, on either side of north.
        assertEquals(1, Heading.stickySector(31.0, 0))
        assertEquals(7, Heading.stickySector(325.0, 0))
        assertEquals(1, Heading.stickySector(20.0, 1))
        assertEquals(0, Heading.stickySector(14.0, 1))
    }

    @Test
    fun smootherWrapsAcrossNorthWithoutAJump() {
        val smoother = HeadingSmoother(timeConstantMs = 200.0)
        smoother.update(355.0, 0)
        var t = 0L
        repeat(50) {
            t += 60
            val v = smoother.update(5.0, t)
            // Every value stays near north; a naive average would pass through 180.
            assertTrue("value $v left the short arc", abs(Heading.shortestDelta(0.0, v)) <= 5.0 + 1e-9)
        }
        assertEquals(0.0, Heading.shortestDelta(5.0, smoother.value!!), 0.01)
    }

    @Test
    fun smootherConvergesWithTheTimeConstant() {
        val smoother = HeadingSmoother(timeConstantMs = 200.0)
        assertEquals(10.0, smoother.update(10.0, 1_000), 1e-9) // the first reading is taken as is
        // One time constant later about 63 % of a small step is done (on the circle, close to linear).
        val after = smoother.update(20.0, 1_200)
        assertEquals(10.0 + 10.0 * 0.632, after, 0.1)
        var t = 1_200L
        repeat(30) {
            t += 60
            smoother.update(20.0, t)
        }
        assertEquals(20.0, smoother.value!!, 0.01)
        // The same reading twice in the same millisecond changes nothing.
        assertEquals(smoother.value!!, smoother.update(90.0, t), 1e-9)
    }

    @Test
    fun smootherStartsAfreshAfterReset() {
        val smoother = HeadingSmoother()
        smoother.update(10.0, 0)
        smoother.reset()
        assertEquals(null, smoother.value)
        assertEquals(200.0, smoother.update(200.0, 10), 1e-9)
    }
}
