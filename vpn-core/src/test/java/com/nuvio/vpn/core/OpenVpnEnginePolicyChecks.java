package com.nuvio.vpn.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import javax.crypto.spec.SecretKeySpec;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Synthetic compatibility-policy regressions. No provider credentials or real endpoints. */
public final class OpenVpnEnginePolicyChecks {
    private static final String BASE = "client\nremote vpn.example 1194\nauth-user-pass\n"
            + "<ca>\nsynthetic-certificate\n</ca>\n";
    private static int checks;

    private OpenVpnEnginePolicyChecks() {}

    public static int run() throws Exception {
        checks = 0;
        defaults();
        fragmentation();
        legacyKeysize();
        unsupported();
        archivesAndStorage();
        return checks;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("OpenVPN engine policy: " + run() + " checks passed.");
    }

    private static void defaults() throws Exception {
        engine(BASE, OpenVpnEnginePolicy.Engine.OPENVPN3);
        engine(BASE + "# fragment 1300\n; --fragment 1300\n", OpenVpnEnginePolicy.Engine.OPENVPN3);
        for (String block : new String[] {"ca", "cert", "key", "tls-auth", "tls-crypt", "tls-crypt-v2", "crl-verify"}) {
            engine(BASE + "<" + block + ">\nfragment 1300\n</" + block + ">\n", OpenVpnEnginePolicy.Engine.OPENVPN3);
        }
        // Service login is opaque even when a field looks like a directive or another block.
        engine(BASE + "<auth-user-pass>\nfragment 1300\n<fragment>\n</auth-user-pass>\n",
                OpenVpnEnginePolicy.Engine.OPENVPN3);
        engine(BASE + "proto tcp-client\n", OpenVpnEnginePolicy.Engine.OPENVPN3);
        engine(BASE + "unknown-option value\n", OpenVpnEnginePolicy.Engine.OPENVPN3);
        try (ProfileImport.Candidate candidate = read("[Interface]\n[Peer]\n", ProfileImport.Protocol.WIREGUARD)) {
            check(candidate.getOpenVpnEngine() == null);
        }
        char[] owned = BASE.toCharArray();
        char[] before = owned.clone();
        try { check(OpenVpnEnginePolicy.select(owned) == OpenVpnEnginePolicy.Engine.OPENVPN3); check(Arrays.equals(owned, before)); }
        finally { Arrays.fill(owned, '\0'); Arrays.fill(before, '\0'); }
    }

    private static void fragmentation() throws Exception {
        for (String size : new String[] {"68", "1300", "65535", "1300 mtu", "\"1300\"", "'1300'", "1300 # provider", "1300 ; provider"}) {
            engine(BASE + "fragment " + size + "\n", OpenVpnEnginePolicy.Engine.OPENVPN2);
        }
        engine(BASE.replace("client\n", "--tls-client\n--pull\n")
                + "--fragment 1300\nproto udp4\n", OpenVpnEnginePolicy.Engine.OPENVPN2);
        engine(BASE + "fragment 1300\nproto udp6\nremote fallback.example 443 udp\n", OpenVpnEnginePolicy.Engine.OPENVPN2);
        engine(BASE.replace("remote vpn.example 1194\n", "") + "fragment 1300\n"
                + "<connection>\nremote one.example 1194 udp\nproto udp4\n</connection>\n"
                + "<connection>\nremote two.example\nport 443\n</connection>\n", OpenVpnEnginePolicy.Engine.OPENVPN2);
        String common = "dev tun\nproto udp\nnobind\nresolv-retry infinite\npersist-key\npersist-tun\n"
                + "remote-random\nfast-io\ncipher AES-256-CBC\nauth SHA512\ncomp-lzo no\nfragment 1300\nmssfix 1450\n"
                + "sndbuf 0\nrcvbuf 0\nverb 3\nremote-cert-tls server\nkey-direction 1\n"
                + "verify-x509-name \"Provider server\" name\ntls-version-min 1.2\nsetenv CLIENT_CERT 0\n"
                + "setenv-safe UV_SERVICE synthetic\n<tls-auth>\nsynthetic-key\n</tls-auth>\n";
        engine(BASE + common, OpenVpnEnginePolicy.Engine.OPENVPN2);
        for (String malformed : new String[] {"", "0", "67", "65536", "-1", "+1300", "1300x", "1300 mtu extra",
                "1300 unknown", "1300 1400", "99999999999999999999", "\"1300", "1300\\", "1300\0"}) {
            reject(BASE + "fragment " + malformed + "\n");
        }
        reject(BASE + "fragment 1300\nfragment 1300\n");
        reject(BASE + "fragment 1300\n--fragment 1400\n");
        for (String tcp : new String[] {"tcp", "tcp-client", "tcp4-client", "tcp6-client", "tcp-server", "unknown"}) {
            reject(BASE + "fragment 1300\nproto " + tcp + "\n");
            reject(BASE + "fragment 1300\nremote other.example 443 " + tcp + "\n");
            reject(BASE + "fragment 1300\n<connection>\nremote other.example\nproto " + tcp + "\n</connection>\n");
        }
    }

