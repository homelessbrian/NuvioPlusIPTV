package com.nuvio.tv.core.network

import org.junit.Assert.assertEquals
import org.junit.Test

class SourceNetworkRecoveryStateTest {
    @Test fun `upstream recovery on the same wifi publishes once when validation returns`() {
        var recoveries = 0
        val state = SourceNetworkRecoveryState { recoveries++ }
        state.update("wifi", true, false, true)
        state.update("wifi", true, false, false)
        state.update("wifi", true, false, false)
        assertEquals(0, recoveries)
        repeat(3) { state.update("wifi", true, false, true) }
        assertEquals(1, recoveries)
    }

    @Test fun `Android VPN validation does not replace tunnel verification`() {
        var recoveries = 0
        val state = SourceNetworkRecoveryState { recoveries++ }
        state.update("wifi", true, false, true)
        state.update("vpn", true, true, false)
        state.update("vpn", true, true, true)
        state.update("vpn", true, true, false)
        state.update("vpn", true, true, true)
        assertEquals(0, recoveries)
    }

    @Test fun `initial and duplicate network callbacks do not retry searches`() {
        var recoveries = 0
        val state = SourceNetworkRecoveryState { recoveries++ }
        repeat(3) { state.update("wifi", true, false) }
        assertEquals(0, recoveries)
    }

    @Test fun `returning and replaced physical networks each publish once`() {
        var recoveries = 0
        val state = SourceNetworkRecoveryState { recoveries++ }
        state.update("wifi", true, false)
        state.update(null, false, false)
        state.update("wifi", true, false)
        state.update("wifi", true, false)
        state.update("ethernet", true, false)
        state.update("ethernet", true, false)
        assertEquals(2, recoveries)
    }

    @Test fun `an unverified VPN interface waits for the separate verified signal`() {
        var recoveries = 0
        val state = SourceNetworkRecoveryState { recoveries++ }
        state.update("wifi", true, false)
        state.update("vpn", false, true)
        state.update("vpn", true, true)
        assertEquals(0, recoveries)
        state.update("wifi", true, false)
        assertEquals(1, recoveries)
    }
}
