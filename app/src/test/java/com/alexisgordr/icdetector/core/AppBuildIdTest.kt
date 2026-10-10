package com.alexisgordr.icdetector.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 3.0 — AppVersion = versionName+commit, para separar en los datos las compilaciones de una beta. */
class AppBuildIdTest {

    private fun read(path: String) = listOf(File(path), File("../$path")).first { it.exists() }.readText()

    @Test fun `the version carries the commit of the build`() {
        assertEquals("3.0.0-beta4+6bb5ed3", AppBuildId.format("3.0.0-beta4", "6bb5ed3"))
        assertEquals("3.0.0-beta4+6bb5ed3-dirty", AppBuildId.format("3.0.0-beta4", "6bb5ed3-dirty"))
        assertEquals("3.0.0+0123456789abcdef", AppBuildId.format(" 3.0.0 ", " 0123456789abcdef\n"))
    }

    @Test fun `an unknown or unexpanded commit is nogit, never invented`() {
        assertEquals("3.0.0-beta4+nogit", AppBuildId.format("3.0.0-beta4", null))
        assertEquals("3.0.0-beta4+nogit", AppBuildId.format("3.0.0-beta4", ""))
        // Fichero BUILD_COMMIT en un checkout normal: git no lo ha rellenado.
        assertEquals("3.0.0-beta4+nogit", AppBuildId.format("3.0.0-beta4", "\$Format:%h\$"))
        assertEquals("3.0.0-beta4+nogit", AppBuildId.format("3.0.0-beta4", "not a commit"))
    }

    @Test fun `without a version name there is no build id, as before`() {
        assertNull(AppBuildId.format(null, "6bb5ed3"))
        assertNull(AppBuildId.format("  ", "6bb5ed3"))
    }

    @Test fun `gradle, git archive and the app are wired together`() {
        val gradle = read("app/build.gradle.kts")
        assertTrue(gradle.contains("buildConfig = true"))
        assertTrue(gradle.contains("buildConfigField(\"String\", \"BUILD_COMMIT\", \"\\\"\$buildCommit\\\"\")"))
        assertTrue(gradle.contains("\"git\", \"describe\", \"--always\", \"--dirty\", \"--abbrev=7\", \"--exclude=*\""))
        assertTrue(gradle.contains("file(\"BUILD_COMMIT\")"))
        assertTrue(read(".gitattributes").contains("BUILD_COMMIT export-subst"))
        assertEquals("\$Format:%h\$", read("BUILD_COMMIT").trim())
        val main = "app/src/main/java/com/alexisgordr/icdetector"
        assertTrue(read("$main/service/MiniICService.kt").contains("com.alexisgordr.icdetector.core.AppBuildId.current("))
        listOf("ForensicExporter", "TopologyExporter", "GeometryExporter").forEach {
            assertTrue(it, read("$main/forensics/$it.kt").contains("AppBuildId.current("))
        }
    }
}