    private static void legacyKeysize() throws Exception {
        // Representative ExpressVPN documented options, with synthetic trust material only.
        String legacy = BASE.replace("client\n", "tls-client\npull\n")
                + "dev tun\nproto udp\nfragment 1300\nmssfix 1200\nauth SHA512\n"
                + "sndbuf 524288\nrcvbuf 524288\n";
        for (String bits : new String[] {"128", "192", "256"}) {
            for (String mode : new String[] {"CBC", "GCM"}) {
                engine(legacy + "keysize " + bits + "\ncipher AES-" + bits + "-" + mode + "\n",
                        OpenVpnEnginePolicy.Engine.OPENVPN2);
            }
        }
        for (String size : new String[] {"256", "\"256\"", "'256'", "256 # provider", "256 ; provider"})
            engine(legacy + "cipher AES-256-CBC\n--keysize " + size + "\n", OpenVpnEnginePolicy.Engine.OPENVPN2);
        engine(legacy + "keysize 256\ndata-ciphers AES-256-GCM:AES-256-CBC\n"
                + "data-ciphers-fallback AES-256-CBC\n", OpenVpnEnginePolicy.Engine.OPENVPN2);
        engine(legacy + "ncp-ciphers AES-256-GCM\nkeysize 256\n", OpenVpnEnginePolicy.Engine.OPENVPN2);
        String provider = legacy + "cipher AES-256-CBC\nkeysize 256\nroute-method exe\nroute-delay 2\n"
                + "ns-cert-type server\nverify-x509-name Synthetic name\n";
        engine(provider, OpenVpnEnginePolicy.Engine.OPENVPN2);
        for (String method : new String[] {"exe", "ipapi", "adaptive"})
            engine(legacy + "route-method " + method + "\n", OpenVpnEnginePolicy.Engine.OPENVPN2);
        for (String delay : new String[] {"", "0", "2", "2 30", "3600 3600"})
            engine(legacy + "route-delay " + delay + "\n", OpenVpnEnginePolicy.Engine.OPENVPN2);

        String opaque = "<auth-user-pass>\nkeysize 128\nns-cert-type client\n</auth-user-pass>\n";
        char[] original = (provider + opaque).toCharArray();
        char[] before = original.clone();
        char[] normalized = OpenVpnEnginePolicy.normalizeLegacyKeysize(original);
        try {
            String text = new String(normalized);
            check(normalized != original && Arrays.equals(original, before));
            check(!text.contains("keysize 256") && text.contains("cipher AES-256-CBC\n") && text.contains(opaque));
            check(text.contains("ns-cert-type server\n") && text.contains("verify-x509-name Synthetic name\n")
                    && text.contains("route-method exe\nroute-delay 2\n"));
            check(OpenVpnEnginePolicy.select(original) == OpenVpnEnginePolicy.Engine.OPENVPN2);
            char[] again = OpenVpnEnginePolicy.normalizeLegacyKeysize(normalized);
            try { check(Arrays.equals(normalized, again)); }
            finally { Arrays.fill(again, '\0'); }
        } finally { Arrays.fill(original, '\0'); Arrays.fill(before, '\0'); Arrays.fill(normalized, '\0'); }
        char[] core3 = (BASE + "cipher AES-256-CBC\nkeysize 256\n").toCharArray();
        normalized = OpenVpnEnginePolicy.normalizeLegacyKeysize(core3);
        try {
            check(normalized != core3 && Arrays.equals(normalized, core3));
            engine(new String(core3), OpenVpnEnginePolicy.Engine.OPENVPN3);
        } finally { Arrays.fill(core3, '\0'); Arrays.fill(normalized, '\0'); }

        for (String size : new String[] {"", "0", "64", "0256", "512", "-256", "+256", "256 extra", "\"256", "256\\"})
            reject(legacy + "cipher AES-256-CBC\nkeysize " + size + "\n");
        for (String options : new String[] {
                "keysize 256\n", "keysize 256\ndata-ciphers-fallback AES-256-CBC\n",
                "keysize 128\ncipher AES-256-CBC\n", "keysize 256\ncipher CHACHA20-POLY1305\n",
                "keysize 256\ncipher BF-CBC\n", "keysize 256\ncipher AES-256-CBC extra\n",
                "keysize 256\nkeysize 256\ncipher AES-256-CBC\n",
                "keysize 256\ncipher AES-256-CBC\ncipher AES-256-GCM\n",
                "keysize 256\ncipher AES-256-CBC\ndata-ciphers AES-256-GCM:AES-128-GCM\n",
                "keysize 256\ncipher AES-256-CBC\ndata-ciphers-fallback AES-128-CBC\n",
                "keysize 256\ndata-ciphers AES-256-GCM\nncp-ciphers AES-256-GCM\n",
                "\"keysize\" 256\ncipher AES-256-CBC\n", "'keysize' 256\ncipher AES-256-CBC\n",
                "keysize 256\n\"cipher\" AES-256-CBC\n",
                "keysize 256\ncipher AES-256-CBC\n<connection>\nremote other.example 1194\n</connection>\n",
                "keysize 256\ncipher AES-256-CBC\nunknown-option private-value-sentinel\n",
                "keysize 256\ncipher AES-256-CBC\nns-cert-type client\n",
                "keysize 256\ncipher AES-256-CBC\nns-cert-type server\nns-cert-type server\n",
                "keysize 256\ncipher AES-256-CBC\nns-cert-type server extra\n",
                "keysize 256\ncipher AES-256-CBC\nroute-method unknown\n",
                "keysize 256\ncipher AES-256-CBC\nroute-method exe extra\n",
                "keysize 256\ncipher AES-256-CBC\nroute-delay -1\n",
                "keysize 256\ncipher AES-256-CBC\nroute-delay 3601\n",
                "keysize 256\ncipher AES-256-CBC\nroute-delay 2 30 40\n",
                "keysize 256\ncipher AES-256-CBC\ntls-version-min 1.0\n"}) {
            reject(legacy + options);
        }
        for (String bad : new String[] {"ns-cert-type", "ns-cert-type client", "ns-cert-type SERVER",
                "ns-cert-type server server", "ns-cert-type server\n--ns-cert-type server",
                "\"ns-cert-type\" server", "route-delay x", "route-delay 0 -1", "route-delay 0 3601",
                "route-method", "route-method EXE"}) reject(legacy + bad + "\n");
    }

