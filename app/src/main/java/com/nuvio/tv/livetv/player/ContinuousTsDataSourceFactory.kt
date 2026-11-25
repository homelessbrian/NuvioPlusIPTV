package com.nuvio.tv.livetv.player

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource

/**
 * A continuous TV transport has no file end to seek to. Some providers nevertheless send a
 * Content-Length, which makes the progressive TS extractor open the stream again at its tail
 * and then its start just to estimate a file duration. Keep the real transport and EOF/error
 * behavior, but leave the length unknown to the loader and extractor for this live-only path.
 */
@UnstableApi
internal class ContinuousTsDataSourceFactory(
    private val upstream: HttpDataSource.Factory,
) : HttpDataSource.Factory {
    override fun setDefaultRequestProperties(defaultRequestProperties: Map<String, String>): HttpDataSource.Factory {
        upstream.setDefaultRequestProperties(defaultRequestProperties)
        return this
    }

    override fun createDataSource(): HttpDataSource = ContinuousTsDataSource(upstream.createDataSource())

    private class ContinuousTsDataSource(private val upstream: HttpDataSource) : HttpDataSource by upstream {
        override fun open(dataSpec: DataSpec): Long {
            upstream.open(dataSpec)
            return C.LENGTH_UNSET.toLong()
        }
    }
}
