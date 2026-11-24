package com.nuvio.vpn.android;

/**
 * Whether a WireGuard connection is still usable, judged the way WireGuard apps do it.
 *
 * WireGuard goes quiet while nothing uses it: no new handshake for minutes is normal, and the next
 * packet sent starts a new one at once. So an old handshake alone never counts as broken. Only when
 * something was sent after the keys ran out and no new handshake followed within a few seconds is the
 * connection treated as not working.
 */
final class HandshakeHealth {
    /** WireGuard's own limit: keys older than this are no longer used. */
    private static final long KEYS_EXPIRE_MS = 180_000;
    /** How long a send after that may wait for the new handshake. */
    private static final long RECONNECT_GRACE_MS = 15_000;

    private long lastSent = -1;
    private long lastHandshake;
    private long sentAfterExpiryAt;

    synchronized boolean usable(long sent, long handshakeMillis) {
        long now = System.currentTimeMillis();
        if (handshakeMillis <= 0) { lastSent = sent; return false; }
        if (handshakeMillis != lastHandshake) { lastHandshake = handshakeMillis; sentAfterExpiryAt = 0; }
        boolean expired = now - handshakeMillis > KEYS_EXPIRE_MS;
        if (expired && sentAfterExpiryAt == 0 && lastSent >= 0 && sent > lastSent) sentAfterExpiryAt = now;
        lastSent = sent;
        return !expired || sentAfterExpiryAt == 0 || now - sentAfterExpiryAt < RECONNECT_GRACE_MS;
    }
}
