package com.nuvio.bench;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteCursor;
import android.database.sqlite.SQLiteStatement;
import android.os.Build;
import android.os.Debug;
import android.os.SystemClock;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Android SQLite query/decoding reproduction, not a whole-app UI/latency benchmark. */
public final class GuideQueryBenchmark {
    static final long HOUR = 3_600_000L;
    static final long HALF_HOUR = HOUR / 2;
    static final long TILE = 4 * HOUR;
    static final long NOW = Math.floorDiv(1_800_000_000_000L, TILE) * TILE;
    static final int PAST_HOURS = 12;
    static final int FUTURE_DAYS = 3;
    static final int SLOTS = (PAST_HOURS + FUTURE_DAYS * 24) * 2;
    static final Comparator<Program> BY_START = new Comparator<Program>() {
        public int compare(Program a, Program b) { return Long.compare(a.start, b.start); }
    };
    static Object retained;
    static long visited, decoded, queries;
    static Cursor prepareCursor(Cursor cursor) {
        if (Boolean.getBoolean("nuvio.benchmark.forwardOnly") && Build.VERSION.SDK_INT >= 28 && cursor instanceof SQLiteCursor)
            ((SQLiteCursor) cursor).setFillWindowForwardOnly(true);
        return cursor;
    }

    // Same fields/defaults as EpgProgram; explicit Java reproduction avoids loading an app/DI.
    static final class Program {
        final long start, stop;
        final String title, description, category, episode, icon;
        final Integer year;
        final List<String> people;
        Program(Cursor c) {
            start = c.getLong(1); stop = c.getLong(2); title = c.getString(3);
            episode = c.isNull(4) ? null : c.getString(4);
            icon = c.isNull(5) ? null : c.getString(5);
            description = null; category = null; year = null; people = Collections.emptyList();
            decoded++;
        }
    }

    static String key(int channel) { return "channel-" + channel; }
    static List<String> keys(int first, int count) {
        ArrayList<String> result = new ArrayList<>();
        for (int i = first; i < first + count; i++) result.add(key(i));
        return result;
    }
    static void gc() throws InterruptedException {
        System.gc(); System.runFinalization(); System.gc(); Thread.sleep(60);
    }
    static long heap() { return Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory(); }
    static void add(Map<String, List<Program>> out, String key, Program program) {
        List<Program> list = out.get(key);
        if (list == null) { list = new ArrayList<>(); out.put(key, list); }
        list.add(program);
    }

    /** Exact light range() SQL, channel batching and sorting from baseline 1049818f. */
    static Map<String, List<Program>> range(SQLiteDatabase db, long from, long to, List<String> channels) {
        Map<String, List<Program>> out = new HashMap<>();
        if (channels == null) readRange(db, out, from, to, null);
        else for (int i = 0; i < channels.size(); i += 400)
            readRange(db, out, from, to, channels.subList(i, Math.min(i + 400, channels.size())));
        for (List<Program> programs : out.values()) Collections.sort(programs, BY_START);
        return out;
    }
    static void readRange(SQLiteDatabase db, Map<String, List<Program>> out, long from, long to, List<String> channels) {
        StringBuilder filter = new StringBuilder();
        ArrayList<String> args = new ArrayList<>();
        if (channels != null) {
            filter.append("ch IN (");
            for (String channel : channels) {
                if (!args.isEmpty()) filter.append(',');
                filter.append('?'); args.add(channel);
            }
            filter.append(") AND");
        }
        args.add(Long.toString(from - 6 * HOUR)); args.add(Long.toString(to)); args.add(Long.toString(from));
        String sql = "SELECT ch, start, stop, title, ep, icon FROM programs WHERE " + filter + " start >= ? AND start < ? AND stop > ?";
        queries++;
        try (Cursor cursor = prepareCursor(db.rawQuery(sql, args.toArray(new String[0])))) {
            while (cursor.moveToNext()) { visited++; add(out, cursor.getString(0), new Program(cursor)); }
        }
    }

