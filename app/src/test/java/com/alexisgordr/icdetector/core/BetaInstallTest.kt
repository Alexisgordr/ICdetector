package com.alexisgordr.icdetector.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 3.0 — Una beta se instala como app aparte: no puede actualizar la estable de la campaña ni
 * migrar su base de datos. Y la versión final vuelve sola al identificador de la estable.
 */
class BetaInstallTest {
    private fun file(path: String) =
        listOf(File(path), File("app/$path"), File("../$path")).first { it.exists() }.readText()

    @Test fun `a beta version name gets its own application id and visible name`() {
        val gradle = file("build.gradle.kts").takeIf { it.contains("applicationId") }
            ?: file("app/build.gradle.kts")
        assertTrue(gradle.contains("applicationId = \"com.alexisgordr.icdetector\""))
        assertTrue(gradle.contains("val isBeta = versionName.orEmpty().contains(\"-beta\")"))
        assertTrue(gradle.contains("if (isBeta) applicationIdSuffix = \".beta\""))
        assertTrue(gradle.contains("manifestPlaceholders[\"appLabel\"] = if (isBeta) \"ICdetection β\" else \"ICdetection\""))
    }

    @Test fun `the launcher name comes from the build, not a fixed text`() {
        val manifest = file("src/main/AndroidManifest.xml")
        assertTrue(manifest.contains("android:label=\"\${appLabel}\""))
    }
}
