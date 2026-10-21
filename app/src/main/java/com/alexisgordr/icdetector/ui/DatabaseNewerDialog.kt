package com.alexisgordr.icdetector.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.DialogProperties
import com.alexisgordr.icdetector.R
import com.alexisgordr.icdetector.storage.DatabaseGate

/**
 * 3.1.0 (#47) — Los datos son de una versión más nueva de la app (DatabaseGate). Bloqueante: no se
 * puede descartar tocando fuera ni con «atrás»; el único botón cierra la app. Nada se ha modificado.
 */
@Composable
internal fun DatabaseNewerDialog(state: DatabaseGate.State.NewerSchema, onClose: () -> Unit) {
    val context = LocalContext.current
    val appVersion = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
        .getOrNull().orEmpty().substringBefore('+')
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.database_newer_title)) },
        text = { Text(stringResource(R.string.database_newer_body, state.storedVersion, state.supportedVersion, appVersion)) },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.close)) } }
    )
}
