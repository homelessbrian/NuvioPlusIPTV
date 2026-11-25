package com.nuvio.vpn.android;

import android.os.Process;
import android.os.SystemClock;
import com.nuvio.vpn.core.ProtectionMode;
import com.nuvio.vpn.core.StreamRoutePolicy;
import com.nuvio.vpn.core.VpnPreferences;
import java.net.URI;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/** Bounded observations, not a bandwidth test or proof of why playback stalled. No addresses are retained. */
public final class VpnPlaybackDiagnostics {
    private static final long WINDOW_MS = 10_000, ACTIVE_MS = 15_000, RAW_ACTIVE_MS = 30_000;
    private static final long RAW_ACTIVITY_BYTES = 4096;
    static final VpnPlaybackDiagnostics INSTANCE = new VpnPlaybackDiagnostics(
            SystemClock::elapsedRealtime, Process::getElapsedCpuTime);
    private final LongSupplier clock, cpuClock;
    private final AtomicLong bytes = new AtomicLong(), opens = new AtomicLong(), openFailures = new AtomicLong();
    private final AtomicLong readFailures = new AtomicLong(), eof = new AtomicLong(), timeouts = new AtomicLong();
    private final AtomicLong redirectedDirect = new AtomicLong(), redirectedProtected = new AtomicLong();
    private final AtomicReference<String> ioKind = new AtomicReference<>("none");
    private final AtomicLong gateMax = new AtomicLong(), openMax = new AtomicLong(), readMax = new AtomicLong();
    private final AtomicLong consumerMax = new AtomicLong(), lastByte = new AtomicLong(), lastActivity = new AtomicLong();
    private final AtomicLong hostActivity = new AtomicLong(), lastFailureReport = new AtomicLong(Long.MIN_VALUE);
    private final AtomicInteger sources = new AtomicInteger(), opening = new AtomicInteger(), reading = new AtomicInteger(), status = new AtomicInteger();
    private long sampledAt = -1, previousRx, previousTx, previousSession, rawActivityAt = -1;
    private long cpuAt = -1, previousCpu;
    private String cpuLine = "app_cpu_pct_of_one_core=unavailable; cpu_window_ms=0; cpu_scope=app_only_excludes_children";

    VpnPlaybackDiagnostics(LongSupplier clock, LongSupplier cpuClock) { this.clock = clock; this.cpuClock = cpuClock; }

    /** Compact scope/source-protocol/intended-route for the host ticker; never reads settings from disk. */
    public static String playbackSample(String source) {
        VpnPreferences settings = VpnPlaybackProtection.cachedSettingsForDiagnostics();
        String protocol = "other", intent = "unknown";
        try {
            URI uri = source == null || source.trim().isEmpty() ? null : new URI(source);
            if (uri != null && "http".equalsIgnoreCase(uri.getScheme())) protocol = "http";
            else if (uri != null && "https".equalsIgnoreCase(uri.getScheme())) protocol = "https";
            if (settings != null && uri != null) {
                StreamRoutePolicy.Route route = StreamRoutePolicy.decide(uri, settings.getMode(), true);
                intent = route == StreamRoutePolicy.Route.TUNNEL ? "protected"
                        : route == StreamRoutePolicy.Route.DIRECT ? "direct" : "blocked";
                if (route == StreamRoutePolicy.Route.TUNNEL) INSTANCE.hostActivity.set(INSTANCE.clock.getAsLong());
            }
        } catch (Exception ignored) { }
        return (settings == null ? "unavailable" : settings.getMode().name()) + "/" + protocol + "/" + intent;
    }

    /** Shared by host and VPN samples, including VPN-off comparisons. One process-CPU read per window. */
    public static String appCpuSample() {
        try { return INSTANCE.cpuSample(INSTANCE.clock.getAsLong()); }
        catch (RuntimeException | LinkageError unavailable) { return "app_cpu_pct_of_one_core=unavailable; cpu_window_ms=0; cpu_scope=app_only_excludes_children"; }
    }

    synchronized String cpuSample(long now) {
        if (cpuAt < 0 || now < cpuAt || now - cpuAt > 2 * WINDOW_MS) {
            cpuAt = now;
            previousCpu = cpuClock.getAsLong();
            cpuLine = "app_cpu_pct_of_one_core=unavailable; cpu_window_ms=0; cpu_scope=app_only_excludes_children";
        } else if (now - cpuAt >= WINDOW_MS) {
            long cpu = cpuClock.getAsLong(), elapsed = now - cpuAt;
            cpuLine = "app_cpu_pct_of_one_core=" + (cpu < previousCpu ? "unavailable" : decimal(100.0 * (cpu - previousCpu) / elapsed))
                    + "; cpu_window_ms=" + elapsed + "; cpu_scope=app_only_excludes_children";
            previousCpu = cpu;
            cpuAt = now;
        }
        return cpuLine;
    }

