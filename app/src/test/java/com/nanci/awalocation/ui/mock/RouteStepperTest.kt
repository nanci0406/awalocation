package com.nanci.awalocation.ui.mock

import net.sf.geographiclib.Geodesic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class RouteStepperTest {

    @Test
    fun `dense sub-meter waypoints do not cause jump-ahead pace acceleration`() {
        // 构造包含大量 0.45m 密集路径点的路线（共 200 段 = 90m）
        val waypoints = mutableListOf(39.908822 to 116.397465)
        for (i in 1..200) {
            val prev = waypoints.last()
            val direct = Geodesic.WGS84.Direct(prev.first, prev.second, 45.0 + (i % 5) * 3.0, 0.45)
            waypoints.add(direct.lat2 to direct.lon2)
        }

        var expectedTotalLength = 0.0
        for (i in 1 until waypoints.size) {
            expectedTotalLength += Geodesic.WGS84.Inverse(
                waypoints[i - 1].first,
                waypoints[i - 1].second,
                waypoints[i].first,
                waypoints[i].second,
            ).s12
        }

        val speedMps = 2.5
        val dtSec = 0.1 // 100ms tick -> 0.25m per tick
        val stepMeters = speedMps * dtSec

        var lat = waypoints[0].first
        var lon = waypoints[0].second
        var stage = 1
        var bearing = RouteStepper.initialBearing(waypoints)
        var ticks = 0
        var traveled = 0.0

        while (ticks < 1000) {
            ticks++
            val out = RouteStepper.advanceAlongRoute(
                currentLat = lat,
                currentLon = lon,
                currentStage = stage,
                waypoints = waypoints,
                distanceMeters = stepMeters,
                loop = false,
                fallbackBearing = bearing,
            )
            traveled += Geodesic.WGS84.Inverse(lat, lon, out.lat, out.lon).s12
            lat = out.lat
            lon = out.lon
            stage = out.nextStage
            bearing = out.bearing
            if (out.finished) break
        }

        // 90m / 0.25m = 360 拍（36.0s），绝不能像旧逻辑（<1.0m 直接瞬移）那样提前跑完
        assertEquals(360, ticks)
        assertTrue(abs(traveled - expectedTotalLength) < 0.05)
    }

    @Test
    fun `loop mode smoothly closes back to start without single-tick teleport`() {
        val p0 = 39.908822 to 116.397465
        val p1 = Geodesic.WGS84.Direct(p0.first, p0.second, 90.0, 20.0).let { it.lat2 to it.lon2 }
        val p2 = Geodesic.WGS84.Direct(p1.first, p1.second, 180.0, 20.0).let { it.lat2 to it.lon2 }
        val route = listOf(p0, p1, p2) // 非闭合三角形，p2 距 p0 约 28.28m

        var lat = p0.first
        var lon = p0.second
        var stage = 1
        var bearing = RouteStepper.initialBearing(route)
        val stepMeters = 0.3

        for (tick in 1..400) {
            val out = RouteStepper.advanceAlongRoute(
                currentLat = lat,
                currentLon = lon,
                currentStage = stage,
                waypoints = route,
                distanceMeters = stepMeters,
                loop = true,
                fallbackBearing = bearing,
            )
            assertFalse("Loop mode must never finish automatically", out.finished)
            val actualStep = Geodesic.WGS84.Inverse(lat, lon, out.lat, out.lon).s12
            assertTrue(
                "Tick $tick jumped $actualStep m (expected <= $stepMeters m) — teleport detected!",
                actualStep <= stepMeters + 1e-3,
            )
            lat = out.lat
            lon = out.lon
            stage = out.nextStage
            bearing = out.bearing
        }
    }

    @Test
    fun `degenerate duplicate waypoints terminate safely`() {
        val p = 31.2304 to 121.4737
        val outNonLoop = RouteStepper.advanceAlongRoute(p.first, p.second, 1, listOf(p, p), 0.5, loop = false)
        assertTrue(outNonLoop.finished)

        val outLoop = RouteStepper.advanceAlongRoute(p.first, p.second, 1, listOf(p, p), 0.5, loop = true)
        assertFalse(outLoop.finished)
    }
}
