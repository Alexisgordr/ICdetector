package com.alexisgordr.icdetector.forensics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForensicPolicyTest {
    @Test
    fun `forensic windows are bounded`() {
        assertEquals(60_000L, ForensicRecorder.PRE_WINDOW_MS)
        assertEquals(60_000L, ForensicRecorder.POST_WINDOW_MS)
        assertEquals(30L * 60_000L, ForensicRecorder.MAX_CASE_MS)
        assertTrue(ForensicRecorder.MAX_BUFFER_SAMPLES in 60..300)
    }
}
