package com.nuvio.vpn.android;

import java.io.IOException;

/** The two OpenVPN implementations expose the same protected-tunnel contract. */
interface OpenVpnEngine extends TunnelEngine {
    @Override TunnelTraffic statistics() throws IOException;
    @Override void close();
}