    private static void unsupported() throws Exception {
        String legacy = BASE + "fragment 1300\n";
        for (String option : new String[] {"unknown-option value", "ignore-unknown-option remote-cert-tls",
                "setenv opt unknown-option value", "setenv FORWARD_COMPATIBLE 1", "setenv REMOTE_RANDOM_HOSTNAME",
                "up private-script", "down private-script", "dns-updown private-script", "config private-file", "include private-file",
                "management private-path unix", "management-client", "management-external-key", "plugin private-plugin",
                "auth-user-pass private-file", "ca private-file", "crl-verify private-file", "extra-certs private-file",
                "tls-crypt-v2-verify private-script", "auth-user-pass-verify private-script", "writepid private-file",
                "log private-file", "log-append private-file", "status private-file", "askpass", "askpass private-file",
                "auth-retry interact", "static-challenge value 1", "pkcs11-id value", "providers legacy",
                "dev tap", "dev-type tap", "dev-node private-path", "dev tun0", "proto udp extra",
                "http-proxy proxy.example 8080", "socks-proxy proxy.example 1080", "http-proxy-user-pass private-file",
                "dhcp-option DOMAIN private-domain", "dhcp-option DOMAIN-SEARCH private-domain", "dns server 0 dnssec yes",
                "dhcp-option DNS hostname.example", "route private-route", "pull-filter ignore remote-cert-tls",
                "route-noexec", "route-nopull", "redirect-gateway bypass-dhcp", "auth none", "cipher none", "cipher BF-CBC",
                "data-ciphers AES-256-GCM:?BF-CBC", "tls-version-min 1.0", "tls-version-min 1.3 or-highest",
                "tls-cert-profile insecure", "tls-cipher DEFAULT:@SECLEVEL=0", "tls-cipher NULL-SHA",
                "tls-cipher ALL", "tls-cipher DEFAULT", "tls-ciphersuites DEFAULT",
                "allow-compression yes", "comp-lzo mystery", "compress mystery", "remote-cert-tls client",
                "\"persist-tun\"", "'persist-tun'", "persist-\\tun", "\"verb\" 3",
                "fast-io untrusted", "remote-random-hostname", "chroot private-path", "daemon", "user nobody", "group nobody"}) {
            reject(legacy + option + "\n");
        }
        for (String tag : new String[] {"pkcs12", "dh"}) reject(legacy + "<" + tag + ">\nopaque\n</" + tag + ">\n");
        reject(legacy + "<connection>\nremote other.example\nfragment 1300\n</connection>\n");
        reject("fragment 1300\nremote vpn.example\n");
        reject("client\nfragment 1300\n");
        reject(legacy + "<ca>\nunclosed\n");
        // Errors disclose option names only, never a filename, hostname, account or option value.
        IOException failure = reject(legacy + "setenv PRIVATE_SENTINEL private-value-sentinel\n");
        check(!failure.getMessage().contains("PRIVATE_SENTINEL"));
        check(!failure.getMessage().contains("private-value-sentinel"));
        failure = reject(legacy + "remote private-host-sentinel 443 tcp-client\n");
        check(!failure.getMessage().contains("private-host-sentinel"));
        failure = reject(legacy + "secretmisplacedvalue private-value-sentinel\n");
        check(!failure.getMessage().contains("secretmisplacedvalue"));
        check(!failure.getMessage().contains("private-value-sentinel"));
        check(OpenVpnEnginePolicy.arguments("allow-compression \"no\" # comment").equals(Arrays.asList("allow-compression", "no")));
        check(OpenVpnEnginePolicy.arguments("verify-x509-name \"Provider name\" name").equals(Arrays.asList("verify-x509-name", "Provider name", "name")));
        check(OpenVpnEnginePolicy.arguments("setenv-safe UV_NAME 'literal\\value'").equals(Arrays.asList("setenv-safe", "UV_NAME", "literal\\value")));
        check(OpenVpnEnginePolicy.arguments("auth SHA\"512\"").equals(Arrays.asList("auth", "SHA\"512\"")));
        check(OpenVpnEnginePolicy.arguments("\"auth\"SHA512").equals(Arrays.asList("auth", "SHA512")));
        try { OpenVpnEnginePolicy.arguments("verb 3").add("private"); throw new AssertionError("Mutable argument list."); }
        catch (UnsupportedOperationException expected) { checks++; }
    }

