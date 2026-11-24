package com.nuvio.vpn.android;

import static org.junit.Assert.*;

import android.os.SystemClock;
import com.nuvio.vpn.core.ProtectionMode;
import com.nuvio.vpn.core.VpnPreferences;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

/** Service ordering with a synthetic engine; no provider or native tunnel is contacted. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
@LooperMode(LooperMode.Mode.PAUSED)
public final class VpnConnectionReadinessTest {
    private static final String ID = "bb6c6284-1068-444a-a239-70090a64771b";
    private ServiceController<VpnConnectionService> controller;
    private VpnConnectionService service;
    private FakeEngine engine;
    private ExecutorService worker;

    @Before public void setUp() throws Exception {
        controller = Robolectric.buildService(VpnConnectionService.class).create();
        service = controller.get();
        ((ScheduledFuture<?>) field("monitor").get(service)).cancel(false);
        worker = (ExecutorService) field("worker").get(null);
        new AndroidVpnSettings(service).save(new VpnPreferences(ProtectionMode.HTTP_HTTPS, ID));
        engine = new FakeEngine();
        worker.submit(() -> {
            try {
                field("engine").set(service, engine);
                field("profile").set(service, ID);
                field("scope").set(service, ProtectionMode.HTTP_HTTPS);
                field("requestedProfile").set(null, ID);
                field("requestedMode").set(null, ProtectionMode.HTTP_HTTPS);
                field("started").setLong(service, SystemClock.elapsedRealtime());
                field("current").set(null, new VpnConnectionService.Snapshot(VpnConnectionService.Phase.CONNECTING, 0, 0));
            } catch (Exception error) { throw new AssertionError(error); }
        }).get(2, TimeUnit.SECONDS);
    }

    @After public void tearDown() throws Exception {
        if (engine != null) engine.release.countDown();
        if (controller != null) controller.destroy();
        if (worker != null) worker.submit(() -> {}).get(2, TimeUnit.SECONDS);
        displayFinished();
    }

    @Test public void slowDisplayLookupDoesNotDelayVerifiedPlayback() throws Exception {
        callOnWorker("poll");
        assertTrue(engine.lookupStarted.await(2, TimeUnit.SECONDS));
        assertTrue(VpnConnectionService.verifiedFor(ID, ProtectionMode.HTTP_HTTPS));
        assertNull(VpnConnectionService.snapshot().egress);
        // Monitoring remains usable while the optional request is blocked.
        callOnWorker("poll");
        assertEquals(VpnConnectionService.Phase.CONNECTED, VpnConnectionService.snapshot().phase);
        engine.release.countDown();
        displayFinished();
        assertEquals("203.0.113.7", VpnConnectionService.snapshot().egress);
    }

    @Test public void lateLookupCannotUpdateAChangedConnectionGeneration() throws Exception {
        callOnWorker("poll");
        assertTrue(engine.lookupStarted.await(2, TimeUnit.SECONDS));
        ((AtomicLong) field("generation").get(service)).incrementAndGet();
        engine.release.countDown();
        displayFinished();
        assertNull(VpnConnectionService.snapshot().egress);
    }

    @Test public void disconnectDoesNotWaitForDisplayLookupOrPublishItsLateResult() throws Exception {
        callOnWorker("poll");
        assertTrue(engine.lookupStarted.await(2, TimeUnit.SECONDS));
        callOnWorker("end");
        assertEquals(VpnConnectionService.Phase.IDLE, VpnConnectionService.snapshot().phase);
        assertTrue(engine.closed);
        engine.release.countDown();
        displayFinished();
        assertEquals(VpnConnectionService.Phase.IDLE, VpnConnectionService.snapshot().phase);
        assertNull(VpnConnectionService.snapshot().egress);
    }

    @Test public void failedRequiredProbeNeverBecomesReadyOrStartsDisplayLookup() throws Exception {
        engine.healthy = false;
        callOnWorker("poll");
        assertFalse(VpnConnectionService.verifiedFor(ID, ProtectionMode.HTTP_HTTPS));
        assertEquals(VpnConnectionService.Phase.CONNECTING, VpnConnectionService.snapshot().phase);
        assertEquals(1L, engine.lookupStarted.getCount());
    }

    private void displayFinished() throws Exception {
        ((ExecutorService) field("displayWorker").get(null)).submit(() -> {}).get(2, TimeUnit.SECONDS);
        if (worker != null) worker.submit(() -> {}).get(2, TimeUnit.SECONDS);
    }
    private void callOnWorker(String name) throws Exception {
        Method method = VpnConnectionService.class.getDeclaredMethod(name);
        method.setAccessible(true);
        worker.submit(() -> {
            try { method.invoke(service); }
            catch (Exception error) { throw new AssertionError(error); }
        }).get(2, TimeUnit.SECONDS);
    }
    private static Field field(String name) throws Exception {
        Field field = VpnConnectionService.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
    private static final class FakeEngine implements TunnelEngine {
        final CountDownLatch lookupStarted = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        volatile boolean healthy = true, closed;
        @Override public boolean isUp() { return !closed; }
        @Override public TunnelTraffic statistics() { return new TunnelTraffic(1024, 512, true, 17); }
        @Override public boolean probe() { return healthy; }
        @Override public String egress() {
            lookupStarted.countDown();
            // Also model a native/network operation which returns after cancellation.
            boolean interrupted = false;
            while (true) {
                try { release.await(); break; }
                catch (InterruptedException ignored) { interrupted = true; }
            }
            if (interrupted) Thread.currentThread().interrupt();
            return "203.0.113.7";
        }
        @Override public void close() { closed = true; }
    }
}
