package com.nuvio.vpn.android;

import static org.junit.Assert.*;

import android.content.Context;
import com.nuvio.vpn.core.ProtectionMode;
import com.nuvio.vpn.core.VpnPreferences;
import java.io.IOException;
import java.lang.reflect.Field;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Read-only session prerequisites; no consent, VPN connection or process-death leak claim. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public final class VpnStalkerRoutingTest {
    private Context context;

    @Before public void setUp() throws Exception {
        context = RuntimeEnvironment.getApplication();
        VpnPlaybackProtection.initialize(context);
        VpnPlaybackProtection.setActiveUserProfile(1);
        new AndroidVpnSettings(context).save(VpnPreferences.defaults());
    }

    @Test public void unknownUserCannotUseSharedOffSettingsAsAProtectedSessionIdentity() throws Exception {
        Field active = VpnPlaybackProtection.class.getDeclaredField("activeUserProfile");
        active.setAccessible(true);
        active.setInt(null, 0);
        try {
            assertThrows(IOException.class, VpnPlaybackProtection::requireUniformHttpRouting);
            VpnPlaybackProtection.initializeActiveUserProfile(1);
            assertTrue(VpnPlaybackProtection.requireUniformHttpRouting().startsWith("OFF:"));
        } finally {
            VpnPlaybackProtection.setActiveUserProfile(1);
        }
    }

    @Test public void backgroundBootstrapNeverOverwritesAnActiveUserSelection() {
        VpnPlaybackProtection.setActiveUserProfile(2);
        VpnPlaybackProtection.initializeActiveUserProfile(1);
        assertEquals(2, VpnPlaybackProtection.activeUserProfile());
    }

    @Test public void httpOnlyIsRejectedWithoutChangingTheSavedScope() throws Exception {
        AndroidVpnSettings settings = new AndroidVpnSettings(context);
        settings.save(new VpnPreferences(ProtectionMode.HTTP_ONLY, null));
        assertThrows(IOException.class, VpnPlaybackProtection::requireUniformHttpRouting);
        assertEquals(ProtectionMode.HTTP_ONLY, settings.read().getMode());
    }

    @Test public void uniformModesRemainDistinctWithoutStartingAnyConnection() throws Exception {
        AndroidVpnSettings settings = new AndroidVpnSettings(context);
        for (ProtectionMode mode : new ProtectionMode[] {ProtectionMode.OFF, ProtectionMode.HTTP_HTTPS, ProtectionMode.APP_WIDE}) {
            settings.save(new VpnPreferences(mode, null));
            assertTrue(VpnPlaybackProtection.requireUniformHttpRouting().startsWith(mode.name() + ":"));
            assertEquals(mode, settings.read().getMode());
        }
    }
}
