package com.alexisgordr.icdetector.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 3.0 (corte de campaña) — El panel no pinta el ARFCN con el rojo de alerta cuando H8 falla: H8 es
 * un aviso de calidad de dato. Gris de dato dudoso, peso normal y una descripción accesible EN/ES.
 */
class ArfcnDataQualityDisplayTest {
    private fun file(path: String) = listOf(File(path), File("app/$path")).first { it.exists() }.readText()

    @Test fun `a channel outside the table is grey and described, not red`() {
        val screen = file("src/main/java/com/alexisgordr/icdetector/ui/MainScreen.kt")
        val block = screen.substringAfter("val arfcnOutOfTable = ").substringBefore("SecurityScorePanel(")
        assertTrue(block.contains("Color(0xFF888888)"))
        assertFalse(block.contains("0xFFCF6679"))
        assertFalse(block.contains("ExtraBold"))
        assertTrue(block.contains("R.string.arfcn_out_of_table"))
        assertFalse(screen.contains("esArfcnFalso"))
        listOf("src/main/res/values/strings.xml", "src/main/res/values-es/strings.xml").forEach {
            assertTrue(it, file(it).contains("name=\"arfcn_out_of_table\""))
        }
    }
}
