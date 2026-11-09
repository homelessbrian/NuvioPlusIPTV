package com.nuvio.vpn.android;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import com.nuvio.vpn.core.ProfileImport;
import com.nuvio.vpn.core.ProtectionMode;
import com.nuvio.vpn.core.VpnPreferences;
import com.wireguard.crypto.KeyPair;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowApplication;
import org.robolectric.shadows.ShadowVpnService;

/** Real settings/envelope/parser and Android intent dispatch on a host JVM.
 * Services are observed, not executed: this does not prove a native tunnel,
 * real system consent, Keystore isolation, or protection after process death. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 35})
@LooperMode(LooperMode.Mode.PAUSED)
public final class VpnAppWideStartupTest {
    private static final String ID = "bb6c6284-1068-444a-a239-70090a64771b";
    private Context context;
    private ShadowApplication app;
    private TestAndroidKeyStore keys;
    private ActivityController<Activity> host;

    @Before public void setUp() throws Exception {
        context = RuntimeEnvironment.getApplication();
        app = Shadows.shadowOf((Application) context);
        assertNotEquals("com.nuvio.tv", context.getPackageName());
        // Only the temporary Robolectric application's fixtures are removed.
        for (String name : new String[] {"vpn-scope-v1", "vpn-settings-v2"}) {
            for (String suffix : new String[] {"", ".bak", ".new"}) {
                File file = new File(context.getNoBackupFilesDir(), name + suffix);
                if (file.exists()) assertTrue(file.delete());
            }
        }
        keys = new TestAndroidKeyStore();
        VpnPlaybackProtection.initialize(context);
        drain();
        Field user = VpnPlaybackProtection.class.getDeclaredField("activeUserProfile");
        user.setAccessible(true);
        user.setInt(null, 0); // Models startup before the host publishes its restored profile.
        ShadowVpnService.setPrepareResult(null);
        app.clearStartedServices();
        app.clearNextStartedActivities();
    }

    @After public void tearDown() throws Exception {
        if (host != null) { host.pause().stop().destroy(); host = null; }
        VpnPlaybackProtection.beginProfileRestore(context);
        drain();
        VpnPlaybackProtection.endProfileRestore(context);
        drain();
        if (keys != null) keys.close();
    }

    @Test public void restoredFullAppWaitsForIdentityThenStartsWithoutPlayback() throws Exception {
        saveWireGuard(true);
        settings(ProtectionMode.APP_WIDE);
        foreground();
        assertNull(app.getNextStartedService());
        VpnPlaybackProtection.setActiveUserProfile(1);
        drain();
        assertAppWideStart(app.getNextStartedService());
        assertNull(app.getNextStartedActivity());
        assertFalse(VpnConnectionService.verifiedFor(ID, ProtectionMode.APP_WIDE));
        assertEquals(ProtectionMode.APP_WIDE, new AndroidVpnSettings(context).read().getMode());

        // Resuming while the same request is pending coalesces with that attempt.
        host.pause().resume();
        drain();
        assertNull(app.getNextStartedService());
    }

    @Test public void offAndPlaybackScopesStayOnDemandForForegroundAndExplicitSettingsChanges() throws Exception {
        saveWireGuard(true);
        VpnPlaybackProtection.setActiveUserProfile(1);
        foreground();
        for (ProtectionMode mode : new ProtectionMode[] {ProtectionMode.OFF, ProtectionMode.HTTP_ONLY, ProtectionMode.HTTP_HTTPS}) {
            settings(mode);
            VpnConnectionService.settingsChanged(context, true);
            host.pause().resume();
            drain();
            assertNull(app.getNextStartedService());
            assertNull(app.getNextStartedActivity());
            assertEquals(mode, new AndroidVpnSettings(context).read().getMode());
        }
    }

    @Test public void explicitFullAppMayAskOnceButDeclineAndPassiveResumePreserveIntentWithoutPromptingAgain() throws Exception {
        saveWireGuard(true);
        settings(ProtectionMode.APP_WIDE);
        VpnPlaybackProtection.setActiveUserProfile(1);
        ShadowVpnService.setPrepareResult(new Intent("synthetic-system-vpn-consent"));
        foreground();
        assertNull(app.getNextStartedActivity());
        assertNull(app.getNextStartedService());

        VpnConnectionService.settingsChanged(context, true);
        drain();
        Intent consent = app.getNextStartedActivity();
        assertNotNull(consent);
        assertEquals(VpnPlaybackConsentActivity.class.getName(), consent.getComponent().getClassName());
        assertNull(app.getNextStartedService());
        VpnPlaybackProtection.consentResult(consent.getStringExtra("request"), false);
        drain();
        host.pause().resume();
        drain();
        assertNull(app.getNextStartedActivity());
        assertNull(app.getNextStartedService());
        assertEquals(ProtectionMode.APP_WIDE, new AndroidVpnSettings(context).read().getMode());
    }

    @Test public void backgroundSettingsChangesCannotLaunchConsentOrAService() throws Exception {
        saveWireGuard(true);
        settings(ProtectionMode.APP_WIDE);
        VpnPlaybackProtection.setActiveUserProfile(1);
        ShadowVpnService.setPrepareResult(new Intent("synthetic-system-vpn-consent"));
        VpnConnectionService.settingsChanged(context, true);
        drain();
        assertNull(app.getNextStartedActivity());
        assertNull(app.getNextStartedService());
        assertEquals(ProtectionMode.APP_WIDE, new AndroidVpnSettings(context).read().getMode());
    }

    @Test public void automaticConsentAndPlaybackDemandShareOneAttemptWithoutClaimingConnected() throws Exception {
        saveWireGuard(true);
        settings(ProtectionMode.APP_WIDE);
        VpnPlaybackProtection.setActiveUserProfile(1);
        ShadowVpnService.setPrepareResult(new Intent("synthetic-system-vpn-consent"));
        foreground();
        VpnConnectionService.settingsChanged(context, true);
        drain();
        Intent consent = app.getNextStartedActivity();
        assertNotNull(consent);
        CompletableFuture<Void> playback = VpnPlaybackProtection.prepare(context, "https://fixture.invalid/stream");
        drain();
        assertNull(app.getNextStartedActivity());
        ShadowVpnService.setPrepareResult(null);
        VpnPlaybackProtection.consentResult(consent.getStringExtra("request"), true);
        drain();
        assertAppWideStart(app.getNextStartedService());
        assertNull(app.getNextStartedService());
        assertFalse(playback.isDone());
        assertFalse(VpnConnectionService.verifiedFor(ID, ProtectionMode.APP_WIDE));
    }

    @Test public void aDifferentNuvioProfileInvalidatesTheOldConsentRequest() throws Exception {
        saveWireGuard(true);
        settings(ProtectionMode.APP_WIDE);
        VpnPlaybackProtection.setActiveUserProfile(1);
        ShadowVpnService.setPrepareResult(new Intent("synthetic-system-vpn-consent"));
        foreground();
        VpnConnectionService.settingsChanged(context, true);
        drain();
        Intent consent = app.getNextStartedActivity();
        assertNotNull(consent);

        AndroidVpnSettings settings = new AndroidVpnSettings(context);
        ProfileSelections changed = settings.snapshot();
        changed.overrides.put(2, new VpnPreferences(ProtectionMode.HTTP_HTTPS, ID));
        settings.replace(changed);
        VpnPlaybackProtection.setActiveUserProfile(2);
        VpnPlaybackProtection.consentResult(consent.getStringExtra("request"), true);
        drain();
        assertNull(app.getNextStartedService());
        assertNull(app.getNextStartedActivity());
        assertEquals(ProtectionMode.HTTP_HTTPS, settings.read().getMode());
        assertEquals(ProtectionMode.APP_WIDE, settings.snapshot().forUser(1).getMode());
    }

    @Test public void revokedPermissionNeverReopensConsentOnPassiveResume() throws Exception {
        saveWireGuard(true);
        settings(ProtectionMode.APP_WIDE);
        VpnPlaybackProtection.setActiveUserProfile(1);
        foreground();
        assertAppWideStart(app.getNextStartedService());
        // Simulate Android requiring consent again after revocation. No native
        // service is running in this host test, and no TUN protection is claimed.
        ShadowVpnService.setPrepareResult(new Intent("synthetic-revoked-vpn-consent"));
        host.pause().resume();
        drain();
        assertNull(app.getNextStartedActivity());
        assertNull(app.getNextStartedService());
        assertFalse(VpnConnectionService.verifiedFor(ID, ProtectionMode.APP_WIDE));
        assertEquals(ProtectionMode.APP_WIDE, new AndroidVpnSettings(context).read().getMode());
    }

    @Test public void missingAndInvalidProfilesCannotCreateAServiceOrRequestConsent() throws Exception {
        settings(ProtectionMode.APP_WIDE);
        VpnPlaybackProtection.setActiveUserProfile(1);
        ShadowVpnService.setPrepareResult(new Intent("synthetic-system-vpn-consent"));
        foreground();
        VpnConnectionService.settingsChanged(context, true);
        drain();
        assertNull(app.getNextStartedService());
        assertNull(app.getNextStartedActivity());
        saveWireGuard(false); // Encrypted backup-shaped profile that fails the real protocol parser.
        VpnConnectionService.settingsChanged(context, true);
        drain();
        assertNull(app.getNextStartedService());
        assertNull(app.getNextStartedActivity());
        assertEquals(ProtectionMode.APP_WIDE, new AndroidVpnSettings(context).read().getMode());
    }

    private void settings(ProtectionMode mode) throws Exception {
        new AndroidVpnSettings(context).save(new VpnPreferences(mode, ID));
    }

    private void saveWireGuard(boolean valid) throws Exception {
        String configuration = "[Interface]\n[Peer]\n";
        if (valid) {
            KeyPair client = new KeyPair(), peer = new KeyPair();
            configuration = "[Interface]\nPrivateKey = " + client.getPrivateKey().toBase64()
                    + "\nAddress = 192.0.2.2/32\nDNS = 192.0.2.53\n[Peer]\nPublicKey = "
                    + peer.getPublicKey().toBase64() + "\nAllowedIPs = 0.0.0.0/0\nEndpoint = 192.0.2.1:51820\n";
        }
        try (ProfileImport.Candidate candidate = ProfileImport.read(new ByteArrayInputStream(
                configuration.getBytes(StandardCharsets.UTF_8)), ProfileImport.Protocol.WIREGUARD)) {
            new AndroidProfileStore(context).save(ID, "Synthetic startup fixture", candidate);
        }
    }

    private void foreground() throws Exception {
        host = Robolectric.buildActivity(Activity.class).create().start().resume().visible();
        drain();
    }

    private static void assertAppWideStart(Intent intent) {
        assertNotNull("Full app should start before playback is requested", intent);
        assertEquals(VpnConnectionService.class.getName(), intent.getComponent().getClassName());
        assertEquals("com.nuvio.vpn.CONNECT", intent.getAction());
        assertEquals(ID, intent.getStringExtra("profile"));
        assertEquals(ProtectionMode.APP_WIDE.name(), intent.getStringExtra("scope"));
    }

    private static void drain() throws Exception {
        Field field = VpnPlaybackProtection.class.getDeclaredField("worker");
        field.setAccessible(true);
        ExecutorService worker = (ExecutorService) field.get(null);
        for (int i = 0; i < 3; i++) {
            worker.submit(() -> {}).get(5, TimeUnit.SECONDS);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
        }
    }
}
