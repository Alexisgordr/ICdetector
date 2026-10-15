package com.alexisgordr.icdetector

import android.app.Application
import com.alexisgordr.icdetector.backup.FirstRun
import com.alexisgordr.icdetector.backup.RestartActivity
import com.alexisgordr.icdetector.backup.RestoreStartup

/**
 * 3.0 — Lo primero del proceso principal: terminar o deshacer una restauración pendiente antes de
 * que la pantalla o el servicio abran la base de datos, y después resolver el estado de la pregunta
 * de la primera apertura (solo se da por resuelta si la restauración terminó bien).
 */
class ICDetectionApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Application.getProcessName().endsWith(RestartActivity.PROCESS_SUFFIX)) return
        val restore = RestoreStartup.run(this)
        FirstRun.resolveAtStart(this, restore.outcome)
    }
}