    private static void archivesAndStorage() throws Exception {
        String legacy = BASE + "fragment 1300\n";
        ByteArrayOutputStream zipBytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(zipBytes, StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry("provider.ovpn"));
            zip.write("client\nremote vpn.example 1194\nfragment 1300\nca ca.crt\n".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("ca.crt"));
            zip.write("synthetic-certificate".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        byte[] archive = zipBytes.toByteArray();
        try (ProfileImport.Candidate imported = OpenVpnDocument.read(new ByteArrayInputStream(archive), ProfileImport.Protocol.OPENVPN)) {
            check(imported.getOpenVpnEngine() == OpenVpnEnginePolicy.Engine.OPENVPN2);
            char[] flattened = imported.copyContents();
            try { check(OpenVpnEnginePolicy.select(flattened) == OpenVpnEnginePolicy.Engine.OPENVPN2); }
            finally { Arrays.fill(flattened, '\0'); }
        } finally { Arrays.fill(archive, (byte) 0); }
        String id = "bb6c6284-1068-444a-a239-70090a64771b";
        byte[] sealed;
        try (ProfileImport.Candidate imported = read(legacy, ProfileImport.Protocol.OPENVPN)) {
            check(imported.getProtocol() == ProfileImport.Protocol.OPENVPN);
            sealed = ProfileEnvelope.seal(id, "Compatibility", imported, new SecretKeySpec(new byte[32], "AES"));
        }
        try (ProfileEnvelope.Opened opened = ProfileEnvelope.open(id, sealed, new SecretKeySpec(new byte[32], "AES"))) {
            check(opened.getProtocol() == ProfileImport.Protocol.OPENVPN);
            char[] contents = opened.copyContents();
            try { check(OpenVpnEnginePolicy.select(contents) == OpenVpnEnginePolicy.Engine.OPENVPN2); }
            finally { Arrays.fill(contents, '\0'); }
        } finally { Arrays.fill(sealed, (byte) 0); }
        // The strict text backup path derives the same engine again; storage needs no new schema/protocol.
        engine(legacy, OpenVpnEnginePolicy.Engine.OPENVPN2);
    }

    private static void engine(String profile, OpenVpnEnginePolicy.Engine expected) throws Exception {
        try (ProfileImport.Candidate candidate = read(profile, ProfileImport.Protocol.OPENVPN)) {
            check(candidate.getOpenVpnEngine() == expected);
            char[] contents = candidate.copyContents();
            try { check(OpenVpnEnginePolicy.select(contents) == expected); }
            finally { Arrays.fill(contents, '\0'); }
        }
    }
    private static IOException reject(String profile) throws Exception {
        try (ProfileImport.Candidate candidate = read(profile, ProfileImport.Protocol.OPENVPN)) {
            throw new AssertionError("Unsupported compatibility profile accepted.");
        } catch (IOException expected) {
            checks++;
            char[] contents = profile.toCharArray();
            try {
                OpenVpnEnginePolicy.select(contents);
                throw new AssertionError("Stored profile bypassed compatibility policy.");
            } catch (IOException policyRejected) { checks++; }
            finally { Arrays.fill(contents, '\0'); }
            return expected;
        }
    }
    private static ProfileImport.Candidate read(String text, ProfileImport.Protocol protocol) throws IOException {
        return ProfileImport.read(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), protocol);
    }
    private static void check(boolean value) { if (!value) throw new AssertionError("OpenVPN engine policy regression."); checks++; }
}
