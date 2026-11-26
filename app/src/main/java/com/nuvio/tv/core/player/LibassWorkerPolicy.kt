package com.nuvio.tv.core.player

import android.app.ActivityManager
import android.content.Context
import android.system.Os

/** Limit native subtitle worker overhead on small devices. */
internal object LibassWorkerPolicy {
    private const val THREADS_ENV = "LIBASS_RENDER_THREADS"
    private const val LOW_RAM_MAX_BYTES = 2L shl 30

    /** Called before providers or players can initialize libass. */
    fun apply(context: Context) {
        apply(
            physicalRamBytes = {
                val info = ActivityManager.MemoryInfo()
                context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(info)
                info.totalMem
            },
            existingOverride = { Os.getenv(THREADS_ENV) },
            setOverride = { value, overwrite -> Os.setenv(THREADS_ENV, value, overwrite) },
        )
    }

    internal fun apply(
        physicalRamBytes: () -> Long,
        existingOverride: () -> String?,
        setOverride: (value: String, overwrite: Boolean) -> Unit,
    ): Boolean = try {
        // Even an empty or automatic override belongs to the caller. Avoid reading RAM then.
        if (existingOverride() != null || physicalRamBytes() !in 1..LOW_RAM_MAX_BYTES) {
            false
        } else {
            // Do not overwrite an explicit override installed since the check above either.
            setOverride("1", false)
            true
        }
    } catch (_: Exception) {
        // Unknown device/environment support leaves libass's automatic policy in place.
        false
    }
}
