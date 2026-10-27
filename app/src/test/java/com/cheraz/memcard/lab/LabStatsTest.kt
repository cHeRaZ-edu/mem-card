package com.cheraz.memcard.lab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LabStatsTest {
    @Test
    fun emptyListHasNoSummary() {
        assertNull(LabStats.summarize(emptyList()))
    }

    @Test
    fun summaryOfOneToHundred() {
        val s = LabStats.summarize((1L..100L).toList().shuffled())!!
        assertEquals(100, s.count)
        assertEquals(1L, s.min)
        assertEquals(100L, s.max)
        assertEquals(50.5, s.avg, 0.0001)
        assertEquals(95L, s.p95)
    }

    @Test
    fun singleValueIsEveryStat() {
        val s = LabStats.summarize(listOf(7L))!!
        assertEquals(7L, s.min)
        assertEquals(7L, s.p95)
        assertEquals(7L, s.max)
    }

    @Test
    fun throughputInKilobytesPerSecond() {
        assertEquals(1024.0, LabStats.kbPerSec(1024L * 1024L, 1_000_000_000L), 0.0001)
        assertEquals(0.0, LabStats.kbPerSec(100L, 0L), 0.0)
    }
}
