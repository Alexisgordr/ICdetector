package com.alexisgordr.icdetector.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alexisgordr.icdetector.MainActivity
import com.alexisgordr.icdetector.R
import com.alexisgordr.icdetector.backup.BackupController
import com.alexisgordr.icdetector.backup.BackupExporter
import com.alexisgordr.icdetector.backup.BackupFormat
import com.alexisgordr.icdetector.backup.BackupManifest
import com.alexisgordr.icdetector.backup.BackupPolicy
import com.alexisgordr.icdetector.backup.RestartActivity
import com.alexisgordr.icdetector.backup.RestoreStager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 3.0 — Ajustes → Copia de seguridad: exportar todos los datos a un fichero y restaurarlos.
 *
 * Restaurar SUSTITUYE los datos actuales. Orden: validar la copia sin tocar nada → confirmar →
 * parar la monitorización → guardar una copia de los datos actuales donde elija el usuario → solo
 * si eso sale bien, reiniciar la app, que instala la copia al arrancar. Cancelar o fallar en
 * cualquier paso deja todo como estaba.
 */
@Composable
fun BackupSection() {
    val context = LocalContext.current
    // Textos fuera de la composición (callbacks): con LocalResources, no con el Context.
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var staged by remember { mutableStateOf<BackupManifest?>(null) }

    val activity = context as? MainActivity
    val cancelledText = stringResource(R.string.restore_cancelled)

    fun cancelRestore(text: String) {
        BackupController.discardStaged(context)
        activity?.resumeMonitoringAfterRestoreCancelled()
        message = text
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(BackupFormat.MIME_TYPE)
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { BackupController.exportTo(context, uri) }
            busy = false
            message = when (result) {
                is BackupExporter.Result.Ok -> resources.getString(
                    R.string.backup_export_ok, BackupController.displayName(context, uri) ?: ""
                )
                is BackupExporter.Result.Failed -> resources.getString(R.string.backup_export_failed, result.reason)
            }
        }
    }

    val preBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(BackupFormat.MIME_TYPE)
    ) { uri ->
        if (uri == null) {
            cancelRestore(cancelledText)
            return@rememberLauncherForActivityResult
        }
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { BackupController.saveCurrentAndConfirm(context, uri) }
            if (result is BackupExporter.Result.Ok) {
                RestartActivity.restart(context)
            } else {
                busy = false
                val reason = (result as BackupExporter.Result.Failed).reason
                cancelRestore(resources.getString(R.string.restore_pre_backup_failed, reason))
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
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

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.backup_section_title), color = Color.White, fontSize = 14.sp,
            fontFamily = FontFamily.Monospace)
        Text(stringResource(R.string.backup_section_body), color = Color(0xFF888888), fontSize = 11.sp,
            fontFamily = FontFamily.Monospace, lineHeight = 15.sp)
        Button(
            onClick = { exportLauncher.launch(BackupController.suggestedName("ICdetection-backup")) },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333)),
            shape = RoundedCornerShape(4.dp)
        ) { Text(stringResource(R.string.backup_export), color = Color.White, fontFamily = FontFamily.Monospace) }
        Button(
            onClick = { importLauncher.launch(arrayOf("*/*")) },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333)),
            shape = RoundedCornerShape(4.dp)
        ) { Text(stringResource(R.string.backup_import), color = Color.White, fontFamily = FontFamily.Monospace) }
        Text(stringResource(R.string.backup_import_replaces), color = Color(0xFFCF6679), fontSize = 11.sp,
            fontFamily = FontFamily.Monospace, lineHeight = 15.sp)
        if (busy) {
            Text(stringResource(R.string.backup_working), color = Color(0xFF888888), fontSize = 11.sp,
                fontFamily = FontFamily.Monospace)
        }
    }

    staged?.let { manifest ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.restore_confirm_title)) },
            text = { Text(stringResource(R.string.restore_confirm_body, manifest.appVersion, manifest.createdAtUtc)) },
            confirmButton = {
                TextButton(onClick = {
                    staged = null
                    busy = true
                    scope.launch {
                        // Primero terminar las escrituras pendientes; si no se puede, no se sigue.
                        val stopped = activity?.stopMonitoringForRestore() ?: true
                        busy = false
                        if (stopped) {
                            preBackupLauncher.launch(BackupController.suggestedName("ICdetection-before-restore"))
                        } else {
                            BackupController.discardStaged(context)
                            message = resources.getString(R.string.restore_drain_failed)
                        }
                    }
                }) { Text(stringResource(R.string.restore_confirm_continue)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    staged = null
                    BackupController.discardStaged(context)
                    message = cancelledText
                }) { Text(stringResource(R.string.cancel)) }
            }
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

fun rejectionText(reason: BackupPolicy.Rejection): Int = when (reason) {
    BackupPolicy.Rejection.NOT_A_BACKUP -> R.string.restore_rejected_not_backup
    BackupPolicy.Rejection.NOT_ICDETECTION -> R.string.restore_rejected_not_icdetection
    BackupPolicy.Rejection.UNKNOWN_FORMAT -> R.string.restore_rejected_unknown_format
    BackupPolicy.Rejection.MISSING_VERSION -> R.string.restore_rejected_missing_version
    BackupPolicy.Rejection.CAMPAIGN_VERSION -> R.string.restore_rejected_campaign
    BackupPolicy.Rejection.UNSUPPORTED_SCHEMA -> R.string.restore_rejected_campaign
    BackupPolicy.Rejection.FUTURE_SCHEMA -> R.string.restore_rejected_future
    BackupPolicy.Rejection.CHECKSUM_MISMATCH -> R.string.restore_rejected_damaged
    BackupPolicy.Rejection.CORRUPT_DATABASE -> R.string.restore_rejected_damaged
    BackupPolicy.Rejection.TOO_LARGE -> R.string.restore_rejected_damaged
}
