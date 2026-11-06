#!/usr/bin/env bash
set -euo pipefail

# Exercise the real production parser without building native Android libraries.
# Storage/Context placeholders only satisfy javac; calling them fails the check.
repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
work_dir="$(mktemp -d "${TMPDIR:-/tmp}/nuvio-vpn-profiles.XXXXXX")"
download_file=""
cleanup() {
    rm -rf -- "$work_dir"
    if [[ -n "$download_file" ]]; then rm -f -- "$download_file"; fi
}
trap cleanup EXIT

javac_cmd="${JAVAC:-javac}"
java_cmd="${JAVA:-java}"
wireguard_version="1.0.20260102"
wireguard_sha256="2b9c16db026496123e4db695d26d03d1958a201096c7c4c89b21077dc70f3119"
if ! grep -Fq "implementation(\"com.wireguard.android:tunnel:$wireguard_version\")" \
    "$repo_root/vpn-android/build.gradle.kts"; then
    echo "Update the host parser check version and SHA-256 to match the Android dependency." >&2
    exit 1
fi

"$javac_cmd" -version
cache_dir="${NUVIO_VPN_CACHE_DIR:-$work_dir/dependencies}"
mkdir -p -- "$cache_dir"
wireguard_aar="$cache_dir/wireguard-tunnel-$wireguard_version-$wireguard_sha256.aar"
verify_aar() {
    [[ -f "$1" ]] && printf '%s  %s\n' "$wireguard_sha256" "$1" | sha256sum --check --status
}
if verify_aar "$wireguard_aar"; then
    echo "Using SHA-256 verified WireGuard parser cache."
else
    if [[ -e "$wireguard_aar" ]]; then
        echo "Discarding WireGuard parser cache with an invalid SHA-256." >&2
        rm -f -- "$wireguard_aar"
    fi
    # Download beside the cache entry so only a verified, complete file is published.
    download_file="$(mktemp "$cache_dir/.wireguard-download.XXXXXX")"
    artifact_path="com/wireguard/android/tunnel/$wireguard_version/tunnel-$wireguard_version.aar"
    for repository in \
        https://repo.maven.apache.org/maven2 \
        https://repo1.maven.org/maven2 \
        https://maven-central.storage-download.googleapis.com/maven2; do
        echo "Downloading pinned WireGuard parser from $repository."
        # curl honors Retry-After; otherwise wait five seconds between retries.
        if curl --fail --location --retry 2 --retry-delay 5 --retry-max-time 45 \
            --silent --show-error --connect-timeout 20 --max-time 60 \
            "$repository/$artifact_path" --output "$download_file" \
            && verify_aar "$download_file"; then
            mv -f -- "$download_file" "$wireguard_aar"
            download_file=""
            break
        fi
        echo "No verified WireGuard parser obtained from $repository; trying the next mirror." >&2
    done
    if ! verify_aar "$wireguard_aar"; then
        echo "Could not download the pinned WireGuard parser with the required SHA-256." >&2
        exit 1
    fi
fi
unzip -p "$wireguard_aar" classes.jar > "$work_dir/wireguard.jar"

mkdir -p "$work_dir/classes" "$work_dir/src/android/content" "$work_dir/src/android/os" \
    "$work_dir/src/com/nuvio/vpn/android"
cat > "$work_dir/src/android/content/Context.java" <<'JAVA'
package android.content;
public class Context {
    public Context getApplicationContext() { throw new AssertionError("Android Context used in host parser check"); }
    public String getPackageName() { throw new AssertionError("Android Context used in host parser check"); }
}
JAVA
cat > "$work_dir/src/android/os/Build.java" <<'JAVA'
package android.os;
public final class Build {
    // Select the library's pre-29 numeric-address fallback on the host JVM.
    public static final class VERSION { public static final int SDK_INT = 28; }
    public static final class VERSION_CODES { public static final int Q = 29; }
}
JAVA
cat > "$work_dir/src/com/nuvio/vpn/android/AndroidProfileStore.java" <<'JAVA'
package com.nuvio.vpn.android;
import android.content.Context;
import com.nuvio.vpn.core.ProfileEnvelope;
final class AndroidProfileStore {
    AndroidProfileStore(Context context) { throw new AssertionError("Android storage used in host parser check"); }
    ProfileEnvelope.Opened open(String id) { throw new AssertionError("Android storage used in host parser check"); }
}
JAVA
cat > "$work_dir/src/com/nuvio/vpn/android/AndroidVpnSettings.java" <<'JAVA'
package com.nuvio.vpn.android;
import android.content.Context;
import com.nuvio.vpn.core.VpnPreferences;
final class AndroidVpnSettings {
    AndroidVpnSettings(Context context) { throw new AssertionError("Android settings used in host parser check"); }
    VpnPreferences read() { throw new AssertionError("Android settings used in host parser check"); }
}
JAVA

mapfile -d '' -t core_sources < <(find "$repo_root/vpn-core/src/main/java" -name '*.java' -print0)
mapfile -d '' -t core_test_sources < <(find "$repo_root/vpn-core/src/test/java" -name '*.java' -print0)
"$javac_cmd" --release 8 -d "$work_dir/classes" "${core_sources[@]}" \
    "${core_test_sources[@]}"
"$java_cmd" -cp "$work_dir/classes" com.nuvio.vpn.core.VpnCoreChecks

mapfile -d '' -t host_sources < <(find "$work_dir/src" -name '*.java' -print0)
"$javac_cmd" --release 8 -cp "$work_dir/classes:$work_dir/wireguard.jar" -d "$work_dir/classes" \
    "${host_sources[@]}" \
    "$repo_root/vpn-android/src/main/java/com/nuvio/vpn/android/WireGuardProfiles.java" \
    "$repo_root/vpn-android/src/sharedTest/java/com/nuvio/vpn/android/WireGuardProfileChecks.java"
"$java_cmd" -cp "$work_dir/classes:$work_dir/wireguard.jar" com.nuvio.vpn.android.WireGuardProfileChecks
