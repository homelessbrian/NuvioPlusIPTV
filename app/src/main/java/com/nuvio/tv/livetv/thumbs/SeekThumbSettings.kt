package com.nuvio.tv.livetv.thumbs

import android.app.ActivityManager
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Seek thumbnail settings (Settings > Playback > Seek Thumbnails). Off by default; every limit is
 * a choice the user can change. Kept on this device only (each box has its own memory and storage).
 */
object SeekThumbSettings {
    /** When thumbnails are made. */
    enum class When(val label: String, val detail: String) {
        SEEKING("Only When Paused or Seeking", "The lightest choice: nothing is made while the video just plays"),
        WATCHING("While Watching", "Also made in the background while the video plays, when it has loaded well ahead"),
        RIGHT_AWAY("Right Away", "The whole video, from the start, as soon as it opens. Uses the most data"),
    }

    data class Values(
        val enabled: Boolean = false,
        val include4k: Boolean = false,
        val whenToMake: When = When.SEEKING,
        val storageMb: Int = 50,
        /** When thumbnails were last paused to keep playback smooth (0: never). */
        val lastLowMemoryAt: Long = 0L,
    )

    val STORAGE_CHOICES = listOf(50, 100, 250, 500)

    private const val FILE = "seek_thumbnails"
    private const val ENABLED = "enabled"
    private const val INCLUDE_4K = "include_4k"
    private const val WHEN = "when"
    private const val STORAGE_MB = "storage_mb"
    private const val LOW_MEMORY_AT = "low_memory_at"

    private val _values = MutableStateFlow(Values())
    val values: StateFlow<Values> = _values.asStateFlow()
    @Volatile private var loaded = false

    fun load(context: Context): Values {
        if (loaded) return _values.value
        synchronized(this) {
            if (loaded) return _values.value
            val p = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            _values.value = Values(
                enabled = p.getBoolean(ENABLED, false),
                // Not chosen yet: on for devices with plenty of memory, off for lower-end ones (anyone can change it).
                include4k = if (p.contains(INCLUDE_4K)) p.getBoolean(INCLUDE_4K, false) else !isLowMemoryDevice(context.applicationContext),
                whenToMake = runCatching { When.valueOf(p.getString(WHEN, null) ?: When.SEEKING.name) }.getOrDefault(When.SEEKING),
                storageMb = p.getInt(STORAGE_MB, 50).takeIf { it in STORAGE_CHOICES } ?: 50,
                lastLowMemoryAt = p.getLong(LOW_MEMORY_AT, 0L),
            )
            loaded = true
        }
        return _values.value
    }

    fun update(context: Context, change: (Values) -> Values) {
        load(context)
        val next = change(_values.value)
        _values.value = next
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putBoolean(ENABLED, next.enabled)
            .putBoolean(INCLUDE_4K, next.include4k)
            .putString(WHEN, next.whenToMake.name)
            .putInt(STORAGE_MB, next.storageMb)
            .putLong(LOW_MEMORY_AT, next.lastLowMemoryAt)
            .apply()
    }

    /** Android counts this box as low on memory (most cheap sticks and older boxes). */
    fun isLowMemoryDevice(context: Context): Boolean =
        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.isLowRamDevice || am.memoryClass <= 192
        }.getOrDefault(false)
}
