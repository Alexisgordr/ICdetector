package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.models.RadioTech
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 3.0 (#22) — Un solo validador de frecuencia e identificador físico por tecnología. */
class RadioChannelsTest {

    @Test fun `earfcn 0 is a real band 1 carrier`() {
        assertTrue(RadioChannels.isValidChannel(RadioTech.LTE, 0))
        assertEquals(0, RadioChannels.channelOrNull(RadioTech.LTE, 0))
    }

    @Test fun `android unavailable is never a channel or physical id`() {
        RadioTech.values().forEach { radio ->
            assertFalse(RadioChannels.isValidChannel(radio, Int.MAX_VALUE))
            assertFalse(RadioChannels.isValidPhysicalId(radio, Int.MAX_VALUE))
        }
        assertNull(RadioChannels.channelOrNull(RadioTech.LTE, null))
    }

    @Test fun `channel ranges follow each technology`() {
        assertTrue(RadioChannels.isValidChannel(RadioTech.LTE, 262_143))
        assertFalse(RadioChannels.isValidChannel(RadioTech.LTE, 262_144))
        assertTrue(RadioChannels.isValidChannel(RadioTech.NR, 632_448))
        assertFalse(RadioChannels.isValidChannel(RadioTech.NR, 3_279_166))
        assertTrue(RadioChannels.isValidChannel(RadioTech.UMTS, 10_700))
        assertTrue(RadioChannels.isValidChannel(RadioTech.GSM, 62))
        assertFalse(RadioChannels.isValidChannel(RadioTech.GSM, 1_024))
        assertFalse(RadioChannels.isValidChannel(RadioTech.UNKNOWN, 100))
        assertFalse(RadioChannels.isValidChannel(RadioTech.LTE, -1))
    }

    @Test fun `physical ids follow each technology instead of one generic limit`() {
        assertTrue(RadioChannels.isValidPhysicalId(RadioTech.LTE, 503))
        assertFalse(RadioChannels.isValidPhysicalId(RadioTech.LTE, 504))   // antes valía hasta 1007
        assertTrue(RadioChannels.isValidPhysicalId(RadioTech.NR, 1_007))
        assertTrue(RadioChannels.isValidPhysicalId(RadioTech.UMTS, 511))
        assertFalse(RadioChannels.isValidPhysicalId(RadioTech.GSM, 10))
    }

    @Test fun `stable-site keeps excluding gsm from rf fingerprints`() {
        assertFalse(StableSiteNeighbourEvidence.isValidArfcn(RadioTech.GSM, 62))
        assertTrue(StableSiteNeighbourEvidence.isValidArfcn(RadioTech.LTE, 0))
    }

    @Test fun `history learning no longer drops earfcn 0`() {
        val helper = listOf(
            File("src/main/java/com/alexisgordr/icdetector/storage/CellDbHelper.kt"),
            File("app/src/main/java/com/alexisgordr/icdetector/storage/CellDbHelper.kt")
        ).first { it.exists() }.readText()
        assertFalse(helper.contains("takeIf { it > 0 }"))
        assertFalse(helper.contains("takeIf { it in 0..1007 }"))
        assertEquals(2, Regex("RadioChannels\\.channelOrNull\\(").findAll(helper).count())
    }
}
