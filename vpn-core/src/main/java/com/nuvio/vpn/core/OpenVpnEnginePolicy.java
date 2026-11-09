package com.nuvio.vpn.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Engine selection is a property of a validated profile, never a retry after a connection failure. */
public final class OpenVpnEnginePolicy {
    public enum Engine { OPENVPN3, OPENVPN2 }

    private OpenVpnEnginePolicy() {}

    /** Bounded preflight only: the selected engine must also validate before saving or connecting.
     * The caller retains ownership of the private input and must wipe it after use. */
    public static Engine select(char[] contents) throws IOException {
        if (contents == null || contents.length == 0 || contents.length > ProfileImport.MAX_BYTES) {
            throw new IOException("OpenVPN configuration is empty or exceeds its size limit.");
        }
        String text = new String(contents);
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        ProfileImport.preflight(text, ProfileImport.Protocol.OPENVPN);
        return selectPreflighted(text);
    }

    static Engine selectPreflighted(String text) throws IOException {
        List<String> directives = new ArrayList<>();
        String inline = null;
        boolean fragment = false;
        for (String raw : text.split("\\r?\\n")) {
            String line = raw.trim();
            if (inline != null) {
                if (line.equalsIgnoreCase("</" + inline + ">")) inline = null;
                continue;
            }
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue;
            if (line.startsWith("<")) {
                if (!line.equals("<connection>") && !line.equals("</connection>")) {
                    inline = line.substring(1, line.length() - 1).toLowerCase(Locale.ROOT);
                    // The compatibility adapter supports PEM material and managed service login only.
                    directives.add(line);
                }
                continue;
            }
            directives.add(line);
            String option = line.split("\\s+", 2)[0];
            if (option.equals("fragment") || option.equals("--fragment")) fragment = true;
        }
        if (!fragment) return Engine.OPENVPN3;

        int fragments = 0;
        for (String directive : directives) {
            if (directive.startsWith("<")) {
                if (!directive.matches("<(ca|cert|key|tls-auth|tls-crypt|tls-crypt-v2|auth-user-pass|extra-certs|crl-verify)>")) {
                    throw unsupported("inline material");
                }
                continue;
            }
            List<String> fields = arguments(directive);
            if (fields.isEmpty() || !directive.split("\\s+", 2)[0].equals(fields.get(0))) {
                throw unsupported("option spelling");
            }
            String option = fields.get(0);
            if (option.startsWith("--")) option = option.substring(2);
            // Do not allow alternate option spelling/quoting to bypass the supported-option contract.
            if (!option.matches("[a-z][a-z0-9-]*")) throw unsupported("option spelling");
            if (option.equals("fragment")) {
                if (++fragments != 1 || !arity(fields, 1, 2) || !number(fields.get(1), 68, 65535)
                        || (fields.size() == 3 && !fields.get(2).equals("mtu"))) {
                    throw new IOException("OpenVPN fragmentation requires one fragment size from 68 to 65535, optionally followed by mtu.");
                }
            } else if (!supported(option, fields)) {
                throw unsupported(option);
            }
        }
        return Engine.OPENVPN2;
    }

