# Android playback VPN

TV and Mobile open the same settings page from **Settings > VPN profiles**. Matching playback waits for authentication and a tunneled HTTPS probe. Media requests, redirects, byte ranges, subtitles and preloads follow the selected mode.

- **Off** uses the ordinary playback connection.
- **HTTP streams only** protects remote HTTP media. HTTPS stays direct.
- **HTTP + HTTPS streams** protects both playback protocols. HTTPS keeps its original TLS session.
- **Full app** uses Android VPN consent and restricts the VPN interface to the host package.

## Profiles and sync

Import a WireGuard configuration or a self-contained OpenVPN TUN profile. OpenVPN supports UDP and TCP, inline CA and certificate material, and service credentials. Importing a profile with a bare `auth-user-pass` directive prompts for credentials. Use **OpenVPN service credentials** to update a previously imported profile. Desktop scripts, external credential files, interactive authentication challenges and external PKI are outside the supported profile format.

OpenVPN's `client` directive and its equivalent `tls-client` plus `pull` form are both accepted. OpenVPN 3 does not support the `fragment` option. Use a provider export without client-side fragmentation, or choose another compatible protocol. Do not remove `fragment` from a profile when the server requires it.

Self-contained OpenVPN profiles may keep provider fallback servers in connection blocks and revocation lists in inline crl-verify blocks. Unsupported or oversized connection blocks are rejected rather than silently dropping their settings.

OpenVPN also accepts a provider ZIP containing exactly one `.ovpn` and its referenced UTF-8 CA, certificate, private key, TLS key or certificate revocation list files (`.crt`, `.cer`, `.pem`, `.key`, `.crl`). References must use relative archive paths within the configuration's folder; parent paths, ambiguous names and files outside the ZIP are rejected. The archive is limited to 32 entries and 1 MiB compressed, decompressed and after conversion. Only referenced material is inlined in memory before the ordinary profile and engine validation; the saved and synced profile stays self-contained. ZIP scripts, credential files and binary PKCS#12 files are unsupported.

WireGuard profiles require interface addresses, DNS servers and one peer with an endpoint. Each DNS server must use an IP family present in the interface addresses. Include a default route for every IP family configured in the interface or peer routes. A provider export with only IPv4 interface addresses and DNS can use `0.0.0.0/0`; a dual-stack export also needs `::/0`. An IPv4-only export can retain `::/0` as a blocking route. DNS search domains require Full app mode.

**Apply VPN settings to** defaults to **All profiles**. The selected VPN and protection mode apply to current and future Nuvio profiles. **Only this profile** starts a separate choice for the active Nuvio profile. Switching back to **All profiles** uses the current shared settings again. Setup Sync restores both the shared choice and individual overrides.

Profiles are encrypted with the device’s Android Keystore. HomelessBrian’s Setup Sync carries a portable `vpn_profiles_v1` entry with the configurations, selected profile and scope. Exported backups contain private VPN configuration, including saved OpenVPN service credentials. The destination validates and re-encrypts the profiles with its own key. Older backups without a VPN entry preserve the local setup.

## Integration and verification

Keep `vpn-core` and `vpn-android` source in sync across the two repositories. The Java core owns route selection, profile preflight, encryption envelopes and versioned settings. Android owns the Keystore, playback gate, settings UI, private playback proxy and app-scoped VPN service. Host adapters connect these to the players and Setup Sync.

Use JDK 21, Go 1.27.3, NDK 29.0.14206865 and SDK CMake 3.22.1. `NUVIO_GO` selects a non-default Go executable. The Go module pins WireGuard and its private IP stack. CMake builds OpenVPN 3 Core 3.11.8 with pinned mbed TLS, Asio and LZ4 sources. Dependency notices ship in the APK. Build the standalone module and its host sequentially within each checkout.

```sh
./gradlew -p vpn-android :lintDebug :testDebugUnitTest :vpn-core:check :checkHttpTunnel
./gradlew -p vpn-android :device-tests:assembleDebug :device-tests:assembleDebugAndroidTest
```

Install the two device-test APKs on an emulator and run:

```sh
adb shell am instrument -w com.nuvio.vpn.android.test.test/androidx.test.runner.AndroidJUnitRunner
```

The harness uses a separate application package and Keystore. Its optional OpenVPN test expects a synthetic profile in the harness’s private `files/acceptance.ovpn` and a server reachable through the tunnel. For a profile that prompts for credentials, configure the test server to accept `fixture-user` and `fixture-password`. Serve HTTP 206 with bytes 31 through 158 containing those byte values, or pass the base64 SHA-256 of the expected 128 bytes as `openvpnReference`.

Run the test with both UDP and TCP configurations. `openvpnScope` accepts `HTTP_ONLY`, `HTTP_HTTPS` and `APP_WIDE`. Grant the **test package** VPN consent on the emulator for the Full app case.

```sh
adb shell am instrument -w -e class com.nuvio.vpn.android.VpnDeviceTest#openVpnAuthenticatedPlaybackRange \
  -e openvpnScope HTTP_HTTPS -e openvpnRangeUrl http://TEST_SERVER/movie \
  com.nuvio.vpn.android.test.test/androidx.test.runner.AndroidJUnitRunner
```

Host builds use `:app:assembleFullDebug` and `:app:assembleFullRelease` on TV and `:androidApp:assembleFullDebug` and `:androidApp:assembleFullRelease` on Mobile. Check actual playback, HTTPS bypass in HTTP-only mode, a seek, next-episode handoff and fresh-login restore on a device.

Run `bash vpn-android/ci/check-profiles.sh` to exercise profile validation with the pinned WireGuard parser. The check uses fail-fast Android storage placeholders; it does not validate a live VPN or Android storage.
