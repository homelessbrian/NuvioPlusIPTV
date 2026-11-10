package com.nuvio.tv.livetv.multiview

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MultiviewPlaybackErrorsTest {
    @Test
    fun `decoder failure reports shared device limits without private exception details`() {
        val failure = PlaybackException(
            "private provider URL and credentials", IllegalStateException("private license key"),
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        )
        val message = MultiviewPlaybackErrors.describe(failure)
        assertEquals(
            "This TV can't decode this stream alongside the others. Remove a pane or choose another channel.",
            message,
        )
        assertFalse(message.contains("private"))
    }

    @Test
    fun `DRM and network errors use fixed recovery messages`() {
        assertEquals(
            "This protected channel couldn't be unlocked. Try another channel.",
            MultiviewPlaybackErrors.describe(
                PlaybackException("private", null, PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED),
            ),
        )
        assertEquals(
            "Can't reach this stream. Check the connection or try again.",
            MultiviewPlaybackErrors.describe(
                PlaybackException("private", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT),
            ),
        )
    }
}