    private static boolean supported(String option, List<String> p) {
        switch (option) {
            case "client": case "tls-client": case "pull": case "nobind": case "persist-key":
            case "persist-tun": case "remote-random": case "float": case "auth-nocache":
            case "mute-replay-warnings": case "comp-noadapt": case "fast-io":
                // The Android adapter removes persist-tun and retains sole ownership of TUN lifetime.
                // Pinned OpenVPN 2 accepts fast-io as a deprecated no-op, not a performance promise.
                return arity(p, 0, 0);
            case "dev": case "dev-type":
                return oneOf(p, "tun");
            case "proto":
                return udp(p);
            case "remote":
                return arity(p, 1, 3) && !p.get(1).isEmpty()
                        && (p.size() < 3 || number(p.get(2), 1, 65535))
                        && (p.size() < 4 || udpName(p.get(3)));
            case "port": case "rport":
                return arity(p, 1, 1) && number(p.get(1), 1, 65535);
            case "resolv-retry":
                return arity(p, 1, 1) && (p.get(1).equals("infinite") || number(p.get(1), 0, Integer.MAX_VALUE));
            case "connect-retry":
                return arity(p, 1, 2) && number(p.get(1), 1, 3600)
                        && (p.size() < 3 || number(p.get(2), 1, 3600));
            case "connect-retry-max":
                return arity(p, 1, 1) && number(p.get(1), 0, Integer.MAX_VALUE);
            case "connect-timeout": case "server-poll-timeout": case "tls-timeout":
            case "hand-window": case "tran-window":
                return arity(p, 1, 1) && number(p.get(1), 1, 3600);
            case "ping": case "ping-restart": case "ping-exit": case "reneg-sec":
            case "reneg-bytes": case "reneg-pkts":
                return arity(p, 1, 1) && number(p.get(1), 0, Integer.MAX_VALUE);
            case "keepalive":
                return arity(p, 2, 2) && number(p.get(1), 1, Integer.MAX_VALUE)
                        && number(p.get(2), 1, Integer.MAX_VALUE);
            case "explicit-exit-notify":
                return arity(p, 0, 1) && (p.size() == 1 || number(p.get(1), 0, 255));
            case "sndbuf": case "rcvbuf":
                return arity(p, 1, 1) && number(p.get(1), 0, Integer.MAX_VALUE);
            case "tun-mtu":
                return arity(p, 1, 1) && number(p.get(1), 576, 65535);
            case "mssfix":
                return arity(p, 0, 2) && (p.size() == 1 || number(p.get(1), 0, 65535))
                        && (p.size() < 3 || p.get(2).equals("mtu") || p.get(2).equals("fixed"));
            case "cipher": case "data-ciphers-fallback":
                return arity(p, 1, 1) && cipher(p.get(1));
            case "data-ciphers": case "ncp-ciphers":
                if (!arity(p, 1, 1)) return false;
                for (String name : p.get(1).split(":", -1)) if (!cipher(name)) return false;
                return true;
            case "auth":
                return oneOf(p, "SHA1", "SHA256", "SHA384", "SHA512");
            case "tls-version-min": case "tls-version-max":
                // A fallback never lowers the minimum TLS version or retries unsupported TLS versions.
                return oneOf(p, "1.2", "1.3");
            case "tls-cipher": case "tls-ciphersuites":
                if (!arity(p, 1, 1)) return false;
                // A TLS allowlist is preserved; OpenSSL expressions/security-level overrides are not.
                for (String suite : p.get(1).split(":", -1)) {
                    if (!suite.matches("(?:TLS[-_]|ECDHE-|DHE-)[A-Z0-9_-]{1,120}")
                            || !suite.matches(".*(?:AES|CHACHA20).*" )
                            || suite.matches(".*(NULL|RC4|DES|MD5|EXPORT|PSK|ANON).*")) return false;
                }
                return true;
            case "remote-cert-tls":
                return oneOf(p, "server");
            case "remote-cert-eku":
                return arity(p, 1, 1) && !p.get(1).isEmpty();
            case "remote-cert-ku":
                if (!arity(p, 1, 16)) return false;
                for (int i = 1; i < p.size(); i++) if (!p.get(i).matches("[0-9A-Fa-f]{1,4}")) return false;
                return true;
            case "verify-x509-name":
                return arity(p, 1, 2) && !p.get(1).isEmpty()
                        && (p.size() < 3 || p.get(2).matches("subject|name|name-prefix"));
            case "peer-fingerprint":
                return arity(p, 1, 1) && p.get(1).matches("[0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){31}");
            case "key-direction":
                return oneOf(p, "0", "1", "bidirectional");
            case "ca": case "cert": case "key": case "tls-auth": case "tls-crypt":
            case "tls-crypt-v2": case "extra-certs": case "crl-verify":
                return oneOf(p, "[inline]");
            case "auth-user-pass":
                return arity(p, 0, 1) && (p.size() == 1 || p.get(1).equals("[inline]"));
            case "allow-compression":
                return oneOf(p, "no", "asym");
            case "comp-lzo":
                return arity(p, 0, 1) && (p.size() == 1 || p.get(1).matches("no|yes|adaptive"));
            case "compress":
                return arity(p, 0, 1) && (p.size() == 1 || p.get(1).matches("stub|stub-v2|lzo|lz4|lz4-v2"));
            case "dhcp-option":
                return arity(p, 2, 2) && p.get(1).matches("DNS|DNS6")
                        && p.get(2).matches("[0-9A-Fa-f:.]{2,45}");
            case "redirect-gateway":
                if (!arity(p, 0, 2)) return false;
                for (int i = 1; i < p.size(); i++) if (!p.get(i).matches("def1|ipv6")) return false;
                return true;
            case "setenv":
                // OpenVPN's setenv opt/FORWARD_COMPATIBLE can hide unsupported options. Never allow them.
                return arity(p, 2, 2) && p.get(1).equals("CLIENT_CERT") && p.get(2).matches("0|1");
            case "setenv-safe":
                return arity(p, 1, 2) && p.get(1).matches("UV_[A-Za-z0-9_]{1,64}");
            case "verb":
                return arity(p, 1, 1) && number(p.get(1), 0, 4);
            case "mute":
                return arity(p, 1, 1) && number(p.get(1), 0, Integer.MAX_VALUE);
            default:
                return false;
        }
    }

