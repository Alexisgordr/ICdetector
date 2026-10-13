package com.alexisgordr.icdetector.storage

import com.alexisgordr.icdetector.models.LOCAL_TRUST_RECONFIGURATION_HISTORY_LIKE
import com.alexisgordr.icdetector.models.LOCAL_TRUST_RECONFIGURATION_REASON
import com.alexisgordr.icdetector.models.SITE_UNVERIFIED_PREFIX
import com.alexisgordr.icdetector.models.SUBTHRESHOLD_PREFIX
import com.alexisgordr.icdetector.models.StoredReasons
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * 3.0 — El contrato de motivos guardados selecciona las filas correctas en SQLite real, con motivos
 * históricos representativos (incluidas combinaciones de varios fallos). No comprueba que una cadena
 * aparezca en el código: ejecuta el SQL que usan las consultas.
 */
class StoredReasonsSqlTest {
    private lateinit var db: Connection
    private val h15 = StoredReasons.H15_REASON
    private val sep = StoredReasons.SEPARATOR

    /** Motivo → (¿limpia?, ¿válida para el historial de H15 con score 100?). */
    private val rows = linkedMapOf<String?, Pair<Boolean, Boolean>>(
        null to (true to true),
        "" to (true to true),
        "   " to (true to true),
        "OK" to (true to true),
        "Celda aislada" to (false to false),
        "$SUBTHRESHOLD_PREFIX Vecinos fantasma" to (false to false),
        h15 to (false to true),
        "$SUBTHRESHOLD_PREFIX $h15" to (false to true),
        "${StoredReasons.confirmingPrefix(2, 3)} $h15" to (false to true),
        "${StoredReasons.confirmingPrefix(1, 2)} $h15" to (false to true),
        "$h15${sep}Celda aislada" to (false to false),                               // H15 + otra regla
        "$SUBTHRESHOLD_PREFIX Celda aislada$sep$h15" to (false to false),
        "${StoredReasons.confirmingPrefix(2, 3)} $h15${sep}Episodio multiseñal (RF, IDENTIDAD)" to (false to false),
        "$SUBTHRESHOLD_PREFIX $LOCAL_TRUST_RECONFIGURATION_REASON (PCI)" to (false to false),
        "$SITE_UNVERIFIED_PREFIX Celda nueva" to (false to false),
        "OK $sep Celda aislada" to (false to false),
        // 3.0 — Reconfiguración local AÑADIDA a otros motivos (antes los sustituía), tal como la guarda
        // TemporalConfidence: no es una fila limpia, no vale para H15 y no es una fila de cuarentena.
        "$SUBTHRESHOLD_PREFIX ${StoredReasons.append("Celda aislada", "$LOCAL_TRUST_RECONFIGURATION_REASON (PCI)")}" to (false to false),
        "$SUBTHRESHOLD_PREFIX ${StoredReasons.append(h15, "$LOCAL_TRUST_RECONFIGURATION_REASON (PCI+GEOMETRY)")}" to (false to false)
    )

    @Before fun open() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use { it.execute("CREATE TABLE history (id INTEGER PRIMARY KEY, score INTEGER, failed_heuristics TEXT)") }
        db.prepareStatement("INSERT INTO history (score, failed_heuristics) VALUES (?, ?)").use { st ->
            rows.keys.forEach { reason -> st.setInt(1, 100); st.setString(2, reason); st.executeUpdate() }
            // Una fila limpia con score bajo: limpia, pero no válida para H15 (score < mínimo).
            st.setInt(1, 60); st.setString(2, "OK"); st.executeUpdate()
        }
    }

    @After fun close() = db.close()

    private fun select(where: String, vararg args: String): List<String?> =
        db.prepareStatement("SELECT failed_heuristics FROM history WHERE $where ORDER BY id").use { st ->
            args.forEachIndexed { i, a -> st.setString(i + 1, a) }
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }

    @Test fun `clean rows are null, blank or OK, never a row with any reason`() {
        val expected = rows.filter { it.value.first }.keys.toList() + "OK"
        assertEquals(expected, select(StoredReasons.cleanSql("failed_heuristics")))
        // La versión Kotlin (reputación) dice lo mismo fila a fila.
        rows.forEach { (reason, flags) -> assertEquals("$reason", flags.first, StoredReasons.isClean(reason)) }
    }

    @Test fun `H15 history takes clean rows above the score and rows whose only reason is H15`() {
        val expected = rows.filter { it.value.second }.keys.toList()
        assertEquals(expected, select(StoredReasons.h15EligibleSql("score", "failed_heuristics"), "85", *StoredReasons.H15_ONLY_LIKE))
    }

    @Test fun `reconfiguration quarantine finds only its own marker`() {
        // Solo la fila cuyo ÚNICO motivo es la reconfiguración: las que la llevan añadida a otro motivo
        // no cuentan como evidencia limpia del nuevo par PCI/ARFCN (además suelen tener score < 100).
        assertEquals(listOf("$SUBTHRESHOLD_PREFIX $LOCAL_TRUST_RECONFIGURATION_REASON (PCI)"),
            select("failed_heuristics LIKE ?", LOCAL_TRUST_RECONFIGURATION_HISTORY_LIKE))
    }

    @Test fun `appended reconfiguration keeps every reason and the row stays out of trusted learning`() {
        val merged = rows.keys.filterNotNull().filter { it.contains(sep) && it.contains(LOCAL_TRUST_RECONFIGURATION_REASON) }
        assertEquals(2, merged.size)
        assertEquals(emptyList<String>(), select("(${StoredReasons.cleanSql("failed_heuristics")}) AND failed_heuristics LIKE ?", "%$LOCAL_TRUST_RECONFIGURATION_REASON%"))
        assertEquals("ni H15 la toma como fila de solo-H15", false,
            select(StoredReasons.h15EligibleSql("score", "failed_heuristics"), "85", *StoredReasons.H15_ONLY_LIKE).any { it in merged })
        assertEquals(true, merged[0].contains("Celda aislada") && merged[1].contains(h15))
    }

    @Test fun `the stored strings are exactly the historical ones`() {
        assertEquals("OK", StoredReasons.CLEAN)
        assertEquals(" | ", StoredReasons.SEPARATOR)
        assertEquals("Identidad RF inestable: misma Cell ID alternando PCI en la misma portadora (posible clon)", h15)
        assertEquals("[2/3 ciclos confirmando]", StoredReasons.confirmingPrefix(2, 3))
        assertEquals("[sub-umbral]", SUBTHRESHOLD_PREFIX)
        assertEquals("[sub-umbral] Cambio en identidad celular consolidada (%)", LOCAL_TRUST_RECONFIGURATION_HISTORY_LIKE)
        assertEquals(listOf("Identidad RF inestable:%", "[sub-umbral] Identidad RF inestable:%", "[%ciclos confirmando] Identidad RF inestable:%"),
            StoredReasons.H15_ONLY_LIKE.toList())
    }
}
