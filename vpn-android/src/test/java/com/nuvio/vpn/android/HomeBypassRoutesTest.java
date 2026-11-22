package com.nuvio.vpn.android;

import static org.junit.Assert.*;

import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.Test;

/** Full app mode: only the network folder's address skips the tunnel; everything else stays in it. */
public class HomeBypassRoutesTest {
    private static long ip(String s) { return HomeBypassRoutes.parse(s); }

    private static int hits(List<HomeBypassRoutes.Cidr> routes, long address) {
        int n = 0;
        for (HomeBypassRoutes.Cidr c : routes) if (c.contains(address)) n++;
        return n;
    }

    @Test public void noFolderKeepsTheDefaultRoute() {
        List<HomeBypassRoutes.Cidr> r = HomeBypassRoutes.allExcept(Collections.emptyList());
        assertEquals(1, r.size());
        assertEquals("0.0.0.0/0", r.get(0).toString());
    }

    @Test public void everyOtherAddressStillGoesIntoTheTunnelOnce() {
        long nas = ip("192.168.1.20");
        List<HomeBypassRoutes.Cidr> r = HomeBypassRoutes.allExcept(Collections.singletonList(nas));
        assertEquals(32, r.size());
        assertEquals(0, hits(r, nas));
        long total = 0;
        for (HomeBypassRoutes.Cidr c : r) total += 1L << (32 - c.prefix);
        assertEquals((1L << 32) - 1, total);
        Random random = new Random(7);
        for (int i = 0; i < 20000; i++) {
            long x = random.nextLong() & 0xFFFFFFFFL;
            if (x != nas) assertEquals(1, hits(r, x));
        }
        assertEquals(1, hits(r, ip("192.168.1.19")));
        assertEquals(1, hits(r, ip("192.168.1.21")));
        assertEquals(1, hits(r, ip("10.64.0.1")));
    }

    @Test public void onlyHomeAddressesThatDontClashWithTheVpn() {
        List<HomeBypassRoutes.Cidr> tunnel = java.util.Arrays.asList(HomeBypassRoutes.parseCidr("10.2.0.2/32"), HomeBypassRoutes.parseCidr("10.8.0.0/24"));
        List<Long> dns = Collections.singletonList(ip("10.2.0.1"));
        assertEquals(Collections.singletonList(ip("192.168.1.20")), HomeBypassRoutes.usable(Collections.singletonList(ip("192.168.1.20")), tunnel, dns));
        assertTrue(HomeBypassRoutes.usable(Collections.singletonList(ip("10.8.0.50")), tunnel, dns).isEmpty());
        assertTrue(HomeBypassRoutes.usable(Collections.singletonList(ip("10.2.0.1")), tunnel, dns).isEmpty());
        assertTrue(HomeBypassRoutes.usable(Collections.singletonList(ip("8.8.8.8")), tunnel, dns).isEmpty());
        assertFalse(HomeBypassRoutes.isHomeAddress(ip("172.32.0.1")));
        assertTrue(HomeBypassRoutes.isHomeAddress(ip("172.31.0.1")));
        assertTrue(ip("nas.local") < 0);
    }
}