    static boolean protectedSource(String source, ProtectionMode mode) {
        try { return StreamRoutePolicy.decide(new URI(source), mode, true) == StreamRoutePolicy.Route.TUNNEL; }
        catch (Exception ignored) { return false; }
    }

    Transfer mediaOpen(long gateMs) { return mediaOpen(gateMs, clock.getAsLong()); }
    Transfer mediaOpen(long gateMs, long openingAt) {
        opens.incrementAndGet(); max(gateMax, gateMs); lastActivity.set(clock.getAsLong());
        return new Transfer(this, openingAt);
    }
    void redirected(boolean protectedBody) {
        (protectedBody ? redirectedProtected : redirectedDirect).incrementAndGet();
        lastActivity.set(clock.getAsLong());
    }

    void gateFailed(long elapsedMs, Throwable failure) {
        opens.incrementAndGet(); openFailures.incrementAndGet(); max(gateMax, elapsedMs);
        long now = clock.getAsLong(); lastActivity.set(now); failure(failure);
        long last = lastFailureReport.get();
        if ((last == Long.MIN_VALUE || now - last >= WINDOW_MS) && lastFailureReport.compareAndSet(last, now))
            VpnPlaybackProtection.report("vpn_media_gate_failed: wait_ms=" + elapsedMs + "; failure=" + failureKind(failure));
    }

    static final class Transfer {
        private final VpnPlaybackDiagnostics owner;
        private final long openingAt;
        private final AtomicInteger state = new AtomicInteger(); // 0 opening, 1 open, 2 closed
        private final AtomicBoolean ended = new AtomicBoolean();
        private long readEndedAt = -1;
        Transfer(VpnPlaybackDiagnostics owner, long openingAt) {
            this.owner = owner; this.openingAt = openingAt; owner.opening.incrementAndGet();
        }
        void opened() {
            long now = owner.clock.getAsLong();
            max(owner.openMax, now - openingAt); owner.lastActivity.set(now);
            owner.sources.incrementAndGet();
            if (state.compareAndSet(0, 1)) owner.opening.decrementAndGet();
            else owner.sources.decrementAndGet();
        }
        void openFailed(Throwable failure) {
            long now = owner.clock.getAsLong();
            max(owner.openMax, now - openingAt); owner.lastActivity.set(now);
            owner.openFailures.incrementAndGet(); owner.failure(failure); close();
        }
        long readStarted() {
            long now = owner.clock.getAsLong();
            if (readEndedAt >= 0) max(owner.consumerMax, now - readEndedAt);
            owner.reading.incrementAndGet();
            return now;
        }
        void readFinished(long since, int count, Throwable failure) {
            long now = owner.clock.getAsLong();
            max(owner.readMax, now - since); readEndedAt = now; owner.reading.decrementAndGet();
            owner.lastActivity.set(now);
            if (failure != null) { owner.readFailures.incrementAndGet(); owner.failure(failure); }
            else if (count > 0) { owner.bytes.addAndGet(count); owner.lastByte.set(now); }
            else if (count == -1 && ended.compareAndSet(false, true)) owner.eof.incrementAndGet();
        }
        void close() {
            int previous = state.getAndSet(2);
            if (previous == 1) owner.sources.decrementAndGet();
            else if (previous == 0) owner.opening.decrementAndGet();
        }
    }

    private void failure(Throwable failure) {
        String kind = failureKind(failure);
        ioKind.set(kind);
        if ("timeout".equals(kind)) timeouts.incrementAndGet();
        for (int i = 0; failure != null && i < 4; i++, failure = failure.getCause()) {
            if (failure instanceof androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
                int code = ((androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) failure).responseCode;
                if (code >= 100 && code <= 599) status.set(code);
            }
        }
    }
    private static String failureKind(Throwable failure) {
        for (int i = 0; failure != null && i < 4; i++, failure = failure.getCause()) {
            if (failure instanceof java.net.SocketTimeoutException) return "timeout";
            if (failure instanceof java.net.UnknownHostException) return "dns";
            if (failure instanceof javax.net.ssl.SSLException) return "tls";
            if (failure instanceof java.net.ConnectException || failure instanceof java.net.NoRouteToHostException) return "connect";
            if (failure instanceof androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) return "http";
            if (failure instanceof java.io.InterruptedIOException) return "interrupted";
        }
        return "io";
    }

