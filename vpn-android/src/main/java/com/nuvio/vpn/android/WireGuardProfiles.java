package com.nuvio.vpn.android;

import android.content.Context;
import com.nuvio.vpn.core.ProfileEnvelope;
import com.nuvio.vpn.core.ProfileImport;
import com.nuvio.vpn.core.ProtectionMode;
import com.nuvio.vpn.core.VpnPreferences;
import com.wireguard.config.BadConfigException;
import com.wireguard.config.Config;
import com.wireguard.config.InetNetwork;
import com.wireguard.config.Interface;
import com.wireguard.config.Peer;
import java.io.BufferedReader;
import java.io.CharArrayReader;
import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.security.GeneralSecurityException;
import java.util.Arrays;

/** Real WireGuard parsing and app-scope normalization. Does not start a VPN. */
public final class WireGuardProfiles {
    private WireGuardProfiles() {}

    public static Config parse(ProfileImport.Candidate candidate) throws IOException {
        if (candidate == null || candidate.getProtocol() != ProfileImport.Protocol.WIREGUARD) {
            throw new IOException("A WireGuard configuration is required.");
        }
        return parseContents(candidate.copyContents());
    }

    /** Reopens the encrypted selection, validates it, and prepares host-only routing; starts no service. */
    static Config prepareSelected(Context context, ProtectionMode scope) throws IOException, GeneralSecurityException {
        Context app = context.getApplicationContext();
        VpnPreferences preferences = new AndroidVpnSettings(app).read();
        if (scope == ProtectionMode.OFF || preferences.getMode() != scope || preferences.getSelectedProfileId() == null) {
            throw new IOException("Select a VPN profile and protection scope first.");
        }
        try (ProfileEnvelope.Opened opened = new AndroidProfileStore(app).open(preferences.getSelectedProfileId())) {
            if (opened.getProtocol() != ProfileImport.Protocol.WIREGUARD) {
                throw new IOException("A WireGuard configuration is required.");
            }
            Config config = parseContents(opened.copyContents());
            validateForScope(config, scope);
            return forAppWide(config, app.getPackageName());
        }
    }

    /**
     * Backup restore: only the basic shape is checked, so one older profile that a later
     * rule would refuse never blocks restoring the rest. It is still checked again before use.
     */
    static void parseForRestore(ProfileImport.Candidate candidate) throws IOException {
        if (candidate == null || candidate.getProtocol() != ProfileImport.Protocol.WIREGUARD) {
            throw new IOException("A WireGuard configuration is required.");
        }
        char[] text = candidate.copyContents();
        try (BufferedReader reader = new BufferedReader(new CharArrayReader(text))) {
            Config config = Config.parse(reader);
            if (config.getInterface().getAddresses().isEmpty() || config.getPeers().isEmpty()) {
                throw new IOException("WireGuard interface addresses and peers are required.");
            }
        } catch (BadConfigException | IllegalArgumentException e) {
            throw new IOException("WireGuard configuration is invalid.");
        } finally { Arrays.fill(text, '\0'); }
    }

    private static Config parseContents(char[] text) throws IOException {
        try (BufferedReader reader = new BufferedReader(new CharArrayReader(text))) {
            Config config = Config.parse(reader);
            validate(config);
            return config;
        } catch (BadConfigException | IllegalArgumentException e) {
            // WireGuard parser diagnostics can contain the rejected private key or other input.
            throw new IOException("WireGuard configuration is invalid.");
        } finally { Arrays.fill(text, '\0'); }
    }

    /** Common requirements for every supported mode, checked before import and again before use. */
    private static void validate(Config config) throws IOException {
        if (config == null || config.getInterface().getAddresses().isEmpty()) {
            throw new IOException("WireGuard profiles require an interface Address.");
        }
        if (config.getPeers().size() != 1 || !config.getPeers().get(0).getEndpoint().isPresent()) {
            throw new IOException("WireGuard profiles require exactly one remote VPN peer with an Endpoint.");
        }
        if (config.getInterface().getDnsServers().isEmpty()) {
            throw new IOException("WireGuard profiles require at least one DNS server IP address.");
        }
        // GoBackend's default-route/single-peer path leaves unconfigured address families blocked.
        // Reject partial routes for a family the configuration enables, which could otherwise bypass.
        boolean configuredV4 = false;
        boolean configuredV6 = false;
        boolean defaultV4 = false;
        boolean defaultV6 = false;
        for (InetNetwork network : config.getInterface().getAddresses()) {
            if (network.getAddress() instanceof Inet6Address) configuredV6 = true; else configuredV4 = true;
        }
        for (InetAddress address : config.getInterface().getDnsServers()) {
            // A DNS server's family needs a default route below, but not an interface address of its own.
            if (address instanceof Inet6Address) configuredV6 = true; else configuredV4 = true;
        }
        for (Peer peer : config.getPeers()) for (InetNetwork network : peer.getAllowedIps()) {
            if (network.getAddress() instanceof Inet6Address) {
                configuredV6 = true;
                if (network.getMask() == 0) defaultV6 = true;
            } else {
                configuredV4 = true;
                if (network.getMask() == 0) defaultV4 = true;
            }
        }
        if ((!defaultV4 && !defaultV6) || (configuredV4 && !defaultV4) || (configuredV6 && !defaultV6)) {
            throw new IOException("WireGuard profiles require default AllowedIPs routes for every configured IP family "
                    + "(0.0.0.0/0 for IPv4 and ::/0 for IPv6).");
        }
    }

    static void validateForScope(Config config, ProtectionMode scope) throws IOException {
        validate(config);
        if (scope != null && scope.isPlaybackScoped() && !config.getInterface().getDnsSearchDomains().isEmpty()) {
            throw new IOException("Streams-only modes do not support DNS search domains. "
                    + "Use Full app mode or a profile with DNS server IP addresses only.");
        }
    }

    /**
     * Discard imported application filters and constrain the interface to this host app.
     * The real backend must additionally enforce full routing and validate handshakes.
     */
    public static Config forAppWide(Config config, String hostPackage) throws IOException {
        if (hostPackage == null || !hostPackage.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")) {
            throw new IOException("A host application package is required.");
        }
        validate(config);
        Interface original = config.getInterface();
        Interface.Builder builder = new Interface.Builder().addAddresses(original.getAddresses())
                .addDnsServers(original.getDnsServers()).addDnsSearchDomains(original.getDnsSearchDomains())
                .setKeyPair(original.getKeyPair()).includeApplication(hostPackage);
        try {
            if (original.getListenPort().isPresent()) builder.setListenPort(original.getListenPort().get());
            if (original.getMtu().isPresent()) builder.setMtu(original.getMtu().get());
            return new Config.Builder().setInterface(builder.build()).addPeers(config.getPeers()).build();
        } catch (BadConfigException e) { throw new IOException("WireGuard interface configuration is invalid."); }
    }
}
