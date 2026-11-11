# Sports host allocation checks

These JDK-only diagnostics use `java.management` and HotSpot's allocation counter. They are deliberately outside Android's `app/src/test` source set. Their numbers measure cumulative caller allocation and retained result identities, **not Android process memory or PSS**. No forced GC or live network is used.

Run with PowerShell 7 and a JDK that supports `com.sun.management.ThreadMXBean`:

```powershell
./ci/benchmarks/sports/run.ps1 -JavaHome $env:JAVA_HOME -AppClasses ./app/build/tmp/kotlin-classes/fullDebug
```

The runner uses cached Kotlin/JUnit/JSON/OkHttp/Okio dependencies, compiles the exact listed production Sports sources, and also runs the Android-compatible cache and HockeyTech functional tests. `AppClasses` supplies the remaining application model dependencies from a compatible build. Each run writes to a fresh temporary directory; pass `-OutputDirectory` to choose a fresh evidence directory. Both HockeyTech tests and its host benchmark reuse the single synthetic fixture in `SportsHockeyTechTest.kt`.

The cache diagnostic holds eight callers inside one cold-key request before releasing the loader. It reports loader calls, per-thread cumulative allocation, distinct returned payload identities and retained payload characters. The season diagnostic reads three days from a cached 3,657-game synthetic season and reports allocation, all 37 output events, serialized bytes and SHA-256. Use the same JVM, fixture and runner when comparing source revisions; the preserved earlier measurements remain historical evidence.

`SportsFeedDateFormattingTest` exercises actual scoreboard output against the former fresh-formatter normalization path, including DST gaps/repeated hours, Eastern midnights, negative epochs, Gregorian-cutover dates, process-default changes and eight concurrent workers. The original full nested-JSON day/month/season checks also run.

For a pinned incremental comparison, keep the test/benchmark sources identical and select the production checkout explicitly:

```powershell
./ci/benchmarks/sports/run.ps1 -JavaHome $env:JAVA_HOME -AppClasses /path/to/compatible/app/classes -ProductionSourceRoot /path/to/pinned/30a5fc7b -OutputDirectory /path/to/new-before-results
./ci/benchmarks/sports/run.ps1 -JavaHome $env:JAVA_HOME -AppClasses /path/to/compatible/app/classes -OutputDirectory /path/to/new-after-results
```

Optional `-MatchSourceClasses /path/to/compiled/selected/source/classes` also runs `SportsMatchInputsAllocationBenchmark`. Compile the actual `SportsRepository` source from the selected before/after revision first; that directory takes precedence over the compatible application dependencies. The diagnostic invokes its private `MatchInputs` class without changing production visibility. For the pinned eager constructor, the adapter supplies the exact pre-change filtering argument; for the lazy constructor it uses the captured snapshot/settings directly. Four snapshot-only fences plus one real consumer must traverse all 26,716 catalogue entries five times before and once after; visibility/pick/parental behavior and concurrent list identity remain checked. This measures channel-materialization allocation only, excluding preference reads, database work and matching. It does not measure application PSS.

## Matched current-base HockeyTech pair

Use `-HockeyOnly` on **both** sides when comparing upstream `67570ddd` with `99fd3388` or a later checkout of the same Sports sources. It compiles the six exact production sources from `-ProductionSourceRoot` and runs these classes in this exact order: `SportsHockeyTechTest`, `SportsFeedDateFormattingTest`, `SportsHockeyTechAllocationBenchmark` (seven tests total). The cache/loading-registry and MatchInputs tests/diagnostics are omitted from both runs; upstream lacks their newer contracts. `-HockeyOnly` rejects `-MatchSourceClasses`.

Run the committed harness/test sources from the current checkout while pointing at clean, pinned production checkouts. Keep the same JDK, dependencies, test sources and warmup order, and use fresh output directories:

```powershell
./ci/benchmarks/sports/run.ps1 -HockeyOnly -JavaHome $env:JAVA_HOME -AppClasses /path/to/compatible/app/classes -ProductionSourceRoot /path/to/pinned/67570ddd -OutputDirectory /path/to/new-overall-before
./ci/benchmarks/sports/run.ps1 -HockeyOnly -JavaHome $env:JAVA_HOME -AppClasses /path/to/compatible/app/classes -ProductionSourceRoot /path/to/pinned/99fd3388 -OutputDirectory /path/to/new-overall-after
```

