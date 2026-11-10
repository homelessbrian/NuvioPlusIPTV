# Guide query benchmark

This is an isolated Android SQLite query, Java programme-decoding and retention benchmark. It reproduces the guide's SQL and materialization strategy; it does **not** measure app startup, remote-button latency, frame time, playback, provider authentication or a real household playlist. It never opens the app or its databases.

## Strategies

- **Baseline:** the `1049818f` light `range()` query for every channel over 21 hours (`now - 3h` through `now + 18h`), followed by `ensureFullRange()`-style 60-channel batches for the first 400 channels over the default three days. Results merge by programme start and sort as the repository did.
- **Windowing only:** the new global now/next SQL and two-nonoverlapping-programme decoder, plus 32 channels requesting eight hours. The real four-hour tile alignment reads 12 hours in this fixture. Tile capacity is 384. The identical request is repeated to verify zero additional queries and decoded programmes.
- **Forward-only experiment:** the same windowing SQL, decoder, data and launch options, with `SQLiteCursor.setFillWindowForwardOnly(true)` on API 28+. It is a separate configuration. [AndroidX documents this option for continuous reads across cursor windows](https://developer.android.com/reference/androidx/core/database/sqlite/SQLiteCursorCompat); its compatibility helper does nothing before API 28. The production helper uses that compatibility API.

The Java `Program` has the same fields and light defaults as `EpgProgram`. Descriptions stay on disk. Episode/icon columns are null in this fixture, so episode-cleaning differences are not measured. The cache and merge are Java reproductions rather than the compiled app's Kotlin classes.

## Fixture and environment

Each channel has the same 30-minute schedule covering 12 past hours and three future days. The schema and `(ch,start)`, `start`, and `stop` indexes match the app. Both strategies read the **same complete, read-only database**.

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
