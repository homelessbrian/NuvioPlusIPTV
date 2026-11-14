package com.nuvio.vpn.android;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Synthetic management/configuration checks; these do not claim a VPN connection. */
public final class OpenVpn2ManagementChecks {
    private static int checks;

    public static void main(String[] args) throws Exception {
        Sink sink = new Sink();
        OpenVpn2Management session = new OpenVpn2Management(sink);
        check(!session.statistics().authenticated);
        session.line(">HOLD:Waiting for hold release:0");
        check(sink.commands.get(0).equals("state on\nbytecount 1\nhold release\n"));
        tunnel(session);
        check(sink.protectedCount == 1 && sink.opened == 1);
        session.line(">STATE:1,CONNECTED,SUCCESS,untrusted-details");
        session.line(">BYTECOUNT:12345,54321");
        TunnelTraffic first = session.statistics();
        check(first.authenticated && first.session > 0 && first.received == 12345 && first.sent == 54321);
        session.line(">PASSWORD:Auth-Token:private-token-must-not-be-logged");
        check(session.statistics().authenticated && session.statistics().session == first.session);
        session.line(">LOG:1,I,untrusted-private-details");
        check(session.statistics().authenticated);
        session.line(">STATE:2,RECONNECTING,tls-error,untrusted-details");
        check(!session.statistics().authenticated && session.statistics().session == 0);
        tunnel(session);
        session.line(">STATE:4,CONNECTED,SUCCESS,untrusted-details");
        check(session.statistics().session > first.session);
        session.line(">PASSWORD:Need 'Auth' username/password");
        check(sink.credentials == 1 && !session.statistics().authenticated);
        session.line(">STATE:5,CONNECTED,SUCCESS,untrusted-details");
        session.end();
        check(session.ended() && !session.statistics().authenticated);
        rejects(session, ">STATE:6,CONNECTED,SUCCESS,untrusted-details");

        for (String request : new String[]{
                ">NEED-OK:Need 'DNSDOMAIN' confirmation MSG:private.example",
                ">NEED-OK:Need 'HTTPPROXY' confirmation MSG:private.example 8080",
                ">NEED-OK:Need 'NEW_SETTING' confirmation MSG:private-value",
                ">NEED-OK:Need 'OPENTUN' confirmation MSG:tap",
                ">NEED-OK:Need 'DNSSERVER' confirmation MSG:private.example",
                ">NEED-OK:Need 'IFCONFIG' confirmation MSG:192.0.2.2 255.0.255.0 1500 subnet",
                ">NEED-OK:Need 'IFCONFIG' confirmation MSG:192.0.2.2 255.255.255.0 99999 subnet",
                ">NEED-OK:Need 'IFCONFIG6' confirmation MSG:2001:db8::2/129 1500",
                ">PASSWORD:Need 'Private Key' password",
                ">PASSWORD:Need 'Auth' username/password SC:private-challenge",
                ">INFOMSG:WEB_AUTH:private-value", ">FATAL:private-value", "ERROR:private-value",
                ">STATE:1,CONNECTED,SUCCESS,private-value", ">BYTECOUNT:-1,5", ">BYTECOUNT:1,2,3"}) {
            rejects(new OpenVpn2Management(new Sink()), request);
        }
        OpenVpn2Management rejected = new OpenVpn2Management(new Sink());
        rejects(rejected, ">PASSWORD:Verification Failed: 'Auth' ['private-details']");
        check(rejected.authRejected() && !rejected.statistics().authenticated);
        Sink failedProtect = new Sink(); failedProtect.rejectProtect = true;
        OpenVpn2Management unprotected = new OpenVpn2Management(failedProtect);
        rejects(unprotected, ">NEED-OK:Need 'PROTECTFD' confirmation MSG:0");
        rejects(unprotected, ">STATE:1,CONNECTED,SUCCESS,private-value");
        OpenVpn2Management unsupportedAfterConnect = new OpenVpn2Management(new Sink());
        tunnel(unsupportedAfterConnect);
        unsupportedAfterConnect.line(">STATE:1,CONNECTED,SUCCESS,untrusted-details");
        rejects(unsupportedAfterConnect, ">NEED-OK:Need 'DNSDOMAIN' confirmation MSG:private.example");
        check(!unsupportedAfterConnect.statistics().authenticated && unsupportedAfterConnect.ended());
        rejects(unsupportedAfterConnect, ">STATE:2,CONNECTED,SUCCESS,untrusted-details");

        String material = "<ca>\nfragment 2\n</ca>\n";
        String profile = "client\n<connection>\nremote vpn.example 1194 udp\n</connection>\n"
                + "fragment 1300\npersist-tun\nverb 4\nallow-compression no\n"
                + material + "<auth-user-pass>\nservice-user\nservice-password\n</auth-user-pass>\n";
        char[] original = profile.toCharArray();
        char[] preflight = OpenVpn2Config.core3Preflight(original);
        String checked = new String(preflight);
        check(checked.contains(material) && !checked.contains("fragment 1300"));
        check(checked.contains("persist-tun") && checked.contains("service-password"));
        try (OpenVpn2Config input = OpenVpn2Config.prepare(original)) {
            String text = new String(input.bytes, StandardCharsets.UTF_8);
            check(text.contains("fragment 1300") && text.contains(material));
            check(!text.contains("persist-tun") && !text.contains("verb 4"));
            check(!text.contains("service-user") && !text.contains("service-password"));
            check(text.contains("auth-user-pass\n") && text.contains("remote-cert-tls server\n"));
            check(text.contains("allow-compression no\n") && !text.contains("allow-compression asym"));
            check(!input.needsCredentials && Arrays.equals(input.username, "service-user".toCharArray()));
            check(Arrays.equals(input.password, "service-password".toCharArray()));
            input.close();
            check(allZero(input.bytes) && allZero(input.username) && allZero(input.password));
        }
        try (OpenVpn2Config input = OpenVpn2Config.prepare("client\nauth-user-pass\nfragment 1300\n".toCharArray())) {
            check(input.needsCredentials && input.username == null && input.password == null);
        }
        for (String setting : new String[]{"allow-compression \"no\"", "allow-compression 'no'"}) {
            try (OpenVpn2Config input = OpenVpn2Config.prepare(("fragment 1300\n" + setting + "\n").toCharArray())) {
                String text = new String(input.bytes, StandardCharsets.UTF_8);
                check(text.contains("allow-compression no\n") && !text.contains("allow-compression asym"));
            }
        }
        try (OpenVpn2Config input = OpenVpn2Config.prepare("fragment 1300\ncipher AES-256-CBC\n".toCharArray())) {
            String text = new String(input.bytes, StandardCharsets.UTF_8);
            check(text.contains("data-ciphers AES-256-CBC\n") && text.contains("data-ciphers-fallback AES-256-CBC\n"));
        }
        try (OpenVpn2Config input = OpenVpn2Config.prepare("fragment 1300\ncipher AES-256-CBC\ndata-ciphers AES-256-GCM\n".toCharArray())) {
            String text = new String(input.bytes, StandardCharsets.UTF_8);
            check(text.contains("data-ciphers AES-256-GCM\n") && !text.contains("data-ciphers AES-256-CBC\n")
                    && !text.contains("data-ciphers-fallback AES-256-CBC\n"));
        }
        byte[] command = OpenVpn2Config.credentialCommand("password", "quote\"slash\\ space".toCharArray());
        check(new String(command, StandardCharsets.UTF_8).equals("password \"Auth\" \"quote\\\"slash\\\\ space\"\n"));
        Arrays.fill(command, (byte) 0);
        try { OpenVpn2Config.credentialCommand("password", "injected\ncommand".toCharArray()); throw new AssertionError(); }
        catch (IOException expected) { checks++; }
        try { OpenVpn2Config.prepare("<auth-user-pass>\none\ntwo\nthree\n</auth-user-pass>".toCharArray()); throw new AssertionError(); }
        catch (IOException expected) { checks++; }
        Arrays.fill(original, '\0'); Arrays.fill(preflight, '\0');
        legacyKeysize();
        System.out.println("OpenVPN2 management: " + checks + " checks passed.");
    }

