Sports normalization and shared-guide component checks

This is a NEW deterministic, in-code synthetic corpus (sports-fanout-v1-real-game),
not the previously recorded APK RAM fixture bytes. The primary shape contains
4,000 bindings / 4,000 guide channels / 96,000 stored and logical programmes.
The shared shape contains 26,716 bindings / 4,477 guide channels / 107,448 stored
and 641,184 projected logical programmes. Binding shifts are -1h:8,905, 0:8,906,
+1h:8,905. All bindings and all ten programme fields participate in the input hash.

Each guide has 24 two-hour slots around the fixed source clock 1800000000000ms.
Most titles are neutral Team matchup titles; two guide IDs have a known Live NFL
Ravens/Steelers title in slot3, including the guide feeding the final channel.
Descriptions use the public fixture's Unicode text. Episode, icon, year and
people fields are populated too. This workload guarantees actual matches and
guide events rather than treating two empty outputs as matching parity.

The source comparison combines the Unicode finishing change and normalization
before shared-guide alias fanout. See provenance.json for exact source hashes.
No production source was instrumented. Each record comes from a fresh host JVM
and fresh matcher. Fixture creation, channel construction, and the canonical
projected ten-field input hash run BEFORE the timer. The timed calls are
prepareTable, guideEventsDays and match with gentle=false. The synthetic table
readiness marker is reset before each trial, identically for both sources.

stable-guide uses sportsTable=false (the ordinary full-guide path). test-table
uses sportsTable=true, forces a real rebuild, and requires BOTH existing full-
guide validation passes to complete with identical results. Checks being false
in a stable-guide record means the experimental table checks do not apply.
Both modes must have identical matcher and guide-event hashes. Table metadata
hashes are additionally compared within test-table mode. The final channel and
nonempty full programme metadata are asserted in each case. No catalogue cap or
policy bypass is introduced; existing matcher output policy remains unchanged.

All 16 runs appear in trials.json and trials.csv, in actual execution order.
Primary: three fresh-process pairs per mode, ordered before/after, after/before,
before/after. Shared: ONE pair per mode; its results are a single observation.
summary.json contains every value, median, minimum and maximum; no p95 is
estimated and no outlier is dropped. Five native JUnit executions passed: the
same full 26,716-channel regression on both sources plus three grouped-row tests.

elapsedNanos is measured host component time. callerAllocatedBytes is the host
ThreadMXBean caller-thread allocation delta, excluding native SQLite and other
threads. GC columns are ordinary host GC collection counts before/after the
timed calls. There is no explicit GC, app lifecycle action, network or device
action. These results are not Android app PSS/RAM, retained heap, startup or
physical SHIELD acceptance evidence, and do not prove a 512MiB device spike fixed.

derivedTableBuildNormalizationCalls is present ONLY for test-table records.
It is derived from logical rows before versus physical gid/shift rows after,
times two (title plus nonempty description), for the FULL TABLE BUILD PASS only.
It is not a measured counter or the total calls of all matching stages. Blank
CSV cells in stable-guide mode mean not reported, never zero normalization.

Public export contains only synthetic numeric results, hashes and source pins.
No app data, raw logs, SQLite files, process/user IDs or private paths are included.

The public run-listing-fanout.py CLI requires explicit JDK, Android API and cached
app/build-config class paths instead of local machine defaults. This packaging
change was made after the recorded runs; measured Kotlin/workload/timing logic
was unchanged. Both executed and portable runner hashes are recorded.
