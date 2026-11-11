package com.nuvio.bench.norm20261111;

import android.os.Build;
import android.os.Debug;
import android.os.Process;
import android.os.SystemClock;
import com.nuvio.tv.livetv.sports.SportsCatalog;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import org.json.JSONObject;

/** Actual source-compiled SportsCatalog component, never an Activity/app RAM benchmark. */
public final class NormBenchmark {
    private static final int GUIDES = 4000, SLOTS = 24, WARMUP_CALLS = 4096;
    private static final long MEASURE_BUDGET_NS = 90_000_000_000L;
    private static final long WHOLE_BUDGET_NS = 120_000_000_000L;
    private static final String DESCRIPTION = "Programme information exercising descriptions, Unicode café İstanbul Σports, shared guide bindings, Search and Sports.";
    private static volatile long sink;

    private static long stat(String name) {
        String value = Debug.getRuntimeStat(name);
        if (value == null) return -1;
        try { return Long.parseLong(value); }
        catch (NumberFormatException unsupported) { return -1; }
    }

    private static JSONObject counters() throws Exception {
        JSONObject value = new JSONObject();
        for (String key : new String[]{"gc-count", "blocking-gc-count", "gc-time", "blocking-gc-time", "bytes-allocated", "bytes-freed"})
            value.put(key, stat("art.gc." + key));
        return value;
    }

    private static JSONObject heap() throws Exception {
        Runtime r = Runtime.getRuntime();
        long total = r.totalMemory(), free = r.freeMemory();
        return new JSONObject().put("used_bytes", total - free).put("total_bytes", total)
            .put("free_bytes", free).put("max_bytes", r.maxMemory());
    }

    private static void budget(long start, long allowance) {
        if (SystemClock.elapsedRealtimeNanos() - start > allowance)
            throw new IllegalStateException("component-budget-exceeded");
    }

