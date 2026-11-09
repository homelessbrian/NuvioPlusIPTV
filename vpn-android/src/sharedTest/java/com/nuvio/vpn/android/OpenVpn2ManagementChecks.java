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
        System.out.println("OpenVPN2 management: " + checks + " checks passed.");
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
