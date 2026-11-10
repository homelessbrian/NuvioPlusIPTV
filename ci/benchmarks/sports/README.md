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
