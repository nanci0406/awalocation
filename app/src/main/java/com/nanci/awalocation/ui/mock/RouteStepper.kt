package com.nanci.awalocation.ui.mock

import net.sf.geographiclib.Geodesic

data class RouteStepOutcome(
    val lat: Double,
    val lon: Double,
    val bearing: Double,
    val nextStage: Int,
    val finished: Boolean,
)

object RouteStepper {
    /**
     * 沿 WGS84 测地线折线精确推进 [distanceMeters] 米：
     * 1. 跨过中间路径点时自动把剩余步长结转到下一段，不提前瞬移跳过最后 1m，也不在拐点损失步长；
     * 2. [loop] 为 true 时，到达终点后沿测地线按设定速度平滑闭合回起点继续循环，避免终点瞬移回起点造成配速暴冲。
     */
    fun advanceAlongRoute(
        currentLat: Double,
        currentLon: Double,
        currentStage: Int,
        waypoints: List<Pair<Double, Double>>,
        distanceMeters: Double,
        loop: Boolean,
        fallbackBearing: Double = 0.0,
    ): RouteStepOutcome {
        if (waypoints.isEmpty()) {
            return RouteStepOutcome(currentLat, currentLon, normalizeBearing(fallbackBearing), 0, finished = true)
        }

        var lat = currentLat
        var lon = currentLon
        var stage = currentStage.coerceIn(0, waypoints.size)
        var remaining = distanceMeters.coerceAtLeast(0.0)
        var bearing = normalizeBearing(fallbackBearing)

        if (stage >= waypoints.size) {
            if (!loop) {
                val last = waypoints.last()
                return RouteStepOutcome(last.first, last.second, bearing, 0, finished = true)
            }
            stage = 0
        }

        val maxHopGuard = waypoints.size * 2 + 4
        var hops = 0
        while (hops++ < maxHopGuard) {
            if (stage >= waypoints.size) {
                if (loop) {
                    stage = 0
                } else {
                    return RouteStepOutcome(lat, lon, bearing, 0, finished = true)
                }
            }

            val target = waypoints[stage]
            val inverse = Geodesic.WGS84.Inverse(lat, lon, target.first, target.second)
            val segRemaining = inverse.s12
            if (segRemaining > 1e-4) {
                bearing = normalizeBearing(inverse.azi1)
            }

            if (segRemaining <= 1e-4) {
                lat = target.first
                lon = target.second
                stage++
                continue
            }

            if (remaining >= segRemaining) {
                remaining -= segRemaining
                lat = target.first
                lon = target.second
                stage++
                if (remaining <= 1e-5) {
                    if (stage >= waypoints.size) {
                        return if (loop) {
                            RouteStepOutcome(lat, lon, bearing, 0, finished = false)
                        } else {
                            RouteStepOutcome(lat, lon, bearing, 0, finished = true)
                        }
                    }
                    return RouteStepOutcome(lat, lon, bearing, stage, finished = false)
                }
            } else {
                val direct = Geodesic.WGS84.Direct(lat, lon, inverse.azi1, remaining)
                lat = direct.lat2
                lon = direct.lon2
                bearing = normalizeBearing(direct.azi2)
                return RouteStepOutcome(lat, lon, bearing, stage, finished = false)
            }
        }

        return RouteStepOutcome(lat, lon, bearing, stage.coerceIn(0, waypoints.lastIndex), finished = !loop)
    }

    fun initialBearing(waypoints: List<Pair<Double, Double>>, fallback: Double = 0.0): Double {
        if (waypoints.size < 2) return normalizeBearing(fallback)
        val start = waypoints[0]
        for (i in 1 until waypoints.size) {
            val next = waypoints[i]
            val inv = Geodesic.WGS84.Inverse(start.first, start.second, next.first, next.second)
            if (inv.s12 > 1e-4) {
                return normalizeBearing(inv.azi1)
            }
        }
        return normalizeBearing(fallback)
    }

    private fun normalizeBearing(deg: Double): Double {
        var b = deg % 360.0
        if (b < 0.0) b += 360.0
        return b + 0.0
    }
}
