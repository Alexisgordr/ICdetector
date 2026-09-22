package com.alexisgordr.icdetector.forensics

import android.content.Context
import android.net.Uri
import com.alexisgordr.icdetector.storage.CellDbHelper
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object StableSiteExporter {
    fun export(context: Context, db: CellDbHelper, uri: Uri) {
        val files=db.getStableSiteExportFiles()
        context.contentResolver.openOutputStream(uri)?.use { raw ->
            ZipOutputStream(raw).use { zip -> files.forEach { (name,text) ->
                zip.putNextEntry(ZipEntry(name));zip.write(text.toByteArray());zip.closeEntry()
            } }
        } ?: error("Unable to open Stable-Site export destination")
    }
}
