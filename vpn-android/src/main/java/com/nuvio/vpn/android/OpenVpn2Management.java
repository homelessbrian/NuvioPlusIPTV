package com.nuvio.vpn.android;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Bounded Android management protocol, deliberately without raw engine logging.
 * A process being alive is not proof of an authenticated, usable tunnel. */
final class OpenVpn2Management {
    interface Callbacks {
        void command(String command) throws IOException;
        void protect() throws IOException;
        void openTun(String[] addresses, String[] dns, int mtu) throws IOException;
        void credentials() throws IOException;
    }

    private final Callbacks callbacks;
    private final List<String> addresses = new ArrayList<>(), dns = new ArrayList<>();
    private boolean authenticated, authRejected, ended, protectedTransport, tunEstablished;
    private long received, sent, generation;
    private int mtu = 1500;

    OpenVpn2Management(Callbacks callbacks) { this.callbacks = callbacks; }

    synchronized void line(String line) throws IOException {
        try { processLine(line); }
        catch (IOException | RuntimeException failure) {
            authenticated = false;
            ended = true;
            throw failure;
        }
    }

    private void processLine(String line) throws IOException {
        if (ended) throw new IOException("OpenVPN compatibility connection ended.");
        if (line.length() > 16384 || line.indexOf('\0') >= 0)
            throw new IOException("Invalid OpenVPN management response.");
        if (line.startsWith(">STATE:")) {
            String[] fields = line.substring(7).split(",", 4);
            if (fields.length < 3) throw new IOException("Invalid OpenVPN state response.");
            authenticated = false;
            if (fields[1].equals("CONNECTED")) {
                if (!fields[2].equals("SUCCESS") || !protectedTransport || !tunEstablished)
                    throw new IOException("OpenVPN did not establish a protected tunnel.");
                generation++;
                authenticated = true;
            } else if (fields[1].equals("RECONNECTING")) {
                addresses.clear(); dns.clear(); mtu = 1500; tunEstablished = false;
            } else if (fields[1].equals("EXITING")) {
                ended = true;
            }
        } else if (line.startsWith(">BYTECOUNT:")) {
            String[] fields = line.substring(11).split(",", -1);
            if (fields.length != 2) throw new IOException("Invalid OpenVPN byte count.");
            received = positiveLong(fields[0]); sent = positiveLong(fields[1]);
        } else if (line.startsWith(">HOLD:")) {
            authenticated = false;
            addresses.clear(); dns.clear(); mtu = 1500; tunEstablished = false;
            callbacks.command("state on\nbytecount 1\nhold release\n");
        } else if (line.startsWith(">PASSWORD:")) {
            String request = line.substring(10);
            if (request.startsWith("Auth-Token:")) return;
            authenticated = false;
            if (request.startsWith("Verification Failed:")) {
                authRejected = true;
                throw new IOException("The OpenVPN service credentials were rejected.");
            }
            if (!request.equals("Need 'Auth' username/password"))
                throw new IOException("This OpenVPN profile requires unsupported interactive authentication.");
            callbacks.credentials();
        } else if (line.startsWith(">NEED-OK:")) {
            need(line.substring(9));
        } else if (line.startsWith("PROTECTFD: ")) {
            authenticated = false;
            callbacks.protect(); protectedTransport = true;
        } else if (line.startsWith(">FATAL:") || line.startsWith("ERROR:")
                || line.startsWith(">PROXY:") || line.startsWith(">PK_SIGN:")
                || line.startsWith(">NEED-STR:")) {
            authenticated = false;
            throw new IOException("The OpenVPN compatibility engine could not complete this connection safely.");
        } else if (line.startsWith(">INFOMSG:")) {
            String message = line.substring(9);
            if (message.startsWith("OPEN_URL:") || message.startsWith("CR_TEXT:") || message.startsWith("WEB_AUTH:"))
                throw new IOException("This OpenVPN server requires unsupported interactive authentication.");
        } else if (line.startsWith(">INFO:") || line.startsWith(">LOG:")
                || line.startsWith("SUCCESS:") || line.isEmpty()) {
            // Untrusted details can contain endpoints, credentials and pushed settings.
        } else {
            throw new IOException("Unsupported OpenVPN management response.");
        }
    }

