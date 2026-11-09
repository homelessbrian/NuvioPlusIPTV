# OpenVPN 2 compatibility process

OpenVPN 3 remains the default engine. The Android session factory selects this
separate process only when a profile explicitly requires supported OpenVPN 2
fragmentation. Authentication or TLS errors never trigger engine downgrade.

`CMakeLists.txt` builds the pinned OpenVPN 2.7.9 release, pinned Android build
support, OpenSSL, LZO and LZ4 from source. The APK contains two ABI-specific files:
`libnuvio-openvpn2.so` is a PIE launcher and `libnuvio-openvpn2-engine.so` contains
the engine and static dependencies. Both must be extracted into the read-only
Android native library directory (`useLegacyPackaging = true` and
`android:extractNativeLibs="true"`). Do not copy executables into writable app data.
Neither executable links into the JVM or the OpenVPN 3 library. Android suppresses
CMake RUNPATH handling, so the session sets the child process library search path
to that canonical native library directory alone.

The launcher ties its lifetime to the parent process, disables core dumps and
suppresses raw engine output. The Android owner supplies configuration on stdin
and receives state, byte counts, transport descriptors and TUN requests over a
private Unix management socket. Configuration and credentials are never written
to a temporary profile file. Profile-supplied management, executable hooks,
external files and unsupported protection requirements remain forbidden.

`--nuvio-validate` must be the first launcher flag. It runs the same native option
parser and postprocessing and exits before management, network or TUN setup.
Exit zero establishes option compatibility only; the Java factory also uses
OpenVPN 3 evaluation with the fragmentation directive removed for supplemental
profile and credential preflight. This does not establish server trust or successful
authentication. Readiness still requires actual TLS validation, authentication
and a tunneled HTTPS probe in the selected protection mode.

`patch-engine.cmake` applies reviewed fail-closed Android changes without editing
the source cache: refused management requests abort, and unsupported secure,
custom-port or domain-specific DNS requirements abort before conversion to plain
DNS addresses. Fragmentation and all TLS certificate checks stay in the engine.

The executable and its launcher are GPL-2.0-only, with the upstream linking
exceptions retained. Bundled dependency notices are in
`src/main/assets/vpn-licenses/openvpn2`. Distribute complete corresponding source
for the separate executable and linked dependencies together with its binaries.
Do not relicense engine code under the application's license or copy it into
OpenVPN 3. This architecture keeps the executable's licensing boundary explicit;
it does not remove a distributor's obligations under the respective licenses.
