package com.nuvio.tv.livetv.player

import android.os.SystemClock
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.InterruptedIOException

/** A direct player's HTTP opens, including those still waiting for response headers. */
@UnstableApi
internal class DirectTsHandoff(
    private val scope: CoroutineScope,
    private val now: () -> Long = SystemClock::elapsedRealtime,
) {
    private val drained = CompletableDeferred<Unit>()
    private var retired = false
    private var openingOrOpen = 0
    private var lastResponseClose: Long? = null
    private var release: Job? = null

    @Synchronized private fun begin() {
        if (retired) throw InterruptedIOException("Previous TV tune retired")
        openingOrOpen++
    }

    @Synchronized private fun ended(hadResponse: Boolean) {
        if (hadResponse) lastResponseClose = now()
        openingOrOpen--
        if (retired && openingOrOpen == 0) drained.complete(Unit)
    }

    @Synchronized private fun remainingGrace(): Long =
        lastResponseClose?.let { (it + LIVE_PROVIDER_SETTLE_MS - now()).coerceIn(0L, LIVE_PROVIDER_SETTLE_MS) } ?: 0L

    /** Call before stopping the player so a queued retry cannot acquire another connection. */
    @Synchronized fun retire(): Job? {
        retired = true
        if (openingOrOpen == 0) drained.complete(Unit)
        if (drained.isCompleted && remainingGrace() == 0L) return null
        return release ?: scope.launch {
            drained.await()
            delay(remainingGrace())
        }.also { release = it }
    }

    fun wrap(factory: HttpDataSource.Factory): HttpDataSource.Factory = object : HttpDataSource.Factory {
        override fun setDefaultRequestProperties(defaultRequestProperties: Map<String, String>): HttpDataSource.Factory {
            factory.setDefaultRequestProperties(defaultRequestProperties)
            return this
        }

        override fun createDataSource(): HttpDataSource {
            val network = factory.createDataSource()
            return object : HttpDataSource by network {
                private var acquired = false
                private var hadResponse = false

                override fun open(dataSpec: DataSpec): Long {
                    begin()
                    acquired = true
                    return try {
                        network.open(dataSpec).also { hadResponse = true }
                    } catch (failure: Throwable) {
                        try { close() } catch (closing: Throwable) { failure.addSuppressed(closing) }
                        throw failure
                    }
                }

                override fun close() {
                    if (!acquired) return
                    try { network.close() } finally {
                        acquired = false
                        ended(hadResponse)
                        hadResponse = false
                    }
                }
            }
        }
    }
}
