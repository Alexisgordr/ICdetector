package com.alexisgordr.icdetector

import android.app.Application
import com.alexisgordr.icdetector.backup.RestartActivity
import com.alexisgordr.icdetector.backup.RestoreStartup

/**
 * 3.0 — Lo primero del proceso principal: terminar o deshacer una restauración pendiente antes de
 * que la pantalla o el servicio abran la base de datos.
 */
class ICDetectionApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Application.getProcessName().endsWith(RestartActivity.PROCESS_SUFFIX)) return
        RestoreStartup.run(this)
    }
}
