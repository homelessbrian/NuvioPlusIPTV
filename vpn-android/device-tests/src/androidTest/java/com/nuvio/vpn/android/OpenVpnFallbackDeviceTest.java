package com.nuvio.vpn.android;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.nuvio.vpn.core.OpenVpnEnginePolicy;
import com.nuvio.vpn.core.ProfileEnvelope;
import com.nuvio.vpn.core.ProfileImport;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import javax.crypto.spec.SecretKeySpec;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Real Android packaging/parser checks; no provider account or live tunnel is used. */
@RunWith(AndroidJUnit4.class)
public final class OpenVpnFallbackDeviceTest {
    private static final String ID = "a4a6dfda-f490-4728-a7dd-1318e7d385a2";
    // Invalid PEM deliberately exercises options parsing only. TLS/certificate
    // acceptance requires the separate optional live-server acceptance test.
    private static final String PROFILE = "client\ndev tun\nproto udp\n"
            + "remote vpn.example 1194\nnobind\nauth-user-pass\n"
            + "<ca>\nsynthetic-ca-placeholder\n</ca>\n";
    private Context context;

    @Before public void requireIsolatedTestApplication() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("com.nuvio.vpn.android.test", context.getPackageName());
    }

    @Test public void nativeCompatibilityEngineIsExtractedAndValidatesWithoutConnecting() throws Exception {
        ApplicationInfo app = context.getApplicationInfo();
        assertTrue((app.flags & ApplicationInfo.FLAG_EXTRACT_NATIVE_LIBS) != 0);
        File directory = new File(app.nativeLibraryDir);
        File launcher = new File(directory, "libnuvio-openvpn2.so");
        File engine = new File(directory, "libnuvio-openvpn2-engine.so");
        assertTrue("Compatibility launcher was not installed", launcher.isFile());
        assertTrue("Compatibility launcher cannot execute", launcher.canExecute());
        assertTrue("Compatibility engine was not installed", engine.isFile());
        assertTrue("Compatibility engine cannot be loaded", engine.canRead());
        assertTrue(validateOptions(PROFILE + "fragment 1300\n"));
        assertFalse(validateOptions(PROFILE + "fragment invalid\n"));
        assertFalse(validateOptions(PROFILE + "nuvio-unsupported-option\n"));
    }

    @Test public void engineRequirementSurvivesTheExistingEncryptedEnvelope() throws Exception {
        assertEngine(PROFILE, OpenVpnEnginePolicy.Engine.OPENVPN3);
        assertEngine(PROFILE + "fragment 1300\n", OpenVpnEnginePolicy.Engine.OPENVPN2);
        String fragmentProfile = PROFILE + "fragment 1300\n";
        SecretKeySpec key = new SecretKeySpec(new byte[32], "AES");
        byte[] sealed;
        try (ProfileImport.Candidate candidate = importProfile(fragmentProfile)) {
            sealed = ProfileEnvelope.seal(ID, "Synthetic fragmentation fixture", candidate, key);
        }
        try (ProfileEnvelope.Opened opened = ProfileEnvelope.open(ID, sealed, key)) {
            assertEquals(ProfileImport.Protocol.OPENVPN, opened.getProtocol());
            char[] restored = opened.copyContents();
            try {
                assertArrayEquals(fragmentProfile.toCharArray(), restored);
                assertEquals(OpenVpnEnginePolicy.Engine.OPENVPN2, OpenVpnEnginePolicy.select(restored));
            } finally { Arrays.fill(restored, '\0'); }
        } finally { Arrays.fill(sealed, (byte) 0); }
    }

    @Test public void unsupportedFragmentRequirementsAreRejectedBeforeAnEngineCanStart() throws Exception {
        for (String invalid : new String[] {
                PROFILE.replace("proto udp", "proto tcp-client") + "fragment 1300\n",
                PROFILE + "fragment invalid\n",
                PROFILE + "fragment 1300\nmanagement forbidden-control-socket unix\n",
                PROFILE + "fragment 1300\nscript-security 2\n",
                PROFILE + "fragment 1300\ndata-ciphers none\n"
        }) {
            try (ProfileImport.Candidate ignored = importProfile(invalid)) {
                fail("Unsupported fallback configuration was imported");
            } catch (IOException expected) {
                assertFalse("Private option value appeared in the error",
                        expected.getMessage().contains("forbidden-control-socket"));
            }
            char[] copy = invalid.toCharArray();
            try {
                OpenVpnSessions.evaluate(copy);
                fail("Unsupported fallback configuration passed engine validation");
            } catch (IOException expected) {
                assertFalse("Private option value appeared in the engine error",
                        expected.getMessage().contains("forbidden-control-socket"));
            } finally { Arrays.fill(copy, '\0'); }
        }
    }

    private static void assertEngine(String profile, OpenVpnEnginePolicy.Engine expected) throws Exception {
        try (ProfileImport.Candidate candidate = importProfile(profile)) {
            assertEquals(expected, candidate.getOpenVpnEngine());
            char[] copy = candidate.copyContents();
            try { assertArrayEquals(profile.toCharArray(), copy); }
            finally { Arrays.fill(copy, '\0'); }
        }
    }

    private static ProfileImport.Candidate importProfile(String profile) throws IOException {
        return ProfileImport.read(new ByteArrayInputStream(profile.getBytes(StandardCharsets.UTF_8)),
                ProfileImport.Protocol.OPENVPN);
    }

    private boolean validateOptions(String profile) throws Exception {
        byte[] bytes = profile.getBytes(StandardCharsets.UTF_8);
        try {
            // Exercise the actual production launcher, environment and timeout.
            OpenVpn2Session.validate(context, bytes);
            return true;
        } catch (IOException rejected) {
            return false;
        } finally { Arrays.fill(bytes, (byte) 0); }
    }
}