Sanitized summaries in `recorded/2026-11-10/` distinguish the original experiments and a separate public-harness reproduction:

- Original matched upstream HockeyTech-only pair: **142,703,848 → 48,346,440 allocated bytes** (**136.09 → 46.11 MiB**), seven tests per build. Both returned 37 events/30,974 bytes and identical SHA-256. The original run used a local selection-only wrapper; its observations remain in `overall-hockey-67570ddd-vs-99fd3388.json`.
- Committed `-HockeyOnly` public-harness reproduction: **142,088,064 → 48,769,344 bytes** (**135.51 → 46.51 MiB**), seven tests per build, all passing. It preserves the same six-test warmup and final diagnostic, 37 events/30,974 bytes and output SHA. `public-hockey-only-67570ddd-vs-99fd3388.json` records this separate pair, the exact six production source hashes per revision, identical test hashes and committed runner hash. It does not replace the original pair.
- Incremental `30a5fc7b` → `99fd3388`: **85,077,984 → 49,325,472 bytes** (**81.14 → 47.04 MiB**) for the season diagnostic and **1,901,744 → 381,688 bytes** for the MatchInputs component, with 29 tests per build. Its larger suite/order differs from the seven-test overall pair; do not pool the numbers. The earlier 131.63 MiB source diagnostic remains historical and is not this current-base observation.

These summaries describe one matched pair per experiment, not Android PSS, a cross-device RAM estimate or a benchmark distribution. Source hashes describe actual compiled source bytes; exported Git blobs and Windows checkouts can differ solely in line endings. Recorded summaries contain no application data, private paths, credentials or raw provider responses.
## Shared-guide normalization and matching

The [new native-SQLite source comparison](recorded/2026-11-10/normalization-and-fanout/README.txt) measures the Unicode finishing and alias fanout changes together. It preserves every binding, all ten listing fields and complete real-match/guide-event output. This is a new deterministic corpus, distinct from the APK RAM fixture. The source comparison is `302d4e07` to `7edc641a`; both already contain the earlier guide/Sports changes.

| Shape / mode | Pairs | Median seconds before → after | Caller allocation MiB before → after |
| --- | ---: | ---: | ---: |
| 4,000 channels / stable GUIDE | 3 | 1.373 → 1.085 | 722.634 → 582.668 |
| 4,000 channels / test table + both full-guide checks | 3 | 2.428 → 1.758 | 1,391.515 → 1,116.078 |
| 26,716 shared channels / stable GUIDE | 1 | 5.989 → 3.258 | 4,765.499 → 2,576.659 |
| 26,716 shared channels / test table + both checks | 1 | 12.754 → 6.240 | 9,092.992 → 4,834.492 |

These are **host component timings and cumulative calling-thread allocations**, not Android app RAM/PSS, retained heap or physical-device latency. Shared rows are a single pair, not a repeat distribution. All 16 trials/ranges/GC counts remain in the records; no p95 or outlier filtering. Stable and experimental paths are separate; the experimental path still builds its table and verifies it against the complete guide. The 26,716-channel last-match proof passed on both sources and three additional native alias/shift/metadata regressions passed afterward.

The [offline runner](run-listing-fanout.py) requires explicit JDK, Android API jar, compatible cached app classes and BuildConfig classes; `--help` lists the paths. It defaults to preparation only and needs `--execute` to compile/test. It never contacts a device or the network. A fresh output directory preserves prior runs. Cached dependencies include Kotlin 2.3.0, Robolectric 4.17 and SDK 34's instrumented jar; the recorded native backend was Windows x86_64. The public CLI path arguments were packaged after recording; workload/compiler/timing logic is unchanged and both driver hashes are recorded. Only syntax/help/dry-run verification was repeated after that packaging.

The separate [actual Android normalization component](recorded/2026-11-10/android-normalization/README.txt) compiles the complete before/after `SportsCatalog`, puts its DEX ahead of the same dependency APK and checks every normalized output. Three fresh-VM pairs of 192,000 calls give median **3.748 → 2.161 seconds** and reported allocated bytes **662,437,888 → 340,656,128**. Every output hash agrees. All six observed VM caps were **192 MiB**, despite requesting 512 MiB; this is a shell component, not the 512 MiB application VM. All trials, ordinary GC/runtime counters and the initial compile-dependency correction are retained. The measured Java source and protocol are included; private automation/build scripts and APKs are not.