    /** The service calls this on its existing worker tick, even while an interface is still connecting. */
    synchronized String sample(long received, long sent, long session, String scope, String engine, String phase, long uptimeMs) {
        long now = clock.getAsLong();
        if (sampledAt < 0 || now < sampledAt) {
            if (received >= RAW_ACTIVITY_BYTES || sent >= RAW_ACTIVITY_BYTES) rawActivityAt = now;
            baseline(now, received, sent, session); clearWindow(); return null;
        }
        long elapsed = now - sampledAt;
        if (elapsed < WINDOW_MS) return null;
        boolean valid = session == previousSession && received >= previousRx && sent >= previousTx;
        if (valid && (received - previousRx >= RAW_ACTIVITY_BYTES || sent - previousTx >= RAW_ACTIVITY_BYTES)) rawActivityAt = now;
        // Native players and Full app metadata do not cross the Media3 wrapper. Keep their
        // tunnel observations, followed by a short quiet grace, without logging keepalives forever.
        boolean rawActive = rawActivityAt >= 0 && now >= rawActivityAt && now - rawActivityAt <= RAW_ACTIVE_MS;
        boolean active = rawActive || opening.get() > 0 || reading.get() > 0
                || recent(now, lastActivity.get()) || recent(now, hostActivity.get());
        long delivered = bytes.getAndSet(0), opened = opens.getAndSet(0), failedOpen = openFailures.getAndSet(0);
        long failedRead = readFailures.getAndSet(0), ended = eof.getAndSet(0), timeoutCount = timeouts.getAndSet(0);
        long directRedirects = redirectedDirect.getAndSet(0), protectedRedirects = redirectedProtected.getAndSet(0);
        long gate = gateMax.getAndSet(0), open = openMax.getAndSet(0), read = readMax.getAndSet(0), consumer = consumerMax.getAndSet(0);
        int http = status.getAndSet(0);
        String lastIoKind = ioKind.getAndSet("none");
        String line = active ? "vpn_traffic: received=" + received + "; sent=" + sent + "; window_ms=" + elapsed
                + "; scope=" + scope + "; engine=" + engine + "; phase=" + phase + "; uptime_s=" + (Math.max(0, uptimeMs) / 1000)
                + "; tunnel_rates_valid=" + valid
                + "; tunnel_rx_mbps=" + (valid ? mbps(received - previousRx, elapsed) : "unavailable")
                + "; tunnel_tx_mbps=" + (valid ? mbps(sent - previousTx, elapsed) : "unavailable")
                + "; coverage=media3_routed; media_sources=" + sources.get() + "; media_opening=" + opening.get()
                + "; media_reading=" + reading.get()
                + "; media_bytes=" + delivered + "; media_mbps=" + mbps(delivered, elapsed) + "; media_opens=" + opened
                + "; media_open_fail=" + failedOpen + "; media_read_fail=" + failedRead + "; media_eof=" + ended
                + "; media_timeouts=" + timeoutCount + "; io_kind=" + lastIoKind + "; http_status=" + http
                + "; media_redirect_direct=" + directRedirects + "; media_redirect_protected=" + protectedRedirects
                + "; gate_max_ms=" + gate + "; open_max_ms=" + open + "; read_block_max_ms=" + read
                + "; consumer_gap_max_ms=" + consumer + "; last_byte_age_ms="
                + (lastByte.get() == 0 ? "unavailable" : Long.toString(Math.max(0, now - lastByte.get())))
                + "; " + cpuSample(now) : null;
        baseline(now, received, sent, session);
        return line;
    }
    synchronized void resetConnection() {
        sampledAt = rawActivityAt = -1;
        // Activity and byte ages belong to this connection. Outstanding media leases can
        // still close after a reconnect, so their gauges and the app-wide CPU window survive.
        lastActivity.set(0); hostActivity.set(0); lastByte.set(0);
        try { cpuSample(clock.getAsLong()); } catch (RuntimeException | LinkageError ignored) { }
    }
    private static boolean recent(long now, long since) { return since > 0 && now >= since && now - since <= ACTIVE_MS; }
    private void baseline(long now, long rx, long tx, long session) { sampledAt = now; previousRx = rx; previousTx = tx; previousSession = session; }
    private void clearWindow() {
        bytes.set(0); opens.set(0); openFailures.set(0); readFailures.set(0); eof.set(0); timeouts.set(0);
        redirectedDirect.set(0); redirectedProtected.set(0);
        ioKind.set("none");
        gateMax.set(0); openMax.set(0); readMax.set(0); consumerMax.set(0); status.set(0);
    }
    private static String mbps(long bytes, long elapsedMs) { return decimal(bytes * 0.008 / elapsedMs); }
    private static String decimal(double value) { return String.format(Locale.ROOT, "%.2f", value); }
    private static void max(AtomicLong target, long value) {
        long previous = target.get();
        while (value > previous && !target.compareAndSet(previous, value)) previous = target.get();
    }
}
