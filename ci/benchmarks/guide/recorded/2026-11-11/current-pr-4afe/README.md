# Pinned 67570ddd to 4afe8a39 app comparison

This is a separate, completed **six-trial primary case**, recorded before integrating upstream `6311c16e`. It compares normalized upstream `67570ddd` with combined preview `4afe8a39` (guide production `3d23f4a0` plus AFR). Upstream subsequently merged Sports fast-start, Search ranking/200 results, picker-memory and channel-cache changes. These observations are **not an incremental comparison against that newer base or its integrated candidate**. The original planned shared case failed in setup and was not measured; it is not a zero-valued result or a completed eight-trial study.

All **1,069 exact numeric samples**, including prelaunch rows, ordinary-GC samples, gaps, spikes and unfavorable trials, remain byte-for-byte in the six CSVs. [Manifest](manifest.json), [all statistics](summary.json), [source/input/cold-proof pins](provenance.json) and [shared setup failure](shared-setup-failure.json) are separate. No completed trial was rerun or excluded. No private application data, archive, raw device output, profile, endpoint or heap dump is exported.

## Method and coverage

- Same Android 16 x86_64 emulator, 512 MiB managed cap, observer 0004, signing/version/test package, synthetic input and full cold state restored before each alternating baseline/candidate trial. Full-debug test channel on both sides enables the existing Sports-table build plus full-guide verification. No media was activated.
- Primary input retains **4,000 bindings/guide IDs and 96,000 stored/logical listings**, 24 two-hour programmes each over 48 hours. Before/after all ten listing fields and bindings hash to `450149e798a310d5e1f922c642d4e00a978c45714631eea9c17874b75d1044d1`. A new cold post-trial full backup was bound to the current APK, final CSV/plan, host clocks and full inventory before recomputing this proof. Counts alone were not used as equality evidence.
- Fixed startup window `[90,120)` seconds from the unique positive in-process activity launch-request clock; all guides were verified before 90 seconds. Sampled startup maxima use `[0,120)`. Only trial 3 per role traverses exactly 600 rows, verifies row 601, then waits through the fixed `[30,60)` second post-browse window. Window placement is unchanged across roles.
- The observer reads Runtime and Debug.MemoryInfo in the actual application VM, without requesting GC/trim or changing the target APK. Instrumentation changes class loading, threads, scheduling, I/O and OOM treatment; matched UI-readiness reads add overhead. This is instrumented execution, not passive or zero-overhead RAM measurement. Other host activity and OS file-cache/paging state were not reset or controlled.
- Runtime/native-heap values are bytes; PSS/swap are KiB. Resident PSS is Dalvik + native + other components; Android 16 raw total PSS includes derived SwapPSS. `swap_total_kib` is a different nonproportional counter and must not be added. Hidden support flag remains unknown (`-1`). Aggregated medians are computed independently and need not add like individual rows. Runtime includes unreclaimed garbage; PSS includes graphics/native mappings and is distinct from the managed cap.
- All fixture catalogue/XML/media HTTP deltas were zero. The fixture freezes playlist/guide input; external Sports responses are not frozen. No horizon/channel reduction, cache pruning, forced trim or outlier filtering was used. Transient UI-readiness observations remain in [operator gates](operator-gates.json); their counts are not navigation-key or whole-trial retry counts.

## Results and unfavorable observations

Fixed late-startup **median of three trial medians**:

- Managed Runtime used: **55.63 → 54.51 MiB** (58,328,448 → 57,159,440 bytes). Trial-median ranges **52.41–63.03 → 51.23–80.55 MiB** overlap substantially. Candidate trial 1 uses more managed heap.
- Resident PSS: **457.63 → 449.33 MiB**, alongside derived SwapPSS **0.39 → 4.18 MiB**. Raw total including swap: **462.87 → 460.32 MiB**. The first candidate trial has 39.61 MiB derived swap; its lower resident value must not be read as equivalent memory release.
- **Worse sampled startup maxima:** worst managed Runtime **137.16 → 201.92 MiB**. Every candidate startup maximum exceeds every baseline startup maximum. This series does not establish that the reported RAM spike is fixed.

Single browsing pair (not a repeat distribution): fixed settled Runtime **90.50 → 80.42 MiB**; resident PSS **491.17 → 489.25 MiB**. Sampled browse Runtime maximum **158.43 → 142.87 MiB**. The actual 600-key device-clock intervals were **79.37 → 127.48 seconds**; preserve this unfavorable duration difference. ADB input duration and sampled RAM do not isolate a causal UI-latency effect. Every value/range/cadence/GC counter remains in the JSON/CSVs.

These modest/mixed emulator observations neither reproduce the physical SHIELD Android 11 long-session playback workload nor establish its stall/heap-spike resolution. They are not pooled with the earlier ac39/84 observations or GC-perturbed full-dump diagnostics.

## Shared setup failure and recovery

The baseline APK installed and verified, but the shared full-state restore failed before activity launch, observer start, events or samples. The frozen helper discarded the precise tar stderr/errno; storage, archive hash/listability and identity checks did not establish a cause. Do not infer an authentication, memory or provider failure. The rollback, partial state and failure record were retained privately. No old shared pair was retried. Recovery used a new logged helper, verified the original 643-file/19-directory state before enabling, retained verified APK 89708796 and left the target stopped; the owned observer was removed. A future current-base study is separate.

## Offline numeric replay

From the repository root:

```sh
python ci/benchmarks/guide/summarize-observer-csv.py --record-dir ci/benchmarks/guide/recorded/2026-11-11/current-pr-4afe
```

`cases:["primary"]` explicitly declares six trials; omitting the field or declaring both cases requires all eight. Replay validates the exact 32-column schema, file hashes, sequence/identity/clocks, Runtime/PSS arithmetic, GC counters, cap and fixed windows before calculating all statistics. It never contacts a device/network, requests GC, drops rows or writes files. Numeric replay cannot authenticate the capture protocol or convert the failed shared setup into a completed study. `files.json` hashes the exported files, excluding itself and Git attributes.
