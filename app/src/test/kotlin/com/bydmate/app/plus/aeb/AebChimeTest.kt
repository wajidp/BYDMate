package com.bydmate.app.plus.aeb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class AebChimeTest {
    private val pcm = AebChime.pcm()

    @Test fun `it lasts under half a second and starts and ends near silence`() {
        assertEquals(17_640, pcm.size)
        assertTrue(abs(pcm.first().toInt()) < 100)
        assertTrue(abs(pcm.last().toInt()) < 1_500)
    }

    @Test fun `it is quiet enough not to clip and fades away`() {
        val peak = pcm.maxOf { abs(it.toInt()) }
        assertTrue(peak in 3_000..14_000)
        val tail = pcm.takeLast(2_000).maxOf { abs(it.toInt()) }
        assertTrue(tail < peak / 8)
    }
}