    /** Exact current nowNext() SQL and two nonoverlapping-listing decoder. */
    static Map<String, List<Program>> nowNext(SQLiteDatabase db) {
        Map<String, List<Program>> out = new HashMap<>();
        queries++;
        try (Cursor cursor = prepareCursor(db.rawQuery(
            "SELECT ch, start, stop, title, ep, icon FROM programs WHERE start >= ? AND start < ? AND stop > ? ORDER BY start",
            new String[]{Long.toString(NOW - 6 * HOUR), Long.toString(NOW + 12 * HOUR), Long.toString(NOW)}))) {
            while (cursor.moveToNext()) {
                visited++;
                String key = cursor.getString(0);
                List<Program> list = out.get(key);
                if (list == null) { list = new ArrayList<>(2); out.put(key, list); }
                if (list.size() >= 2) continue;
                long start = cursor.getLong(1);
                if (!list.isEmpty() && start < list.get(0).stop) continue;
                list.add(new Program(cursor));
            }
        }
        return out;
    }

    static Map<String, List<Program>> baseline(SQLiteDatabase db, int count) {
        Map<String, List<Program>> all = range(db, NOW - 3 * HOUR, NOW + 18 * HOUR, null);
        List<String> channels = keys(1, Math.min(400, count));
        // ensureFullRange() uses 60-channel batches and merges by programme start.
        for (int i = 0; i < channels.size(); i += 60) {
            Map<String, List<Program>> batch = range(db, NOW - HOUR, NOW + FUTURE_DAYS * 24 * HOUR,
                channels.subList(i, Math.min(i + 60, channels.size())));
            for (Map.Entry<String, List<Program>> entry : batch.entrySet()) {
                List<Program> old = all.get(entry.getKey());
                if (old == null) { all.put(entry.getKey(), entry.getValue()); continue; }
                HashSet<Long> starts = new HashSet<>();
                for (Program p : old) starts.add(p.start);
                for (Program p : entry.getValue()) if (starts.add(p.start)) old.add(p);
                Collections.sort(old, BY_START);
            }
        }
        return all;
    }

    static final class Tile {
        final String channel; final long start;
        Tile(String channel, long start) { this.channel = channel; this.start = start; }
        public int hashCode() { return channel.hashCode() * 31 + Long.hashCode(start); }
        public boolean equals(Object other) {
            return other instanceof Tile && channel.equals(((Tile) other).channel) && start == ((Tile) other).start;
        }
    }

    static void request(SQLiteDatabase db, LinkedHashMap<Tile, List<Program>> cache,
                        List<String> channels, long from, long to) {
        for (long start = Math.floorDiv(from, TILE) * TILE; start < to; start += TILE) {
            List<String> missing = new ArrayList<>();
            for (String channel : channels) if (cache.get(new Tile(channel, start)) == null) missing.add(channel);
            for (int i = 0; i < missing.size(); i += 32) {
                List<String> part = missing.subList(i, Math.min(i + 32, missing.size()));
                Map<String, List<Program>> got = range(db, start, start + TILE, part);
                for (String channel : part) {
                    List<Program> programs = got.get(channel);
                    cache.put(new Tile(channel, start), programs == null ? Collections.<Program>emptyList() : programs);
                    while (cache.size() > 384) cache.remove(cache.keySet().iterator().next());
                }
            }
        }
    }
    static Map<String, List<Program>> windowed(SQLiteDatabase db, Map<String, Object> metrics) {
        Map<String, List<Program>> overview = nowNext(db);
        LinkedHashMap<Tile, List<Program>> cache = new LinkedHashMap<>(16, .75f, true);
        List<String> viewport = keys(1, 32);
        // Eight requested hours starting one hour before now: actual 4-hour tiling reads 12h.
        long from = NOW - HOUR, to = NOW + 7 * HOUR;
        request(db, cache, viewport, from, to);
        long queriesBefore = queries, decodedBefore = decoded, hitStart = SystemClock.elapsedRealtimeNanos();
        request(db, cache, viewport, from, to);
        metrics.put("cacheHitMs", (SystemClock.elapsedRealtimeNanos() - hitStart) / 1e6);
        metrics.put("cacheHitQueries", queries - queriesBefore);
        metrics.put("cacheHitDecoded", decoded - decodedBefore);
        metrics.put("tiles", cache.size());
        Map<String, List<Program>> combined = new HashMap<>(overview);
        for (String channel : viewport) {
            ArrayList<Program> programs = new ArrayList<>();
            HashSet<Long> starts = new HashSet<>();
            for (Map.Entry<Tile, List<Program>> entry : cache.entrySet()) if (entry.getKey().channel.equals(channel))
                for (Program p : entry.getValue()) if (starts.add(p.start)) programs.add(p);
            for (Program p : overview.get(channel)) if (starts.add(p.start)) programs.add(p);
            Collections.sort(programs, BY_START); combined.put(channel, programs);
        }
        // Keep overview + tile owners as the actual new repository does, including duplicate
        // owner-held programme objects where the merged display list chooses a tile object.
        metrics.put("ownerRetainedPrograms", count(overview) + decodedBefore - count(overview));
        retained = new Object[]{overview, cache, combined};
        return combined;
    }
    static long count(Map<String, List<Program>> map) {
        long count = 0; for (List<Program> list : map.values()) count += list.size(); return count;
    }

