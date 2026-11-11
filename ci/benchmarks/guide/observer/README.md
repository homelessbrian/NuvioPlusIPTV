# Matched instrumentation RAM observer

This packages the exact reviewed **build-0004** Java/manifest used for the matched instrumented comparison. [Source hashes](source-sha256.json) pin their bytes; `.gitattributes` preserves LF endings. The recorded companion APK was 12,432 bytes, SHA-256 `09977bbfac81adf91991da98ef895f5d67a2ab693785696a400ed106d4d99f0f`. No binary, app state, profile data or restore/measurement driver is included. The [six-trial primary comparison](../recorded/2026-11-10/matched-observer-0004/README.md) preserves every numeric sample.

## Host-only build

Requires JDK 21, Android SDK platform 36/build-tools 36.0.0, PowerShell (7 for non-Windows hosts), and an existing Android debug keystore matching the target APK's signing certificate (`androiddebugkey`, standard debug password `android`). From the repository root:

```powershell
pwsh -File ci/benchmarks/guide/observer/build.ps1 -JavaHome /path/to/jdk-21 -AndroidSdk /path/to/android-sdk -DebugKeyStore /path/to/debug.keystore -OutDirectory ci/benchmarks/guide/observer/.run/build-01
```

Quote Windows paths as separate arguments. The script requires a new output directory, verifies pinned source bytes, and builds/signs only this companion through javac, D8, aapt2 and ZIP alignment. It records input/tool/APK hashes, signature verification and the packaged manifest. It never installs, starts a process on a device, restores state or deletes app data. Rebuilt APK bytes can differ with tools, ZIP timestamps and signing keys; run both comparison sides with the same rebuilt companion. **The portable build passed under PowerShell 7 with the pinned toolchain during a measurement setup gap.** Signature v2 and alignment passed; both compiled manifest and `classes.dex` were byte-identical to the measured companion. Container hashes differ. See [validation](build-validation.json). No rebuilt companion was installed or substituted in the comparison.

## Target and app packaging

The fixed instrumentation package is `com.nuvio.bench.ram20261110`, targeting only `com.nuvio.livetv.groupvpnpreview`'s main process. It has no launcher/resources/dependencies/permissions or AndroidX/JUnit runner. The two measured app APKs were built from baseline `67570ddd` and combined candidate `ac39dab9`, with these identical test-packaging overrides in `app/build.gradle.kts`:

- `minutesSince2026` fixed to `451216`, giving test version code `200451216`.
- Non-Playstore debug `applicationId` set to `com.nuvio.livetv.groupvpnpreview`; the Playstore debug ID remains `com.nuvio.appdebug`.

The observer does not rewrite either original built target APK. Trial launch uses `am instrument --no-test-api-access -e duration_seconds 900` with the fixed component. This explicitly disables test-API access; there is no profiling, no-restart, animation, hidden-API exemption or UI-automation flag. The build script does not issue that command.

Both target builds use `:app:assembleFullDebug -Pdebuggable=true -PlivetvTestBuild=true -Pandroid.injected.build.abi=x86_64`. The test flag sets `NUVIO_CHANNEL=test`, which also enables the existing Sports-table build/full-guide verification path in both revisions. It is additional test-channel work, not a stable-channel configuration. The observer build itself does not set or change these app flags.

## Measurements and lifetime

`onCreate` checks the target package and application UID against the executing VM's UID, then exclusively creates `cache/guide_ram_nogc_20261110.csv`. Existing files/links fail. A background daemon logs about once per second, flushing each numeric row and skipping missed ticks. One MemoryInfo, builder, character buffer and writer are reused. `onStart` requests the normal MainActivity through `Intent.makeMainActivity` plus `FLAG_ACTIVITY_NEW_TASK`. No instrumentation `finish` is called; the bounded logger stops after its duration (default 600 seconds, allowed 2..1800), while the trial operator later force-stops the target. Partial final rows, gaps and failed captures must be preserved and labelled.

The CSV reads `Runtime.totalMemory/freeMemory/maxMemory` and `used = total - free` in **bytes**, native heap counters in **bytes**, and a reused `Debug.MemoryInfo` in **KiB**. Runtime used includes garbage not yet reclaimed and is not retained heap. Reads are sequential, not atomic. ART GC counts/times before/after each sample are retained; collections during/between samples and outliers are not discarded.

On the inspected Android 16 implementation, raw `getTotalPss()` includes SwapPSS. Resident component PSS is `dalvikPss + nativePss + otherPss`; derived SwapPSS is raw total minus that sum. The CSV also records each component and public `summary.total-swap`. The hidden SwapPSS-support flag is explicitly unknown (`-1`); zero derived swap does not prove kernel support. These public APIs avoid the full `dumpMemInfo` GC path. [AOSP Debug.java](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android16-release/core/java/android/os/Debug.java), [native implementation](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android16-release/core/jni/android_os_Debug.cpp)

## Clock and observation boundary

The analysis origin is the unique positive **device** `main_launch_requested_elapsed_ns`, not host command time or Home/Guide readiness. Earlier rows remain separate; `since_oncreate_ns` uses the earlier instrumentation-entry clock. Startup peaks use `[0,120)` seconds from launch, with late summaries fixed at `[90,120)` and at least 20 complete samples. Guide readiness must be observed before 90 seconds. Only trial 3 on each side browses 600 rows; its separate settle summary uses `[30,60)` seconds after the recorded browse-end marker, also requiring 20 samples. Preserve all values, gaps and GC-coincident rows; never move a window to replace missing data.

Instrumentation adds class loading, a thread, buffers, allocation/statistics reads and CSV writes, and changes OOM/process-state treatment. GUI hierarchy dumps, focus checks and screenshots at matched milestones add further overhead. The first logger row may follow initial application work. This is a **matched instrumented A/B comparison**, not passive pristine-app execution or zero overhead. It does not establish SHIELD playback or device behavior.

Earlier full per-package `dumpsys meminfo` observations were corrected to **GC-perturbed diagnostics** because that framework path requests GC. Preserve their unfavorable results and failed attempts separately; never pool them with this series or reuse their former natural/acceptance label. No forced GC, heap dump, allocation tracker, JVMTI, debugger, tracing, performance snapshot or animation change is requested by this observer. [AOSP ActivityThread](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android16-release/core/java/android/app/ActivityThread.java), [Instrumentation](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android16-release/core/java/android/app/Instrumentation.java)
