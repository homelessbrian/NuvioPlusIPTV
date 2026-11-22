package com.nuvio.vpn.android;

import android.content.Context;
import android.content.SharedPreferences;
import com.nuvio.vpn.core.ProtectionMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;

/**
 * Full app mode sends everything the app does through the VPN, and the VPN can't reach the home
 * network: so a network folder (a NAS or computer at home where recordings are saved) couldn't
 * be reached. The host names that folder's address here, and Full app tunnels then route every
 * IPv4 address except it, so the app reaches it directly. Only that address, only home-network
 * (private) addresses, and never one the VPN uses itself (its own addresses, routes or DNS):
 * then it stays in the tunnel, as before, and [skipped] says why.
 *
 * Streams-only modes never carry the app's other connections, so they're unaffected.
 */
public final class VpnHomeBypass {
    private static final String PREFS = "nuvio_vpn_home_bypass";
    private static final String KEY = "addresses";
    private static volatile String skipped;
    /** The addresses the newest Full app connection was set up with ([key] form). */
    private static volatile String appliedKey;

    private VpnHomeBypass() { }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * The home-network addresses to reach directly (IPv4, like 192.168.1.20; anything else is
     * ignored). Remembered on this device. A Full app VPN that's on reconnects to use them when
     * they've changed.
     */
    public static void set(Context context, Collection<String> addresses) {
        TreeSet<Long> wanted = new TreeSet<>();
        if (addresses != null) for (String a : addresses) {
            long n = HomeBypassRoutes.parse(a);
            if (n >= 0 && HomeBypassRoutes.isHomeAddress(n)) wanted.add(n);
        }
        StringBuilder value = new StringBuilder();
        for (Long n : wanted) { if (value.length() > 0) value.append(','); value.append(HomeBypassRoutes.format(n)); }
        if (value.toString().equals(key(context))) return;
        prefs(context).edit().putString(KEY, value.toString()).commit();
        skipped = null;
        // Only a Full app connection uses these: it ends (the same way as after a profile change)
        // and, once it has, connects again with them.
        Context app = context.getApplicationContext();
        VpnConnectionService.userProfileChanged(app);
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
            () -> VpnPlaybackProtection.requestAutomaticAppWide(false), 2_000);
    }

    /** What's set, as stored (compared to see whether a connection is still up to date). */
    static String key(Context context) {
        try { return prefs(context).getString(KEY, ""); } catch (Exception unavailable) { return ""; }
    }

    private static List<Long> wanted(Context context) {
        List<Long> out = new ArrayList<>();
        for (String a : key(context).split(",")) {
            long n = HomeBypassRoutes.parse(a);
            if (n >= 0) out.add(n);
        }
        return out;
    }

    /**
     * Why the network folder isn't reached directly on the current Full app connection (it clashes
     * with the VPN's own addresses), or null.
     */
    public static String skipped() { return skipped; }

    /**
     * For the network folder's Test: a tip when the Full app VPN is on and the folder can't skip
     * it (a computer name instead of an IP address, a clash with the VPN, or an address outside
     * the home network). Null when there's nothing to say. Reads settings: call off the main thread.
     */
    public static String hint(Context context, String host) {
        try {
            if (new AndroidVpnSettings(context.getApplicationContext()).read().getMode() != ProtectionMode.APP_WIDE) return null;
        } catch (Exception unavailable) { return null; }
        String h = host == null ? "" : host.trim();
        if (h.indexOf(':') > 0 && h.indexOf(':') == h.lastIndexOf(':')) h = h.substring(0, h.indexOf(':'));
        long n = HomeBypassRoutes.parse(h);
        if (n < 0) return "The VPN is on in Full app mode: type the computer's IP address (like 192.168.1.20) so it can be reached past the VPN.";
        if (!HomeBypassRoutes.isHomeAddress(n)) return "The VPN is on in Full app mode, and only home network addresses (like 192.168.x.x) are reached past it.";
        String why = skipped;
        if (why != null) return why;
        return null;
    }

    /** The VPN is set to Full app mode (reads settings: call off the main thread). */
    public static boolean fullAppOn(Context context) {
        try { return new AndroidVpnSettings(context.getApplicationContext()).read().getMode() == ProtectionMode.APP_WIDE; }
        catch (Exception unavailable) { return false; }
    }

    /**
     * After [set]: waits (up to [timeoutMs]) until a Full app VPN is connected again using the new
     * addresses. True at once when Full app mode isn't on. Blocks: call off the main thread.
     */
    public static boolean awaitConnected(Context context, long timeoutMs) {
        if (!fullAppOn(context)) return true;
        long until = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < until) {
            if (VpnConnectionService.snapshot().phase == VpnConnectionService.Phase.CONNECTED && key(context).equals(appliedKey)) return true;
            try { Thread.sleep(250); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        }
        return false;
    }

    /**
     * The IPv4 routes for a Full app tunnel with interface addresses [tunnel] (and any routes the
     * profile names besides the default one) and DNS servers [dns]: everything, minus the home
     * addresses that can safely skip it.
     */
    static List<HomeBypassRoutes.Cidr> ipv4Routes(Context context, Collection<String> tunnel, Collection<String> dns) {
        appliedKey = key(context);
        List<Long> wanted = wanted(context);
        if (wanted.isEmpty()) { skipped = null; return HomeBypassRoutes.allExcept(new ArrayList<>()); }
        List<HomeBypassRoutes.Cidr> own = new ArrayList<>();
        if (tunnel != null) for (String t : tunnel) { HomeBypassRoutes.Cidr c = HomeBypassRoutes.parseCidr(t); if (c != null) own.add(c); }
        List<Long> dnsAddresses = new ArrayList<>();
        if (dns != null) for (String d : dns) { long n = HomeBypassRoutes.parse(d); if (n >= 0) dnsAddresses.add(n); }
        List<Long> usable = HomeBypassRoutes.usable(wanted, own, dnsAddresses);
        if (usable.size() < wanted.size()) {
            skipped = "The VPN uses the same home network addresses as the network folder, so the folder can't be reached "
                + "while the VPN is on in Full app mode. Use \"HTTP + HTTPS streams\" mode instead.";
        } else skipped = null;
        VpnPlaybackProtection.report(usable.isEmpty() ? "home_bypass=none" + (skipped != null ? "; clashes_with_vpn" : "")
            : "home_bypass=" + usable.size() + " address" + (usable.size() == 1 ? "" : "es"));
        return HomeBypassRoutes.allExcept(usable);
    }
}
