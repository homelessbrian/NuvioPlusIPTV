package com.nuvio.vpn.android;

interface TunnelEngine extends AutoCloseable {
    boolean isUp();
    default boolean authRejected() { return false; }
    default String lastDetail() { return null; }
    TunnelTraffic statistics() throws Exception;
    boolean probe();
    String egress();
    @Override void close() throws Exception;
}

final class TunnelTraffic {
    final long received, sent, handshakeMillis;
    final boolean authenticated;
    final long session;
    /** Last allowlisted OpenVPN 2 management milestone, independent of tunnel verification. */
    final String openVpnState;
    /** WireGuard: usable unless something was sent after the keys ran out and no new handshake came (HandshakeHealth). */
    TunnelTraffic(long received, long sent, long handshakeMillis, HandshakeHealth health) {
        this.received = received;
        this.sent = sent;
        this.handshakeMillis = handshakeMillis;
        authenticated = health.usable(sent, handshakeMillis);
        session = 0;
        openVpnState = null;
    }
    TunnelTraffic(long received, long sent, boolean authenticated, long session) {
        this(received, sent, authenticated, session, null);
    }
    TunnelTraffic(long received, long sent, boolean authenticated, long session, String openVpnState) {
        this.received = received;
        this.sent = sent;
        this.handshakeMillis = 0;
        this.authenticated = authenticated;
        this.session = session;
        this.openVpnState = openVpnState;
    }
}
