package com.alexisgordr.icdetector

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 3.1.0 — El APK instalado (manifiesto fusionado con todas sus dependencias) no pide INTERNET y
 * Android no se lo ha concedido; sí conserva ACCESS_NETWORK_STATE.
 */
@RunWith(AndroidJUnit4::class)
class NoInternetPermissionInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun mergedManifestHasNoInternetPermission() {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        val requested = info.requestedPermissions.orEmpty().toList()
        assertFalse(requested.toString(), "android.permission.INTERNET" in requested)
        assertTrue(requested.toString(), "android.permission.ACCESS_NETWORK_STATE" in requested)
        assertTrue(context.checkSelfPermission("android.permission.INTERNET") == PackageManager.PERMISSION_DENIED)
    }
}
