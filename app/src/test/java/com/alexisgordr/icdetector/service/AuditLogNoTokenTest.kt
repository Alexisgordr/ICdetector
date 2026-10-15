package com.alexisgordr.icdetector.service

import com.alexisgordr.icdetector.models.CellData
import com.alexisgordr.icdetector.models.RadioTech
import com.alexisgordr.icdetector.models.VerificationStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 3.0 — El terminal no dice que falta la verificación cuando, sin token, no se consulta nada. */
class AuditLogNoTokenTest {
    private val cell = CellData(
        isRegistered = true, networkType = "4G LTE", cellId = "100", mnc = "07", tac = "1", dbm = -80,
        mcc = "214", radioTech = RadioTech.LTE, verified = VerificationStatus.PENDING
    )

    private fun audit(token: Boolean): String {
        val lines = mutableListOf<String>()
        AuditLogController(log = { tag, text -> lines += "$tag $text" }, publishStatus = {}, zeroOnlyCellCount = { 0 },
            hasCredentials = { token }).generate(cell)
        return lines.joinToString("\n")
    }

    @Test fun `without a token the audit is not provisional`() {
        val text = audit(token = false)
        assertFalse(text.contains("Resultado provisional"))
        assertFalse(text.contains("Falta la respuesta de las bases públicas"))
        assertTrue(text.contains(AuditLogController.NO_TOKEN_NOTE))
    }

    @Test fun `with a token a pending cell still waits for the answer`() {
        val text = audit(token = true)
        assertTrue(text.contains("Resultado provisional (falta la verificación)"))
        assertTrue(text.contains("Falta la respuesta de las bases públicas"))
    }
}
