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
