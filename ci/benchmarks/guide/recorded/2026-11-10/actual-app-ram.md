# Actual app RAM observations

These measurements use the combined x86_64 debug app on an Android API36 emulator, with a separate4,000-channel/96,000-programme fixture. They do not use the672,000-row or2,688,000-row query benchmark databases.

The baseline contains upstream1049818 plus the group/Express fixes. After builds also contain parser660fd2d9 and guide changes; the last measured functional guide source is7d816f90, combined preview5fca4878. APK/source hashes and source-trace hashes are in actual-app-ram-provenance.json. Every completed numeric trace, including unfavorable initial observations, is retained in actual-app-ram-samples.csv. actual-app-ram-summary.json records statistics and limitations.

## Main replay

Restore the same original six-channel app state while the preview is stopped/disabled, verify the complete restored file inventory, then start sampling only the preview package with dumpsys meminfo -s near1Hz. Import the fixed4k fixture through Update now. Return to All channels and the first current programme, allow30seconds, send600 discrete D-pad Down events at100ms target spacing, and allow another30seconds. No media is played; the fixture returns404 for media URLs.

Settled statistics use the last-five median from the first30 valid phase samples. Peaks are the largest sampled PSS and can miss shorter peaks. PSS is divided by1024 to obtain MiB. Keep SwapPSS separately; missing original swap readings are unknown. Full meminfo snapshots occur after sampling and are not simultaneous substitutes for its medians.

Main final observations remain unfavorable: import peak349.874→425.522MiB, settled291.797→340.102MiB,600-row browsing peak298.213→348.089MiB, post-browse294.953→344.931MiB. The earlier after build is retained too. This is one replay per build, not evidence of a stable speed/RAM tradeoff across devices.

The replay is not an import-duration or UI-latency benchmark. Its import markers include settings/navigation and begin at different elapsed startup times:195.125s baseline,130.066s initial after build,81.051s final after build. A large final Java-memory rise occurs before the manual import. Startup work, wall-clock cache freshness, file-cache state, GC and OS residency were not isolated; the responsible source change is not established by these traces.

## Fresh-process warm checks

The original baseline has three complete31-sample warm runs: last-five PSS medians304.228/304.452/307.608MiB; median304.452MiB. The separate full snapshots each report264KiB SwapPSS. Each run force-stops only the preview, launches, waits8seconds, enters LiveTV through Home/Down3, waits3seconds, focuses the first current programme, verifies channel0001, then samples31times near1Hz.

Final warm attempts failed startup before guide sampling and are excluded. The emulator also slowed during package installation and read-only file transfers. The owned idle host Gradle daemon was stopped and the test emulator restarted for a new paired control series. The older three baseline runs must not be paired silently with controls collected after that boundary. No final warm-RAM reduction is claimed.

## Data integrity and profiling limits

Baseline and final cold database copies each contain96,000programme rows/4,000channel keys with empty staging. All10 fields match:ch,start,stop,title,descr,cat,ep,icon,year,people. Logical SHA-256:d52279e67684dd804e40078bf01666503ac7e31677db9e489bfda799023ef83c. The checksum sorts all fields and hashes compact UTF8 JSON tuples with newline separators.

Forced-GC heap dumps and allocation profiling are private, separate diagnostics. They do not replace natural PSS observations and are not included here. Neither appdata backups, heap dumps, logs nor mapping files are published. The portable synthetic server is under ../../app-fixture; use the same frozen manifest/XML for both builds, and retain all observations.
