package com.nuvio.vpn.android;

import com.nuvio.vpn.core.ProfileImport;
import com.nuvio.vpn.core.ProtectionMode;
import com.wireguard.config.Config;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

/** Shared real-library checks for simulated and device Android runtimes; opens no VPN/network. */
public final class WireGuardProfileChecks {
    private static final String SAMPLE = "[Interface]\nPrivateKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=\n"
            + "Address = 10.0.0.2/32\nDNS = 10.0.0.1\nMTU = 1280\nListenPort = 51820\n"
            + "[Peer]\nPublicKey = AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=\n"
            + "AllowedIPs = 0.0.0.0/0\nEndpoint = 192.0.2.1:51820\n";
    private static int checks;

    public static void main(String[] args) throws Exception {
        Config original = parse(SAMPLE);
        Config normalized = WireGuardProfiles.forAppWide(original, "com.example.nuvio");
        check(normalized.getInterface().getIncludedApplications().equals(Collections.singleton("com.example.nuvio")));
        check(normalized.getInterface().getExcludedApplications().isEmpty());
        check(original.getInterface().getIncludedApplications().isEmpty());
        check(normalized.getInterface().getMtu().equals(original.getInterface().getMtu()));
        check(normalized.getInterface().getListenPort().equals(original.getInterface().getListenPort()));
        check(normalized.getInterface().getAddresses().equals(original.getInterface().getAddresses()));
        check(normalized.getInterface().getDnsServers().equals(original.getInterface().getDnsServers()));
        check(normalized.getPeers().equals(original.getPeers()));
        Config excluded = parse(SAMPLE.replace("MTU = 1280", "ExcludedApplications = com.example.nuvio\nMTU = 1280"));
        check(WireGuardProfiles.forAppWide(excluded, "com.example.nuvio").getInterface().getExcludedApplications().isEmpty());
        Config included = parse(SAMPLE.replace("MTU = 1280", "IncludedApplications = com.example.other\nMTU = 1280"));
        check(WireGuardProfiles.forAppWide(included, "com.example.nuvio").getInterface().getIncludedApplications()
                .equals(Collections.singleton("com.example.nuvio")));
        rejects(SAMPLE.replace("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", "private-key-must-not-be-logged"));
        rejects(SAMPLE.replace("Address = 10.0.0.2/32\n", ""));
        rejects(SAMPLE.replace("DNS = 10.0.0.1\n", ""));
        rejects(SAMPLE.replace("Endpoint = 192.0.2.1:51820\n", ""));
        rejects(SAMPLE + "[Peer]\nPublicKey = AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI=\n"
                + "AllowedIPs = 192.0.2.0/24\nEndpoint = 192.0.2.2:51820\n");
        rejects(SAMPLE.replace("0.0.0.0/0", "10.0.0.0/8"));
        rejects(SAMPLE.replace("Address = 10.0.0.2/32", "Address = 10.0.0.2/32, fd00::2/128"));
        String dualRoutes = SAMPLE.replace("AllowedIPs = 0.0.0.0/0", "AllowedIPs = 0.0.0.0/0, ::/0");
        // A route for an unconfigured family blocks that family rather than requiring a source address.
        check(WireGuardProfiles.forAppWide(parse(dualRoutes), "com.example.nuvio").getPeers().size() == 1);
        rejects(dualRoutes.replace("DNS = 10.0.0.1", "DNS = fd00::1"));
        rejects(dualRoutes.replace("DNS = 10.0.0.1", "DNS = fd00::1, 10.0.0.1"));
        rejects(dualRoutes.replace("Address = 10.0.0.2/32", "Address = fd00::2/128"));
        // Revalidate configurations obtained outside the import path, including stored selections.
        rejectsBeforeUse(dualRoutes.replace("DNS = 10.0.0.1", "DNS = fd00::1"));
        rejectsBeforeUse(SAMPLE.replace("0.0.0.0/0", "10.0.0.0/8"));
        Config dual = parse(SAMPLE.replace("Address = 10.0.0.2/32", "Address = 10.0.0.2/32, fd00::2/128")
                .replace("DNS = 10.0.0.1", "DNS = 10.0.0.1, fd00::1")
                .replace("AllowedIPs = 0.0.0.0/0", "AllowedIPs = 0.0.0.0/0, ::/0"));
        check(WireGuardProfiles.forAppWide(dual, "com.example.nuvio").getPeers().size() == 1);
        Config multipleDns = parse(SAMPLE.replace("DNS = 10.0.0.1", "DNS = 10.0.0.1, 10.0.0.3"));
        check(multipleDns.getInterface().getDnsServers().size() == 2);
        Config noMtu = parse(SAMPLE.replace("MTU = 1280\n", ""));
        check(!WireGuardProfiles.forAppWide(noMtu, "com.example.nuvio").getInterface().getMtu().isPresent());
        Config psk = parse(SAMPLE.replace("Endpoint =", "PresharedKey = AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI=\n"
                + "PersistentKeepalive = 25\nEndpoint ="));
        Config normalizedPsk = WireGuardProfiles.forAppWide(psk, "com.example.nuvio");
        check(normalizedPsk.getPeers().get(0).getPreSharedKey().equals(psk.getPeers().get(0).getPreSharedKey()));
        check(normalizedPsk.getPeers().get(0).getPersistentKeepalive().equals(psk.getPeers().get(0).getPersistentKeepalive()));
        Config search = parse(SAMPLE.replace("DNS = 10.0.0.1", "DNS = 10.0.0.1, vpn.example"));
        WireGuardProfiles.validateForScope(search, ProtectionMode.APP_WIDE);
        check(WireGuardProfiles.forAppWide(search, "com.example.nuvio").getInterface().getDnsSearchDomains()
                .equals(search.getInterface().getDnsSearchDomains()));
        for (ProtectionMode mode : new ProtectionMode[] {ProtectionMode.HTTP_ONLY, ProtectionMode.HTTP_HTTPS}) {
            try {
                WireGuardProfiles.validateForScope(search, mode);
                throw new AssertionError("Streams-only DNS search domains accepted.");
            } catch (IOException expected) { check(expected.getMessage().contains("Full app")); }
        }
        try { WireGuardProfiles.forAppWide(original, ""); throw new AssertionError("Invalid package accepted."); }
        catch (IOException expected) { checks++; }
        System.out.println("WireGuard profiles: " + checks + " checks passed.");
    }

    private static Config parse(String text) throws Exception {
        try (ProfileImport.Candidate candidate = ProfileImport.read(
                new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), ProfileImport.Protocol.WIREGUARD)) {
            return WireGuardProfiles.parse(candidate);
        }
    }

    private static void rejects(String text) throws Exception {
        try {
            parse(text);
            throw new AssertionError("Invalid WireGuard configuration accepted.");
        } catch (IOException expected) {
            check(!expected.getMessage().contains("private-key-must-not-be-logged"));
        }
    }

    private static void rejectsBeforeUse(String text) throws Exception {
        try (BufferedReader reader = new BufferedReader(new StringReader(text))) {
            try {
                WireGuardProfiles.forAppWide(Config.parse(reader), "com.example.nuvio");
                throw new AssertionError("Invalid stored WireGuard configuration accepted.");
            } catch (IOException expected) { checks++; }
        }
    }

    private static void check(boolean value) {
        checks++;
        if (!value) throw new AssertionError("WireGuard profile check failed: " + checks);
    }
}