    private static void legacyKeysize() throws Exception {
        String material = "<ca>\nkeysize 128\nns-cert-type client\nsynthetic-certificate\n</ca>\n";
        String auth = "<auth-user-pass>\nkeysize 128\nsynthetic-password\n</auth-user-pass>\n";
        String constraints = "cipher AES-256-CBC\ndata-ciphers AES-256-GCM:AES-256-CBC\n"
                + "data-ciphers-fallback AES-256-CBC\n";
        String platform = "route-method exe\nroute-delay 2\n";
        String profile = "tls-client\npull\ndev tun\nproto udp\nremote vpn.example 1195\n"
                + "fragment 1300\nkeysize 256\nauth SHA512\nverify-x509-name Synthetic name\n"
                + "ns-cert-type server\nremote-cert-tls server\n" + platform + constraints + material + auth;
        char[] original = profile.toCharArray(), before = original.clone();
        char[] preflight = OpenVpn2Config.core3Preflight(original);
        try {
            String checked = new String(preflight);
            check(!checked.contains("keysize 256") && !checked.contains("fragment 1300"));
            check(checked.contains(constraints) && checked.contains(material) && checked.contains(auth));
            check(!checked.contains("ns-cert-type server\n") && checked.contains("remote-cert-tls server\n")
                    && checked.contains("verify-x509-name Synthetic name\n") && checked.contains(platform));
            try (OpenVpn2Config input = OpenVpn2Config.prepare(original)) {
                String text = new String(input.bytes, StandardCharsets.UTF_8);
                check(!text.contains("keysize 256") && text.contains("fragment 1300\n"));
                check(text.contains(constraints) && text.contains(material));
                check(text.contains("verify-x509-name Synthetic name\n") && text.contains("remote-cert-tls server\n"));
                check(text.contains("ns-cert-type server\n") && text.contains(platform));
                check(!text.contains("synthetic-password") && text.contains("auth-user-pass\n"));
                check(Arrays.equals(input.username, "keysize 128".toCharArray()) && !input.needsCredentials);
                check(Arrays.equals(original, before));
            }
        } finally { Arrays.fill(original, '\0'); Arrays.fill(before, '\0'); Arrays.fill(preflight, '\0'); }
        for (String invalid : new String[] {
                "cipher AES-128-CBC\nkeysize 256\n", "cipher AES-256-CBC\nkeysize 256\nkeysize 256\n",
                "cipher AES-256-CBC\n\"keysize\" 256\n", "keysize 256\n",
                "cipher AES-256-CBC\nkeysize 256 extra\n"}) {
            char[] source = ("fragment 1300\n" + invalid).toCharArray();
            try {
                try { OpenVpn2Config.core3Preflight(source); throw new AssertionError("Unsafe preflight normalization."); }
                catch (IOException expected) { checks++; }
                try { OpenVpn2Config.prepare(source); throw new AssertionError("Unsafe native normalization."); }
                catch (IOException expected) { checks++; }
            } finally { Arrays.fill(source, '\0'); }
        }
        for (String purpose : new String[] {"client", "", "server extra", "server\nns-cert-type server"}) {
            char[] source = ("client\nremote vpn.example 1194\nfragment 1300\nns-cert-type " + purpose + "\n").toCharArray();
            try {
                try { OpenVpn2Config.core3Preflight(source); throw new AssertionError("Unsafe certificate preflight."); }
                catch (IOException expected) { checks++; }
                try { OpenVpn2Config.prepare(source); throw new AssertionError("Unsafe native certificate purpose."); }
                catch (IOException expected) { checks++; }
            } finally { Arrays.fill(source, '\0'); }
        }
        char[] core3 = "client\nremote vpn.example 1194\nns-cert-type server\nkeysize 256\ncipher AES-256-CBC\n".toCharArray();
        char[] unchanged = OpenVpn2Config.core3Preflight(core3);
        try { check(unchanged != core3 && Arrays.equals(unchanged, core3)); }
        finally { Arrays.fill(core3, '\0'); Arrays.fill(unchanged, '\0'); }
        // A sole fixed cipher is still promoted unchanged for OpenVPN 2.7 negotiation.
        try (OpenVpn2Config input = OpenVpn2Config.prepare("fragment 1300\nkeysize 256\ncipher AES-256-CBC\n".toCharArray())) {
            String text = new String(input.bytes, StandardCharsets.UTF_8);
            check(!text.contains("keysize") && text.contains("data-ciphers AES-256-CBC\n")
                    && text.contains("data-ciphers-fallback AES-256-CBC\n"));
        }
    }

