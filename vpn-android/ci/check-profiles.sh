#!/usr/bin/env bash
set -euo pipefail

# Exercise the real production parser without building native Android libraries.
# Storage/Context placeholders only satisfy javac; calling them fails the check.
repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
work_dir="$(mktemp -d "${TMPDIR:-/tmp}/nuvio-vpn-profiles.XXXXXX")"
trap 'rm -rf -- "$work_dir"' EXIT

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
curl --fail --location --retry 3 --silent --show-error --connect-timeout 20 --max-time 120 \
    "https://repo.maven.apache.org/maven2/com/wireguard/android/tunnel/$wireguard_version/tunnel-$wireguard_version.aar" \
    --output "$work_dir/wireguard.aar"
printf '%s  %s\n' "$wireguard_sha256" "$work_dir/wireguard.aar" | sha256sum --check --status
unzip -p "$work_dir/wireguard.aar" classes.jar > "$work_dir/wireguard.jar"

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
"$javac_cmd" --release 8 -d "$work_dir/classes" "${core_sources[@]}" \
    "$repo_root/vpn-core/src/test/java/com/nuvio/vpn/core/VpnCoreChecks.java"
"$java_cmd" -cp "$work_dir/classes" com.nuvio.vpn.core.VpnCoreChecks

mapfile -d '' -t host_sources < <(find "$work_dir/src" -name '*.java' -print0)
"$javac_cmd" --release 8 -cp "$work_dir/classes:$work_dir/wireguard.jar" -d "$work_dir/classes" \
    "${host_sources[@]}" \
    "$repo_root/vpn-android/src/main/java/com/nuvio/vpn/android/WireGuardProfiles.java" \
    "$repo_root/vpn-android/src/sharedTest/java/com/nuvio/vpn/android/WireGuardProfileChecks.java"
"$java_cmd" -cp "$work_dir/classes:$work_dir/wireguard.jar" com.nuvio.vpn.android.WireGuardProfileChecks
