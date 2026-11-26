package com.nuvio.tv.livetv.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import kotlinx.coroutines.CompletableDeferred
import java.io.InterruptedIOException
import java.net.HttpURLConnection

/** Ownership of the one provider connection, including connections awaiting HTTP headers. */
internal class LiveTsFeed : DataSource.Factory {
    val bytes = LiveTsBuffer()
    val firstReader = CompletableDeferred<Unit>()
    val providerReleased = CompletableDeferred<Unit>()
    private var closed = false
    private var connection: HttpURLConnection? = null

    @Synchronized fun opening(c: HttpURLConnection) {
        if (closed) {
            c.disconnect()
            throw InterruptedIOException("Live session closed")
        }
        connection = c
    }

    @Synchronized fun released(c: HttpURLConnection) {
        if (connection === c) connection = null
    }

    /** Revoke synchronously; the caller disconnects this socket on an IO worker. */
    fun retire(): HttpURLConnection? {
        bytes.stop()
        firstReader.cancel()
        return synchronized(this) { closed = true; connection.also { connection = null } }
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
