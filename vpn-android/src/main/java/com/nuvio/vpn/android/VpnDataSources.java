package com.nuvio.vpn.android;

import android.net.Uri;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.TransferListener;
import android.os.SystemClock;
import com.nuvio.vpn.core.ProtectionMode;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/** Keep the host's original transport outside HTTP-only mode, including its TLS behavior. */
@UnstableApi
public final class VpnDataSources {
    private VpnDataSources() { }
    public static HttpDataSource.Factory route(HttpDataSource.Factory baseline, HttpDataSource.Factory httpOnly) {
        return new HttpDataSource.Factory() {
            @Override public HttpDataSource.Factory setDefaultRequestProperties(Map<String, String> headers) {
                baseline.setDefaultRequestProperties(headers);
                httpOnly.setDefaultRequestProperties(headers);
                return this;
            }
            @Override public HttpDataSource createDataSource() { return new Routed(baseline.createDataSource(), httpOnly.createDataSource()); }
        };
    }
    private static final class Routed implements HttpDataSource {
        private final HttpDataSource baseline, httpOnly;
        private HttpDataSource active;
        private VpnPlaybackDiagnostics.Transfer transfer;
        Routed(HttpDataSource baseline, HttpDataSource httpOnly) { this.baseline = baseline; this.httpOnly = httpOnly; active = baseline; }
        @Override public long open(DataSpec spec) throws HttpDataSourceException {
            if (transfer != null) { transfer.close(); transfer = null; }
            long gateStarted = SystemClock.elapsedRealtime();
            com.nuvio.vpn.core.VpnPreferences cached = VpnPlaybackProtection.cachedSettingsForDiagnostics();
            boolean protectedBeforeGate = cached != null && VpnPlaybackDiagnostics.protectedSource(spec.uri.toString(), cached.getMode());
            boolean gateCompleted = false;
            try {
                VpnPlaybackProtection.beforeRequest(spec.uri.toString());
                gateCompleted = true;
                long gateMs = SystemClock.elapsedRealtime() - gateStarted;
                ProtectionMode mode = VpnPlaybackProtection.settings().getMode();
                active = mode.isPlaybackScoped() ? httpOnly : baseline;
                long openStarted = SystemClock.elapsedRealtime();
                boolean initiallyProtected = VpnPlaybackDiagnostics.protectedSource(spec.uri.toString(), mode);
                if (initiallyProtected)
                    transfer = VpnPlaybackDiagnostics.INSTANCE.mediaOpen(gateMs, openStarted);
                long size = active.open(spec);
                // Redirects can cross HTTP-only scope. Observe the final response URI without
                // changing transport selection, and never let a diagnostic getter fail playback.
                String finalSource = spec.uri.toString();
                try {
                    Uri finalUri = active.getUri();
                    if (finalUri != null) finalSource = finalUri.toString();
                } catch (RuntimeException | LinkageError ignored) { }
                boolean protectedBody = VpnPlaybackDiagnostics.protectedSource(finalSource, mode);
                if (initiallyProtected != protectedBody) VpnPlaybackDiagnostics.INSTANCE.redirected(protectedBody);
                if (protectedBody) {
                    if (transfer == null) transfer = VpnPlaybackDiagnostics.INSTANCE.mediaOpen(gateMs, openStarted);
                } else if (transfer != null) { transfer.close(); transfer = null; }
                if (transfer != null) transfer.opened();
                return size;
            } catch (IOException failure) {
                if (transfer != null) { transfer.openFailed(failure); transfer = null; }
                else if (!gateCompleted && protectedBeforeGate) VpnPlaybackDiagnostics.INSTANCE.gateFailed(SystemClock.elapsedRealtime() - gateStarted, failure);
                throw HttpDataSourceException.createForIOException(failure, spec, HttpDataSourceException.TYPE_OPEN);
            } catch (RuntimeException failure) {
                if (transfer != null) { transfer.openFailed(failure); transfer = null; }
                throw failure;
            }
        }
        @Override public int read(byte[] buffer, int offset, int length) throws HttpDataSourceException {
            VpnPlaybackDiagnostics.Transfer observed = transfer;
            if (observed == null) return active.read(buffer, offset, length);
            long since = observed.readStarted();
            int count = 0;
            Throwable failure = null;
            try { count = active.read(buffer, offset, length); return count; }
            catch (HttpDataSourceException | RuntimeException error) { failure = error; throw error; }
            finally { observed.readFinished(since, count, failure); }
        }
        @Override public void close() throws HttpDataSourceException {
            try { active.close(); }
            finally { if (transfer != null) { transfer.close(); transfer = null; } }
        }
        @Override public Uri getUri() { return active.getUri(); }
        @Override public int getResponseCode() { return active.getResponseCode(); }
        @Override public Map<String, List<String>> getResponseHeaders() { return active.getResponseHeaders(); }
        @Override public void addTransferListener(TransferListener listener) { baseline.addTransferListener(listener); httpOnly.addTransferListener(listener); }
        @Override public void setRequestProperty(String name, String value) { baseline.setRequestProperty(name, value); httpOnly.setRequestProperty(name, value); }
        @Override public void clearRequestProperty(String name) { baseline.clearRequestProperty(name); httpOnly.clearRequestProperty(name); }
        @Override public void clearAllRequestProperties() { baseline.clearAllRequestProperties(); httpOnly.clearAllRequestProperties(); }
    }
}
