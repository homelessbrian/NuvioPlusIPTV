package com.nuvio.tv.livetv.multiview

import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.nuvio.tv.livetv.model.LiveTvLoadReport

/**
 * How hot the box is, for the report. A box that heats up slows its chip down to protect
 * itself, which looks just like panes slowly turning into a slide show.
 */
internal object MultiviewBoxHeat {
    private fun level(status: Int): String = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> "normal"
        PowerManager.THERMAL_STATUS_LIGHT -> "warm"
        PowerManager.THERMAL_STATUS_MODERATE -> "hot (may be slowing down)"
        PowerManager.THERMAL_STATUS_SEVERE -> "very hot (slowing down)"
        PowerManager.THERMAL_STATUS_CRITICAL,
        PowerManager.THERMAL_STATUS_EMERGENCY,
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "overheating"
        else -> "unknown"
    }

    /** E.g. "heat normal, 64% of the slow-down point"; "heat not reported" on older TVs. */
    fun describe(context: Context): String {
        if (Build.VERSION.SDK_INT < 29) return "heat not reported"
        val power = context.getSystemService(PowerManager::class.java) ?: return "heat not reported"
        val status = runCatching { level(power.currentThermalStatus) }.getOrDefault("unknown")
        // 1.0 is where the box starts slowing down; boxes that don't measure it give no number.
        val headroom = if (Build.VERSION.SDK_INT >= 30) {
            runCatching { power.getThermalHeadroom(0) }.getOrNull()?.takeIf { !it.isNaN() && it >= 0f }
        } else null
        return "heat $status" + (headroom?.let { ", ${Math.round(it * 100)}% of the slow-down point" } ?: "")
    }

    /** Adds a report line whenever the box's heat level changes, until closed. */
    fun watch(context: Context): AutoCloseable {
        if (Build.VERSION.SDK_INT < 29) return AutoCloseable { }
        val power = context.getSystemService(PowerManager::class.java) ?: return AutoCloseable { }
        val listener = PowerManager.OnThermalStatusChangedListener { status ->
            LiveTvLoadReport.add("Multiview: box heat is now ${level(status)}")
        }
        return runCatching {
            power.addThermalStatusListener(listener)
            AutoCloseable { runCatching { power.removeThermalStatusListener(listener) } }
        }.getOrDefault(AutoCloseable { })
    }
}
