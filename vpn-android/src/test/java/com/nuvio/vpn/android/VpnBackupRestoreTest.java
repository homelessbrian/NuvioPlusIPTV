package com.nuvio.vpn.android;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import com.nuvio.vpn.core.ProtectionMode;
import com.nuvio.vpn.core.VpnPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class VpnBackupRestoreTest {
    private static final String FIRST = "a4a6dfda-f490-4728-a7dd-1318e7d385a0";
    private static final String SECOND = "bb6c6284-1068-444a-a239-70090a64771b";
    private static final String CONFIG = "client\nremote vpn.example 1194\n";
    private Context context;
    private TestAndroidKeyStore keys;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        keys = new TestAndroidKeyStore();
        VpnPlaybackProtection.initialize(context);
    }

    @After public void tearDown() {
        keys.close();
    }

    @Test public void equivalentBackupsKeepTheStoredProfilesAndPlaybackRevision() throws Exception {
        JSONObject first = profile(FIRST, "First", CONFIG);
        JSONObject second = profile(SECOND, "Second", CONFIG);
        VpnBackup.restoreSetup(context, setup(new JSONArray().put(first).put(second)).toString());
        String stored = VpnBackup.exportSetup(context);
        AndroidProfileStore store = new AndroidProfileStore(context);
        byte[] firstRevision = store.revision(FIRST);
        byte[] secondRevision = store.revision(SECOND);
        long playbackRevision = VpnPlaybackProtection.settingsRevision();

        JSONObject reordered = new JSONObject().put("version", 1).put("scope", "HTTP_ONLY")
                .put("profileOverrides", new JSONObject()).put("selected", FIRST)
                .put("profiles", new JSONArray().put(second).put(profile(FIRST, " First ", "\uFEFF" + CONFIG)));
        VpnBackup.restoreSetup(context, reordered.toString());
        VpnBackup.restoreSetup(context, reordered.toString());

        assertEquals(playbackRevision, VpnPlaybackProtection.settingsRevision());
        assertArrayEquals(firstRevision, store.revision(FIRST));
        assertArrayEquals(secondRevision, store.revision(SECOND));
        assertEquals(stored, VpnBackup.exportSetup(context));
    }

    @Test public void changedConfigurationStillRestoresAndInvalidatesPlayback() throws Exception {
        VpnBackup.restoreSetup(context, setup(new JSONArray().put(profile(FIRST, "First", CONFIG))).toString());
        long playbackRevision = VpnPlaybackProtection.settingsRevision();

        String updated = "client\nremote updated.example 443\n";
        VpnBackup.restoreSetup(context, setup(new JSONArray().put(profile(FIRST, "First", updated))).toString());

        assertTrue(VpnPlaybackProtection.settingsRevision() > playbackRevision);
        assertEquals(updated, new JSONObject(VpnBackup.exportSetup(context)).getJSONArray("profiles")
                .getJSONObject(0).getString("configuration"));
    }

    @Test public void changedProfileOverrideStillRestores() throws Exception {
        JSONObject backup = setup(new JSONArray().put(profile(FIRST, "First", CONFIG)));
        VpnBackup.restoreSetup(context, backup.toString());
        long playbackRevision = VpnPlaybackProtection.settingsRevision();

        backup.put("profileOverrides", new JSONObject().put("2",
                new JSONObject().put("scope", "OFF").put("selected", FIRST)));
        VpnBackup.restoreSetup(context, backup.toString());

        assertTrue(VpnPlaybackProtection.settingsRevision() > playbackRevision);
        assertEquals(new VpnPreferences(ProtectionMode.OFF, FIRST), new AndroidVpnSettings(context).snapshot().forUser(2));
    }

    private static JSONObject profile(String id, String label, String configuration) throws Exception {
        return new JSONObject().put("configuration", configuration).put("protocol", "OPENVPN")
                .put("name", label).put("id", id);
    }

    private static JSONObject setup(JSONArray profiles) throws Exception {
        return new JSONObject().put("version", 1).put("profiles", profiles).put("selected", FIRST).put("scope", "HTTP_ONLY");
    }
}
