package com.nuvio.vpn.android;

import static org.junit.Assert.*;

import android.content.Context;
import android.os.Looper;
import com.nuvio.vpn.core.ProtectionMode;
import com.nuvio.vpn.core.VpnPreferences;
import java.io.File;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

/** Host Android checks for the persisted mode behind the TV connection card. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 35})
@LooperMode(LooperMode.Mode.PAUSED)
public final class VpnSettingsControllerTest {
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        // These are Robolectric's temporary application files, never host app data.
        assertNotEquals("com.nuvio.tv", context.getPackageName());
        for (String name : new String[] {"vpn-scope-v1", "vpn-settings-v2"}) {
            for (String suffix : new String[] {"", ".bak", ".new"}) {
                File file = new File(context.getNoBackupFilesDir(), name + suffix);
                if (file.exists()) assertTrue(file.delete());
            }
        }
        VpnPlaybackProtection.setActiveUserProfile(1);
    }

    @Test public void statusAndReopeningPreserveEverySharedProtectionMode() throws Exception {
        for (int mode : new int[] {VpnSettingsController.MODE_HTTP,
                VpnSettingsController.MODE_HTTP_HTTPS, VpnSettingsController.MODE_APP}) {
            VpnSettingsController controller = controller();
            try {
                controller.setMode(mode);
                waitUntilReady(controller);
                assertEquals(mode, controller.state().mode);
                controller.connection();
                assertEquals(mode, controller.state().mode);
            } finally { controller.close(); }

            AndroidVpnSettings reopened = new AndroidVpnSettings(context);
            ProtectionMode expected = protectionMode(mode);
            assertEquals(expected, reopened.read().getMode());
            ProfileSelections saved = reopened.snapshot();
            assertTrue(saved.overrides.isEmpty());
            assertEquals(expected, saved.forUser(1).getMode());
            assertEquals(expected, saved.forUser(2).getMode());

            VpnSettingsController reopenedController = controller();
            try {
                reopenedController.refresh();
                waitUntilReady(reopenedController);
                assertEquals(mode, reopenedController.state().mode);
                reopenedController.connection();
                assertEquals(expected, new AndroidVpnSettings(context).read().getMode());
            } finally { reopenedController.close(); }
        }
    }

    @Test public void explicitTurnOffStillPersistsOff() throws Exception {
        new AndroidVpnSettings(context).save(new VpnPreferences(ProtectionMode.HTTP_HTTPS, null));
        VpnSettingsController controller = controller();
        try {
            controller.refresh();
            waitUntilReady(controller);
            assertTrue(controller.connection().canTurnOff);
            controller.turnOff();
            waitUntilReady(controller);
            assertEquals(VpnSettingsController.MODE_OFF, controller.state().mode);
            assertEquals(ProtectionMode.OFF, new AndroidVpnSettings(context).read().getMode());
        } finally { controller.close(); }
    }

    private VpnSettingsController controller() {
        return new VpnSettingsController(context, ignored -> {});
    }

    private static ProtectionMode protectionMode(int mode) {
        return mode == VpnSettingsController.MODE_HTTP ? ProtectionMode.HTTP_ONLY
                : mode == VpnSettingsController.MODE_HTTP_HTTPS ? ProtectionMode.HTTP_HTTPS
                : ProtectionMode.APP_WIDE;
    }

    private static void waitUntilReady(VpnSettingsController controller) throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!controller.state().loaded || controller.state().busy) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            if (System.nanoTime() >= deadline) fail("VPN settings did not finish saving/refreshing.");
            Thread.sleep(10);
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
}
