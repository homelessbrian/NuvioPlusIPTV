package com.nuvio.bench.ram20261110;

import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Debug;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;

/** Private observation helper: runs inside the unchanged target application's VM. */
public final class RamInstrumentation extends Instrumentation {
    private static final String TARGET = "com.nuvio.livetv.groupvpnpreview";
    private static final String MAIN = "com.nuvio.tv.MainActivity";
    private static final String CSV_NAME = "guide_ram_nogc_20261110.csv";
    private static final String TAG = "GuideRamNoGc";
    private static final long PERIOD_NS = 1_000_000_000L;
    private static final int DEFAULT_SECONDS = 600;
    private static final int MAX_SECONDS = 1800;
    private volatile boolean running;
    private volatile long mainLaunchRequestedNs;
    private Thread logger;
    private Context target;

    @Override public void onCreate(Bundle arguments) {
        final long origin = SystemClock.elapsedRealtimeNanos();
        target = getTargetContext();
        if (!TARGET.equals(target.getPackageName())
                || target.getApplicationInfo().uid != Process.myUid()) {
            throw new SecurityException("Target package/VM UID mismatch");
        }
        int seconds = DEFAULT_SECONDS;
        if (arguments != null && arguments.containsKey("duration_seconds")) {
            seconds = Integer.parseInt(arguments.getString("duration_seconds"));
        }
        if (seconds < 2 || seconds > MAX_SECONDS) {
            throw new IllegalArgumentException("duration_seconds must be 2..1800");
        }
        final BufferedWriter csv;
        try {
            File cache = target.getCacheDir().getCanonicalFile();
            File output = new File(cache, CSV_NAME);
            // CREATE_NEW refuses an existing file or link. No caller-controlled output path.
            csv = Files.newBufferedWriter(output.toPath(), StandardCharsets.US_ASCII,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            csv.write("sample,elapsed_realtime_ns,uptime_ms,pid,uid,runtime_total_bytes,"
                    + "runtime_free_bytes,runtime_max_bytes,runtime_used_bytes,debug_memory_total_pss_kib,"
                    + "resident_component_pss_kib,swap_pss_derived_kib,swap_total_kib,"
                    + "has_swapped_out_pss_hidden,dalvik_pss_kib,native_pss_kib,other_pss_kib,"
                    + "native_heap_allocated_bytes,native_heap_size_bytes,native_heap_free_bytes,"
                    + "gc_count_before,gc_blocking_count_before,gc_time_ms_before,"
                    + "gc_blocking_time_ms_before,gc_count_after,gc_blocking_count_after,"
                    + "gc_time_ms_after,gc_blocking_time_ms_after,sample_end_elapsed_ns,"
                    + "sample_duration_ns,since_oncreate_ns,main_launch_requested_elapsed_ns\n");
            csv.flush();
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot exclusively create fixed cache CSV", failure);
        }
        final int sampleLimit = seconds;
        running = true;
        logger = new Thread(() -> log(csv, origin, sampleLimit), "guide-ram-nogc-1hz");
        logger.setDaemon(true);
        logger.start();
        // Only dispatch onStart. Automatic performance snapshots/profiling are never enabled.
        start();
    }

    @Override public void onStart() {
        Intent intent = Intent.makeMainActivity(new ComponentName(TARGET, MAIN));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        mainLaunchRequestedNs = SystemClock.elapsedRealtimeNanos();
        target.startActivity(intent);
        // No finish(): the target remains instrumented until the operator force-stops it.
    }

    private void log(BufferedWriter csv, long origin, int sampleLimit) {
        try (BufferedWriter output = csv) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            Runtime runtime = Runtime.getRuntime();
            Debug.MemoryInfo memory = new Debug.MemoryInfo();
            StringBuilder line = new StringBuilder(768);
            char[] row = new char[1024];
            long scheduled = origin;
            long deadline = origin + sampleLimit * PERIOD_NS;
            for (int sample = 0; running && scheduled < deadline; sample++) {
                long remaining;
                while (running && (remaining = scheduled - SystemClock.elapsedRealtimeNanos()) > 0) {
                    Thread.sleep(remaining / 1_000_000L, (int) (remaining % 1_000_000L));
                }
                if (!running) break;
                long begin = SystemClock.elapsedRealtimeNanos();
                if (begin >= deadline) break;
                long uptime = SystemClock.uptimeMillis();
                long gcBefore = stat("art.gc.gc-count");
                long blockingBefore = stat("art.gc.blocking-gc-count");
                long gcTimeBefore = stat("art.gc.gc-time");
                long blockingTimeBefore = stat("art.gc.blocking-gc-time");
                long total = runtime.totalMemory();
                long free = runtime.freeMemory();
                long max = runtime.maxMemory();
                Debug.getMemoryInfo(memory);
                long totalPss = memory.getTotalPss();
                long residentPss = (long) memory.dalvikPss + memory.nativePss + memory.otherPss;
                // Android16 getTotalPss includes SwapPSS; the individual PSS fields do not.
                long swapPss = totalPss - residentPss;
                long swapTotal = parse(memory.getMemoryStat("summary.total-swap"));
                long nativeAllocated = Debug.getNativeHeapAllocatedSize();
                long nativeSize = Debug.getNativeHeapSize();
                long nativeFree = Debug.getNativeHeapFreeSize();
                long gcAfter = stat("art.gc.gc-count");
                long blockingAfter = stat("art.gc.blocking-gc-count");
                long gcTimeAfter = stat("art.gc.gc-time");
                long blockingTimeAfter = stat("art.gc.blocking-gc-time");
                long end = SystemClock.elapsedRealtimeNanos();
                line.setLength(0);
                line.append(sample).append(',').append(begin).append(',').append(uptime)
                        .append(',').append(Process.myPid()).append(',').append(Process.myUid())
                        .append(',').append(total).append(',').append(free).append(',').append(max)
                        .append(',').append(total - free).append(',').append(totalPss)
                        .append(',').append(residentPss).append(',').append(swapPss)
                        .append(',').append(swapTotal).append(",-1")
                        .append(',').append(memory.dalvikPss).append(',').append(memory.nativePss)
                        .append(',').append(memory.otherPss)
                        .append(',').append(nativeAllocated).append(',').append(nativeSize)
                        .append(',').append(nativeFree).append(',').append(gcBefore)
                        .append(',').append(blockingBefore).append(',').append(gcTimeBefore)
                        .append(',').append(blockingTimeBefore).append(',').append(gcAfter)
                        .append(',').append(blockingAfter).append(',').append(gcTimeAfter)
                        .append(',').append(blockingTimeAfter).append(',').append(end)
                        .append(',').append(end - begin).append(',').append(begin - origin)
                        .append(',').append(mainLaunchRequestedNs).append('\n');
                line.getChars(0, line.length(), row, 0);
                output.write(row, 0, line.length());
                output.flush();
                // Skip missed ticks instead of emitting bursts to catch up after a long read.
                long afterWrite = SystemClock.elapsedRealtimeNanos();
                scheduled = origin + ((afterWrite - origin) / PERIOD_NS + 1) * PERIOD_NS;
            }
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        } catch (IOException failure) {
            Log.e(TAG, "CSV logging stopped after write failure", failure);
        } finally {
            running = false;
        }
    }

    private static long stat(String key) {
        return parse(Debug.getRuntimeStat(key));
    }

    private static long parse(String value) {
        if (value == null) return -1L;
        try { return Long.parseLong(value); }
        catch (NumberFormatException unsupported) { return -1L; }
    }

    @Override public void onDestroy() {
        running = false;
        if (logger != null) logger.interrupt();
    }
}
