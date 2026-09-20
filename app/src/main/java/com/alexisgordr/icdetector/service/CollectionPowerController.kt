package com.alexisgordr.icdetector.service

import android.annotation.SuppressLint
import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager

/** Owns the power policy required by continuous field collection. */
internal class CollectionPowerController(private val context: Context) {
    private var wakeLock: PowerManager.WakeLock? = null

    @SuppressLint("WakelockTimeout")
    fun acquire() {
        val lock = wakeLock ?: context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ICdetector::collection")
            .apply { setReferenceCounted(false) }
            .also { wakeLock = it }
        if (!lock.isHeld) lock.acquire()
    }

    fun release() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    fun isBatteryCritical(): Boolean = try {
        val battery = context.getSystemService(BatteryManager::class.java)
        val percentage = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        !battery.isCharging && percentage in 1..CRITICAL_PERCENT
    } catch (_: Exception) { false }

    companion object { const val CRITICAL_PERCENT = 5 }
}
