package com.nuvio.vpn.android;

import static org.junit.Assert.*;
import android.content.Context;
import android.net.Uri;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.TransferListener;
import com.nuvio.vpn.core.ProtectionMode;
import com.nuvio.vpn.core.VpnPreferences;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSystemClock;

/** Real routing/gate wrapper with synthetic transports; no native engine or provider connection. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public final class VpnDataSourceDiagnosticsTest {
    private static final String ID = "bb6c6284-1068-444a-a239-70090a64771b";
    private ExecutorService io;
    private Context context;

    @Before public void setup() throws Exception {
        io = Executors.newSingleThreadExecutor();
        context = RuntimeEnvironment.getApplication();
        VpnPlaybackProtection.initialize(context);
        Field worker = VpnPlaybackProtection.class.getDeclaredField("worker"); worker.setAccessible(true);
        ((ExecutorService) worker.get(null)).submit(() -> {}).get(2, TimeUnit.SECONDS);
        VpnPlaybackProtection.setActiveUserProfile(1);
        set(VpnConnectionService.class, "running", true);
        set(VpnConnectionService.class, "requestedProfile", ID);
        mode(ProtectionMode.HTTP_ONLY);
        VpnPlaybackDiagnostics.INSTANCE.resetConnection();
        assertNull(sample());
        ShadowSystemClock.advanceBy(Duration.ofMillis(1));
    }
    @After public void cleanup() throws Exception {
        set(VpnConnectionService.class, "running", false);
        io.shutdownNow();
    }

    @Test public void protectedMediaCountsOneOpenAndExactBytesWithoutLoggingTheSource() throws Exception {
        Fake baseline = new Fake(), protectedData = new Fake();
        HttpDataSource source = VpnDataSources.route(factory(baseline), factory(protectedData)).createDataSource();
        onIo(() -> {
            assertEquals(-1L, source.open(spec("http://private.example/stream?token=never-log-this")));
            byte[] destination = new byte[3];
            assertEquals(3, source.read(destination, 0, 3));
            assertArrayEquals(new byte[] {1, 2, 3}, destination);
            assertEquals(-1, source.read(destination, 0, 3));
            assertEquals(-1, source.read(destination, 0, 3));
            source.close(); source.close(); return null;
        });
        ShadowSystemClock.advanceBy(Duration.ofSeconds(10));
        String line = sample();
        assertTrue(line, line.contains("media_opens=1"));
        assertTrue(line, line.contains("media_bytes=3"));
        assertTrue(line, line.contains("media_eof=1"));
        assertTrue(line, line.contains("media_sources=0"));
        assertFalse(line, line.contains("private.example"));
        assertFalse(line, line.contains("never-log-this"));
        assertEquals(0, baseline.opens); assertEquals(1, protectedData.opens);
    }

    @Test public void httpsInHttpOnlyModeDoesNotAppearAsProtectedMedia() throws Exception {
        for (String unresolved : new String[] {null, "", "  "})
            assertEquals("HTTP_ONLY/other/unknown", VpnPlaybackDiagnostics.playbackSample(unresolved));
        Fake data = new Fake();
        HttpDataSource source = VpnDataSources.route(factory(new Fake()), factory(data)).createDataSource();
        onIo(() -> { source.open(spec("https://private.example/movie")); source.read(new byte[3], 0, 3); source.close(); return null; });
        assertEquals("HTTP_ONLY/https/direct", VpnPlaybackDiagnostics.playbackSample("https://private.example/movie"));
        ShadowSystemClock.advanceBy(Duration.ofSeconds(10));
        assertNull(sample());
    }

    @Test public void failedReadAndCloseKeepOriginalExceptionsAndReleaseTheMediaGauge() throws Exception {
        Fake data = new Fake(); data.readTimeout = true; data.closeFails = true;
        HttpDataSource source = VpnDataSources.route(factory(new Fake()), factory(data)).createDataSource();
        onIo(() -> {
            source.open(spec("http://private.example/stream?token=never-log-this"));
            assertSame(data.readError, assertThrows(HttpDataSource.HttpDataSourceException.class, () -> source.read(new byte[3], 0, 3)));
            assertSame(data.closeError, assertThrows(HttpDataSource.HttpDataSourceException.class, source::close));
            return null;
        });
        ShadowSystemClock.advanceBy(Duration.ofSeconds(10));
        String line = sample();
        assertTrue(line, line.contains("media_read_fail=1"));
        assertTrue(line, line.contains("media_timeouts=1"));
        assertTrue(line, line.contains("io_kind=timeout"));
        assertTrue(line, line.contains("media_sources=0"));
        assertFalse(line, line.contains("never-log-this"));
    }

    @Test public void redirectsCountTheFinalBodyScopeAndRetainTheRealOpenDuration() throws Exception {
        for (boolean becomesProtected : new boolean[] {false, true}) {
            VpnPlaybackDiagnostics.INSTANCE.resetConnection(); assertNull(sample());
            ShadowSystemClock.advanceBy(Duration.ofMillis(1));
            String original = (becomesProtected ? "https" : "http") + "://private.example/start?token=never-log-this";
            Fake data = new Fake(); data.openDelayMs = 250;
            data.redirected = Uri.parse((becomesProtected ? "http" : "https") + "://private.example/body?token=never-log-this");
            HttpDataSource source = VpnDataSources.route(factory(new Fake()), factory(data)).createDataSource();
            onIo(() -> { assertEquals(-1L, source.open(spec(original))); readBody(source); return null; });
            ShadowSystemClock.advanceBy(Duration.ofSeconds(10));
            String line = sample();
            assertTrue(line, line.contains("media_bytes=" + (becomesProtected ? 3 : 0)));
            assertTrue(line, line.contains("media_opening=0"));
            assertTrue(line, line.contains("media_sources=0"));
            assertTrue(line, line.contains("media_redirect_direct=" + (becomesProtected ? 0 : 1)));
            assertTrue(line, line.contains("media_redirect_protected=" + (becomesProtected ? 1 : 0)));
            if (becomesProtected) assertTrue(line, line.contains("open_max_ms=250"));
            assertFalse(line, line.contains("never-log-this"));
            assertEquals("HTTP_ONLY/" + (becomesProtected ? "https/direct" : "http/protected"),
                    VpnPlaybackDiagnostics.playbackSample(original));
            assertEquals(1, data.opens);
        }
    }

    @Test public void aMissingOrFailingFinalUriGetterUsesTheOriginalScopeWithoutChangingPlayback() throws Exception {
        for (int getter = 0; getter < 3; getter++) {
            VpnPlaybackDiagnostics.INSTANCE.resetConnection(); assertNull(sample());
            ShadowSystemClock.advanceBy(Duration.ofMillis(1));
            Fake data = new Fake(); data.nullUri = getter == 0;
            if (getter == 1) data.getterFailure = new IllegalStateException("never-log-this");
            if (getter == 2) data.getterFailure = new NoClassDefFoundError("never-log-this");
            HttpDataSource source = VpnDataSources.route(factory(new Fake()), factory(data)).createDataSource();
            onIo(() -> {
                assertEquals(-1L, source.open(spec("http://private.example/stream?token=never-log-this")));
                if (data.getterFailure != null)
                    assertSame(data.getterFailure, assertThrows(Throwable.class, source::getUri));
                else assertNull(source.getUri());
                readBody(source); return null;
            });
            ShadowSystemClock.advanceBy(Duration.ofSeconds(10));
            String line = sample();
            assertTrue(line, line.contains("media_bytes=3"));
            assertTrue(line, line.contains("media_open_fail=0"));
            assertTrue(line, line.contains("media_redirect_direct=0"));
            assertTrue(line, line.contains("media_redirect_protected=0"));
            assertFalse(line, line.contains("never-log-this"));
        }
    }

    private static void readBody(HttpDataSource source) throws HttpDataSource.HttpDataSourceException {
        byte[] bytes = new byte[3];
        assertEquals(3, source.read(bytes, 0, bytes.length));
        assertArrayEquals(new byte[] {1, 2, 3}, bytes);
        assertEquals(-1, source.read(bytes, 0, bytes.length)); source.close();
    }

    private void mode(ProtectionMode mode) throws Exception {
        new AndroidVpnSettings(context).save(new VpnPreferences(mode, ID));
        VpnPlaybackProtection.settings();
        set(VpnConnectionService.class, "requestedMode", mode);
        set(VpnConnectionService.class, "current", new VpnConnectionService.Snapshot(VpnConnectionService.Phase.CONNECTED, 1, 1));
    }
    private <T> T onIo(Callable<T> work) throws Exception { return io.submit(work).get(5, TimeUnit.SECONDS); }
    private static String sample() { return VpnPlaybackDiagnostics.INSTANCE.sample(100, 10, 1, "HTTP_ONLY", "openvpn3", "CONNECTED", 10_000); }
    private static DataSpec spec(String source) { return new DataSpec(Uri.parse(source)); }
    private static void set(Class<?> owner, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name); field.setAccessible(true); field.set(null, value);
    }
    private static HttpDataSource.Factory factory(Fake value) {
        return new HttpDataSource.Factory() {
            @Override public HttpDataSource createDataSource() { return value; }
            @Override public HttpDataSource.Factory setDefaultRequestProperties(Map<String, String> headers) { return this; }
        };
    }
    private static final class Fake implements HttpDataSource {
        int opens, reads; boolean readTimeout, closeFails, nullUri;
        long openDelayMs;
        Uri redirected;
        Throwable getterFailure;
        DataSpec request;
        HttpDataSourceException readError, closeError;
        @Override public long open(DataSpec spec) {
            opens++; request = spec;
            readError = HttpDataSourceException.createForIOException(new SocketTimeoutException("never-log-this"), spec, HttpDataSourceException.TYPE_READ);
            closeError = HttpDataSourceException.createForIOException(new IOException("never-log-this"), spec, HttpDataSourceException.TYPE_CLOSE);
            if (openDelayMs > 0) ShadowSystemClock.advanceBy(Duration.ofMillis(openDelayMs));
            return -1;
        }
        @Override public int read(byte[] buffer, int offset, int length) throws HttpDataSourceException {
            if (readTimeout) throw readError;
            if (reads++ > 0) return -1;
            buffer[offset] = 1; buffer[offset + 1] = 2; buffer[offset + 2] = 3; return 3;
        }
        @Override public void close() throws HttpDataSourceException { if (closeFails) throw closeError; }
        @Override public Uri getUri() {
            if (getterFailure instanceof RuntimeException) throw (RuntimeException) getterFailure;
            if (getterFailure instanceof LinkageError) throw (LinkageError) getterFailure;
            return nullUri ? null : redirected != null ? redirected : request == null ? null : request.uri;
        }
        @Override public int getResponseCode() { return 200; }
        @Override public Map<String, List<String>> getResponseHeaders() { return Collections.emptyMap(); }
        @Override public void addTransferListener(TransferListener listener) { }
        @Override public void setRequestProperty(String name, String value) { }
        @Override public void clearRequestProperty(String name) { }
        @Override public void clearAllRequestProperties() { }
    }
}
