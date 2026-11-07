package com.nuvio.vpn.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Document-picker import. ZIP references are resolved in memory, never on the filesystem. */
public final class OpenVpnDocument {
    private static final int MAX_ENTRIES = 32;

    private OpenVpnDocument() {}

    /** The caller retains ownership of input. Stored profiles and backups remain plain text. */
    public static ProfileImport.Candidate read(InputStream input, ProfileImport.Protocol protocol) throws IOException {
        if (input == null || protocol == null) throw new IllegalArgumentException("Input and protocol are required.");
        byte[] raw = readBounded(input, ProfileImport.MAX_BYTES);
        try {
            if (raw.length >= 2 && raw[0] == 'P' && raw[1] == 'K') {
                if (protocol != ProfileImport.Protocol.OPENVPN) throw invalid("ZIP bundles are supported only for OpenVPN.");
                return bundle(raw);
            }
            return ProfileImport.read(new ByteArrayInputStream(raw), protocol);
        } finally { Arrays.fill(raw, (byte) 0); }
    }

    private static ProfileImport.Candidate bundle(byte[] raw) throws IOException {
        Map<String, Metadata> directory = centralDirectory(raw);
        Map<String, byte[]> files = new HashMap<>();
        int total = 0;
        String config = null;
        try {
            try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(raw), StandardCharsets.UTF_8)) {
                ZipEntry entry;
                Set<String> seen = new HashSet<>();
                while ((entry = zip.getNextEntry()) != null) {
                    Metadata metadata = directory.get(entry.getName());
                    if (metadata == null || !seen.add(entry.getName())) throw invalid("ZIP bundle contains ambiguous entries.");
                    byte[] content = readBounded(zip, ProfileImport.MAX_BYTES - total);
                    total += content.length;
                    if (entry.getCrc() != metadata.crc || entry.getSize() != content.length
                            || entry.getSize() != metadata.size || entry.getCompressedSize() != metadata.compressedSize
                            || entry.getMethod() != metadata.method) {
                        Arrays.fill(content, (byte) 0);
                        throw invalid("ZIP bundle is damaged or incomplete.");
                    }
                    if (entry.isDirectory()) {
                        Arrays.fill(content, (byte) 0);
                        if (entry.getSize() != 0) throw invalid("ZIP bundle contains an invalid directory.");
                    } else {
                        files.put(entry.getName(), content);
                        text(content);
                        if (entry.getName().toLowerCase(Locale.ROOT).endsWith(".ovpn")) config = entry.getName();
                    }
                }
                if (seen.size() != directory.size()) throw invalid("ZIP bundle is damaged or incomplete.");
            }
            if (config == null) throw invalid("ZIP bundle must contain exactly one .ovpn configuration.");
            String flattened = flatten(text(files.get(config)), config, files);
            byte[] encoded = flattened.getBytes(StandardCharsets.UTF_8);
            try {
                if (encoded.length > ProfileImport.MAX_BYTES) throw invalid("Combined OpenVPN configuration exceeds 1 MiB.");
                return ProfileImport.read(new ByteArrayInputStream(encoded), ProfileImport.Protocol.OPENVPN);
            } finally { Arrays.fill(encoded, (byte) 0); }
        } finally { for (byte[] bytes : files.values()) Arrays.fill(bytes, (byte) 0); }
    }

    private static String flatten(String config, String name, Map<String, byte[]> files) throws IOException {
        String parent = name.substring(0, name.lastIndexOf('/') + 1);
        StringBuilder output = new StringBuilder();
        Set<String> expanded = new HashSet<>();
        Set<String> directions = globalDirections(config);
        String inline = null;
        for (String rawLine : config.split("\\r?\\n", -1)) {
            String line = rawLine.trim();
            if (inline != null) {
                output.append(rawLine).append('\n');
                if (line.equalsIgnoreCase("</" + inline + ">")) inline = null;
                continue;
            }
            if (isInline(line)) {
                inline = line.substring(1, line.length() - 1);
            }
            String option = line.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
            if (option.startsWith("--")) option = option.substring(2);
            if (!option.matches("ca|cert|key|tls-auth|tls-crypt|tls-crypt-v2|crl-verify|extra-certs")) {
                output.append(rawLine).append('\n');
                continue;
            }
            List<String> fields = fields(line);
            if (fields.size() < 2 || fields.get(1).equals("[inline]")) {
                output.append(rawLine).append('\n');
                continue;
            }
            if (!expanded.add(option) || fields.size() > (option.equals("tls-auth") ? 3 : 2)) {
                throw invalid("ZIP bundle contains an ambiguous certificate or key reference.");
            }
            String reference = fields.get(1);
            safePath(reference, false);
            byte[] material = files.get(parent + reference);
            if (material == null || reference.toLowerCase(Locale.ROOT).endsWith(".ovpn")) {
                throw invalid("An OpenVPN certificate or key is missing from the ZIP bundle.");
            }
            if (fields.size() == 3) {
                String direction = fields.get(2);
                if (!direction.equals("0") && !direction.equals("1") && !direction.equals("bidirectional")) {
                    throw invalid("ZIP bundle has an invalid TLS key direction.");
                }
                for (String global : directions) {
                    if (!global.equals(direction)) throw invalid("ZIP bundle has conflicting TLS key directions.");
                }
                output.append("key-direction ").append(direction).append('\n');
            }
            String contents = text(material);
            // Match the core merger's protection against breaking out of an opaque material block.
            if (contents.matches("(?s).*<\\s*/.*")) throw invalid("ZIP certificate or key contains an invalid inline closing tag.");
            int end = contents.length();
            while (end > 0 && (contents.charAt(end - 1) == '\r' || contents.charAt(end - 1) == '\n')) end--;
            // mbedTLS's CRL parser treats an extra trailing blank line as another malformed PEM record.
            output.append('<').append(option).append(">\n").append(contents, 0, end).append('\n')
                    .append("</").append(option).append(">\n");
            if (output.length() > ProfileImport.MAX_BYTES) throw invalid("Combined OpenVPN configuration exceeds 1 MiB.");
        }
        return output.toString();
    }

    private static Set<String> globalDirections(String config) throws IOException {
        Set<String> directions = new HashSet<>();
        String inline = null;
        boolean connection = false;
        for (String rawLine : config.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (inline != null) {
                if (line.equalsIgnoreCase("</" + inline + ">")) inline = null;
                continue;
            }
            if (isInline(line)) { inline = line.substring(1, line.length() - 1); continue; }
            if (line.equalsIgnoreCase("<connection>")) { connection = true; continue; }
            if (line.equalsIgnoreCase("</connection>")) { connection = false; continue; }
            if (connection) continue;
            String option = line.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
            if (!option.equals("key-direction") && !option.equals("--key-direction")) continue;
            List<String> tokens = fields(line);
            if (tokens.size() == 2) directions.add(tokens.get(1));
        }
        return directions;
    }

    private static boolean isInline(String line) {
        return line.matches("(?i)<(ca|cert|key|tls-auth|tls-crypt|tls-crypt-v2|crl-verify|extra-certs|auth-user-pass|pkcs12|dh)>");
    }

    /** OpenVPN filenames may be quoted; comments are recognized only between tokens. */
    private static List<String> fields(String line) throws IOException {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        char quote = 0;
        boolean started = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\\') {
                if (++i == line.length()) throw invalid("OpenVPN bundle has an invalid quoted filename.");
                field.append(line.charAt(i));
                started = true;
            } else if (quote != 0) {
                if (c == quote) quote = 0;
                else field.append(c);
            } else if (c == '\'' || c == '"') {
                quote = c;
                started = true;
            } else if (Character.isWhitespace(c)) {
                if (started) { fields.add(field.toString()); field.setLength(0); started = false; }
            } else if ((c == '#' || c == ';') && !started) {
                break;
            } else {
                field.append(c);
                started = true;
            }
        }
        if (quote != 0) throw invalid("OpenVPN bundle has an invalid quoted filename.");
        if (started) fields.add(field.toString());
        return fields;
    }

    /** Check the complete central directory as ZipInputStream alone accepts a truncated archive tail. */
    private static Map<String, Metadata> centralDirectory(byte[] raw) throws IOException {
        int end = -1;
        for (int i = raw.length - 22; i >= Math.max(0, raw.length - 65557); i--) {
            if (u32(raw, i) == 0x06054b50L && i + 22 + u16(raw, i + 20) == raw.length) { end = i; break; }
        }
        if (end < 0 || u16(raw, end + 4) != 0 || u16(raw, end + 6) != 0
                || u16(raw, end + 8) != u16(raw, end + 10)) throw invalid("ZIP bundle is damaged, incomplete or split across files.");
        int count = u16(raw, end + 10);
        long length = u32(raw, end + 12);
        long start = u32(raw, end + 16);
        if (count < 1 || count > MAX_ENTRIES || start + length != end) throw invalid("ZIP bundle must contain 1 to 32 entries and one .ovpn file.");
        Map<String, Metadata> entries = new HashMap<>();
        Set<String> names = new HashSet<>();
        Map<String, String> spellings = new HashMap<>();
        List<Metadata> localEntries = new ArrayList<>();
        int at = (int) start;
        int configs = 0;
        for (int n = 0; n < count; n++) {
            if (at < 0 || at > end - 46 || u32(raw, at) != 0x02014b50L) throw invalid("ZIP bundle is damaged or incomplete.");
            int nameLength = u16(raw, at + 28);
            int extraLength = u16(raw, at + 30);
            int commentLength = u16(raw, at + 32);
            int next = at + 46 + nameLength + extraLength + commentLength;
            if (next > end || u16(raw, at + 34) != 0 || (u16(raw, at + 8) & 0x41) != 0) throw invalid("Encrypted or split ZIP bundles are not supported.");
            int method = u16(raw, at + 10);
            if (method != ZipEntry.STORED && method != ZipEntry.DEFLATED) throw invalid("ZIP bundle uses an unsupported compression method.");
            String name = text(Arrays.copyOfRange(raw, at + 46, at + 46 + nameLength));
            boolean folder = name.endsWith("/");
            safePath(name, folder);
            String canonical = folder ? name.substring(0, name.length() - 1) : name;
            if (!names.add(canonical.toLowerCase(Locale.ROOT))) throw invalid("ZIP bundle contains duplicate or ambiguous paths.");
            String prefix = "";
            for (String part : canonical.split("/")) {
                prefix += part;
                String old = spellings.put(prefix.toLowerCase(Locale.ROOT), prefix);
                if (old != null && !old.equals(prefix)) throw invalid("ZIP bundle contains duplicate or ambiguous paths.");
                prefix += "/";
            }
            int unixType = (int) (u32(raw, at + 38) >>> 16) & 0170000;
            if (unixType != 0 && unixType != (folder ? 0040000 : 0100000)) throw invalid("ZIP bundle may contain only regular certificate files.");
            if (!folder) {
                if (name.toLowerCase(Locale.ROOT).endsWith(".ovpn")) configs++;
                else if (!name.toLowerCase(Locale.ROOT).matches(".*\\.(crt|cer|pem|key|crl)")) throw invalid("ZIP bundle may contain only one .ovpn and certificate or key files.");
            }
            long size = u32(raw, at + 24);
            long compressed = u32(raw, at + 20);
            long local = u32(raw, at + 42);
            if (size > ProfileImport.MAX_BYTES || compressed > raw.length || local >= start) throw invalid("ZIP bundle exceeds its size limit or is damaged.");
            int header = (int) local;
            if (header > start - 30 || u32(raw, header) != 0x04034b50L
                    || u16(raw, header + 6) != u16(raw, at + 8) || u16(raw, header + 8) != method) {
                throw invalid("ZIP bundle has inconsistent file headers.");
            }
            int localNameLength = u16(raw, header + 26);
            int localExtraLength = u16(raw, header + 28);
            long data = local + 30 + localNameLength + localExtraLength;
            if (localNameLength != nameLength || data + compressed > start
                    || !sameBytes(raw, header + 30, at + 46, nameLength)) {
                throw invalid("ZIP bundle has inconsistent file headers.");
            }
            Metadata metadata = new Metadata(method, u32(raw, at + 16), size, compressed,
                    header, (int) (data + compressed), u16(raw, at + 8));
            entries.put(name, metadata);
            localEntries.add(metadata);
            at = next;
        }
        if (at != end || configs != 1) throw invalid("ZIP bundle must contain exactly one .ovpn configuration.");
        Collections.sort(localEntries, Comparator.comparingInt(entry -> entry.local));
        int expected = 0;
        for (Metadata metadata : localEntries) {
            if (metadata.local != expected) throw invalid("ZIP bundle has overlapping or inconsistent file records.");
            expected = metadata.dataEnd;
            if ((metadata.flags & 8) != 0) {
                if (expected > start - 12) throw invalid("ZIP bundle has an incomplete data descriptor.");
                if (u32(raw, expected) == 0x08074b50L) expected += 4;
                if (expected > start - 12 || u32(raw, expected) != metadata.crc
                        || u32(raw, expected + 4) != metadata.compressedSize || u32(raw, expected + 8) != metadata.size) {
                    throw invalid("ZIP bundle has an invalid data descriptor.");
                }
                expected += 12;
            } else if (u32(raw, metadata.local + 14) != metadata.crc
                    || u32(raw, metadata.local + 18) != metadata.compressedSize || u32(raw, metadata.local + 22) != metadata.size) {
                throw invalid("ZIP bundle has inconsistent file headers.");
            }
        }
        if (expected != start) throw invalid("ZIP bundle has inconsistent file records.");
        return entries;
    }

    private static void safePath(String path, boolean directory) throws IOException {
        if (path.isEmpty() || path.length() > 1024 || path.startsWith("/") || path.indexOf('\\') >= 0 || path.indexOf(':') >= 0) {
            throw invalid("ZIP bundle contains an unsafe path.");
        }
        String checked = directory ? path.substring(0, path.length() - 1) : path;
        for (String part : checked.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) throw invalid("ZIP bundle contains an unsafe path.");
        }
        for (int i = 0; i < path.length(); i++) {
            if (Character.isISOControl(path.charAt(i))) throw invalid("ZIP bundle contains an unsafe path.");
        }
    }

    private static String text(byte[] raw) throws IOException {
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString();
            if (text.startsWith("\uFEFF")) text = text.substring(1);
            if (text.trim().isEmpty() || text.indexOf('\0') >= 0) throw invalid("OpenVPN bundle files must contain UTF-8 text.");
            return text;
        } catch (CharacterCodingException e) { throw invalid("OpenVPN bundle files must contain UTF-8 text."); }
    }

    private static byte[] readBounded(InputStream input, int limit) throws IOException {
        WipingBuffer output = new WipingBuffer();
        byte[] buffer = new byte[4096];
        try {
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (count == 0) throw invalid("Could not read VPN document.");
                if (count > limit - output.size()) throw invalid("VPN document or decompressed ZIP bundle exceeds 1 MiB.");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        } finally { output.wipe(); Arrays.fill(buffer, (byte) 0); }
    }

    private static int u16(byte[] raw, int at) { return (raw[at] & 255) | ((raw[at + 1] & 255) << 8); }
    private static long u32(byte[] raw, int at) { return (u16(raw, at) & 65535L) | ((long) u16(raw, at + 2) << 16); }
    private static boolean sameBytes(byte[] raw, int first, int second, int size) {
        for (int i = 0; i < size; i++) if (raw[first + i] != raw[second + i]) return false;
        return true;
    }
    private static IOException invalid(String reason) { return new IOException(reason); }

    private static final class Metadata {
        final int method;
        final long crc, size, compressedSize;
        final int local, dataEnd, flags;
        Metadata(int method, long crc, long size, long compressedSize, int local, int dataEnd, int flags) {
            this.method = method; this.crc = crc; this.size = size; this.compressedSize = compressedSize;
            this.local = local; this.dataEnd = dataEnd; this.flags = flags;
        }
    }
    private static final class WipingBuffer extends ByteArrayOutputStream {
        void wipe() { Arrays.fill(buf, (byte) 0); reset(); }
    }
}
