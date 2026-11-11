# Current upstream comparison: 6311c16e → 4636df60

**Merge hold: startup managed memory improved in this study, but the sampled
browsing peak regressed.** This record does not establish that the guide PR is
ready to merge. Allocation attribution and a targeted fix remain necessary.

This is the whole guide PR against its current upstream base. It supersedes
neither the earlier component experiments nor the older 675-source whole-PR
records; those records and their negative results remain intact.

## Sources and artifacts

- Baseline source: `6311c16e9d7361be54cf4b74ae84afa619dbd587`.
  APK SHA256: `d5d19e54a6ad0f119d3033288f85bcceb2fc294097d0f4be7dd8e7d24573f331`.
- Candidate source: `4636df605eb111897bfbb91913300188719399e0`.
  APK SHA256: `631bf8e80f43ad7c21c64652f4ac68eb0dd55d6d50938382e5848cd98096d19a`.
- Both are full debug x86_64 builds with identical isolated application packaging
  and version override, tested on the same API 36 emulator. The target managed
  runtime maximum was 512 MiB. These results do not describe a physical Android 11
  NVIDIA SHIELD or video playback.

The [provenance](provenance.json) binds both case plans, common-state archives,
fixture manifests, observer, collector, source/APK hashes and fresh cold proofs.
The complete archives themselves remain private.

## Method and retained data

The primary case contains 4,000 channel bindings and 96,000 stored/logical
programme rows. Its fixed order is B1, A1, B2, A2, B3, A3. The shared-guide case
contains 26,716 bindings, 4,477 guide IDs and 107,448 stored rows representing
641,184 projected rows, including the original zero and ±1-hour shifts. Its
single B1/A1 pair is reported separately.

All eight accepted trials contribute **1,317 complete numeric rows**, unchanged
from their frozen CSVs. No row, GC-coincident sample, cadence gap or outlier was
removed. The first primary A1 attempt failed during host ADB connection/startup,
before the fixed late window. Its **49 partial rows** and **one-row earlier
startup-prefix snapshot** are retained separately in
[protocol-failures](protocol-failures/manifest.json), with the exact move,
failure and single-retry authorization hashes. The prefix is overlapping
evidence, not an additional independent sample. None of these failure rows enters
the accepted statistics. An application or RAM cause was not established.

The matched observer reads the target's Runtime and Debug.MemoryInfo directly.
It adds class-loading, thread, scheduling and logging overhead. The study is
instrumented; it is not a natural or zero-overhead memory measurement. The
reviewed collector requests no explicit GC, trim or cache eviction.

- Startup late window: `[90,120)` seconds from the unique in-process main-launch
  request; at least 20 complete rows were required.
- Startup sampled peak: `[0,120)` seconds. Only primary trial 3 of each role runs
  the fixed 600 Down-key browse at minimum 100ms spacing.
- Settled window: `[30,60)` seconds after browse completion. Actual browse time
  was 112.53s baseline and 94.97s candidate; this one pair does not establish a
  causal UI speed improvement.
- Agent-owned build/JVM/compiler/benchmark jobs were stopped during accepted
  samples. Other host activity, the user's PC games, OS file cache, paging and
  unrelated scheduling were uncontrolled. No host-load threshold was used to
  discard samples.
- The local fixture froze catalogue/guide input; external application/network
  responses were not frozen. Catalogue/XML/media fixture request deltas were 0
  in accepted trials, with no media activation. This does not prove no local
  reparsing or no external Sports traffic.

## Before and after numbers

All numbers below are MiB, baseline → candidate. Runtime values are bytes divided
by 1,048,576; PSS values are KiB divided by 1,024. The numeric files retain exact
integers, sample ranges, per-trial medians, GC counters and cadence.

Primary late startup, **median of the three trial medians**:

- Managed Runtime used: **79.77 → 64.01**.
- Resident component PSS: 479.62 → 472.80.
- Derived SwapPSS: 0.93 → 0.39; raw total PSS 480.01 → 473.19.
- Native heap allocated: 38.24 → 39.94, higher.

Worst startup sampled maxima across the three primary trials:

- Managed Runtime used:185.84 →87.59.
- Resident component PSS:699.28 →577.21; raw total PSS699.67 →577.60.
- Native heap allocated:69.55 →69.69. Maxima of different metrics can occur at
  different samples and must not be added together. Candidate trial 1's resident
  startup peak 569.31 exceeded its paired baseline 532.06; that result is retained.

