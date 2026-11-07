package com.nuvio.vpn.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.crypto.spec.SecretKeySpec;

/** Provider-shaped bundle regressions. All key contents are synthetic and never printed. */
public final class OpenVpnBundleChecks {
    private static int checks;
    private static final String BASE = "client\nremote vpn.example 1194\nauth-user-pass\n";
    private static final String CERT = "-----BEGIN CERTIFICATE-----\nsynthetic-certificate\n-----END CERTIFICATE-----\n";
    private static final String KEY = "-----BEGIN PRIVATE KEY-----\nsynthetic-private-key\n-----END PRIVATE KEY-----\n";

    private OpenVpnBundleChecks() {}

    public static int run() throws Exception {
        checks = 0;
        ordinaryDocuments();
        providerBundles();
        archiveFailures();
        referenceFailures();
        bounds();
        return checks;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("OpenVPN bundles: " + run() + " checks passed.");
    }

    private static void ordinaryDocuments() throws Exception {
        try (ProfileImport.Candidate candidate = read(BASE.getBytes(StandardCharsets.UTF_8))) {
            check(contents(candidate).equals(BASE));
        }
        String wg = "[Interface]\nPrivateKey=synthetic\n[Peer]\nPublicKey=synthetic\n";
        try (ProfileImport.Candidate candidate = OpenVpnDocument.read(
                new ByteArrayInputStream(wg.getBytes(StandardCharsets.UTF_8)), ProfileImport.Protocol.WIREGUARD)) {
            check(candidate.getProtocol() == ProfileImport.Protocol.WIREGUARD);
        }
        final boolean[] closed = {false};
        ByteArrayInputStream owned = new ByteArrayInputStream(BASE.getBytes(StandardCharsets.UTF_8)) {
            @Override public void close() { closed[0] = true; }
        };
        read(owned).close();
        check(!closed[0]);
        try {
            OpenVpnDocument.read(new ByteArrayInputStream(zip(files("openvpn.ovpn", BASE), false)), ProfileImport.Protocol.WIREGUARD);
            throw new AssertionError("WireGuard accepted ZIP document.");
        } catch (IOException expected) { checks++; }
    }

