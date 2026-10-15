package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.models.VerificationStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/** 3.0 — Sin token de OpenCellID no se muestra "consultando": la comprobación está desactivada. */
class PublicDbNoteTest {
    @Test fun `without a token a pending cell is not shown as being queried`() {
        assertEquals(PublicDbNote.CHECK_OFF_NO_TOKEN, PublicDbNote.of(VerificationStatus.PENDING, hasCredentials = false))
        assertEquals(PublicDbNote.QUERYING, PublicDbNote.of(VerificationStatus.PENDING, hasCredentials = true))
    }

    @Test fun `real results are shown as they are, with or without a token`() {
        listOf(true, false).forEach { token ->
            assertEquals(PublicDbNote.REGISTERED, PublicDbNote.of(VerificationStatus.VERIFIED, token))
            assertEquals(PublicDbNote.NOT_FOUND, PublicDbNote.of(VerificationStatus.NOT_FOUND, token))
            assertEquals(PublicDbNote.REJECTED, PublicDbNote.of(VerificationStatus.REJECTED, token))
            assertEquals(PublicDbNote.ERROR, PublicDbNote.of(VerificationStatus.ERROR, token))
        }
    }
}