Primary 600-key browse, **one pair only**:

- Managed sampled peak: **129.43 →151.94**, higher by 22.51MiB.
- Resident component PSS sampled peak:656.12 →702.86.
- Raw total PSS sampled peak:656.51 →715.87.
- Native heap allocated sampled peak:54.43 →56.95.
- Settled managed median:113.29 →100.26.
- Settled resident PSS median:525.98 →489.55, while derived SwapPSS rose
  **0.39 →24.47**. The resident reduction therefore includes paging; it is not
  equivalent to an equal reduction in total memory footprint.
- Settled raw total PSS median:526.37 →514.03; native allocated median
  42.05 →45.09, higher.

Shared-guide case, **one pair only**, late-window medians:

- Managed Runtime used:87.56 →84.24.
- Resident component PSS:504.31 →483.04, while derived SwapPSS rose
  **0.39 →13.75**.
- Raw total PSS:504.70 →496.79; native heap allocated 42.25 →40.44.

These are small matched studies, not performance distributions or significance
claims. Resident component PSS is Dalvik+native+other PSS. On this Android build,
the raw total also includes derived SwapPSS; `swap_total_kib` is a separate
nonproportional metric. Independent medians/maxima need not sum. A 512MiB managed
runtime limit does not cap total process PSS, graphics or native allocations.

## Correctness and runtime smoke

Both fresh cold after-proofs passed: all channel keys and serialized legacy
channel fields remained intact, and all ten logical programme fields and shifts
matched the common data. Primary logical SHA256:
`450149e798a310d5e1f922c642d4e00a978c45714631eea9c17874b75d1044d1`.
Shared logical SHA256:
`5d04f45de1c6abb60de8bb7cadb75b7859acd6a6c158e83d9a521e42a5775510`.
This is full-data preservation evidence, not a test of every Search query or
every real Sports event/channel association.

The separate [runtime smoke](runtime-smoke.json) ran after all memory samples
and cold proofs were frozen. Both builds found the same synthetic future
programme 4–6 hours ahead, beyond now/next, on an unvisited guide channel. Focus
remained on the first result at observations more than 35s apart; exactly 20 Right
keys then focused channel 650 without activation. Focus was checked at endpoints,
not continuously. The baseline's preliminary ancestor-only caption extractor
missed the flattened sibling caption; the retained coordinate proof and exact
navigation established identity.

Both builds visibly progressed from Sports matching busy to an idle hub within
the 180s bound, with 173 final games and no visible error labels. Observed idle
bounds 115.88s/92.55s are **not a Sports speed benchmark**: network responses and
initial event state were not identical. Backend/network failure totals are
unknown. This does not prove real event-channel matching, playback or physical
SHIELD behavior. Original app data was restored exactly; the latest APK was
retained with the app stopped, and the owned observer was removed.

The [supplied ExpressVPN profile option checks](expressvpn-options-only.json)
also passed on both exact APKs: actual Java import/preparation and native
validation of the unchanged raw and exact prepared profile exited 0. No provider
connection, credentials, VPN service or host VPN change occurred. These checks
establish local profile-option compatibility only; provider authentication,
TLS/tunnel establishment and streaming remain unverified. No profile content,
certificate, key, endpoint or credentials is included here.

## Replay and file integrity

[numeric-replay-validation.json](numeric-replay-validation.json) records an exact
independent replay of all eight trials: fixed windows, peaks, GC intervals,
cadence gaps, 512MiB caps, chronology, grouped medians and ranges all matched.
From the repository root, recompute the public numeric results with:

```sh
python ci/benchmarks/guide/summarize-observer-csv.py --record-dir ci/benchmarks/guide/recorded/2026-11-11/upstream6311-4636
```

Full archive/source/afterproof gates were checked separately by the exporter; a numeric replay alone cannot
recreate private full-data proofs.

[files.json](files.json) is the unchanged exporter-generated numeric artifact
manifest. [supplemental-files.json](supplemental-files.json) adds hashes for this
README and the separately reviewed runtime/option-check supplements. Neither
manifest includes its own hash. Raw app data, UI/log captures, profiles and traces
are excluded.
