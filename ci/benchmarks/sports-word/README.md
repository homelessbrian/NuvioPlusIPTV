# Sports whole-word helper microbenchmark

This harness compiles the exact Kotlin `SportsCatalog.hasWord` function from the selected Git baseline and current checkout into separate objects. It does not transcribe the algorithms in Java. Generated source, full-file/function SHA256 hashes and raw measurements go in ignored `out/`.

The default baseline is `e4d3d6e801c79835e53d3d967339268029f587b2`. The default repository path assumes these files live at `ci/benchmarks/sports-word`; pass `-Repository` for another location.

## Run

Requirements: Git, Python 3 (`py -3`), JDK 21 (`JAVA_HOME`), and the project's Kotlin 2.3.0 compiler dependencies already in the Gradle cache. No dependencies are downloaded by this driver. Pass `-GradleCache` to override the normal user cache location. The helper is compiled with Kotlin JVM target 17, matching the app.

```powershell
./run.ps1 -Scenario sports
./run.ps1 -Scenario substring-misses
./run.ps1 -Scenario overlap-misses
```

For Android ART measurements, additionally set `ANDROID_HOME` (or `ANDROID_SDK_ROOT`), have Android SDK platform/build-tools 36 installed, and start an owned Android 36 x86_64 emulator. Explicitly choose its serial:

```powershell
./run.ps1 -Android -Serial emulator-5556 -Scenario sports
./run.ps1 -Android -Serial emulator-5556 -Scenario substring-misses
./run.ps1 -Android -Serial emulator-5556 -Scenario overlap-misses
```

`-Android` also runs the desktop measurement. It pushes only a standalone DEX jar to a unique `/data/local/tmp/nuvio-sports-word-bench-<process-id>/` directory, runs it through `app_process`, then removes that jar and directory. It does not install or start the app, change emulator settings, open a window, or request streams. Only emulator serials are accepted. The D8 log is saved in `out/d8.log`; a newer-Kotlin metadata warning does not alter the helper bytecode being measured.

## Workloads and measurements

Each scenario has 8,192 immutable text/term pairs. A SHA256 covers every UTF-8 text, NUL separator, term and newline in order, so the desktop and ART inputs can be compared exactly. Both variants receive the same inputs. Each runner performs four paired warmups followed by seven measured pairs with alternating variant order.

- `sports`: seeded synthetic normalized sports descriptions and varying literal aliases; 128 passes, 1,048,576 helper calls per sample. Each variant must return 492,800 matches.
- `substring-misses`: 24 repetitions of `manchester`, searched for `man`; one pass, 8,192 calls and zero matches. This tests frequent internal substring matches without a whole-word match.
- `overlap-misses`: a single 512-character `a` word, searched for `aa`; one pass, 8,192 calls and zero matches. This tests overlapping internal occurrences.

The desktop runner measures current-thread allocated bytes with `ThreadMXBean`. ART measures the change in the process-wide `art.gc.bytes-allocated` counter and GC count. ART counters can include runtime/background allocation and reporting granularity; a zero median is not a claim that the entire process allocates nothing.

ART also samples managed heap used and process PSS after each measured pass, outside the timing/allocation interval. These are **post-pass samples, not true peak RAM**. Both variants run in the same process and share GC/JIT state, so their samples do not establish a retained-memory saving. Raw JSONL contains every sample; report medians and ranges rather than selecting the best run.

Results depend on the workload and runtime. The repeated-`manchester` workload took longer on the desktop JVM in the recorded run while improving on ART. Keep that limitation when presenting the results; do not describe the optimization as a universal CPU speedup. `recorded-results.txt` contains all measured pairs and the source/corpus hashes for this change.

This benchmark measures one helper on a desktop JVM and an emulator. It does not measure whole Sports matching, guide browsing, physical Fire TV performance, or the app's peak RAM, and cannot establish the cause or resolution of a native crash. Whole-matcher output parity and device validation are separate checks.
