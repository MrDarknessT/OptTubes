package ru.grain.monitor

import org.junit.Assert.*
import org.junit.Test

class SensorFilterTest {
    private fun frame(code: Int) = IntArray(96) { code }
    private fun strict(window: Long = 1800) = SensorFilter(window, 70, SensorFilter.Mode.RESET_ON_GREEN)
    private fun rejects(block: () -> Unit) {
        try { block(); fail("Expected IllegalArgumentException") } catch (_: IllegalArgumentException) { }
    }

    @Test fun startupIsNeutral() = assertTrue(strict().statuses().all { it == SensorFilter.UNKNOWN })

    @Test fun faultsWaitForACompleteWindowAndRecoverOnOnePacket() {
        for (code in intArrayOf(0, 2, 3)) {
            val f = strict()
            for (t in 0L until 1800L step 30) f.accept(frame(code), t)
            assertEquals(-1, f.statuses()[0])
            f.accept(frame(code), 1800)
            assertEquals(code, f.statuses()[0])
            f.accept(frame(1), 1830)
            assertEquals(1, f.statuses()[0])
            assertEquals(0, f.diagnosis(0, 1830).samples)
            for (t in 1860L until 3660L step 30) f.accept(frame(code), t)
            assertEquals(1, f.statuses()[0])
            f.accept(frame(code), 3660)
            assertEquals(code, f.statuses()[0])
        }
    }

    @Test fun allWindowSettingsUseElapsedTime() {
        for (window in longArrayOf(1000, 1500, 1800, 2000)) {
            val f = strict(window)
            for (t in 0L until window step 30) f.accept(frame(2), t)
            assertEquals(-1, f.statuses()[0])
            f.accept(frame(2), window)
            assertEquals(2, f.statuses()[0])
        }
    }

    @Test fun sparseHealthyGrainDoesNotAlarm() {
        val f = strict()
        for (t in 0L until 60000L step 30) {
            f.accept(frame(if (t % 900 == 0L) 1 else 0), t)
            assertTrue(f.statuses().all { it == 1 })
        }
    }

    @Test fun tubesHaveIndependentHistories() {
        val f = strict()
        val a = frame(1).apply { this[12] = 2 }
        for (t in 0L..1800L step 30) f.accept(a, t)
        assertEquals(2, f.statuses()[12])
        assertEquals(1, f.statuses()[13])
        a[12] = 1
        a[13] = 3
        f.accept(a, 1830)
        assertEquals(1, f.statuses()[12])
        assertEquals(1, f.statuses()[13])
    }

    @Test fun missingPacketsAreUnknownAndDoNotCountAsFaultEvidence() {
        val f = strict()
        for (t in 0L..1800L step 30) f.accept(frame(1), t)
        f.checkStale(3600)
        assertEquals(1, f.statuses()[0])
        f.checkStale(3601)
        assertEquals(SensorFilter.STALE, f.statuses()[0])
        f.accept(frame(2), 3630)
        assertEquals(SensorFilter.UNKNOWN, f.statuses()[0])
        f.accept(frame(1), 3660)
        assertEquals(1, f.statuses()[0])
    }

    @Test fun malformedFramesAreRejectedAtomically() {
        val f = strict()
        f.accept(frame(1), 0)
        rejects { f.accept(IntArray(95), 30) }
        rejects { f.accept(frame(0).apply { this[95] = 4 }, 30) }
        assertEquals(1, f.diagnosis(0, 30).raw)
        rejects { f.accept(frame(0), 0) }
        rejects { f.accept(frame(0), -1) }
        f.accept(frame(2), 30)
        assertEquals(1, f.statuses()[0])
    }

    @Test fun rollingExactlySeventyPercentTriggersButSeventyOneDoesNot() {
        for (threshold in intArrayOf(70, 71)) {
            val f = SensorFilter(1800, threshold, SensorFilter.Mode.ROLLING_PERCENT)
            for (t in 0L..1800L step 30) f.accept(frame(if ((t / 30) % 10 < 7) 2 else 1), t)
            assertEquals(if (threshold == 70) 2 else 1, f.statuses()[0])
            f.accept(frame(1), 1830)
            assertEquals(1, f.statuses()[0])
            assertEquals(60, f.diagnosis(0, 1830).samples)
        }
    }

    @Test fun strictModeIgnoresIntermittentSeventyPercentFaults() {
        val f = strict()
        for (t in 0L until 10000L step 30) f.accept(frame(if ((t / 30) % 10 < 7) 2 else 1), t)
        assertEquals(1, f.statuses()[0])
    }

    @Test fun dominantBadStateIsShown() {
        val f = strict()
        for (t in 0L..1800L step 30) f.accept(frame(if ((t / 30) % 3 == 0L) 3 else 2), t)
        assertEquals(2, f.statuses()[0])
    }

    @Test fun allThirteenScenariosProduceValidCompleteFramesOrMissingPackets() {
        val sim = Simulator(frame(3))
        assertEquals(13, Simulator.names.size)
        assertEquals(Simulator.names.size, Simulator.descriptions.size)
        for (scenario in Simulator.names.indices) {
            sim.reset()
            val f = strict()
            for (tick in 0L until 900L) {
                sim.next(scenario, tick)?.let { packet ->
                    assertEquals(96, packet.size)
                    assertTrue(packet.all { it in 0..3 })
                    f.accept(packet, tick * 30)
                }
                f.checkStale(tick * 30)
            }
        }
    }

    @Test fun manualOverridesAndOnePacketRecoveryWork() {
        val sim = Simulator(frame(3))
        sim.override(95, 2)
        assertEquals(2, sim.next(1, 0)!![95])
        sim.pulse(95)
        assertEquals(1, sim.next(1, 1)!![95])
        assertEquals(-1, sim.overrideAt(95))
        sim.reset()
        assertEquals(-1, sim.overrideAt(95))
    }

    @Test fun normalGeneratorToleratesSomeSkippedSchedulerTicks() {
        val sim = Simulator(frame(3))
        val f = strict()
        var t = 0L
        while (t < 60000) {
            f.accept(sim.next(1, t / 30)!!, t)
            if (t > 1800) assertTrue(f.statuses().all { it == 1 })
            t += if (t % 300 == 0L) 60 else 30
        }
    }

    @Test fun slowerGrainRequiresALongerWindow() {
        for (window in longArrayOf(1000, 1800, 2000)) {
            val sim = Simulator(frame(3))
            val f = strict(window)
            var sawAlarm = false
            for (tick in 0L until 1000L) {
                f.accept(sim.next(12, tick)!!, tick * 30)
                if (tick > 60 && f.statuses().any { it == 0 }) sawAlarm = true
            }
            assertEquals(window == 1000L, sawAlarm)
        }
    }

    @Test fun actualBlockageScenarioHasOnlyFourFaultyTubes() {
        val sim = Simulator(frame(3))
        val f = strict()
        for (tick in 0L..120L) f.accept(sim.next(3, tick)!!, tick * 30)
        assertEquals(4, f.statuses().count { it == 2 })
        assertEquals(92, f.statuses().count { it == 1 })
    }

    @Test fun resetClearsOldEvidence() {
        val f = strict()
        for (t in 0L..1800L step 30) f.accept(frame(2), t)
        f.reset()
        assertTrue(f.statuses().all { it == -1 })
        f.accept(frame(2), 0)
        assertEquals(-1, f.statuses()[0])
    }
}