    private void need(String request) throws IOException {
        if (!request.startsWith("Need '") || request.indexOf("' confirmation MSG:") < 6)
            throw new IOException("Invalid OpenVPN tunnel request.");
        int end = request.indexOf("' confirmation MSG:", 6);
        String kind = request.substring(6, end);
        String extra = request.substring(end + 19).trim();
        if (kind.equals("PROTECTFD")) {
            authenticated = false;
            callbacks.protect(); protectedTransport = true;
        } else if (kind.equals("DNSSERVER") || kind.equals("DNS6SERVER")) {
            if (!OpenVpnTun.numericAddress(extra) || dns.size() >= 8)
                throw new IOException("The VPN server supplied unsupported DNS settings.");
            dns.add(extra);
        } else if (kind.equals("IFCONFIG")) {
            String[] fields = extra.split(" +", -1);
            if (fields.length != 4 || !OpenVpnTun.numericAddress(fields[0]) || fields[0].indexOf(':') >= 0
                    || !OpenVpnTun.numericAddress(fields[1]) || fields[1].indexOf(':') >= 0
                    || !fields[3].matches("net30|p2p|subnet"))
                throw new IOException("The VPN server supplied unsupported IPv4 settings.");
            mtu = mtu(fields[2]);
            int prefix = fields[3].equals("subnet") ? maskPrefix(fields[1]) : 32;
            addAddress(fields[0] + "/" + prefix);
        } else if (kind.equals("IFCONFIG6")) {
            String[] fields = extra.split(" +", -1);
            if (fields.length != 2) throw new IOException("The VPN server supplied unsupported IPv6 settings.");
            int slash = fields[0].lastIndexOf('/');
            if (slash < 1 || fields[0].indexOf(':') < 0 || !OpenVpnTun.numericAddress(fields[0].substring(0, slash)))
                throw new IOException("The VPN server supplied unsupported IPv6 settings.");
            int prefix = integer(fields[0].substring(slash + 1));
            if (prefix < 0 || prefix > 128) throw new IOException("Invalid IPv6 prefix.");
            mtu = mtu(fields[1]);
            addAddress(fields[0]);
        } else if (kind.equals("ROUTE") || kind.equals("ROUTE6")) {
            // Same as Core3: the host installs its complete routes, never a pushed bypass.
        } else if (kind.equals("PERSIST_TUN_ACTION")) {
            // Reopen even when the provider requests persistent TUN; never reuse a stale private stack.
            callbacks.command("needok 'PERSIST_TUN_ACTION' OPEN_BEFORE_CLOSE\n");
            return;
        } else if (kind.equals("OPENTUN")) {
            if (!extra.equals("tun") || addresses.isEmpty() || dns.isEmpty() || !protectedTransport)
                throw new IOException("The VPN server did not supply a protected IP tunnel with DNS.");
            callbacks.openTun(addresses.toArray(new String[0]), dns.toArray(new String[0]), mtu);
            tunEstablished = true;
            return; // The callback sends its acknowledgement atomically with the TUN fd.
        } else {
            // DOMAIN/HTTPPROXY and future settings must not silently claim support.
            throw new IOException("The VPN server requested tunnel settings this app cannot apply safely.");
        }
        callbacks.command("needok '" + kind + "' ok\n");
    }

    private void addAddress(String value) throws IOException {
        if (addresses.size() >= 8) throw new IOException("Too many VPN tunnel addresses.");
        addresses.add(value);
    }
    private static int mtu(String text) throws IOException {
        int value = integer(text);
        if (value < 576 || value > 9000) throw new IOException("Unsupported VPN tunnel MTU.");
        return value;
    }
    private static int maskPrefix(String mask) throws IOException {
        long value = 0;
        for (String part : mask.split("\\.")) value = value << 8 | integer(part);
        boolean zero = false;
        int prefix = 0;
        for (int bit = 31; bit >= 0; bit--) {
            if ((value & 1L << bit) == 0) zero = true;
            else if (zero) throw new IOException("Invalid VPN subnet mask.");
            else prefix++;
        }
        return prefix;
    }
    private static int integer(String text) throws IOException {
        try { return Integer.parseInt(text); }
        catch (NumberFormatException invalid) { throw new IOException("Invalid VPN numeric setting."); }
    }
    private static long positiveLong(String text) throws IOException {
        try { long result = Long.parseLong(text); if (result < 0) throw new NumberFormatException(); return result; }
        catch (NumberFormatException invalid) { throw new IOException("Invalid VPN byte count."); }
    }

    synchronized TunnelTraffic statistics() { return new TunnelTraffic(received, sent, authenticated && !ended, authenticated ? generation : 0); }
    synchronized boolean ended() { return ended; }
    synchronized boolean authRejected() { return authRejected; }
    synchronized void end() { authenticated = false; ended = true; }
}