    private static void providerBundles() throws Exception {
        Map<String, String> cyberghost = files("openvpn.ovpn", BASE + "ca ca.crt\ncert client.crt\nkey client.key\n",
                "ca.crt", CERT, "client.crt", CERT, "client.key", KEY);
        try (ProfileImport.Candidate candidate = read(zip(cyberghost, false))) {
            String flat = contents(candidate);
            check(flat.contains("<ca>\n" + CERT));
            check(flat.contains("<cert>\n" + CERT));
            check(flat.contains("<key>\n" + KEY));
            check(!flat.contains("ca ca.crt"));
            String id = "debb58e3-2041-49cc-8a54-ec1a3d0319b0";
            byte[] sealed = ProfileEnvelope.seal(id, "Bundle", candidate, new SecretKeySpec(new byte[32], "AES"));
            try (ProfileEnvelope.Opened opened = ProfileEnvelope.open(id, sealed, new SecretKeySpec(new byte[32], "AES"))) {
                char[] restored = opened.copyContents();
                try {
                    check(new String(restored).equals(flat));
                    try (ProfileImport.Candidate backup = ProfileImport.read(new ByteArrayInputStream(
                            new String(restored).getBytes(StandardCharsets.UTF_8)), ProfileImport.Protocol.OPENVPN)) {
                        check(contents(backup).equals(flat));
                    }
                } finally { Arrays.fill(restored, '\0'); }
            } finally { Arrays.fill(sealed, (byte) 0); }
        }
        Map<String, String> nested = files("export/", "", "export/openvpn.ovpn",
                BASE + "--ca \"ca file.pem\" # provider CA\nkey 'client key.key'\n",
                "export/ca file.pem", CERT, "export/client key.key", KEY, "export/unused.crt", "unused-material");
        try (ProfileImport.Candidate candidate = read(zip(nested, true))) {
            check(contents(candidate).contains("<ca>\n" + CERT));
            check(!contents(candidate).contains("unused-material"));
        }
        for (String direction : new String[] {"0", "1", "bidirectional"}) {
            try (ProfileImport.Candidate candidate = read(zip(files("client.ovpn", BASE + "tls-auth ta.key " + direction + "\n",
                    "ta.key", "synthetic-static-key"), false))) {
                check(contents(candidate).contains("key-direction " + direction + "\n<tls-auth>"));
            }
        }
        for (String config : new String[] {BASE + "key-direction 1\ntls-auth ta.key\n",
                BASE + "tls-auth ta.key\nkey-direction 1\n", BASE + "key-direction 1\ntls-auth ta.key 1\n"}) {
            try (ProfileImport.Candidate candidate = read(zip(files("client.ovpn", config, "ta.key", KEY), false))) {
                check(contents(candidate).contains("key-direction 1\n"));
                check(!contents(candidate).contains("key-direction bidirectional"));
            }
        }
        try (ProfileImport.Candidate candidate = read(zip(files("client.ovpn", BASE
                + "<auth-user-pass>\nsynthetic-user\nkey-direction 0\n</auth-user-pass>\ntls-auth ta.key 1\n", "ta.key", KEY), false))) {
            check(contents(candidate).contains("key-direction 1\n<tls-auth>"));
        }
        for (String option : new String[] {"tls-crypt", "tls-crypt-v2", "extra-certs", "crl-verify"}) {
            try (ProfileImport.Candidate candidate = read(zip(files("client.ovpn", BASE + option + " material.pem\n",
                    "material.pem", CERT), false))) {
                check(contents(candidate).contains("<" + option + ">"));
            }
        }
        for (String ending : new String[] {"", "\n", "\r\n\r\n"}) {
            String crl = "-----BEGIN X509 CRL-----\nsynthetic-revocation-list\n-----END X509 CRL-----";
            try (ProfileImport.Candidate candidate = read(zip(files("client.ovpn", BASE + "crl-verify list.crl\n",
                    "list.crl", crl + ending), false))) {
                check(contents(candidate).contains("<crl-verify>\n" + crl + "\n</crl-verify>"));
                check(!contents(candidate).contains("-----END X509 CRL-----\n\n"));
            }
        }
        // Bundling must preserve existing opaque inline data instead of treating it as directives.
        String inline = BASE + "<ca>\nca absent.crt\n</ca>\n";
        try (ProfileImport.Candidate candidate = read(zip(files("client.ovpn", inline), false))) {
            check(contents(candidate).contains("ca absent.crt"));
        }
        // Archives never become an accepted storage or backup format.
        try {
            ProfileImport.read(new ByteArrayInputStream(zip(cyberghost, false)), ProfileImport.Protocol.OPENVPN);
            throw new AssertionError("Strict profile importer accepted ZIP backup.");
        } catch (IOException expected) { checks++; }
    }

