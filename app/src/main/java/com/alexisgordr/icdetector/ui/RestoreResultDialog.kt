package com.alexisgordr.icdetector.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.alexisgordr.icdetector.R
import com.alexisgordr.icdetector.backup.RestoreStartup
import androidx.core.content.edit

/** 3.0 — Tras reiniciar por una restauración, dice una vez cómo ha terminado. */
@Composable
fun RestoreResultDialog() {
    val context = LocalContext.current
    val prefs = remember { RestoreStartup.prefs(context) }
    var result by remember { mutableStateOf(prefs.getString(RestoreStartup.PREF_LAST_RESULT, null)) }
    val value = result ?: return
    val preBackup = prefs.getString(RestoreStartup.PREF_PRE_BACKUP_NAME, null).orEmpty()
    val reason = value.substringAfter(':', "")
    val text = when {
        value == "RESTORED" && preBackup.isNotEmpty() -> stringResource(R.string.restore_result_ok, preBackup)
        value == "RESTORED" -> stringResource(R.string.restore_result_ok_no_name)
        value.startsWith("PENDING") -> stringResource(R.string.restore_result_pending, reason)
        else -> stringResource(R.string.restore_result_rolled_back, reason)
    }
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.restore_result_title)) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = {
                prefs.edit { remove(RestoreStartup.PREF_LAST_RESULT); remove(RestoreStartup.PREF_PRE_BACKUP_NAME) }
                result = null
            }) { Text("OK") }
        }
    )
}
