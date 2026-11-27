package com.nuvio.tv.livetv.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import kotlinx.coroutines.CompletableDeferred
import java.io.InterruptedIOException
import java.net.HttpURLConnection

internal const val LIVE_PROVIDER_SETTLE_MS = 200L

/** Ownership of the one provider connection, including connections awaiting HTTP headers. */
internal class LiveTsFeed : DataSource.Factory {
    val bytes = LiveTsBuffer()
    val firstReader = CompletableDeferred<Unit>()
    val providerReleased = CompletableDeferred<Unit>()
    private var closed = false
    private var connection: HttpURLConnection? = null
    @Volatile var requestedProvider = false
        private set
    private var lastPlaybackCloseNs: Long? = null

    @Synchronized fun opening(c: HttpURLConnection) {
        if (closed) {
            c.disconnect()
            throw InterruptedIOException("Live session closed")
        }
        connection = c
        requestedProvider = true
    }

    @Synchronized fun released(c: HttpURLConnection, acceptedPlayback: Boolean) {
        if (connection === c) connection = null
        if (acceptedPlayback) lastPlaybackCloseNs = System.nanoTime()
    }

    /** A one-slot server can observe its socket closing shortly after disconnect() returns. */
    @Synchronized fun remainingSettleMs(): Long {
        val closedAt = lastPlaybackCloseNs ?: return 0
        val remainingNs = LIVE_PROVIDER_SETTLE_MS * 1_000_000 - (System.nanoTime() - closedAt)
        return if (remainingNs <= 0) 0 else (remainingNs + 999_999) / 1_000_000
    }

    /** Revoke synchronously; the caller disconnects this socket on an IO worker. */
    fun retire(): HttpURLConnection? {
        val active = synchronized(this) { closed = true; connection.also { connection = null } }
        bytes.stop()
        firstReader.cancel()
        return active
    }

    override fun createDataSource(): DataSource = object : BaseDataSource(false) {
        private var address: Uri? = null
        private var position = 0L
        override fun open(dataSpec: DataSpec): Long {
            transferInitializing(dataSpec)
            position = dataSpec.position
            bytes.attach(this, position)
            address = dataSpec.uri
            firstReader.complete(Unit)
            transferStarted(dataSpec)
            return C.LENGTH_UNSET.toLong()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val n = bytes.readLive(this, position, buffer, offset, length)
            if (n > 0) { position += n; bytesTransferred(n) }
            return n
        }

        override fun getUri(): Uri? = address
        override fun close() {
            bytes.detach(this)
            if (address != null) { address = null; transferEnded() }
        }
    }

}
