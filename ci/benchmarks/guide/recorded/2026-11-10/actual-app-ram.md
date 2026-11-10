# Actual app RAM observations

These measurements use the combined x86_64 debug app on an Android API36 emulator, with a separate 4,000-channel/96,000-programme fixture. They do not use the 672,000-row or 2,688,000-row query benchmark databases.

The baseline contains upstream 1049818 plus the group/Express fixes. After builds also contain parser 660fd2d9 and guide changes; the last measured functional guide source is 7d816f90, combined preview 5fca4878. APK/source hashes and source-trace hashes are in actual-app-ram-provenance.json. Every completed numeric trace, including unfavorable initial observations, is retained in actual-app-ram-samples.csv. actual-app-ram-summary.json records statistics and limitations.

## Main replay

Restore the same original six-channel app state while the preview is stopped/disabled, verify the complete restored file inventory, then start sampling only the preview package with dumpsys meminfo -s near 1 Hz. Import the fixed4k fixture through Update now. Return to All channels and the first current programme, allow 30 seconds, send 600 discrete D-pad Down events at 100 ms target spacing, and allow another 30 seconds. No media is played; the fixture returns404 for media URLs.

Settled statistics use the last-five median from the first 30 valid phase samples. Peaks are the largest sampled PSS and can miss shorter peaks. PSS is divided by 1024 to obtain MiB. Keep SwapPSS separately; missing original swap readings are unknown. Full meminfo snapshots occur after sampling and are not simultaneous substitutes for its medians.

Main final observations remain unfavorable: import peak349.874→425.522MiB, settled291.797→340.102MiB,600-row browsing peak298.213→348.089MiB, post-browse294.953→344.931MiB. The earlier after build is retained too. This is one replay per build, not evidence of a stable speed/RAM tradeoff across devices.

The replay is not an import-duration or UI-latency benchmark. Its import markers include settings/navigation and begin at different elapsed startup times:195.125s baseline,130.066s initial after build,81.051s final after build. A large final Java-memory rise occurs before the manual import. Startup work, wall-clock cache freshness, file-cache state, GC and OS residency were not isolated; the responsible source change is not established by these traces.

## Fresh-process warm checks

The original baseline has three complete 31-sample warm runs: last-five PSS medians304.228/304.452/307.608MiB; median304.452MiB. The separate full snapshots each report264KiB SwapPSS. Each run force-stops only the preview, launches, waits 8 seconds, enters LiveTV through Home/Down3, waits 3 seconds, focuses the first current programme, verifies channel0001, then samples 31 times near 1 Hz.

Earlier final warm attempts failed startup before guide sampling and are excluded. Android also slowed during installation/file transfer. After stopping the owned idle host Gradle daemon and restarting only the test emulator, a new paired control series completed with no ANR in either build. Its identical hardened navigation verifies sidebar Home before Enter.

New baseline controls: per-run settled PSS 281.648/281.900/279.496MiB, median281.648MiB. New final controls: 357.021/270.785/274.977MiB, median274.977MiB. Each run has 31 samples; statistics use the last-five median. Full SwapPSS 221KiB baseline and 225 KiB final. The first final outlier is retained; no stable RAM win is claimed. Older baseline repetitions are a separate series, not silently paired with these controls.

## Data integrity and profiling limits

Baseline and final cold database copies each contain 96,000 programme rows/4,000channel keys with empty staging. All 10 fields match:ch,start,stop,title,descr,cat,ep,icon,year,people. Logical SHA-256:d52279e67684dd804e40078bf01666503ac7e31677db9e489bfda799023ef83c. The checksum sorts all fields and hashes compact UTF8 JSON tuples with newline separators.

Forced-GC heap dumps and allocation profiling are private, separate diagnostics. They do not replace natural PSS observations and are not included here. Neither appdata backups, heap dumps, logs nor mapping files are published. The portable synthetic server is under ../../app-fixture; use the same frozen manifest/XML for both builds, and retain all observations.

The loopback server was unavailable during the clean-guest controls; both builds used the complete cached 4k database. The unchanged frozen server was restarted for separate allocation profiling. Search still found future channel 650 and Sports opened 148 upcoming games after the final 600-row traversal, with no stream activation.