    static void hashRow(MessageDigest digest, String key, long start, long stop, String title, String episode, String icon) {
        for (String value : new String[]{key, Long.toString(start), Long.toString(stop), title,
            episode == null ? "<null>" : episode, icon == null ? "<null>" : icon}) {
            digest.update(value.getBytes(StandardCharsets.UTF_8)); digest.update((byte) 0);
        }
    }
    static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(); for (byte b : bytes) out.append(String.format(java.util.Locale.US, "%02x", b & 255)); return out.toString();
    }
    static String checksum(Map<String, List<Program>> programs) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        ArrayList<String> sorted = new ArrayList<>(programs.keySet()); Collections.sort(sorted);
        for (String channel : sorted) for (Program p : programs.get(channel)) hashRow(digest, channel, p.start, p.stop, p.title, p.episode, p.icon);
        return hex(digest.digest());
    }
    static String expectedWindowChecksum(int channels) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        List<String> sorted = keys(1, channels); Collections.sort(sorted);
        for (String channel : sorted) {
            int id = Integer.parseInt(channel.substring("channel-".length()));
            int first = id <= 32 ? PAST_HOURS * 2 - 8 : PAST_HOURS * 2;
            int end = id <= 32 ? PAST_HOURS * 2 + 16 : PAST_HOURS * 2 + 2;
            for (int slot = first; slot < end; slot++) {
                long at = NOW - PAST_HOURS * HOUR + slot * HALF_HOUR;
                hashRow(digest, channel, at, at + HALF_HOUR,
                    "Synthetic programme " + slot + " - identical schedule on every broadcast alias", null, null);
            }
        }
        return hex(digest.digest());
    }

    static void populate(File path, int channels) {
        if (path.exists()) throw new IllegalStateException("Fixture already exists; use a distinct verified scratch file");
        SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(path, null);
        db.enableWriteAheadLogging();
        db.execSQL("CREATE TABLE programs (ch TEXT NOT NULL, start INTEGER NOT NULL, stop INTEGER NOT NULL, title TEXT NOT NULL, descr TEXT, cat TEXT, ep TEXT, icon TEXT, year INTEGER, people TEXT)");
        long start = SystemClock.elapsedRealtimeNanos();
        db.beginTransaction();
        try (SQLiteStatement insert = db.compileStatement("INSERT INTO programs (ch,start,stop,title,descr,cat) VALUES (?,?,?,?,?,?)")) {
            for (int channel = 1; channel <= channels; channel++) for (int slot = 0; slot < SLOTS; slot++) {
                long at = NOW - PAST_HOURS * HOUR + slot * HALF_HOUR;
                insert.clearBindings(); insert.bindString(1, key(channel)); insert.bindLong(2, at); insert.bindLong(3, at + HALF_HOUR);
                insert.bindString(4, "Synthetic programme " + slot + " - identical schedule on every broadcast alias");
                insert.bindString(5, "Synthetic description retained on disk. No provider, media, credentials or external network. This fixture represents the same complete guide for both query strategies.");
                insert.bindString(6, "Sports"); insert.executeInsert();
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        db.execSQL("CREATE INDEX programs_ch_start ON programs(ch,start)");
        db.execSQL("CREATE INDEX programs_stop ON programs(stop)");
        db.execSQL("CREATE INDEX programs_start ON programs(start)");
        db.close();
        System.out.println("{\"fixtureChannels\":" + channels + ",\"rows\":" + ((long) channels * SLOTS) + ",\"setupMs\":" + (SystemClock.elapsedRealtimeNanos() - start) / 1e6 + ",\"databaseBytes\":" + path.length() + "}");
    }

    public static void main(String[] args) throws Exception {
        File file = new File(args[1]); int channels = Integer.parseInt(args[2]);
        File parent = file.getCanonicalFile().getParentFile();
        if (parent == null || !"/data/local/tmp".equals(parent.getParent()) ||
            !parent.getName().startsWith("nuvio-guide-query-bench-"))
            throw new IllegalArgumentException("Scratch path required");
        if (args[0].equals("create")) { populate(file, channels); return; }
        SQLiteDatabase db = SQLiteDatabase.openDatabase(file.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
        gc(); long heapBefore = heap(), pssBefore = Debug.getPss();
        long start = SystemClock.elapsedRealtimeNanos();
        Map<String, Object> metrics = new LinkedHashMap<>();
        Map<String, List<Program>> result = args[0].equals("baseline") ? baseline(db, channels) : windowed(db, metrics);
        long duration = SystemClock.elapsedRealtimeNanos() - start;
        if (args[0].equals("baseline")) { retained = result; metrics.put("ownerRetainedPrograms", count(result)); }
        metrics.put("mode", args[0]); metrics.put("channels", channels); metrics.put("api", Build.VERSION.SDK_INT);
        metrics.put("rowsOnDisk", ((long) channels * SLOTS)); metrics.put("readDecodeMergeMs", duration / 1e6);
        metrics.put("queries", queries); metrics.put("visitedRows", visited); metrics.put("decodedPrograms", decoded);
        metrics.put("displayRetainedPrograms", count(result));
        gc(); metrics.put("heapDeltaBytes", heap() - heapBefore); metrics.put("pssKiB", Debug.getPss());
        metrics.put("pssBeforeKiB", pssBefore); metrics.put("javaMaxHeapBytes", Runtime.getRuntime().maxMemory());
        metrics.put("architecture", System.getProperty("os.arch")); metrics.put("databaseReadOnly", db.isReadOnly());
        metrics.put("processId", android.os.Process.myPid());
        metrics.put("cursorConfiguration", Boolean.getBoolean("nuvio.benchmark.forwardOnly") ? "forward_only_api28" : "default");
        metrics.put("displayChecksum", checksum(result));
        if (!args[0].equals("baseline")) {
            String expected = expectedWindowChecksum(channels);
            metrics.put("expectedWindowChecksum", expected);
            metrics.put("outputMatchesExpected", expected.equals(metrics.get("displayChecksum")));
        }
        try (Cursor records = db.rawQuery("SELECT COUNT(*) FROM programs", null)) {
            if (records.moveToFirst()) metrics.put("actualRowsOnDisk", records.getLong(0));
        }
        try (Cursor version = db.rawQuery("SELECT sqlite_version()", null)) {
            if (version.moveToFirst()) metrics.put("sqliteVersion", version.getString(0));
        }
        // This query is deliberately AFTER timing/memory: an unseen last channel remains on disk.
        Map<String, List<Program>> neverBrowsed = range(db, NOW, NOW + HOUR, keys(channels, 1));
        metrics.put("neverBrowsedLastChannelPrograms", count(neverBrowsed));
        db.close(); System.out.println(new JSONObject(metrics).toString());
    }
}
