package com.alexisgordr.icdetector.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alexisgordr.icdetector.R
import com.alexisgordr.icdetector.backup.BackupController
import com.alexisgordr.icdetector.backup.BackupManifest
import com.alexisgordr.icdetector.backup.FirstRun
import com.alexisgordr.icdetector.backup.RestartActivity
import com.alexisgordr.icdetector.backup.RestoreStager
import com.alexisgordr.icdetector.backup.RestoreStartup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 3.0 — Primera apertura: «¿Quieres restaurar una copia de seguridad?». Mientras se muestra no se
 * recoge nada. Cancelar el selector de fichero vuelve aquí sin guardar ninguna decisión; la decisión
 * se guarda solo tras restaurar bien (al arrancar) o tras terminar el borrado de «Empezar de cero».
 */
@Composable
fun FirstRunScreen(onStartedFresh: () -> Unit) {
    val context = LocalContext.current
    // Textos fuera de la composición (callbacks): con LocalResources, no con el Context.
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var staged by remember { mutableStateOf<BackupManifest?>(null) }
    var confirmFresh by remember { mutableStateOf(false) }
    val foreignData = remember { FirstRun.hasForeignData(context) }

    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { BackupController.stage(context, uri) }
            busy = false
            when (result) {
                is RestoreStager.Result.Staged -> staged = result.manifest
                is RestoreStager.Result.Rejected -> message = resources.getString(rejectionText(result.reason))
            }
        }
    }

    fun startFresh() {
        busy = true
        scope.launch {
            val done = withContext(Dispatchers.IO) { FirstRun.startFresh(context) }
            busy = false
            // Solo si se borró todo y se guardó la decisión; si no, la pregunta sigue pendiente.
            if (done) onStartedFresh() else message = resources.getString(R.string.first_run_fresh_failed)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0A))
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(stringResource(R.string.first_run_title), color = Color.White, fontSize = 18.sp,
            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.first_run_body), color = Color(0xFFBBBBBB), fontSize = 13.sp,
            fontFamily = FontFamily.Monospace, lineHeight = 18.sp)
        if (foreignData) {
            Text(stringResource(R.string.first_run_foreign_data), color = Color(0xFFCF6679), fontSize = 12.sp,
                fontFamily = FontFamily.Monospace, lineHeight = 17.sp)
        }
        Button(
            onClick = { restoreLauncher.launch(arrayOf("*/*")) },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333)),
            shape = RoundedCornerShape(4.dp)
        ) { Text(stringResource(R.string.first_run_restore), color = Color.White, fontFamily = FontFamily.Monospace) }
        Button(
            onClick = { if (foreignData) confirmFresh = true else startFresh() },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333)),
            shape = RoundedCornerShape(4.dp)
        ) { Text(stringResource(R.string.first_run_fresh), color = Color.White, fontFamily = FontFamily.Monospace) }
        if (busy) {
            Text(stringResource(R.string.backup_working), color = Color(0xFF888888), fontSize = 11.sp,
                fontFamily = FontFamily.Monospace)
        }
    }

    staged?.let { manifest ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.first_run_restore_confirm_title)) },
            text = {
                Text(stringResource(
                    if (foreignData) R.string.first_run_restore_confirm_replace else R.string.first_run_restore_confirm_body,
                    manifest.appVersion, manifest.createdAtUtc
                ))
            },
            confirmButton = {
                TextButton(onClick = {
                    staged = null
                    busy = true
                    scope.launch {
                        withContext(Dispatchers.IO) { RestoreStartup.engine(context).requestInstall() }
                        RestartActivity.restart(context)
                    }
                }) { Text(stringResource(R.string.restore_confirm_continue)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    staged = null
                    BackupController.discardStaged(context)
                }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    if (confirmFresh) {
        AlertDialog(
            onDismissRequest = { confirmFresh = false },
            title = { Text(stringResource(R.string.first_run_fresh_confirm_title)) },
            text = { Text(stringResource(R.string.first_run_fresh_confirm_body)) },
            confirmButton = {
                TextButton(onClick = { confirmFresh = false; startFresh() }) {
                    Text(stringResource(R.string.restore_confirm_continue))
                }
            },
            dismissButton = { TextButton(onClick = { confirmFresh = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }

    message?.let { text ->
        AlertDialog(
            onDismissRequest = { message = null },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } }
        )
    }
}
