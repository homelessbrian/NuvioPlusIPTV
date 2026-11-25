package com.nuvio.vpn.android;

import static org.junit.Assert.*;

import javax.crypto.spec.SecretKeySpec;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Host crypto checks with a synthetic key; does not validate a real Android Keystore. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public final class VpnBackupCacheTest {
    private final SecretKeySpec key = new SecretKeySpec(new byte[32], "AES");

    private static JSONObject entry(String value) throws Exception {
        return new JSONObject().put("t", "str").put("v", value);
    }

    @Test public void protectsVpnAndEveryUsenetProfileWithoutChangingPortableEntries() throws Exception {
        JSONObject original = new JSONObject().put("vpn_profiles_v1", entry("synthetic-vpn-secret"))
                .put("usenet_sources_v1_p1", entry("synthetic-provider-secret"))
                .put("usenet_sources_v1_p37", entry("synthetic-indexer-secret"))
                .put("device_settings_v1", entry("hardware-settings"))
                .put("unrelated", entry("keep-visible"));
        String sealed = VpnBackup.sealCache(original.toString(), key);
        assertFalse(sealed.contains("synthetic-vpn-secret"));
        assertFalse(sealed.contains("synthetic-provider-secret"));
        assertFalse(sealed.contains("synthetic-indexer-secret"));
        assertTrue(sealed.contains("hardware-settings"));
        assertTrue(sealed.contains("keep-visible"));
        JSONObject opened = new JSONObject(VpnBackup.openCache(sealed, key));
        for (java.util.Iterator<String> names = original.keys(); names.hasNext();) {
            String name = names.next();
            assertEquals(original.getJSONObject(name).toString(), opened.getJSONObject(name).toString());
        }
    }

    @Test public void bindsCiphertextToItsProfileAndFailsClosedOnDamage() throws Exception {
        JSONObject sealed = new JSONObject(VpnBackup.sealCache(new JSONObject()
                .put("usenet_sources_v1_p1", entry("synthetic-secret")).toString(), key));
        JSONObject moved = new JSONObject().put("usenet_sources_v1_p2", sealed.getJSONObject("usenet_sources_v1_p1"));
        assertThrows(Exception.class, () -> VpnBackup.openCache(moved.toString(), key));
        sealed.getJSONObject("usenet_sources_v1_p1").put("localEncrypted", "damaged");
        assertThrows(Exception.class, () -> VpnBackup.openCache(sealed.toString(), key));
        assertThrows(Exception.class, () -> VpnBackup.openCache(new JSONObject()
                .put("usenet_sources_v1_p1", new JSONObject().put("iv", "missing-ciphertext")).toString(), key));
    }

    @Test public void keepsLegacyPlaintextReadableAndMatchesOnlyTheExactFamily() throws Exception {
        String legacy = new JSONObject().put("usenet_sources_v1_p2", entry("legacy")).toString();
        assertEquals(legacy, VpnBackup.openCache(legacy, key));
        String unrelated = new JSONObject().put("usenet_sources_v1_p0", entry("zero"))
                .put("usenet_sources_v1_p2_suffix", entry("suffix"))
                .put("usenet_sources_v2_p2", entry("future-family")).toString();
        assertEquals(unrelated, VpnBackup.sealCache(unrelated, key));
    }

    @Test public void publicErrorsNeverExposeMalformedPlaintextOrItsCause() {
        Exception opening = assertThrows(Exception.class, () -> VpnBackup.openCache("synthetic-provider-secret not json"));
        Exception sealing = assertThrows(Exception.class, () -> VpnBackup.sealCache("synthetic-indexer-secret not json"));
        assertEquals("Local backup settings could not be opened.", opening.getMessage());
        assertEquals("Local backup settings could not be protected.", sealing.getMessage());
        assertNull(opening.getCause());
        assertNull(sealing.getCause());
    }
}
