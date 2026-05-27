package com.alexisgordr.icdetector.network

import com.alexisgordr.icdetector.models.CellData
import com.alexisgordr.icdetector.models.VerificationStatus
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy

object WigleClient {
    fun tryWigleSync(
        cell: CellData,
        wigleApiName: String,
        wigleApiToken: String,
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

        val credentials = okhttp3.Credentials.basic(wigleApiName, wigleApiToken)
        
        // Mapeo de tecnología para WiGLE (indispensable para evitar ambigüedades)
        val radioType = when {
            cell.networkType.contains("5G") || cell.networkType.contains("NR") -> "NR"
            cell.networkType.contains("4G") || cell.networkType.contains("LTE") -> "LTE"
            cell.networkType.contains("3G") || cell.networkType.contains("WCDMA") -> "UMTS"
            else -> "GSM"
        }

        // URL con parámetros oficiales y tipo de radio
        val url = "https://api.wigle.net/api/v2/cell/search?mcc=${cell.mcc}&mnc=${cell.mnc}&lac=${cell.tac}&cellid=${cell.cellId}"


        val request = Request.Builder()
            .url(url)
            .header("Authorization", credentials)
            .header("User-Agent", "ICdetection/1.0 (Android)")
            .header("Accept", "application/json")
            .build()
        
        return try {
            currentClient.newCall(request).execute().use { response ->
                val responseCode = response.code
                val body = response.body.string()
                
                if (response.isSuccessful) {
                    val json = JSONObject(body)
                    if (json.has("success") && json.getBoolean("success") && json.has("results")) {
                        val results = json.getJSONArray("results")
                        if (results.length() > 0) {
                            Pair(VerificationStatus.VERIFIED, results.getJSONObject(0))
                        } else {
                            Pair(VerificationStatus.NOT_FOUND, null)
                        }
                    } else {
                        Pair(VerificationStatus.ERROR, null)
                    }
                } else {
                    if (responseCode == 404) Pair(VerificationStatus.NOT_FOUND, null) 
                    else Pair(VerificationStatus.ERROR, null)
                }
            }
        } catch (_: IOException) {
            Pair(VerificationStatus.ERROR, null)
        }
    }
}
