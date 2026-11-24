package com.nuvio.tv.livetv.ui

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

/** Detailed frame/stack sampling is opt-in and expires even if the report is left open. */
internal object FrameCapture {
    val until = MutableStateFlow(0L)
    fun start() { until.value = SystemClock.elapsedRealtime() + 5 * 60_000L }
    fun stop() { until.value = 0L }
}

@Composable
internal fun frameCaptureActive(): Boolean {
    val deadline by FrameCapture.until.collectAsStateWithLifecycle()
    LaunchedEffect(deadline) {
        if (deadline > 0L) {
            delay((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
            FrameCapture.until.compareAndSet(deadline, 0L)
        }
    }
    return deadline > SystemClock.elapsedRealtime()
}