    private static void archiveFailures() throws Exception {
        reject(files("ca.crt", CERT));
        reject(files("first.ovpn", BASE, "second.ovpn", BASE));
        reject(files("client.ovpn", BASE, "ca.crt", CERT, "CA.CRT", CERT));
        reject(files("client.ovpn", BASE, "Export/first.crt", CERT, "export/other.crt", CERT));
        reject(files("client.ovpn", BASE, "../ca.crt", CERT));
        reject(files("client.ovpn", BASE, "/ca.crt", CERT));
        reject(files("client.ovpn", BASE, "C:/ca.crt", CERT));
        reject(files("client.ovpn", BASE, "dir\\ca.crt", CERT));
        reject(files("client.ovpn", BASE, "dir/./ca.crt", CERT));
        reject(files("client.ovpn", BASE, "ca.crt\n", CERT));
        reject(files("client.ovpn", BASE, "nested.zip", "PK"));
        reject(files("client.ovpn", BASE, "script.sh", "untrusted"));
        reject(files("client.ovpn", BASE, "private.p12", "binary"));
        reject(files("client.ovpn", BASE, "unused.key", "binary\0material"));
        reject(files("client.ovpn", BASE, "empty.crt", ""));

        byte[] valid = zip(files("client.ovpn", BASE, "first.crt", CERT, "other.crt", CERT), true);
        byte[] duplicate = valid.clone();
        replace(duplicate, "other.crt", "first.crt");
        rejects(duplicate);
        rejects(Arrays.copyOf(valid, valid.length - 1));
        rejects(Arrays.copyOf(valid, firstCentral(valid)));
        byte[] centralCrc = valid.clone();
        centralCrc[firstCentral(centralCrc) + 16] ^= 1;
        rejects(centralCrc);
        byte[] localCrc = valid.clone();
        localCrc[14] ^= 1;
        rejects(localCrc);
        byte[] payload = valid.clone();
        payload[30 + u16(payload, 26) + u16(payload, 28)] ^= 1;
        rejects(payload);
        byte[] localName = valid.clone();
        localName[30] ^= 1;
        rejects(localName);
        byte[] localOffset = valid.clone();
        localOffset[firstCentral(localOffset) + 42] ^= 1;
        rejects(localOffset);
        byte[] encrypted = valid.clone();
        encrypted[firstCentral(encrypted) + 8] |= 1;
        rejects(encrypted);
        byte[] symlink = valid.clone();
        int central = firstCentral(symlink);
        symlink[central + 40] = 0; symlink[central + 41] = (byte) 0xa0;
        rejects(symlink);
        byte[] invalidUtf8 = valid.clone();
        int name = firstCentral(invalidUtf8) + 46;
        invalidUtf8[name] = (byte) 0xc3; invalidUtf8[name + 1] = 0x28;
        rejects(invalidUtf8);
        ByteArrayOutputStream invalidMaterial = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(invalidMaterial, StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry("client.ovpn"));
            zip.write((BASE + "key client.key\n").getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("client.key"));
            zip.write(new byte[] {(byte) 0xc3, 0x28}); zip.closeEntry();
        }
        rejects(invalidMaterial.toByteArray());
    }

    private static void referenceFailures() throws Exception {
        reject(files("client.ovpn", BASE + "ca absent.crt\n"));
        reject(files("client.ovpn", BASE + "ca ../ca.crt\n", "ca.crt", CERT));
        reject(files("client.ovpn", BASE + "ca /ca.crt\n", "ca.crt", CERT));
        reject(files("client.ovpn", BASE + "ca CA.CRT\n", "ca.crt", CERT));
        reject(files("client.ovpn", BASE + "ca 'ca.crt\n", "ca.crt", CERT));
        reject(files("client.ovpn", BASE + "ca ca.crt extra\n", "ca.crt", CERT));
        reject(files("client.ovpn", BASE + "ca ca.crt\nca other.crt\n", "ca.crt", CERT, "other.crt", CERT));
        reject(files("client.ovpn", BASE + "tls-auth ta.key 2\n", "ta.key", KEY));
        reject(files("client.ovpn", BASE + "key-direction 1\ntls-auth ta.key 0\n", "ta.key", KEY));
        reject(files("client.ovpn", BASE + "tls-auth ta.key 0\nkey-direction 1\n", "ta.key", KEY));
        reject(files("client.ovpn", BASE + "crl-verify list.crl dir\n", "list.crl", CERT));
        reject(files("client.ovpn", BASE + "key client.key\n", "client.key", "text\n</KEY>\nremote injected.example\n"));
        reject(files("client.ovpn", BASE + "key client.key\n", "client.key", "text\n< / key >\n"));
        reject(files("client.ovpn", BASE + "up untrusted\n"));
        reject(files("client.ovpn", BASE + "config other.key\n", "other.key", BASE));
        reject(files("client.ovpn", BASE + "auth-user-pass credentials.key\n", "credentials.key", "synthetic-user\nsynthetic-password\n"));
        reject(files("client.ovpn", BASE + "ca ca.crt\n", "ca.crt", "binary\0certificate"));
    }

    private static void bounds() throws Exception {
        Map<String, String> entries = files("client.ovpn", BASE);
        for (int i = 0; i < 32; i++) entries.put("cert" + i + ".crt", CERT);
        reject(entries);
        entries.remove("cert31.crt");
        try (ProfileImport.Candidate candidate = read(zip(entries, false))) { check(contents(candidate).contains("remote vpn.example")); }
        char[] large = new char[700000]; Arrays.fill(large, 'A');
        String material = new String(large);
        // Each member fits, but their actual aggregate decompressed size does not.
        reject(files("client.ovpn", BASE, "first.crt", material, "other.crt", material));
        // One member may be referenced by different options, so bound the expanded text too.
        reject(files("client.ovpn", BASE + "ca material.pem\ncert material.pem\n", "material.pem", material));
        byte[] oversized = new byte[ProfileImport.MAX_BYTES + 1];
        oversized[0] = 'P'; oversized[1] = 'K';
        rejects(oversized);
        Arrays.fill(large, '\0');
    }

    private static Map<String, String> files(String... pairs) {
        Map<String, String> files = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) files.put(pairs[i], pairs[i + 1]);
        return files;
    }
    private static byte[] zip(Map<String, String> files, boolean stored) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String> file : files.entrySet()) {
                byte[] body = file.getValue().getBytes(StandardCharsets.UTF_8);
                ZipEntry entry = new ZipEntry(file.getKey());
                if (stored) {
                    CRC32 crc = new CRC32(); crc.update(body);
                    entry.setMethod(ZipEntry.STORED); entry.setSize(body.length); entry.setCrc(crc.getValue());
                }
                zip.putNextEntry(entry); zip.write(body); zip.closeEntry();
                Arrays.fill(body, (byte) 0);
            }
        }
        return bytes.toByteArray();
    }
    private static void reject(Map<String, String> files) throws Exception { rejects(zip(files, false)); }
    private static void rejects(byte[] raw) throws Exception {
        try (ProfileImport.Candidate unexpected = read(raw)) { throw new AssertionError("Invalid OpenVPN bundle accepted."); }
        catch (IOException expected) { checks++; }
        finally { Arrays.fill(raw, (byte) 0); }
    }
    private static ProfileImport.Candidate read(byte[] raw) throws IOException { return read(new ByteArrayInputStream(raw)); }
    private static ProfileImport.Candidate read(ByteArrayInputStream raw) throws IOException {
        return OpenVpnDocument.read(raw, ProfileImport.Protocol.OPENVPN);
    }
    private static String contents(ProfileImport.Candidate candidate) {
        char[] contents = candidate.copyContents();
        try { return new String(contents); } finally { Arrays.fill(contents, '\0'); }
    }
    private static int firstCentral(byte[] raw) {
        for (int i = 0; i < raw.length - 3; i++) if (raw[i] == 'P' && raw[i + 1] == 'K' && raw[i + 2] == 1 && raw[i + 3] == 2) return i;
        throw new AssertionError("Missing fixture central directory.");
    }
    private static int u16(byte[] raw, int at) { return (raw[at] & 255) | ((raw[at + 1] & 255) << 8); }
    private static void replace(byte[] raw, String oldValue, String newValue) {
        byte[] oldBytes = oldValue.getBytes(StandardCharsets.UTF_8), newBytes = newValue.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i <= raw.length - oldBytes.length; i++) {
            boolean same = true;
            for (int j = 0; j < oldBytes.length; j++) if (raw[i + j] != oldBytes[j]) { same = false; break; }
            if (same) { System.arraycopy(newBytes, 0, raw, i, newBytes.length); i += oldBytes.length - 1; }
        }
    }
    private static void check(boolean result) { if (!result) throw new AssertionError("OpenVPN bundle regression."); checks++; }
}