    private static void tunnel(OpenVpn2Management session) throws Exception {
        session.line(">NEED-OK:Need 'PROTECTFD' confirmation MSG:0");
        session.line(">NEED-OK:Need 'IFCONFIG' confirmation MSG:192.0.2.2 255.255.255.0 1500 subnet");
        session.line(">NEED-OK:Need 'DNSSERVER' confirmation MSG:192.0.2.1");
        session.line(">NEED-OK:Need 'ROUTE' confirmation MSG:untrusted-route-ignored");
        session.line(">NEED-OK:Need 'PERSIST_TUN_ACTION' confirmation MSG:");
        session.line(">NEED-OK:Need 'OPENTUN' confirmation MSG:tun");
    }
    private static final class Sink implements OpenVpn2Management.Callbacks {
        final List<String> commands = new ArrayList<>();
        int protectedCount, opened, credentials;
        boolean rejectProtect;
        @Override public void command(String command) { commands.add(command); }
        @Override public void protect() throws IOException {
            if (rejectProtect) throw new IOException("Protection refused.");
            protectedCount++;
        }
        @Override public void openTun(String[] addresses, String[] dns, int mtu) {
            check(addresses.length == 1 && addresses[0].endsWith("/24") && dns.length == 1 && mtu == 1500);
            opened++;
        }
        @Override public void credentials() { credentials++; }
    }
    private static void rejects(OpenVpn2Management session, String request) throws Exception {
        try { session.line(request); throw new AssertionError("Unsupported management request accepted."); }
        catch (IOException expected) {
            check(!expected.getMessage().contains("private-value") && !expected.getMessage().contains("private.example"));
        }
    }
    private static boolean allZero(byte[] value) { for (byte item : value) if (item != 0) return false; return true; }
    private static boolean allZero(char[] value) { for (char item : value) if (item != 0) return false; return true; }
    private static void check(boolean value) { if (!value) throw new AssertionError("OpenVPN2 invariant failed."); checks++; }
}
