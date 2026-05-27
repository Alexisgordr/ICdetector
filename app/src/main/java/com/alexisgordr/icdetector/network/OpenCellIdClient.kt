package com.alexisgordr.icdetector.network

import com.alexisgordr.icdetector.models.CellData
import com.alexisgordr.icdetector.models.VerificationStatus
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy

object OpenCellIdClient {
    fun tryOpenCellIdSyncWithData(
        cell: CellData,
        openCellIdKey: String,
        isProxyEnabled: Boolean,
        client: OkHttpClient
    ): Pair<VerificationStatus, JSONObject?> {
        val currentClient = if (isProxyEnabled) {
            client.newBuilder()
                .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", 9050)))
                .build()
        } else {
            client
        }

        val url = "https://opencellid.org/cell/get?key=$openCellIdKey&mcc=${cell.mcc}&mnc=${cell.mnc}&lac=${cell.tac}&cellid=${cell.cellId}&format=json"
        val request = Request.Builder().url(url).build()
        
        return try {
            currentClient.newCall(request).execute().use { response ->
                val body = response.body.string()
                if (response.isSuccessful) {
                    val json = JSONObject(body)
                    if (json.has("lat")) {
                        Pair(VerificationStatus.VERIFIED, json)
                    } else {
                        Pair(VerificationStatus.NOT_FOUND, null)
                    }
                } else {
                    if (response.code == 404) Pair(VerificationStatus.NOT_FOUND, null) 
                    else Pair(VerificationStatus.ERROR, null)
                }
            }
        } catch (_: IOException) {
            Pair(VerificationStatus.ERROR, null)
        }
    }
}
