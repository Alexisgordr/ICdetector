package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.core.ServingIdentityChange.Identity
import com.alexisgordr.icdetector.models.RadioTech
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 3.0 (#19) — Un handover es un cambio de la identidad completa de la servidora. */
class ServingIdentityChangeTest {
    private val lte = Identity("214", "07", "31601", "100", RadioTech.LTE)

    @Test fun `the first serving cell is a handover`() {
        assertTrue(ServingIdentityChange.isHandover(null, lte))
    }

    @Test fun `the same identity is not a handover`() {
        assertFalse(ServingIdentityChange.isHandover(lte, lte.copy()))
    }

    @Test fun `same cell id on another operator is a handover`() {
        assertTrue(ServingIdentityChange.isHandover(lte, lte.copy(mnc = "01")))
        assertTrue(ServingIdentityChange.isHandover(lte, lte.copy(mcc = "208")))
    }

    @Test fun `same cell id in another tracking area or technology is a handover`() {
        assertTrue(ServingIdentityChange.isHandover(lte, lte.copy(tac = "31602")))
        assertTrue(ServingIdentityChange.isHandover(lte, lte.copy(radio = RadioTech.NR)))
    }

    @Test fun `a field the modem did not fill is not a change`() {
        assertFalse(ServingIdentityChange.isHandover(lte, lte.copy(mcc = "N/A", mnc = "N/A")))
        assertFalse(ServingIdentityChange.isHandover(lte.copy(tac = ""), lte))
        assertFalse(ServingIdentityChange.isHandover(lte, lte.copy(radio = RadioTech.UNKNOWN)))
    }

    @Test fun `a different cell id is always a handover`() {
        assertTrue(ServingIdentityChange.isHandover(lte, lte.copy(cellId = "101")))
    }

    @Test fun `h10 history stores the full identity`() {
        assertEquals("214-07-31601-100-LTE", lte.key)
        val service = listOf(
            File("src/main/java/com/alexisgordr/icdetector/service/MiniICService.kt"),
            File("app/src/main/java/com/alexisgordr/icdetector/service/MiniICService.kt")
        ).first { it.exists() }.readText()
        assertTrue(service.contains("ServingIdentityChange.isHandover(prevServingIdentity, servingIdentity)"))
        assertTrue(service.contains("cellChangeHistory.add(Pair(servingIdentity.key, currentTime))"))
        assertFalse(service.contains("if (cid != prevCid)"))
    }
}
