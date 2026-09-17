package com.alexisgordr.icdetector.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WigleClientTest {
    @Test
    fun `HTTP 429 activa el bloqueo global`() {
        assertTrue(WigleClient.isRateLimited(429, null))
    }

    @Test
    fun `mensaje real de cuota activa el bloqueo aunque el HTTP sea 200`() {
        assertTrue(WigleClient.isRateLimited(200, "too many queries today"))
    }

    @Test
    fun `un error de red normal no se confunde con cuota`() {
        assertFalse(WigleClient.isRateLimited(503, "service unavailable"))
    }
}
