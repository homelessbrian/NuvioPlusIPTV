package com.nuvio.vpn.android;

import com.nuvio.vpn.core.OpenVpnEnginePolicy;
import com.nuvio.vpn.core.ProfileEnvelope;
import com.nuvio.vpn.core.ProfileImport;
import java.io.IOException;
import java.util.Arrays;

/** Compatibility is a property of a validated profile, never a retry after an
 * authentication, certificate or transport failure in the default engine. */
final class OpenVpnSessions {
    private OpenVpnSessions() {}

    static int evaluate(char[] text) throws IOException {
        try {
            if (OpenVpnEnginePolicy.select(text) == OpenVpnEnginePolicy.Engine.OPENVPN3)
                return OpenVpnSession.evaluateCore3(text);
            char[] preflight = OpenVpn2Config.core3Preflight(text);
            int readiness = OpenVpnSession.evaluateCore3(preflight);
            try (OpenVpn2Config input = OpenVpn2Config.prepare(text)) {
                OpenVpn2Session.validate(VpnPlaybackProtection.application(), input.bytes);
                if (input.needsCredentials) return 2;
                return readiness;
            }
        } finally { Arrays.fill(text, '\0'); }
    }

    static OpenVpnEngine selected(VpnConnectionService service, String id, HttpStreamTunnel proxy) throws Exception {
        try (ProfileEnvelope.Opened opened = new AndroidProfileStore(service).open(id)) {
            if (opened.getProtocol() != ProfileImport.Protocol.OPENVPN) throw new IOException("Profile changed.");
            char[] text = opened.copyContents();
            try {
                if (OpenVpnEnginePolicy.select(text) == OpenVpnEnginePolicy.Engine.OPENVPN3)
                    return OpenVpnSession.selectedCore3(service, text, proxy);
                // Saved/backup content is validated again against the installed engine.
                char[] check = text.clone();
                if (evaluate(check) != 1) throw new IOException("Set the OpenVPN service credentials in VPN profiles.");
                return OpenVpn2Session.start(service, proxy, OpenVpn2Config.prepare(text));
            } finally { Arrays.fill(text, '\0'); }
        }
    }
}
