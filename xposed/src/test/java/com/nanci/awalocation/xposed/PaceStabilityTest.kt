package com.nanci.awalocation.xposed

import com.nanci.awalocation.xposed.hooks.sensor.StepSensorHook
import com.nanci.awalocation.xposed.utils.FakeLoc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.abs

class PaceStabilityTest {

    @Before
    fun resetState() {
        FakeLoc.resetMotionAndJitter()
        FakeLoc.accuracy = 4.5f
        FakeLoc.speed = 10.0 / 3.6
        FakeLoc.speedAmplitude = 1.0
        FakeLoc.stepCadence = 0f
    }

    @Test
    fun `10Hz jitter does not inflate cumulative running or walking pace`() {
        for (kmh in listOf(5.0, 10.0, 15.0)) {
            FakeLoc.resetMotionAndJitter()
            val speedMps = kmh / 3.6
            FakeLoc.speed = speedMps
            FakeLoc.accuracy = 8.0f

            var trueLat = 39.908822
            var trueLon = 116.397465
            var nowNs = 1_000_000_000L
            val dtSec = 0.1 // 100ms (10Hz)
            val stepMeters = speedMps * dtSec
            val totalTicks = 3000 // 300 seconds = 5 minutes

            FakeLoc.markMoved(nowNs)
            var prevJittered = FakeLoc.jitterLocation(trueLat, trueLon, nowNs = nowNs)
            var cumulativeMeasuredMeters = 0.0

            repeat(totalTicks) {
                nowNs += 100_000_000L
                FakeLoc.markMoved(nowNs)
                val (nextLat, nextLon) = FakeLoc.moveLocation(trueLat, trueLon, stepMeters, 35.0)
                trueLat = nextLat
                trueLon = nextLon

                val currentJittered = FakeLoc.jitterLocation(trueLat, trueLon, nowNs = nowNs)
                cumulativeMeasuredMeters += FakeLoc.haversine(
                    prevJittered.first,
                    prevJittered.second,
                    currentJittered.first,
                    currentJittered.second,
                )
                prevJittered = currentJittered
            }

            val expectedMeters = speedMps * (totalTicks * dtSec)
            val relativeError = abs(cumulativeMeasuredMeters - expectedMeters) / expectedMeters
            assertTrue(
                "Expected <0.5% pace error for ${kmh}km/h, got ${relativeError * 100}% (measured=$cumulativeMeasuredMeters, expected=$expectedMeters)",
                relativeError < 0.005,
            )
        }
    }

    @Test
    fun `concurrent reads within 40ms share identical jitter offset`() {
        val baseNs = 5_000_000_000L
        FakeLoc.markMoved(baseNs)
        FakeLoc.jitterLocation(31.2304, 121.4737, nowNs = baseNs)

        val t1 = baseNs + 200_000_000L
        FakeLoc.markMoved(t1)
        val first = FakeLoc.jitterLocation(31.2304, 121.4737, nowNs = t1)
        val second = FakeLoc.jitterLocation(31.2304, 121.4737, nowNs = t1 + 10_000_000L)

        assertEquals(first.first, second.first, 1e-12)
        assertEquals(first.second, second.second, 1e-12)
    }

    @Test
    fun `reportedSpeed is zero when stationary and bounded when moving`() {
        val t0 = 10_000_000_000L
        FakeLoc.speed = 3.0
        assertEquals(0.0f, FakeLoc.reportedSpeed(t0), 1e-6f)

        FakeLoc.markMoved(t0)
        for (step in 1..100) {
            val s = FakeLoc.reportedSpeed(t0 + step * 100_000_000L)
            FakeLoc.markMoved(t0 + step * 100_000_000L)
            assertTrue("Speed $s out of ±4% band around 3.0", s in 2.88f..3.12f)
        }

        // 停止移动超过 2.5s 后自动归零
        val afterTimeout = t0 + 100 * 100_000_000L + 3_000_000_000L
        assertEquals(0.0f, FakeLoc.reportedSpeed(afterTimeout), 1e-6f)
    }

    @Test
    fun `biomechanical auto cadence stays realistic across walk and run speeds`() {
        assertEquals(0f, StepSensorHook.calculateAutoCadence(0.0, 0f), 1e-4f)
        assertEquals(180f, StepSensorHook.calculateAutoCadence(2.5, 180f), 1e-4f)

        val walkCadence = StepSensorHook.calculateAutoCadence(5.0 / 3.6, 0f)
        assertTrue("5 km/h walk cadence=$walkCadence should be ~105-115 spm", walkCadence in 100f..120f)

        val jogCadence = StepSensorHook.calculateAutoCadence(10.0 / 3.6, 0f)
        assertTrue("10 km/h jog cadence=$jogCadence should be ~150-168 spm", jogCadence in 148f..170f)

        val runCadence = StepSensorHook.calculateAutoCadence(15.0 / 3.6, 0f)
        assertTrue("15 km/h run cadence=$runCadence should be ~185-205 spm", runCadence in 180f..205f)
    }
}
