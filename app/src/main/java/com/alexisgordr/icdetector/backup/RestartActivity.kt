package com.alexisgordr.icdetector.backup

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process

/**
 * 3.0 — Reinicia la app tras confirmar una restauración. Vive en un proceso aparte (`:restart`):
 * mata el proceso principal, que así cierra todas sus conexiones a la base, y vuelve a abrir la app.
 * En el arranque nuevo, ICDetectionApp instala la copia antes de que nada abra la base.
 */
class RestartActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mainPid = intent.getIntExtra(EXTRA_MAIN_PID, -1)
        if (mainPid > 0) Process.killProcess(mainPid)
        packageManager.getLaunchIntentForPackage(packageName)?.let {
            startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        }
        finish()
        Runtime.getRuntime().exit(0)
    }

    companion object {
        const val PROCESS_SUFFIX = ":restart"
        private const val EXTRA_MAIN_PID = "main_pid"

        fun restart(context: Context) {
            context.startActivity(
                Intent(context, RestartActivity::class.java)
                    .putExtra(EXTRA_MAIN_PID, Process.myPid())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            Runtime.getRuntime().exit(0)
        }
    }
}
