# Guide query benchmark

Historical APK full-dump RAM observations are diagnostic; read the [measurement correction](recorded/2026-11-10/RAM-MEASUREMENT-CORRECTION.md).

The Java SQL/decoder timings below are **historical component results** reproducing the legacy `1049818f` `programs` schema. They are not measurements of the current normalized `67570ddd` Kotlin implementation. Current source keeps `listings` / `guide_ch` / `chan`, shared guide bindings and per-channel shifts, per-source atomic swaps, and unchanged-source-file reuse. The separate [six-trial matched instrumented comparison](recorded/2026-11-10/matched-observer-0004/README.md) now records the current artifacts: late-startup median Runtime 80.16 → 64.91 MiB and resident PSS 477.77 → 465.54 MiB, with overlapping ranges, a worse first candidate trial and a worse transient browse peak. It does not establish a uniform improvement or resolve the reported SHIELD long-session stall.

This is an isolated Android SQLite query, Java programme-decoding and retention benchmark. It reproduces that legacy guide's SQL and materialization strategy; it does **not** measure app startup, remote-button latency, frame time, playback, provider authentication or a real household playlist. It never opens the app or its databases. The separately documented actual-app generators below provide synthetic input; generating input is not a performance measurement.

## Strategies

- **Baseline:** the `1049818f` light `range()` query for every channel over 21 hours (`now - 3h` through `now + 18h`), followed by `ensureFullRange()`-style 60-channel batches for the first 400 channels over the default three days. Results merge by programme start and sort as the repository did.
- **Windowing only:** the new global now/next SQL and two-nonoverlapping-programme decoder, plus 32 channels requesting eight hours. The real four-hour tile alignment reads 12 hours in this fixture. Tile capacity is 384. The identical request is repeated to verify zero additional queries and decoded programmes.
- **Forward-only experiment:** the same windowing SQL, decoder, data and launch options, with `SQLiteCursor.setFillWindowForwardOnly(true)` on API 28+. It is a separate configuration. [AndroidX documents this option for continuous reads across cursor windows](https://developer.android.com/reference/androidx/core/database/sqlite/SQLiteCursorCompat); its compatibility helper does nothing before API 28. The production helper uses that compatibility API.

The Java `Program` has the same fields and light defaults as `EpgProgram`. Descriptions stay on disk. Episode/icon columns are null in this fixture, so episode-cleaning differences are not measured. The cache and merge are Java reproductions rather than the compiled app's Kotlin classes.

## Fixture and environment

Each channel has the same 30-minute schedule covering 12 past hours and three future days. The schema and `(ch,start)`, `start`, and `stop` indexes match the legacy `1049818f` app, rather than the current normalized database. Both strategies read the **same complete, read-only database**.

- 4,000 channels: 672,000 listings; database 236,285,952 bytes.
- 16,000 channels: 2,688,000 listings; database 947,130,368 bytes.
- Recorded on an API 36 x86_64 emulator, SQLite 3.44.5. A fresh `app_process` was used per sample.
- The launch requested `-Xmx768m`, but actual `Runtime.maxMemory()` was **201,326,592 bytes (192 MiB)**. Use that measured cap, not the requested flag, when interpreting the results.
- The emulator shared its host with an active Gradle build. Host load and OS file-cache state were not controlled or reset. These are component measurements, not a portable latency guarantee.
- The separately collected actual-app RAM fixture had 4,000 channels and 96,000 listings. Its PSS/interaction measurements are a different experiment and must not be combined with this dataset.

`displayRetainedPrograms` counts the merged display graph. `ownerRetainedPrograms` also includes overview/tile objects retained by their owners, including overlap. `visitedRows` counts rows returned through the Java cursor loop, not SQLite's internal cursor-window refills. Heap delta is the retained Java heap after explicit GC; PSS is the entire fresh benchmark process, not app PSS.

## Recorded results

All raw records are in `recorded/2026-11-10/`. The CSV contains every completed observation; the JSON summary records sample counts and reports no p95 for fewer than five samples.

- **4k, five alternating trials per strategy:** baseline median 8,831.37 ms, sampled p95 11,330.77 ms; windowing-only median 3,752.67 ms, sampled p95 5,555.41 ms. Nearest-rank p95 with five samples is the observed maximum. Display-retained programmes: 211,200 to 8,704; decoded programmes: 226,400 to 8,768. These five new-path samples were **windowing only, before the production forward-only helper**; they are not an exact timing of the final combined path.
- **16k, one completed baseline and two windowing-only observations:** baseline 561,239.54 ms; windowing 51,826.01 and 50,980.90 ms. Display-retained programmes: 715,200 to 32,704; decoded programmes: 730,400 to 32,768. There is no five-trial large-case median/p95 claim.
- **Separate 16k controlled pair:** forward-only 31,758.46 ms; default-cursor control 43,577.67 ms. That pair suggests about 27% less query/decode/merge time in this run. It is **one pair**, not a five-trial median or a general speedup guarantee. Both produced the identical checksum for all 32,704 expected display records, the same object counts, and the same 2,688,000 stored rows.

The long baseline completed before a cancellation signal could be sent, so its actual 561.24-second query result is preserved. Repeated long baseline trials were then stopped; two windowing samples had already finished. A 300-second **process measurement budget** applied to the later controlled pair and neither timed out. No censored baseline or invented 300/301-second query duration is claimed. A future runner timeout is labelled explicitly and does not invent a query-only duration.

An in-flight `/proc` snapshot is also retained. Its roughly 202 GB `rchar` value is logical read traffic, including cache hits; its `read_bytes` counter was about 7 GB. Those counters must not be described as 202 GB of physical disk reads. The snapshot is supporting I/O evidence, not a profiler attribution.

Every sample verified that the last never-browsed channel still returned its two programmes in the requested hour. Repeated tile requests made zero extra queries/decodes. The full 16k database SHA-256 was unchanged before and after the controlled pair:

```text
670e00151fd11b1a352474fa24715437ca7dd4b65d5a42095c8b33ca061d63f5
```

The controlled pair's full sorted display checksum was:

```text
93be033f79c09f2607deed0ea8b271a8336a34fcb41a0e4595ccfbeb965518ef
```

The device scratch files and benchmark processes were removed; `device-cleanup.txt` records `CLEAN`. No VOD queries, external media, app data or UI operations were used.

## Run another measurement

Requirements: Python 3, JDK 21, Android SDK platform 36/build-tools 36.0.0/platform-tools, and an explicitly selected emulator/device with at least 2.2 GB free scratch storage. The runner uses a new owned directory below `/data/local/tmp`, refuses an existing host output directory, and removes only its generated device filenames. It never recursively removes a device directory.

```sh
python run.py --serial emulator-5556 --sdk /path/to/android-sdk --java-home /path/to/jdk-21 --out /path/to/new-results --forward-pair
python summarize.py /path/to/new-results
```

`ANDROID_HOME`/`ANDROID_SDK_ROOT` and `JAVA_HOME` can supply the SDK/JDK paths. Windows paths work as separate quoted arguments as well. To permit a completed slow 16k baseline like the recorded run, explicitly use `--large-budget 900`; the default is 300 seconds. Default sampling is five alternating 4k trials and one 16k trial per strategy. A strategy that times out is not repeatedly retried. `--forward-pair` adds one separately labelled large-case forward/default pair.

The portable runner adds bounded process timeout handling and a unique scratch directory. It preserves the measured SQL/decoder configuration; exact sources used for the recorded runs are retained under `recorded-sources/`, with SHA-256 hashes in the recorded directory. Source compilation and the portable CLI were checked without repeating device measurements after packaging.

## Historical 12-hour actual-app RAM fixture

`app-fixture/server.py` is a separate, self-contained Python 3 fixture for actual-app comparisons: 4,000 live channels, 96,000 programmes, three live groups, and small synthetic movie/series metadata. It uses only the standard library and the adjacent `small_fixture.py`; there is no dependency on another checkout. The small helper still defaults to six live channels when used on its own. Neither helper reads app data or serves playable media: every media URL returns HTTP 404. The only credentials are the synthetic `demo` / `demo` pair.

From the repository root, start a new comparison fixture on an available port:

```sh
python ci/benchmarks/guide/app-fixture/server.py --output-dir ci/benchmarks/guide/.run/ram-compare-01 --port 18082
```

On Windows, `py -3` can replace `python`. The default output directory, when omitted, is `ci/benchmarks/guide/.run/ram-fixture` regardless of the working directory. Generated files are ignored by Git. The server binds **only `127.0.0.1`**, and `--port 0` selects an unused loopback port reported at startup. A test device needs its own explicitly selected loopback forwarding to that host port. Configure its synthetic Xtream source as `http://127.0.0.1:18082` with username/password `demo` / `demo`; do not substitute real provider credentials.

On first generation, the schedule starts at the current UTC hour minus two hours and contains 24 half-hour slots. `fixture.json` freezes that base epoch, channel/programme counts, XML byte count, and SHA-256 hashes. Restart with the **same output directory** for both APK versions to reuse the identical `guide.xml` and stream metadata. Do not create a fresh current-time fixture between versions. If the comparison outlasts its 12-hour schedule, generate a new directory and repeat both versions against that new fixture.

The startup JSON and unauthenticated `/health` endpoint report `xml_bytes`, `xml_sha256`, and `live_streams_sha256`; `/health` also reports completed XML transfers and bytes sent. The XML hash covers the exact file bytes. The stream hash covers the UTF-8 bytes of Python's `json.dumps(streams, sort_keys=True)` using its default separators. Restart checks both hashes and refuses changed files or a conflicting `--base-epoch`. `--generate-only` writes/verifies the fixture and exits without opening a port. For an independent file check, use `sha256sum` on `guide.xml` or PowerShell `Get-FileHash -Algorithm SHA256`.

To reproduce the historical fixture byte for byte, use a separate directory:

```sh
python ci/benchmarks/guide/app-fixture/server.py --output-dir ci/benchmarks/guide/.run/ram-historical --base-epoch 1794294000 --generate-only
```

That epoch is **2026-11-10 07:00:00 UTC**, and the frozen guide ends at 19:00:00 UTC. It produces:

- XML: **34,017,648 bytes**, SHA-256 `ed9f806b84542c37461ffb77e70790d917dcf22cceab0084441d85becc0356a9`.
- Canonical live-stream metadata: SHA-256 `b4d5551ae1f31aa79f61dd6fd8e035f4814c0725f2edf5799555b98217b87223`.

Use current-time generation for a new interactive RAM measurement; the historical epoch exists to reproduce the recorded input. This fixture has a different schedule and listing count from the isolated SQLite benchmark above. Keep actual-app RAM observations separate from those component results. Commit only sanitized measurement records and fixture sources; generated XML, private app data, archives, and HPROF files are not benchmark artifacts to publish.

## Normalized 48-hour actual-app input

`app-fixture/normalized_fixture.py` is the self-contained standard-library generator for the newer normalized comparison input. It contains no snapshot, restore, app-data, ADB or measurement helpers and does not contact a device. Preparation and verification use only the explicitly supplied fixture directory. Serving is a separate action requiring `--execute`, binds only `127.0.0.1:18081`, and serves no playable media. The fixed port is part of the frozen playlist bytes; run only one fixture server at a time. The only credentials are the synthetic `demo` / `demo` pair.

The cases use 24 **two-hour** programmes per guide ID, covering 48 hours from `floor(frozen_epoch / 7200) * 7200 - 7200`. This differs from the historical generator's 24 half-hour programmes and 12-hour coverage even though the primary channel/programme counts are the same.

- `primary`: 4,000 channels and guide IDs, 96,000 stored/logical programmes, Xtream ingestion, and zero binding shifts.
- `shared-m3u`: 26,716 logical channels sharing 4,477 guide IDs, 107,448 stored listings and 641,184 projected programmes. Import `playlist.m3u` through the M3U path for its `tvg-shift` values of -1/0/+1 hour. `bindings.json` records the expected mapping; actual native import must verify `chan.sh` before claiming shift coverage. Its companion Xtream stream metadata does not itself establish those shifts.

From the repository root, prepare a **new** directory and verify it before use:

```sh
python ci/benchmarks/guide/app-fixture/normalized_fixture.py prepare --case primary --out ci/benchmarks/guide/.run/normalized-primary-01
python ci/benchmarks/guide/app-fixture/normalized_fixture.py verify --fixture ci/benchmarks/guide/.run/normalized-primary-01 --for-run
```

On Windows, `py -3` can replace `python`. `prepare` refuses every existing output directory, including an empty one; it never regenerates an existing fixture. Use the **same frozen directory for both APK versions**. `--epoch EPOCH_SECONDS` fixes the generation epoch when reproducing an existing input into a different new directory. For the same case and epoch, `guide.xml`, `playlist.m3u`, `streams.json` and `bindings.json` retain exactly the private normalized generator's payload bytes. Independently generated `fixture.json` files can differ in `created_utc`; each records the four payload byte counts and SHA-256 hashes. Packaging validation reproduced all eight payload files byte-for-byte across both frozen cases; actual XML counts, existing-output refusal, tamper/expiry rejection and the no-execute serving guard passed. See the [sanitized validation record](recorded/2026-11-10/normalized-fixture-validation.json).

Serving requires a separate explicit command after verification:

```sh
python ci/benchmarks/guide/app-fixture/normalized_fixture.py serve --fixture ci/benchmarks/guide/.run/normalized-primary-01 --execute
```

Stop it with Ctrl+C. Omitting `--execute` fails before starting a listener. A separately selected test device needs its own explicit loopback forwarding; this generator neither configures nor contacts it. Use `http://127.0.0.1:18081` with `demo` / `demo` for the primary Xtream case, or `http://127.0.0.1:18081/get.php?username=demo&password=demo` for the shared M3U case. The unauthenticated `/health` endpoint reports fixture identity and local request/transfer counters. No directory listing or arbitrary-file endpoint is provided.

To prepare the optional shared case, use a different new directory:

```sh
python ci/benchmarks/guide/app-fixture/normalized_fixture.py prepare --case shared-m3u --out ci/benchmarks/guide/.run/normalized-shared-01
python ci/benchmarks/guide/app-fixture/normalized_fixture.py verify --fixture ci/benchmarks/guide/.run/normalized-shared-01 --for-run
```

Both `verify` and `serve` check the declared case, shape, endpoint, all four file sizes and SHA-256 hashes. `verify --for-run` and `serve` also reject dates before the fixture base or at/after `base + 24 hours`, and require at least 24 future hours of coverage. Configure `epgPastHours = 24` and `epgFutureDays >= 2` for complete import. If either comparison version outlasts that conservative import window, prepare a **new pair** in a new directory; never overwrite or refresh only one side. Plain `verify` omits the expiry check so historical frozen input can still be checked.

The manifest's static `Team 9 vs Team 12` Search probe is only an example; it can age into current/next. The actual primary-case smoke freezes one common target four to six hours ahead for both builds: `slot = floor((now - base) / 7200) + 3`, `target = base + slot * 7200`, query `Team 9 vs Team ((9 + slot) % 32)`, channel 650. Recheck at execution that this target remains beyond current/next and within the six-hour Search horizon. If it has aged out, record a new common target for **both builds**. Viewport prefetch after 600 browsed rows ends below channel 650. Finding that unbrowsed channel in Search is **not a real Sports-event matching proof**. The fixture's synthetic teams and Sports category do not establish a match to a live scoreboard event. The separate 26,716-channel source matcher test remains the full-catalogue Sports proof; app/device observations are separate evidence. This generator alone makes no RAM, latency or playback claim; the linked matched comparison is a separate experiment.

## Bounded ownership before publication batching

The [first bounded-ownership comparison](recorded/2026-11-11/bounded-df9f5146/) measures pre-bounded `4636df60` against `df9f5146`: eight accepted trials, 1,930 numeric rows, three complete primary browsing pairs and a separate shared-guide startup pair. Both fresh full-data proofs preserve every channel binding, all ten programme fields and all shifts.

This cohort regresses RAM peaks. Median primary browsing managed peak is **129.39 -> 145.20 MiB**; worst sampled browsing peak is **139.48 -> 162.57 MiB**. The shared pair's sampled startup managed maximum is **115.40 -> 199.44 MiB**. Its lower late resident PSS accompanies more swap rather than a smaller total footprint. All samples and the separate zero-sample setup rejection remain recorded. The estimated model budget stays within its limit, which does not establish lower whole-app heap use or merge readiness. Subsequent allocation/publication fixes require their own measurements; these results remain unchanged.