    private static boolean udp(List<String> p) { return arity(p, 1, 1) && udpName(p.get(1)); }
    private static boolean udpName(String value) { return value.matches("udp|udp4|udp6"); }
    private static boolean cipher(String value) {
        return value.matches("AES-(128|192|256)-(CBC|GCM)|CHACHA20-POLY1305");
    }
    private static boolean arity(List<String> p, int min, int max) {
        return p.size() - 1 >= min && p.size() - 1 <= max;
    }
    private static boolean oneOf(List<String> p, String... values) {
        if (!arity(p, 1, 1)) return false;
        for (String value : values) if (p.get(1).equals(value)) return true;
        return false;
    }
    private static boolean number(String value, int min, int max) {
        if (!value.matches("[0-9]{1,10}")) return false;
        try { long number = Long.parseLong(value); return number >= min && number <= max; }
        catch (NumberFormatException ignored) { return false; }
    }

    /** Native-compatible argument boundaries for the Android adapter's validated directives.
     * Returned values may contain private profile data: never log them or retain them after use.
     * Call only outside opaque certificate/key/login blocks. This method is not profile validation. */
    public static List<String> arguments(String line) throws IOException {
        if (line == null) throw unsupported("empty option");
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        // Native OpenVPN treats a closing quote as the end of a token. Quotes inside an
        // unquoted token are literal, and backslashes inside single quotes are literal.
        int state = 0; // 0 between tokens; 1 unquoted; 2 double quoted; 3 single quoted
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c < 32 && c != '\t') throw unsupported("control characters");
            if (c == '\\' && state != 3) {
                if (++i == line.length()) throw unsupported("quoted argument");
                char escaped = line.charAt(i);
                if (escaped != '\\' && escaped != '"' && escaped != ' ' && escaped != '\t') {
                    throw unsupported("quoted argument");
                }
                field.append(escaped);
                if (state == 0) state = 1;
            } else if (state == 0) {
                if (c == '#' || c == ';') break;
                if (c == ' ' || c == '\t') continue;
                if (c == '"') state = 2;
                else if (c == '\'') state = 3;
                else { field.append(c); state = 1; }
            } else if ((state == 2 && c == '"') || (state == 3 && c == '\'')) {
                fields.add(field.toString()); field.setLength(0); state = 0;
            } else if (state == 1 && (c == ' ' || c == '\t')) {
                fields.add(field.toString()); field.setLength(0); state = 0;
            } else {
                field.append(c);
            }
        }
        if (state == 2 || state == 3) throw unsupported("quoted argument");
        if (state == 1) fields.add(field.toString());
        return Collections.unmodifiableList(fields);
    }

    private static IOException unsupported(String option) {
        // Unknown first tokens can be misplaced private values. No untrusted text leaves this check.
        return new IOException("OpenVPN 2 compatibility mode cannot safely support one or more options in this profile. "
                + "Use a supported provider profile or the provider's app.");
    }
}
