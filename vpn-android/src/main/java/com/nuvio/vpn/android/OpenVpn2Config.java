package com.nuvio.vpn.android;

import com.nuvio.vpn.core.OpenVpnEnginePolicy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.List;

/** In-memory process input. Service credentials travel only over private IPC;
 * neither a plaintext configuration nor an auth file is written to disk. */
final class OpenVpn2Config implements AutoCloseable {
    final byte[] bytes;
    final char[] username, password;
    final boolean needsCredentials;

    private OpenVpn2Config(byte[] bytes, char[] username, char[] password, boolean required) {
        this.bytes = bytes;
        this.username = username;
        this.password = password;
        needsCredentials = required && username == null;
    }

    static char[] core3Preflight(char[] source) throws IOException {
        StringBuilder output = new StringBuilder();
        String block = null;
        for (String line : new String(source).split("\\r?\\n", -1)) {
            String trimmed = line.trim().toLowerCase(Locale.ROOT);
            if (block != null) {
                output.append(line).append('\n');
                if (trimmed.equals("</" + block + ">")) block = null;
            } else {
                if (inlineOpening(trimmed))
                    block = trimmed.substring(1, trimmed.length() - 1);
                String option = option(trimmed);
                if (!option.equals("fragment")) output.append(line).append('\n');
            }
        }
        return output.toString().toCharArray();
    }

    static OpenVpn2Config prepare(char[] source) throws IOException {
        StringBuilder output = new StringBuilder();
        String block = null;
        char[] username = null, password = null;
        boolean required = false, authBlock = false, noCompression = false;
        String cipher = null;
        boolean negotiatedCiphers = false, fallbackCipher = false;
        int authFields = 0;
        try {
            for (String line : new String(source).split("\\r?\\n", -1)) {
                String trimmed = line.trim().toLowerCase(Locale.ROOT);
                if (authBlock) {
                    if (trimmed.equals("</auth-user-pass>")) {
                        if (authFields != 2) throw new IOException("The saved OpenVPN service credentials are invalid.");
                        authBlock = false;
                    } else {
                        if (line.isEmpty() || line.indexOf('\0') >= 0 || authFields >= 2)
                            throw new IOException("The saved OpenVPN service credentials are invalid.");
                        if (authFields++ == 0) username = line.toCharArray();
                        else password = line.toCharArray();
                    }
                    continue;
                }
                if (block != null) {
                    output.append(line).append('\n');
                    if (trimmed.equals("</" + block + ">")) block = null;
                    continue;
                }
                if (trimmed.equals("<auth-user-pass>")) {
                    if (username != null) throw new IOException("The OpenVPN service credentials are ambiguous.");
                    required = authBlock = true;
                    continue;
                }
                if (inlineOpening(trimmed))
                    block = trimmed.substring(1, trimmed.length() - 1);
                String option = option(trimmed);
                if (option.equals("auth-user-pass")) { required = true; continue; }
                if (option.equals("cipher")) cipher = OpenVpnEnginePolicy.arguments(line.trim()).get(1);
                if (option.equals("data-ciphers") || option.equals("ncp-ciphers")) negotiatedCiphers = true;
                if (option.equals("data-ciphers-fallback")) fallbackCipher = true;
                // The app owns TUN lifetime, purpose verification and compression policy in both engines.
                if (option.equals("allow-compression")) {
                    List<String> arguments = OpenVpnEnginePolicy.arguments(trimmed);
                    if (arguments.size() == 2 && arguments.get(1).equals("no")) noCompression = true;
                    continue;
                }
                if (option.equals("persist-tun") || option.equals("verb")) continue;
                output.append(line).append('\n');
            }
            if (authBlock) throw new IOException("The saved OpenVPN service credentials are incomplete.");
            if (required) output.append("auth-user-pass\n");
            // OpenVPN2.7 no longer uses --cipher for TLS data-channel negotiation.
            // Preserve a provider's sole explicit cipher without adding algorithms
            // to any explicit data-ciphers constraint.
            if (cipher != null && !negotiatedCiphers) {
                output.append("data-ciphers ").append(cipher).append('\n');
                if (!fallbackCipher) output.append("data-ciphers-fallback ").append(cipher).append('\n');
            }
            output.append("remote-cert-tls server\nallow-compression ").append(noCompression ? "no" : "asym").append("\nverb 0\n");
            return new OpenVpn2Config(output.toString().getBytes(StandardCharsets.UTF_8), username, password, required);
        } catch (IOException | RuntimeException failure) {
            if (username != null) Arrays.fill(username, '\0');
            if (password != null) Arrays.fill(password, '\0');
            throw failure;
        }
    }

    private static String option(String line) throws IOException {
        if (line.isEmpty() || line.startsWith("#") || line.startsWith(";") || line.startsWith("<")) return "";
        String value = OpenVpnEnginePolicy.arguments(line).get(0);
        return value.startsWith("--") ? value.substring(2) : value;
    }

    private static boolean inlineOpening(String line) {
        return line.startsWith("<") && line.endsWith(">") && !line.startsWith("</") && !line.equals("<connection>");
    }

    static byte[] credentialCommand(String kind, char[] value) throws IOException {
        if (!kind.equals("username") && !kind.equals("password")) throw new IOException("Invalid credential request.");
        if (value == null || value.length == 0 || value.length > 1024) throw new IOException("OpenVPN service credentials are required.");
        StringBuilder command = new StringBuilder(kind).append(" \"Auth\" \"");
        for (char c : value) {
            if (c == '\r' || c == '\n' || c == '\0') throw new IOException("Invalid OpenVPN service credentials.");
            if (c == '\\' || c == '"') command.append('\\');
            command.append(c);
        }
        return command.append("\"\n").toString().getBytes(StandardCharsets.UTF_8);
    }

    @Override public void close() {
        Arrays.fill(bytes, (byte) 0);
        if (username != null) Arrays.fill(username, '\0');
        if (password != null) Arrays.fill(password, '\0');
    }
}
