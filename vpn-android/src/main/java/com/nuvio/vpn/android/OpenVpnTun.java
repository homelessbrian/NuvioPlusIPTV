package com.nuvio.vpn.android;

import android.net.VpnService;
import android.os.ParcelFileDescriptor;

/** App-owned routes are identical for both OpenVPN engines. Provider route
 * exclusions never override the selected protection scope. */
final class OpenVpnTun {
    private OpenVpnTun() {}

    static int establish(VpnConnectionService service, HttpStreamTunnel proxy,
                         String[] addresses, String[] dns, int mtu) {
        try {
            if (addresses.length == 0 || addresses.length > 8 || dns.length == 0 || dns.length > 8
                    || mtu < 576 || mtu > 9000) throw new IllegalArgumentException();
            boolean ipv4 = false, ipv6 = false;
            for (String cidr : addresses) {
                int slash = cidr.lastIndexOf('/');
                if (slash < 1 || !numericAddress(cidr.substring(0, slash))) throw new IllegalArgumentException();
                int prefix = Integer.parseInt(cidr.substring(slash + 1));
                if (cidr.indexOf(':') >= 0) {
                    if (prefix < 0 || prefix > 128 || mtu < 1280) throw new IllegalArgumentException();
                    ipv6 = true;
                } else {
                    if (prefix < 0 || prefix > 32) throw new IllegalArgumentException();
                    ipv4 = true;
                }
            }
            for (String server : dns) {
                if (!numericAddress(server) || (server.indexOf(':') >= 0 ? !ipv6 : !ipv4))
                    throw new IllegalArgumentException();
            }
            if (proxy != null) return proxy.attachOpenVpn(addresses, dns, mtu);
            VpnService.Builder builder = service.new Builder().setSession("OpenVPN").setMtu(mtu);
            builder.addAllowedApplication(service.getPackageName());
            for (String cidr : addresses) {
                int slash = cidr.lastIndexOf('/');
                builder.addAddress(cidr.substring(0, slash), Integer.parseInt(cidr.substring(slash + 1)));
            }
            for (String server : dns) builder.addDnsServer(server);
            if (ipv4) builder.addRoute("0.0.0.0", 0);
            if (ipv6) builder.addRoute("::", 0);
            ParcelFileDescriptor tun = builder.establish();
            if (tun == null) VpnConnectionService.noteFailure("Android would not set up the VPN connection.");
            return tun == null ? -1 : tun.detachFd();
        } catch (Exception failure) {
            VpnConnectionService.noteFailure("The VPN server's tunnel settings could not be applied safely.");
            return -1;
        }
    }

    // Do not resolve a server-supplied hostname outside the protected tunnel.
    static boolean numericAddress(String value) {
        if (value == null || value.isEmpty()) return false;
        if (value.indexOf(':') >= 0) {
            if (!value.matches("[0-9a-fA-F:.]+")) return false;
            try { return java.net.InetAddress.getByName(value) instanceof java.net.Inet6Address; }
            catch (Exception invalid) { return false; }
        }
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) return false;
            int number = 0;
            for (int i = 0; i < part.length(); i++) {
                char c = part.charAt(i);
                if (c < '0' || c > '9') return false;
                number = number * 10 + c - '0';
            }
            if (number > 255) return false;
        }
        return true;
    }
}
