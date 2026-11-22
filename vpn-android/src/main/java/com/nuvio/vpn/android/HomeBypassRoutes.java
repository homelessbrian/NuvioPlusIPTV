package com.nuvio.vpn.android;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;

/**
 * Route maths for letting a few home-network addresses skip a Full app tunnel: the IPv4 routes
 * that cover every address except those. Plain Java (no Android), so it can be checked anywhere.
 */
final class HomeBypassRoutes {
    private HomeBypassRoutes() { }

    /** A route: [network] with [prefix] bits, like 192.168.0.0/16. */
    static final class Cidr {
        final long network;
        final int prefix;
        Cidr(long network, int prefix) { this.network = network; this.prefix = prefix; }
        long first() { return network; }
        long last() { return network + (1L << (32 - prefix)) - 1; }
        boolean contains(long address) { return address >= first() && address <= last(); }
        @Override public String toString() { return format(network) + "/" + prefix; }
    }

    /** A dotted IPv4 address as a number, or -1 if it isn't one. */
    static long parse(String text) {
        if (text == null) return -1;
        String[] parts = text.trim().split("\\.", -1);
        if (parts.length != 4) return -1;
        long value = 0;
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) return -1;
            for (int i = 0; i < part.length(); i++) if (!Character.isDigit(part.charAt(i))) return -1;
            int n = Integer.parseInt(part);
            if (n > 255) return -1;
            value = (value << 8) | n;
        }
        return value;
    }

    static String format(long address) {
        return ((address >> 24) & 255) + "." + ((address >> 16) & 255) + "." + ((address >> 8) & 255) + "." + (address & 255);
    }

    /** "10.8.0.2/24" (or a bare address, as /32), or null if it isn't IPv4. */
    static Cidr parseCidr(String text) {
        if (text == null) return null;
        int slash = text.indexOf('/');
        long address = parse(slash < 0 ? text : text.substring(0, slash));
        if (address < 0) return null;
        int prefix = 32;
        if (slash >= 0) {
            try { prefix = Integer.parseInt(text.substring(slash + 1).trim()); } catch (NumberFormatException e) { return null; }
            if (prefix < 0 || prefix > 32) return null;
        }
        long mask = prefix == 0 ? 0 : (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
        return new Cidr(address & mask, prefix);
    }

    /** Home-network (private or link-local) addresses: the only ones ever let past the tunnel. */
    static boolean isHomeAddress(long a) {
        return (a >> 24) == 10 ||                       // 10.0.0.0/8
            (a >> 20) == ((172L << 4) | 1) ||          // 172.16.0.0/12
            (a >> 16) == ((192L << 8) | 168) ||        // 192.168.0.0/16
            (a >> 16) == ((169L << 8) | 254);          // 169.254.0.0/16
    }

    /**
     * Which of [wanted] can skip the tunnel: home addresses that aren't inside the VPN's own
     * addresses or routes ([tunnel]) and aren't one of its DNS servers ([dns]). An address the VPN
     * itself uses must stay in the tunnel, or the VPN would break.
     */
    static List<Long> usable(Collection<Long> wanted, Collection<Cidr> tunnel, Collection<Long> dns) {
        List<Long> out = new ArrayList<>();
        for (Long a : new TreeSet<>(wanted)) {
            if (a == null || a < 0 || !isHomeAddress(a)) continue;
            if (dns.contains(a)) continue;
            boolean clash = false;
            for (Cidr c : tunnel) if (c.prefix > 0 && c.contains(a)) { clash = true; break; }
            if (!clash) out.add(a);
        }
        return out;
    }

    /** Routes covering every IPv4 address except [excluded] (0.0.0.0/0 when there are none). */
    static List<Cidr> allExcept(Collection<Long> excluded) {
        List<Cidr> out = new ArrayList<>();
        split(0L, 0, new TreeSet<>(excluded), out);
        return out;
    }

    private static void split(long network, int prefix, TreeSet<Long> excluded, List<Cidr> out) {
        Cidr range = new Cidr(network, prefix);
        Long inside = excluded.ceiling(range.first());
        if (inside == null || inside > range.last()) { out.add(range); return; }
        if (prefix == 32) return; // the excluded address itself
        long half = 1L << (31 - prefix);
        split(network, prefix + 1, excluded, out);
        split(network + half, prefix + 1, excluded, out);
    }
}
