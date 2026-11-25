package com.nuvio.tv.livetv.sports;

import androidx.annotation.Keep;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CancellationException;

/** Bounded ASCII candidate filtering. Managed matching still determines the final results. */
@Keep
final class SportsTextNative {
    private static final boolean LOADED = load();
    private static final AtomicLong completedBatches = new AtomicLong();

    private SportsTextNative() {}

    private static boolean load() {
        try { System.loadLibrary("nuviosportstext"); return true; }
        catch (RuntimeException | LinkageError unavailable) { return false; }
    }

    static boolean available() { return LOADED; }
    static long completedBatches() { return completedBatches.get(); }

    interface Kernel extends AutoCloseable {
        Batch filter(char[] packed, int used, int[] offsets, int[] titleLengths,
                     int[] descriptionLengths, int rows);
        @Override void close();
    }

    static final class Batch {
        final int[] rows;
        final String[] titles;
        final String[] descriptions;
        Batch(int[] rows, String[] titles, String[] descriptions) {
            this.rows = rows; this.titles = titles; this.descriptions = descriptions;
        }
    }

    static Kernel open(String[] terms) {
        if (!LOADED) return null;
        try {
            // Allocate the Java owner before nativeCreate can publish a live handle.
            Session session = new Session(0);
            session.handle = nativeCreate(terms);
            return session.handle == 0 ? null : session;
        } catch (CancellationException cancelled) { throw cancelled;
        } catch (RuntimeException | LinkageError | OutOfMemoryError unavailable) {
            return null;
        }
    }

    private static final class Session implements Kernel {
        private long handle;
        Session(long handle) { this.handle = handle; }

        @Override public synchronized Batch filter(char[] packed, int used, int[] offsets,
                                                   int[] titleLengths, int[] descriptionLengths, int rows) {
            if (handle == 0) return null;
            try {
                Object[] value = nativeBatch(handle, packed, used, offsets, titleLengths, descriptionLengths, rows);
                if (value == null || value.length != 3 || !(value[0] instanceof int[])
                        || !(value[1] instanceof String[]) || !(value[2] instanceof String[])) {
                    close(); return null;
                }
                Batch batch = new Batch((int[]) value[0], (String[]) value[1], (String[]) value[2]);
                if (batch.rows.length != batch.titles.length || batch.rows.length != batch.descriptions.length
                        || batch.rows.length > rows) { close(); return null; }
                int previous = -1;
                for (int i = 0; i < batch.rows.length; i++) {
                    int row = batch.rows[i];
                    if (row <= previous || row >= rows || batch.titles[i] == null || batch.descriptions[i] == null
                            || batch.titles[i].length() > titleLengths[row] * 4 + 2
                            || batch.descriptions[i].length() > descriptionLengths[row] * 4 + 2) {
                        close(); return null;
                    }
                    previous = row;
                }
                completedBatches.incrementAndGet();
                return batch;
            } catch (CancellationException cancelled) { throw cancelled;
            } catch (RuntimeException | LinkageError | OutOfMemoryError unavailable) {
                close(); return null;
            }
        }

        @Override public synchronized void close() {
            long closing = handle;
            handle = 0;
            if (closing != 0) {
                try { nativeDestroy(closing); }
                catch (RuntimeException | LinkageError ignored) { }
            }
        }
    }

    private static native long nativeCreate(String[] terms);
    private static native void nativeDestroy(long handle);
    /** Object[]{int[] retained row indices, String[] normalized titles, String[] normalized descriptions}. */
    private static native Object[] nativeBatch(long handle, char[] packed, int used, int[] offsets,
                                              int[] titleLengths, int[] descriptionLengths, int rows);
}