    private static void hashAscii(MessageDigest digest, String value, byte[] scratch) {
        if (value.length() > scratch.length) throw new IllegalStateException("Unexpected normalized length");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c > 127) throw new IllegalStateException("Normalization result was not ASCII");
            scratch[i] = (byte)c;
        }
        digest.update(scratch, 0, value.length());
        digest.update((byte)'\n');
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) result.append(String.format(Locale.ROOT, "%02x", b & 255));
        return result.toString();
    }

    public static void main(String[] args) throws Exception {
        long wholeStart = SystemClock.elapsedRealtimeNanos();
        JSONObject result = new JSONObject().put("role", SourcePin.ROLE)
            .put("catalog_source_sha256", SourcePin.SOURCE_SHA256)
            .put("dependency_apk_sha256", SourcePin.APK_SHA256)
            .put("method", "actual-SportsCatalog-norm-Android-component")
            .put("pid", Process.myPid()).put("uid", Process.myUid())
            .put("sdk_int", Build.VERSION.SDK_INT).put("runtime_version", System.getProperty("java.vm.version"));
        try {
            if (args.length != 2 || !args[0].equals(SourcePin.ROLE) || !args[1].equals(SourcePin.SOURCE_SHA256))
                throw new IllegalArgumentException("Pinned role/source arguments required");
            if (Process.myUid() != 2000) throw new IllegalStateException("Expected fresh shell app_process UID");
            String cp = System.getProperty("java.class.path");
            if (cp == null || !cp.matches("/data/local/tmp/nuvio-sports-norm-20261111-[a-z0-9-]+/" + SourcePin.ROLE
                    + "\\.jar:/data/local/tmp/nuvio-sports-norm-20261111-[a-z0-9-]+/dependency84\\.apk"))
                throw new IllegalStateException("Expected override DEX first, pinned APK second");
            if (SportsCatalog.class.getClassLoader() != NormBenchmark.class.getClassLoader())
                throw new IllegalStateException("Catalog/harness loader differs");
            int regexTailFields = 0;
            for (Field field : SportsCatalog.class.getDeclaredFields())
                if (field.getName().equals("NOT_WORD") || field.getName().equals("SPACES")) regexTailFields++;
            if (regexTailFields != SourcePin.REGEX_TAIL_FIELDS)
                throw new IllegalStateException("Catalog definition does not match source role");
            result.put("catalog_regex_tail_fields", regexTailFields).put("class_path", cp)
                .put("catalog_loader", SportsCatalog.class.getClassLoader().toString());

            String[] titles = new String[32 * SLOTS], expected = new String[titles.length];
            for (int team = 0; team < 32; team++) for (int slot = 0; slot < SLOTS; slot++) {
                int n = team * SLOTS + slot;
                titles[n] = "Team " + team + " vs Team " + ((team + slot) % 32) + " (1999)";
                expected[n] = SportsCatalog.INSTANCE.norm(titles[n]);
            }
            String expectedDescription = SportsCatalog.INSTANCE.norm(DESCRIPTION);
            // Every distinct fixture input is evaluated using this exact Catalog implementation.
            // Hash the full 192,000-result sequence outside the timed pass with a reused buffer.
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] scratch = new byte[512];
            for (int guide = 0; guide < GUIDES; guide++) for (int slot = 0; slot < SLOTS; slot++) {
                hashAscii(digest, expected[(guide % 32) * SLOTS + slot], scratch);
                hashAscii(digest, expectedDescription, scratch);
            }
            String outputSha = hex(digest.digest());
            for (int call = 0; call < WARMUP_CALLS; call++) {
                String input = (call & 1) == 0 ? titles[(call / 2) % titles.length] : DESCRIPTION;
                sink += SportsCatalog.INSTANCE.norm(input).length();
                if ((call & 1023) == 0) budget(wholeStart, WHOLE_BUDGET_NS);
            }
            JSONObject heapBefore = heap(), countersBefore = counters();
            long allocatedBefore = stat("art.gc.bytes-allocated");
            long begin = SystemClock.elapsedRealtimeNanos(), checkedCharacters = 0;
            int calls = 0;
            for (int guide = 0; guide < GUIDES; guide++) {
                for (int slot = 0; slot < SLOTS; slot++) {
                    int n = (guide % 32) * SLOTS + slot;
                    String a = SportsCatalog.INSTANCE.norm(titles[n]);
                    String b = SportsCatalog.INSTANCE.norm(DESCRIPTION);
                    if (!a.equals(expected[n]) || !b.equals(expectedDescription))
                        throw new IllegalStateException("Measured result differs from semantic pass");
                    checkedCharacters += a.length() + b.length();
                    calls += 2;
                }
                if ((guide & 31) == 0) {
                    budget(begin, MEASURE_BUDGET_NS);
                    budget(wholeStart, WHOLE_BUDGET_NS);
                }
            }
            long elapsed = SystemClock.elapsedRealtimeNanos() - begin;
            long allocatedAfter = stat("art.gc.bytes-allocated");
            JSONObject heapAfter = heap(), countersAfter = counters();
            sink = checkedCharacters;
            if (calls != 192000) throw new IllegalStateException("Measured call count differs");
            result.put("complete", true).put("elapsed_ns", elapsed).put("measured_calls", calls)
                .put("warmup_calls", WARMUP_CALLS).put("untimed_semantic_calls", titles.length + 1)
                .put("description_utf16_chars", DESCRIPTION.length()).put("checked_characters", checkedCharacters)
                .put("output_sha256", outputSha).put("heap_before", heapBefore).put("heap_after", heapAfter)
                .put("counters_before", countersBefore).put("counters_after", countersAfter)
                .put("allocated_bytes_before", allocatedBefore).put("allocated_bytes_after", allocatedAfter)
                .put("allocated_bytes_supported", allocatedBefore >= 0 && allocatedAfter >= allocatedBefore)
                .put("allocated_bytes_delta", allocatedBefore >= 0 && allocatedAfter >= allocatedBefore ? allocatedAfter - allocatedBefore : -1)
                .put("timed_work", "192000 actual norm calls plus exact String.equals validation; SHA outside timing")
                .put("explicit_gc_requested", false).put("app_ram_or_pss_measurement", false);
        } catch (Throwable error) {
            result.put("complete", false).put("error_class", error.getClass().getSimpleName())
                .put("error_category", "component-budget-exceeded".equals(error.getMessage()) ? "budget" : "validation-or-runtime")
                .put("whole_elapsed_ns", SystemClock.elapsedRealtimeNanos() - wholeStart);
        }
        System.out.println(result.toString());
    }
}
